package org.elasticsearch.jingra.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
            // Validate existing Parquet file has correct magic bytes
            if (isValidParquetFile(file)) {
                logger.info("File already exists locally: {} ({} bytes)", filePath, file.length());
                return;
            } else {
                logger.warn("Existing file is corrupted, deleting: {}", filePath);
                file.delete();
            }
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
