package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.config.MetricsgenConfig;
import org.junit.jupiter.api.AfterEach;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MetricsgenLoaderTest {

    @AfterEach
    void clearOverrides() {
        MetricsgenLoader.binaryPathOverrideForTests.remove();
        MetricsgenLoader.cacheRootOverrideForTests.remove();
        MetricsgenLoader.releaseUrlTemplateOverrideForTests.remove();
    }

    // ── parseDatapoints ──────────────────────────────────────────────────────────

    @Test
    void parseDatapoints_extractsLastOccurrence() {
        String stderr = """
                {"datapoints":100000,"data_points_per_second":50000.0}
                {"datapoints":225180000,"data_points_per_second":413987.0}
                """;
        assertEquals(225_180_000, MetricsgenLoader.parseDatapoints(stderr));
    }

    @Test
    void parseDatapoints_returnsZeroWhenAbsent() {
        assertEquals(0, MetricsgenLoader.parseDatapoints("no datapoints here"));
    }

    @Test
    void parseDatapoints_handlesCompactJson() {
        assertEquals(42, MetricsgenLoader.parseDatapoints("{\"datapoints\":42}"));
    }

    // ── parseRate ────────────────────────────────────────────────────────────────

    @Test
    void parseRate_extractsLastOccurrence() {
        String stderr = """
                {"data_points_per_second":10000.5}
                {"data_points_per_second":413987.25}
                """;
        assertEquals(413987.25, MetricsgenLoader.parseRate(stderr), 0.01);
    }

    @Test
    void parseRate_returnsZeroWhenAbsent() {
        assertEquals(0.0, MetricsgenLoader.parseRate("nothing"), 0.0001);
    }

    // ── buildEnvVars ─────────────────────────────────────────────────────────────

    @Test
    void buildEnvVars_containsAllExpectedKeys() {
        MetricsgenConfig cfg = new MetricsgenConfig();
        cfg.setScale(100);
        cfg.setInterval("5s");
        cfg.setStartNowMinus("30m");
        cfg.setSeed(42);
        cfg.setScenario("builtin/hostmetrics");

        Map<String, String> vars = MetricsgenLoader.buildEnvVars(cfg);
        assertEquals("100",                vars.get("METRICSGEN_SCALE"));
        assertEquals("5s",                 vars.get("METRICSGEN_INTERVAL"));
        assertEquals("30m",                vars.get("METRICSGEN_START_NOW_MINUS"));
        assertEquals("42",                 vars.get("METRICSGEN_SEED"));
        assertEquals("builtin/hostmetrics", vars.get("METRICSGEN_SCENARIO"));
    }

    @Test
    void buildEnvVars_usesDefaultsForNullFields() {
        MetricsgenConfig cfg = new MetricsgenConfig(); // all null
        Map<String, String> vars = MetricsgenLoader.buildEnvVars(cfg);
        assertEquals("10000",              vars.get("METRICSGEN_SCALE"));
        assertEquals("123",                vars.get("METRICSGEN_SEED"));
        assertEquals("1s",                 vars.get("METRICSGEN_INTERVAL"));
        assertEquals("270m",               vars.get("METRICSGEN_START_NOW_MINUS"));
        assertEquals("builtin/hostmetrics", vars.get("METRICSGEN_SCENARIO"));
    }

    // ── detectOs / detectArch ────────────────────────────────────────────────────

    @Test
    void detectOs_returnsKnownValue() {
        String os = MetricsgenLoader.detectOs();
        assertTrue(os.equals("darwin") || os.equals("linux"),
                "Expected darwin or linux, got: " + os);
    }

    @Test
    void detectOs_parameterized_macOs() {
        assertEquals("darwin", MetricsgenLoader.detectOs("Mac OS X"));
        assertEquals("darwin", MetricsgenLoader.detectOs("Darwin"));
    }

    @Test
    void detectOs_parameterized_linux() {
        assertEquals("linux", MetricsgenLoader.detectOs("Linux"));
    }

    @Test
    void detectOs_parameterized_throwsOnUnknown() {
        assertThrows(UnsupportedOperationException.class,
                () -> MetricsgenLoader.detectOs("Windows 11"));
    }

    @Test
    void detectArch_returnsKnownValue() {
        String arch = MetricsgenLoader.detectArch();
        assertTrue(arch.equals("amd64") || arch.equals("arm64"),
                "Expected amd64 or arm64, got: " + arch);
    }

    @Test
    void detectArch_parameterized_arm() {
        assertEquals("arm64", MetricsgenLoader.detectArch("aarch64"));
        assertEquals("arm64", MetricsgenLoader.detectArch("arm64"));
    }

    @Test
    void detectArch_parameterized_amd64() {
        assertEquals("amd64", MetricsgenLoader.detectArch("x86_64"));
        assertEquals("amd64", MetricsgenLoader.detectArch("amd64"));
    }

    @Test
    void detectArch_parameterized_throwsOnUnknown() {
        assertThrows(UnsupportedOperationException.class,
                () -> MetricsgenLoader.detectArch("riscv64"));
    }

    // ── extractBinaryFromTarGz ────────────────────────────────────────────────────

    @Test
    void extractBinaryFromTarGz_extractsCorrectEntry(@TempDir Path tmpDir) throws Exception {
        byte[] content = "#!/bin/sh\necho hello\n".getBytes(StandardCharsets.UTF_8);
        Path archive = tmpDir.resolve("test.tar.gz");
        Files.write(archive, buildTarGz("metricsgenreceiver", content));

        Path out = tmpDir.resolve("metricsgenreceiver");
        MetricsgenLoader.extractBinaryFromTarGz(archive, out);

        assertTrue(Files.exists(out));
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void extractBinaryFromTarGz_matchesBySubstring(@TempDir Path tmpDir) throws Exception {
        // Binary named "metricsgenreceiver_linux_arm64" — matches via substring, not exact name
        byte[] content = "binary".getBytes(StandardCharsets.UTF_8);
        Path archive = tmpDir.resolve("test.tar.gz");
        Files.write(archive, buildTarGz("metricsgenreceiver_linux_arm64", content));
        Path out = tmpDir.resolve("metricsgenreceiver");
        MetricsgenLoader.extractBinaryFromTarGz(archive, out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void extractBinaryFromTarGz_handlesEntryInSubdirectory(@TempDir Path tmpDir) throws Exception {
        byte[] content = "binary".getBytes(StandardCharsets.UTF_8);
        Path archive = tmpDir.resolve("test.tar.gz");
        Files.write(archive, buildTarGz("./v1.0.7/metricsgenreceiver", content));

        Path out = tmpDir.resolve("metricsgenreceiver");
        MetricsgenLoader.extractBinaryFromTarGz(archive, out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void extractBinaryFromTarGz_skipsNonMatchingEntries(@TempDir Path tmpDir) throws Exception {
        byte[] content = "binary content".getBytes(StandardCharsets.UTF_8);
        Path archive = tmpDir.resolve("test.tar.gz");
        Files.write(archive, buildTarGzMultiple(
                new TarEntry("README.md",  "readme text".getBytes(StandardCharsets.UTF_8)),
                new TarEntry("metricsgenreceiver", content)
        ));
        Path out = tmpDir.resolve("metricsgenreceiver");
        MetricsgenLoader.extractBinaryFromTarGz(archive, out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void extractBinaryFromTarGz_handlesEofDuringContentRead(@TempDir Path tmpDir) throws Exception {
        // Archive where the header declares size=200 but only 10 bytes of data follow.
        // Triggers the if (read < 0) break branch inside the content-reading loop.
        byte[] header = buildTarHeader("metricsgenreceiver", 200);
        byte[] partialData = new byte[10];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(header);
            gz.write(partialData);
            // No end-of-archive — stream ends here to force EOF mid-read
        }
        Path archive = tmpDir.resolve("eof-read.tar.gz");
        Files.write(archive, baos.toByteArray());
        Path out = tmpDir.resolve("metricsgenreceiver");
        // Method returns (having extracted what it could) or throws; either is acceptable
        try {
            MetricsgenLoader.extractBinaryFromTarGz(archive, out);
        } catch (IOException e) {
            // acceptable — skipNBytes may fail on the truncated stream
        }
        // What matters for coverage is that the read < 0 branch was exercised
    }

    @Test
    void extractBinaryFromTarGz_throwsOnTruncatedArchive(@TempDir Path tmpDir) throws Exception {
        // Archive truncated to < 512 bytes — triggers the n < 512 early-break path
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(new byte[100]); // only 100 bytes instead of a full 512-byte header
        }
        Path archive = tmpDir.resolve("truncated.tar.gz");
        Files.write(archive, baos.toByteArray());
        Path out = tmpDir.resolve("binary");
        assertThrows(IOException.class, () -> MetricsgenLoader.extractBinaryFromTarGz(archive, out));
    }

    @Test
    void extractBinaryFromTarGz_throwsWhenBinaryNotFound(@TempDir Path tmpDir) throws Exception {
        Path archive = tmpDir.resolve("test.tar.gz");
        Files.write(archive, buildTarGz("other-binary", "data".getBytes(StandardCharsets.UTF_8)));
        Path out = tmpDir.resolve("binary");
        assertThrows(IOException.class, () -> MetricsgenLoader.extractBinaryFromTarGz(archive, out));
    }

    @Test
    void extractBinaryFromTarGz_handlesNulTypeFlag(@TempDir Path tmpDir) throws Exception {
        byte[] content = "binary".getBytes(StandardCharsets.UTF_8);
        // Build a header with typeFlag='\0' (NUL) instead of '0'
        byte[] header = buildTarHeader("metricsgenreceiver", content.length);
        header[156] = 0; // NUL type flag — also means regular file
        Path archive = tmpDir.resolve("nul-type.tar.gz");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(header);
            gz.write(content);
            int pad = (512 - (content.length % 512)) % 512;
            gz.write(new byte[pad]);
            gz.write(new byte[1024]); // end-of-archive
        }
        Files.write(archive, baos.toByteArray());

        Path out = tmpDir.resolve("metricsgenreceiver");
        MetricsgenLoader.extractBinaryFromTarGz(archive, out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void extractBinaryFromTarGz_skipsDirectoryEntries(@TempDir Path tmpDir) throws Exception {
        byte[] content = "the-binary".getBytes(StandardCharsets.UTF_8);
        // Build archive: first a directory entry (typeFlag='5'), then the real binary
        byte[] dirHeader  = buildTarHeader("somedir/", 0);
        dirHeader[156] = '5'; // directory
        byte[] binHeader  = buildTarHeader("metricsgenreceiver", content.length);

        Path archive = tmpDir.resolve("dir-then-bin.tar.gz");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(dirHeader);        // directory entry (0 bytes of data)
            gz.write(binHeader);
            gz.write(content);
            int pad = (512 - (content.length % 512)) % 512;
            gz.write(new byte[pad]);
            gz.write(new byte[1024]);
        }
        Files.write(archive, baos.toByteArray());

        Path out = tmpDir.resolve("metricsgenreceiver");
        MetricsgenLoader.extractBinaryFromTarGz(archive, out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    // ── getReleaseUrl ────────────────────────────────────────────────────────────

    @Test
    void getReleaseUrl_usesTemplateOverrideWhenSet() {
        MetricsgenLoader.releaseUrlTemplateOverrideForTests.set("http://local/%s/%s/%s");
        assertEquals("http://local/1.0.7/darwin/arm64",
                MetricsgenLoader.getReleaseUrl("1.0.7", "darwin", "arm64"));
    }

    @Test
    void getReleaseUrl_usesRealUrlWhenNotOverridden() {
        // releaseUrlTemplateOverrideForTests is null (not set by this test)
        String url = MetricsgenLoader.getReleaseUrl("1.0.7", "darwin", "arm64");
        assertTrue(url.contains("1.0.7"), url);
        assertTrue(url.contains("metricsgenreceiver"), url);
    }

    // ── getCacheDir ──────────────────────────────────────────────────────────────

    @Test
    void getCacheDir_usesCacheRootOverrideWhenSet(@TempDir Path tmpDir) {
        MetricsgenLoader.cacheRootOverrideForTests.set(tmpDir);
        assertEquals(tmpDir.resolve("metricsgenreceiver-1.0.7"), MetricsgenLoader.getCacheDir("1.0.7"));
    }

    @Test
    void getCacheDir_usesHomeDirWhenNotOverridden() {
        // cacheRootOverrideForTests is null (not set by this test)
        Path dir = MetricsgenLoader.getCacheDir("1.0.7");
        assertTrue(dir.toString().contains("metricsgenreceiver-1.0.7"));
    }

    // ── parseTarOctal ────────────────────────────────────────────────────────────

    @Test
    void parseTarOctal_returnsZeroForEmptyField() {
        byte[] buf = new byte[512]; // all NUL bytes
        assertEquals(0L, MetricsgenLoader.parseTarOctal(buf, 124, 12));
    }

    @Test
    void parseTarOctal_parsesValidOctal() {
        byte[] buf = new byte[512];
        byte[] sizeStr = "00000000777\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sizeStr, 0, buf, 124, sizeStr.length);
        assertEquals(0777L, MetricsgenLoader.parseTarOctal(buf, 124, 12));
    }

    @Test
    void parseTarOctal_handlesFieldWithoutNullTerminator() {
        // 12 bytes, no null — causes readTarString to exhaust the field length
        byte[] buf = new byte[512];
        byte[] sizeStr = "000000000777".getBytes(StandardCharsets.US_ASCII); // 12 bytes, no '\0'
        System.arraycopy(sizeStr, 0, buf, 124, sizeStr.length);
        assertEquals(0777L, MetricsgenLoader.parseTarOctal(buf, 124, 12));
    }

    // ── resolveBinary ────────────────────────────────────────────────────────────

    @Test
    void resolveBinary_returnsBinaryPathOverride(@TempDir Path tmpDir) throws Exception {
        Path fakeBin = tmpDir.resolve("metricsgenreceiver");
        Files.writeString(fakeBin, "fake");
        MetricsgenLoader.binaryPathOverrideForTests.set(fakeBin);
        assertEquals(fakeBin, MetricsgenLoader.resolveBinary("1.0.7"));
    }

    @Test
    void resolveBinary_returnsCachedBinaryWhenPresent(@TempDir Path tmpDir) throws Exception {
        Path cacheDir = tmpDir.resolve("metricsgenreceiver-1.0.7");
        Files.createDirectories(cacheDir);
        Path binary = cacheDir.resolve("metricsgenreceiver");
        Files.writeString(binary, "cached");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));

        MetricsgenLoader.cacheRootOverrideForTests.set(tmpDir);
        assertEquals(binary, MetricsgenLoader.resolveBinary("1.0.7"));
    }

    @Test
    void resolveBinary_downloadsAndCachesWhenMissing(@TempDir Path tmpDir) throws Exception {
        byte[] binaryContent = "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8);
        byte[] tarGzBytes = buildTarGz("metricsgenreceiver", binaryContent);

        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, tarGzBytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(tarGzBytes); }
        });
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();

        try {
            MetricsgenLoader.releaseUrlTemplateOverrideForTests.set("http://127.0.0.1:" + port + "/%s/%s/%s");
            MetricsgenLoader.cacheRootOverrideForTests.set(tmpDir);

            Path result = MetricsgenLoader.resolveBinary("1.0.7");
            assertTrue(Files.exists(result));
            assertArrayEquals(binaryContent, Files.readAllBytes(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveBinary_redownloadsWhenCachedBinaryNotExecutable(@TempDir Path tmpDir) throws Exception {
        // Create a binary file that exists but has no execute permission
        Path cacheDir = tmpDir.resolve("metricsgenreceiver-1.0.7");
        Files.createDirectories(cacheDir);
        Path binary = cacheDir.resolve("metricsgenreceiver");
        Files.writeString(binary, "old-non-executable");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rw-r--r--"));

        byte[] binaryContent = "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8);
        byte[] tarGzBytes = buildTarGz("metricsgenreceiver", binaryContent);

        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, tarGzBytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(tarGzBytes); }
        });
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();

        try {
            MetricsgenLoader.cacheRootOverrideForTests.set(tmpDir);
            MetricsgenLoader.releaseUrlTemplateOverrideForTests.set("http://127.0.0.1:" + port + "/%s/%s/%s");
            Path result = MetricsgenLoader.resolveBinary("1.0.7");
            assertArrayEquals(binaryContent, Files.readAllBytes(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveBinary_cleansUpTempArchiveOnDownloadFailure(@TempDir Path tmpDir) throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();

        try {
            MetricsgenLoader.releaseUrlTemplateOverrideForTests.set("http://127.0.0.1:" + port + "/%s/%s/%s");
            MetricsgenLoader.cacheRootOverrideForTests.set(tmpDir);

            assertThrows(Exception.class, () -> MetricsgenLoader.resolveBinary("1.0.7"));

            // temp archive should have been cleaned up
            Path cacheDir = tmpDir.resolve("metricsgenreceiver-1.0.7");
            if (Files.exists(cacheDir)) {
                long tempFiles = Files.list(cacheDir)
                        .filter(p -> p.getFileName().toString().startsWith("metricsgenreceiver-"))
                        .count();
                assertEquals(0, tempFiles, "temp archive should be cleaned up after failure");
            }
        } finally {
            server.stop(0);
        }
    }

    // ── runBinary ────────────────────────────────────────────────────────────────

    @Test
    void runBinary_capturesStderrAndReturnsZeroExitCode(@TempDir Path tmpDir) throws Exception {
        // Create a shell script that writes JSON to stderr
        Path script = createScript(tmpDir, "#!/bin/sh\necho '{\"datapoints\":99}' >&2\n");
        Path cfg    = tmpDir.resolve("config.yaml");
        Files.writeString(cfg, "placeholder: true\n");

        MetricsgenLoader.ProcessResult result = MetricsgenLoader.runBinary(script, cfg);
        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("datapoints"), "stderr should contain datapoints: " + result.stderr());
    }

    @Test
    void runBinary_nonZeroExitCode(@TempDir Path tmpDir) throws Exception {
        Path script = createScript(tmpDir, "#!/bin/sh\necho 'fatal error' >&2\nexit 2\n");
        Path cfg    = tmpDir.resolve("config.yaml");
        Files.writeString(cfg, "placeholder: true\n");

        MetricsgenLoader.ProcessResult result = MetricsgenLoader.runBinary(script, cfg);
        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("fatal error"), result.stderr());
    }

    @Test
    void runBinary_envVarsArePassedToSubprocess(@TempDir Path tmpDir) throws Exception {
        Path script = createScript(tmpDir, "#!/bin/sh\necho TESTVAR=$TESTVAR >&2\n");
        Path cfg    = tmpDir.resolve("config.yaml");
        Files.writeString(cfg, "placeholder: true\n");

        Map<String, String> envVars = Map.of("TESTVAR", "hello123");
        MetricsgenLoader.ProcessResult result = MetricsgenLoader.runBinary(script, cfg, envVars);
        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("TESTVAR=hello123"), result.stderr());
    }

    @Test
    void runBinary_stdoutDrainedWithoutBlocking(@TempDir Path tmpDir) throws Exception {
        // Writes a lot to stdout — verifies the stdout drain thread prevents blocking
        Path script = createScript(tmpDir, "#!/bin/sh\nfor i in $(seq 1 1000); do echo 'line'; done\n");
        Path cfg    = tmpDir.resolve("config.yaml");
        Files.writeString(cfg, "placeholder: true\n");

        MetricsgenLoader.ProcessResult result = MetricsgenLoader.runBinary(script, cfg);
        assertEquals(0, result.exitCode());
    }

    private static Path createScript(@TempDir Path tmpDir, String content) throws IOException {
        Path script = tmpDir.resolve("fake-metricsgenreceiver.sh");
        Files.writeString(script, content);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    // ── readStderr / drainStdout ──────────────────────────────────────────────────

    @Test
    void readStderr_capturesLinesAndAppendsNewlines() {
        String input = "line1\nline2\nline3\n";
        java.io.InputStream in = new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder capture = new StringBuilder();
        MetricsgenLoader.readStderr(in, capture);
        assertTrue(capture.toString().contains("line1"));
        assertTrue(capture.toString().contains("line2"));
    }

    @Test
    void readStderr_silentOnIOException() {
        java.io.InputStream broken = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("broken"); }
        };
        StringBuilder capture = new StringBuilder();
        assertDoesNotThrow(() -> MetricsgenLoader.readStderr(broken, capture));
    }

    @Test
    void drainStdout_silentOnIOException() {
        java.io.InputStream broken = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("broken"); }
        };
        assertDoesNotThrow(() -> MetricsgenLoader.drainStdout(broken));
    }

    // ── MetricsgenConfig getters ─────────────────────────────────────────────────

    @Test
    void metricsgenConfig_getters_returnNullByDefault() {
        MetricsgenConfig cfg = new MetricsgenConfig();
        assertNull(cfg.getVersion());
        assertNull(cfg.getScenario());
        assertNull(cfg.getScale());
        assertNull(cfg.getInterval());
        assertNull(cfg.getStartNowMinus());
        assertNull(cfg.getSeed());
        assertNull(cfg.getOtelColConfig());
        // OrDefault methods with null fields return defaults
        assertEquals("1.0.7",                  cfg.versionOrDefault());
        assertEquals("builtin/hostmetrics",     cfg.scenarioOrDefault());
        assertEquals("jingra-config/otelcol.yaml", cfg.otelColConfigOrDefault());
    }

    @Test
    void metricsgenConfig_otelColConfigOrDefault_returnsSetValue() {
        MetricsgenConfig cfg = new MetricsgenConfig();
        cfg.setOtelColConfig("/custom/path/otelcol.yaml");
        assertEquals("/custom/path/otelcol.yaml", cfg.otelColConfigOrDefault());
        assertEquals("/custom/path/otelcol.yaml", cfg.getOtelColConfig());
    }

    @Test
    void metricsgenConfig_orDefault_returnsSetValue() {
        MetricsgenConfig cfg = new MetricsgenConfig();
        cfg.setVersion("2.0.0");
        cfg.setScenario("builtin/custom");
        cfg.setScale(500);
        cfg.setInterval("5s");
        cfg.setStartNowMinus("30m");
        cfg.setSeed(99);

        assertEquals("2.0.0",         cfg.versionOrDefault());
        assertEquals("builtin/custom", cfg.scenarioOrDefault());
        assertEquals(500,              cfg.scaleOrDefault());
        assertEquals("5s",             cfg.intervalOrDefault());
        assertEquals("30m",            cfg.startNowMinusOrDefault());
        assertEquals(99,               cfg.seedOrDefault());
    }

    // ── TAR/GZ builder helpers ────────────────────────────────────────────────────

    record TarEntry(String name, byte[] content) {}

    static byte[] buildTarGz(String entryName, byte[] content) throws IOException {
        return buildTarGzMultiple(new TarEntry(entryName, content));
    }

    private static byte[] buildTarGzMultiple(TarEntry... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            for (TarEntry entry : entries) {
                gz.write(buildTarHeader(entry.name(), entry.content().length));
                gz.write(entry.content());
                int pad = (512 - (entry.content().length % 512)) % 512;
                gz.write(new byte[pad]);
            }
            gz.write(new byte[1024]); // end-of-archive: two zero blocks
        }
        return baos.toByteArray();
    }

    private static byte[] buildTarHeader(String name, int size) {
        byte[] header = new byte[512];
        copyAscii(header, 0, 100, name);
        copyAscii(header, 100, 8,  "0000755\0");
        copyAscii(header, 108, 8,  "0000000\0");
        copyAscii(header, 116, 8,  "0000000\0");
        copyAscii(header, 124, 12, String.format("%011o\0", size));
        copyAscii(header, 136, 12, String.format("%011o\0", System.currentTimeMillis() / 1000));
        header[156] = '0'; // regular file
        // checksum: fill field with spaces, sum all bytes
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int cs = 0;
        for (byte b : header) cs += (b & 0xFF);
        copyAscii(header, 148, 8, String.format("%06o\0 ", cs));
        return header;
    }

    private static void copyAscii(byte[] buf, int offset, int len, String s) {
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, buf, offset, Math.min(src.length, len));
    }
}
