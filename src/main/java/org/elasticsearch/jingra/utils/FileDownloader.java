package org.elasticsearch.jingra.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.data.DatasetReaderFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility for downloading files from URLs with validation.
 */
public class FileDownloader {
    private static final Logger logger = LoggerFactory.getLogger(FileDownloader.class);

    private FileDownloader() {
    }

    /**
     * When set (same-package tests only), used instead of {@link System#getenv(String)} for the download URL.
     */
    static final ThreadLocal<String> downloadUrlOverrideForTests = new ThreadLocal<>();

    /**
     * When set (same-package tests only), used as the base URL for
     * {@link #ensureFilesExistFromBaseUrl} instead of the env-var value.
     */
    public static final ThreadLocal<String> downloadBaseUrlOverrideForTests = new ThreadLocal<>();

    /**
     * When set, returned as the GCS list API JSON response body instead of making a real HTTP call.
     */
    public static final ThreadLocal<String> gcsListResponseOverrideForTests = new ThreadLocal<>();

    /**
     * When set, replaces {@code https://storage.googleapis.com/storage/v1/b} as the GCS list API base.
     * Allows tests to point the list API at a local HTTP server.
     */
    public static final ThreadLocal<String> gcsApiBaseUrlOverrideForTests = new ThreadLocal<>();

    /**
     * When set (same-package tests only), minimum milliseconds between download progress log lines.
     * Default production behavior uses 10000 ms.
     */
    static final ThreadLocal<Long> progressLogIntervalMsForTests = new ThreadLocal<>();

    /**
     * Ensure a file exists locally, downloading from URL if needed.
     * Validates Parquet files by checking magic bytes.
     */
    public static void ensureFileExists(String filePath, String urlEnvVar) throws Exception {
        File file = new File(filePath);

        if (file.exists() && file.length() > 0) {
            if (!filePath.endsWith(".parquet") || isValidParquetFile(file)) {
                logger.info("File already exists locally: {} ({} bytes)", filePath, file.length());
                return;
            }
            logger.warn("Existing Parquet file is corrupted, deleting: {}", filePath);
            file.delete();
        }

        // File doesn't exist or is corrupted, download from URL
        String url = downloadUrlOverrideForTests.get();
        if (url == null) {
            url = System.getenv(urlEnvVar);
        }
        if (url == null || url.isEmpty()) {
            throw new RuntimeException("File not found: " + filePath + " and no URL provided in env var: " + urlEnvVar);
        }

        downloadFile(url, file);
    }

    /**
     * Download a file from URL with progress logging. Follows HTTP redirects; retries on 5xx with backoff.
     */
    private static void downloadFile(String url, File file) throws Exception {
        logger.info("Downloading file from {} to {}", url, file.getPath());

        file.getParentFile().mkdirs();

        int attempt = 0;
        while (true) {
            try {
                downloadOnce(url, file);
                return;
            } catch (Exception e) {
                attempt++;
                boolean retryable = retryableDownloadFailureMessage(e.getMessage());
                if (!retryable || attempt == 3) {
                    throw e;
                }
                long backoff = 500L * attempt;
                logger.warn("Download attempt {} failed, retrying after {} ms: {}", attempt, backoff, e.getMessage());
                Thread.sleep(backoff);
            }
        }
    }

    /**
     * Whether a download failure message should be retried (transient HTTP 5xx and related patterns).
     * Package-private for unit tests in the same package.
     */
    static boolean retryableDownloadFailureMessage(String rawMessage) {
        String msg = rawMessage != null ? rawMessage : "";
        return msg.contains("HTTP 5") || msg.contains(" 502 ") || msg.contains(" 503 ")
                || msg.contains(" 504 ");
    }

    private static long progressLogIntervalMs() {
        Long v = progressLogIntervalMsForTests.get();
        if (v != null && v < 0) {
            return Long.MIN_VALUE;
        }
        return v != null ? v : 10_000L;
    }

    private static void downloadOnce(String url, File file) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(300000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Failed to download file. HTTP " + responseCode + ": " + connection.getResponseMessage());
        }

        long contentLength = connection.getContentLengthLong();
        logger.info("Starting download ({} bytes)...", contentLength > 0 ? contentLength : "unknown size");

        try (InputStream in = connection.getInputStream();
             FileOutputStream fos = new FileOutputStream(file)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            long lastLogTime = System.currentTimeMillis();

            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime > progressLogIntervalMs()) {
                    if (contentLength > 0) {
                        double progress = (totalBytesRead * 100.0) / contentLength;
                        logger.info("Download progress: {} / {} bytes ({}%)",
                                totalBytesRead, contentLength, String.format("%.1f", progress));
                    } else {
                        logger.info("Download progress: {} bytes", totalBytesRead);
                    }
                    lastLogTime = currentTime;
                }
            }

            logger.info("Download complete: {} ({} bytes)", file.getPath(), totalBytesRead);
        } catch (Exception e) {
            if (file.exists()) {
                file.delete();
            }
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Ensure all files described by {@code localGlobPath} exist locally, downloading any that
     * are missing from {@code ${baseUrl}/${filename}}.
     *
     * <p>The base URL (read from {@code baseUrlEnvVar}) is a plain URL prefix with no glob
     * characters, e.g. {@code https://storage.googleapis.com/bucket/prefix}.  Filenames are
     * derived from the local path pattern:</p>
     * <ul>
     *   <li>No glob — the single filename is taken from the local path.</li>
     *   <li>{@code [X-Y]} ranges — enumerated without a network call.</li>
     *   <li>{@code *} — a GCS URL is constructed from base + filename pattern and the GCS
     *       JSON list API is called to discover matching objects.</li>
     * </ul>
     */
    public static void ensureFilesExistFromBaseUrl(String localGlobPath, String baseUrlEnvVar)
            throws Exception {
        java.nio.file.Path localPath = Paths.get(localGlobPath);
        java.nio.file.Path localDir  = localPath.getParent();
        if (localDir == null) localDir = Paths.get(".");
        String filenamePattern = localPath.getFileName().toString();

        // Fast path: if all expected files already exist locally, skip the URL entirely.
        if (allFilesExistLocally(localDir, filenamePattern)) {
            logger.info("All files already present locally, skipping download.");
            return;
        }

        String baseUrl = downloadBaseUrlOverrideForTests.get();
        if (baseUrl == null) {
            baseUrl = System.getenv(baseUrlEnvVar);
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new RuntimeException("Base URL not set in env var: " + baseUrlEnvVar);
        }
        // If the URL contains glob characters it is a full URL pattern, not a bare base URL.
        // Strip the filename portion so we get the directory-level base for constructing
        // per-file download URLs (e.g. "https://host/bucket/part-000[0-4].ndjson.gz"
        // becomes "https://host/bucket").
        String trimmedBase;
        if (baseUrl.contains("[") || baseUrl.contains("*")) {
            int lastSlash = baseUrl.lastIndexOf('/');
            trimmedBase = lastSlash > 0 ? baseUrl.substring(0, lastSlash) : baseUrl;
        } else {
            trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        localDir.toFile().mkdirs();

        List<String> filenames = expandFilenamePattern(filenamePattern, trimmedBase);
        for (String filename : filenames) {
            File localFile = localDir.resolve(filename).toFile();
            if (!localFile.exists() || localFile.length() == 0) {
                downloadFile(trimmedBase + "/" + filename, localFile);
            } else {
                logger.info("File already exists: {}", localFile.getPath());
            }
        }
    }

    private static boolean allFilesExistLocally(java.nio.file.Path dir, String pattern) {
        if (!pattern.contains("*") && !pattern.contains("?") && !pattern.contains("[")) {
            File f = dir.resolve(pattern).toFile();
            return f.exists() && f.length() > 0;
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            // Character range — enumerate locally without hitting the network
            for (String filename : DatasetReaderFactory.enumerateGlobPattern(pattern)) {
                File f = dir.resolve(filename).toFile();
                if (!f.exists() || f.length() == 0) return false;
            }
            return true;
        }
        // Wildcard (*/?): use filesystem glob to check if anything is present
        try {
            List<String> existing = DatasetReaderFactory.expandGlob(dir.resolve(pattern).toString());
            return !existing.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> expandFilenamePattern(String pattern, String baseUrl) throws Exception {
        if (pattern.contains("[")) {
            return DatasetReaderFactory.enumerateGlobPattern(pattern);
        }
        if (pattern.contains("*")) {
            String urlPattern = baseUrl + "/" + pattern;
            return extractFilenamesFromUrls(expandGcsWildcardUrl(urlPattern));
        }
        return List.of(pattern);
    }

    private static List<String> extractFilenamesFromUrls(List<String> urls) {
        List<String> names = new ArrayList<>();
        for (String url : urls) {
            names.add(url.substring(url.lastIndexOf('/') + 1));
        }
        return names;
    }

    private static List<String> expandGcsWildcardUrl(String urlPattern) throws Exception {
        // Allow tests to inject any URL pattern by having the list response pre-set.
        // In production, only GCS URLs are supported.
        if (gcsListResponseOverrideForTests.get() == null
                && gcsApiBaseUrlOverrideForTests.get() == null
                && !urlPattern.contains("storage.googleapis.com")) {
            throw new IllegalArgumentException(
                "Wildcard (*) URL patterns are only supported for GCS URLs "
                + "(storage.googleapis.com). Use [X-Y] ranges for other URLs: " + urlPattern);
        }

        // Parse the URL: extract the base (up to and including the last '/' before '*'),
        // the filename prefix, and the filename suffix.
        int starIdx = urlPattern.indexOf('*');
        int lastSlashBeforeStar = urlPattern.lastIndexOf('/', starIdx);
        String urlBase    = urlPattern.substring(0, lastSlashBeforeStar + 1);
        String filePrefix = urlPattern.substring(lastSlashBeforeStar + 1, starIdx);
        String fileSuffix = urlPattern.substring(starIdx + 1);

        // Parse bucket from a GCS URL; fall back to a placeholder for non-GCS test URLs.
        String bucket;
        if (urlPattern.contains("storage.googleapis.com")) {
            String host = "storage.googleapis.com/";
            int bucketStart = urlPattern.indexOf(host) + host.length();
            int slashAfterBucket = urlPattern.indexOf('/', bucketStart);
            bucket = urlPattern.substring(bucketStart, slashAfterBucket);
        } else {
            bucket = "test-bucket"; // only reachable in test mode (non-GCS URL, overrides active)
        }
        List<String> objectNames = listGcsObjectNames(bucket, filePrefix);

        List<String> urls = new ArrayList<>();
        for (String name : objectNames) {
            String filename = name.substring(name.lastIndexOf('/') + 1);
            if (filename.startsWith(filePrefix) && filename.endsWith(fileSuffix)) {
                urls.add(urlBase + filename);
            }
        }
        return urls;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listGcsObjectNames(String bucket, String prefix) throws Exception {
        String json = gcsListResponseOverrideForTests.get();
        if (json == null) {
            String apiBase = gcsApiBaseUrlOverrideForTests.get();
            if (apiBase == null) apiBase = "https://storage.googleapis.com/storage/v1/b";
            String listUrl = apiBase + "/"
                + URLEncoder.encode(bucket, StandardCharsets.UTF_8)
                + "/o?prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(listUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("GCS list API returned HTTP " + conn.getResponseCode()
                    + " for bucket=" + bucket + " prefix=" + prefix);
            }
            try (InputStream in = conn.getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
        }
        Map<String, Object> body = new ObjectMapper().readValue(json, new TypeReference<>() {});
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null) return List.of();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> item : items) {
            names.add((String) item.get("name"));
        }
        return names;
    }

    /**
     * Check if a file is a valid Parquet file by checking magic bytes.
     * Parquet files have "PAR1" magic bytes at the start and end.
     */
    private static boolean isValidParquetFile(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // Check file has at least 8 bytes (4 for header magic, 4 for footer magic)
            if (raf.length() < 8) {
                return false;
            }

            // Read first 4 bytes - should be "PAR1"
            byte[] header = new byte[4];
            raf.read(header);
            String headerMagic = new String(header, StandardCharsets.US_ASCII);

            // Read last 4 bytes - should be "PAR1"
            raf.seek(raf.length() - 4);
            byte[] footer = new byte[4];
            raf.read(footer);
            String footerMagic = new String(footer, StandardCharsets.US_ASCII);

            return "PAR1".equals(headerMagic) && "PAR1".equals(footerMagic);
        } catch (Exception e) {
            logger.warn("Failed to validate Parquet file: {}", e.getMessage());
            return false;
        }
    }
}
