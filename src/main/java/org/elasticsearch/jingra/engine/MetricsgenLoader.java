package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.config.MetricsgenConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Downloads, caches, and launches the {@code metricsgenreceiver} binary.
 * Shared by any engine that implements {@link BenchmarkEngine#customLoad}.
 */
public class MetricsgenLoader {

    private static final Logger logger = LoggerFactory.getLogger(MetricsgenLoader.class);

    private static final String RELEASE_URL_TEMPLATE =
            "https://github.com/elastic/metricsgenreceiver/releases/download/v%s/metricsgenreceiver_%s_%s.tar.gz";

    /**
     * When set, {@link #resolveBinary} returns this path instead of downloading.
     * Tests inject a pre-built stub binary to avoid network access.
     */
    public static final ThreadLocal<Path> binaryPathOverrideForTests = new ThreadLocal<>();

    /**
     * When set, {@link #resolveBinary} uses this directory as the cache root instead of
     * {@code ~/.cache/jingra/bin/}. Allows tests to create a pre-populated cache in a temp dir.
     */
    public static final ThreadLocal<Path> cacheRootOverrideForTests = new ThreadLocal<>();

    /**
     * When set, {@link #resolveBinary} uses this URL template (same {@code %s/%s/%s} format
     * as {@link #RELEASE_URL_TEMPLATE}) instead of the real GitHub release URL.
     * Allows tests to point at a local HTTP server serving a synthetic tar.gz.
     */
    public static final ThreadLocal<String> releaseUrlTemplateOverrideForTests = new ThreadLocal<>();

    public record ProcessResult(int exitCode, String stderr) {}

    private MetricsgenLoader() {}

    // ── Binary resolution ────────────────────────────────────────────────────────

    /**
     * Returns the path to the {@code metricsgenreceiver} binary, downloading and caching it
     * under {@code ~/.cache/jingra/bin/metricsgenreceiver-<version>/} if not already present.
     * Set {@link #binaryPathOverrideForTests} to skip the download in tests.
     */
    public static Path resolveBinary(String version) throws Exception {
        Path override = binaryPathOverrideForTests.get();
        if (override != null) {
            return override;
        }

        Path cacheDir = getCacheDir(version);
        Path binary = cacheDir.resolve("metricsgenreceiver");

        if (Files.exists(binary) && Files.isExecutable(binary)) {
            logger.debug("Using cached metricsgenreceiver at {}", binary);
            return binary;
        }

        String os   = detectOs();
        String arch = detectArch();
        String url  = getReleaseUrl(version, os, arch);

        logger.info("Downloading metricsgenreceiver v{} ({}/{})...", version, os, arch);
        Files.createDirectories(cacheDir);

        Path tmpArchive = Files.createTempFile(cacheDir, "metricsgenreceiver-", ".tar.gz");
        try {
            downloadFile(url, tmpArchive);
            extractBinaryFromTarGz(tmpArchive, binary);
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
            logger.info("Cached metricsgenreceiver at {}", binary);
        } finally {
            Files.deleteIfExists(tmpArchive);
        }
        return binary;
    }

    static String getReleaseUrl(String version, String os, String arch) {
        String template = releaseUrlTemplateOverrideForTests.get();
        return String.format(template != null ? template : RELEASE_URL_TEMPLATE, version, os, arch);
    }

    static Path getCacheDir(String version) {
        Path cacheRoot = cacheRootOverrideForTests.get();
        return (cacheRoot != null ? cacheRoot : Paths.get(System.getProperty("user.home"),
                ".cache", "jingra", "bin")).resolve("metricsgenreceiver-" + version);
    }

    static String detectOs() {
        return detectOs(System.getProperty("os.name", ""));
    }

    static String detectOs(String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac") || lower.contains("darwin")) return "darwin";
        if (lower.contains("linux"))                            return "linux";
        throw new UnsupportedOperationException("Unsupported OS: " + osName);
    }

    static String detectArch() {
        return detectArch(System.getProperty("os.arch", ""));
    }

    static String detectArch(String archName) {
        String lower = archName.toLowerCase(Locale.ROOT);
        if (lower.equals("aarch64") || lower.equals("arm64")) return "arm64";
        if (lower.equals("x86_64")  || lower.equals("amd64")) return "amd64";
        throw new UnsupportedOperationException("Unsupported architecture: " + archName);
    }

    private static void downloadFile(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Download failed with HTTP " + resp.statusCode() + " from " + url);
        }
    }

    /**
     * Extracts the {@code metricsgenreceiver} executable from a {@code .tar.gz} archive.
     * Implements a minimal TAR reader to avoid external library dependencies.
     */
    static void extractBinaryFromTarGz(Path tarGzFile, Path targetFile) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(tarGzFile));
             BufferedInputStream tar = new BufferedInputStream(gzip)) {

            byte[] headerBuf = new byte[512];
            while (true) {
                int n = tar.readNBytes(headerBuf, 0, 512);
                if (n < 512) break;

                // End-of-archive: two consecutive zero-filled blocks
                boolean allZero = true;
                for (byte b : headerBuf) {
                    if (b != 0) { allZero = false; break; }
                }
                if (allZero) break;

                String entryName = readTarString(headerBuf, 0, 100);
                char typeFlag   = (char) headerBuf[156];
                long entrySize  = parseTarOctal(headerBuf, 124, 12);

                boolean isRegular = typeFlag == '0' || typeFlag == '\0';

                // Mirror the Python script: accept any regular file whose path contains
                // "metricsgenreceiver" as a substring (handles naming variants like
                // "metricsgenreceiver_linux_arm64" produced by some release toolchains).
                if (isRegular && entryName.contains("metricsgenreceiver")) {
                    try (OutputStream out = Files.newOutputStream(targetFile)) {
                        long remaining = entrySize;
                        byte[] buf = new byte[8192];
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int read = tar.read(buf, 0, toRead);
                            if (read < 0) break;
                            out.write(buf, 0, read);
                            remaining -= read;
                        }
                    }
                    // skip padding
                    long pad = (512 - (entrySize % 512)) % 512;
                    tar.skipNBytes(pad);
                    return;
                }

                // Skip this entry's data + padding
                long toSkip = entrySize + (512 - (entrySize % 512)) % 512;
                tar.skipNBytes(toSkip);
            }
            throw new IOException("metricsgenreceiver binary not found in archive: " + tarGzFile);
        }
    }

    private static String readTarString(byte[] buf, int offset, int len) {
        int end = offset;
        while (end < offset + len && buf[end] != 0) end++;
        return new String(buf, offset, end - offset).trim();
    }

    static long parseTarOctal(byte[] buf, int offset, int len) {
        String s = readTarString(buf, offset, len);
        return s.isEmpty() ? 0L : Long.parseLong(s, 8);
    }

    // ── Env var building ─────────────────────────────────────────────────────────

    /**
     * Converts {@link MetricsgenConfig} fields into environment variables consumed by the
     * OTel Collector's native {@code ${VAR}} expansion when reading the committed config template.
     */
    public static Map<String, String> buildEnvVars(MetricsgenConfig cfg) {
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("METRICSGEN_SEED",            String.valueOf(cfg.seedOrDefault()));
        vars.put("METRICSGEN_START_NOW_MINUS",  cfg.startNowMinusOrDefault());
        vars.put("METRICSGEN_INTERVAL",         cfg.intervalOrDefault());
        vars.put("METRICSGEN_SCENARIO",         cfg.scenarioOrDefault());
        vars.put("METRICSGEN_SCALE",            String.valueOf(cfg.scaleOrDefault()));
        return vars;
    }

    // ── Subprocess ───────────────────────────────────────────────────────────────

    /**
     * Launches the binary with {@code --config <configFile>}, streams stderr to the logger,
     * and returns the exit code plus the full captured stderr string.
     * Convenience overload with no extra environment variables.
     */
    public static ProcessResult runBinary(Path binary, Path configFile) throws Exception {
        return runBinary(binary, configFile, Map.of());
    }

    /**
     * Launches the binary with {@code --config <configFile>} and merges {@code envVars} into the
     * process environment. The OTel Collector expands {@code ${VAR}} references in the config
     * template using these variables at startup.
     */
    public static ProcessResult runBinary(Path binary, Path configFile,
                                          Map<String, String> envVars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--config", configFile.toString());
        if (!envVars.isEmpty()) {
            pb.environment().putAll(envVars);
        }
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder stderrCapture = new StringBuilder();

        Thread stderrThread = new Thread(
                () -> readStderr(process.getErrorStream(), stderrCapture), "metricsgen-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        // Drain stdout so the process never blocks on a full pipe
        Thread stdoutThread = new Thread(
                () -> drainStdout(process.getInputStream()), "metricsgen-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        int exitCode = process.waitFor();
        stderrThread.join();
        stdoutThread.join();

        return new ProcessResult(exitCode, stderrCapture.toString());
    }

    // ── Stderr parsing ───────────────────────────────────────────────────────────

    private static final Pattern DATAPOINTS_PATTERN =
            Pattern.compile("\"datapoints\"\\s*:\\s*(\\d+)");
    private static final Pattern RATE_PATTERN =
            Pattern.compile("\"data_points_per_second\"\\s*:\\s*([\\d.]+)");

    /**
     * Scans the last log line containing {@code "datapoints"} and returns the value.
     * Returns 0 if no such line is found.
     */
    public static int parseDatapoints(String stderr) {
        int last = 0;
        Matcher m = DATAPOINTS_PATTERN.matcher(stderr);
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    /** Returns the last {@code data_points_per_second} value from stderr, or 0.0. */
    public static double parseRate(String stderr) {
        double last = 0.0;
        Matcher m = RATE_PATTERN.matcher(stderr);
        while (m.find()) {
            last = Double.parseDouble(m.group(1));
        }
        return last;
    }

    // ── Subprocess I/O helpers (package-private for testing) ─────────────────────

    static void readStderr(InputStream in, StringBuilder capture) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                capture.append(line).append('\n');
                logger.debug("metricsgenreceiver: {}", line);
            }
        } catch (IOException e) {
            logger.warn("Error reading metricsgenreceiver stderr", e);
        }
    }

    static void drainStdout(InputStream in) {
        try (InputStream ignored = in) {
            ignored.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored2) {}
    }
}
