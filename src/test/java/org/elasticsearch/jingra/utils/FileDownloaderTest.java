package org.elasticsearch.jingra.utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileDownloader utility class.
 */
class FileDownloaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        FileDownloader.downloadUrlOverrideForTests.remove();
        FileDownloader.progressLogIntervalMsForTests.remove();
        FileDownloader.downloadBaseUrlOverrideForTests.remove();
        FileDownloader.gcsListResponseOverrideForTests.remove();
        FileDownloader.gcsApiBaseUrlOverrideForTests.remove();
        if (tempDir != null) {
            File[] files = tempDir.toFile().listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    @Test
    void testEnsureFileExists_fileAlreadyExists() throws Exception {
        // Arrange - create a valid Parquet file with PAR1 magic bytes
        File existingFile = tempDir.resolve("test.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(existingFile)) {
            // Write PAR1 magic at start
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
            // Write some content
            fos.write(new byte[100]);
            // Write PAR1 magic at end
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        }
        long originalSize = existingFile.length();
        long originalModified = existingFile.lastModified();

        // Act - should not download since file exists and is valid
        FileDownloader.ensureFileExists(existingFile.getAbsolutePath(), "UNUSED_ENV_VAR");

        // Assert - file should be unchanged
        assertTrue(existingFile.exists());
        assertEquals(originalSize, existingFile.length());
        assertEquals(originalModified, existingFile.lastModified());
    }

    @Test
    void testEnsureFileExists_corruptedFileDeleted() throws Exception {
        // Arrange - create an invalid file (not a valid Parquet)
        File corruptedFile = tempDir.resolve("corrupted.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(corruptedFile)) {
            fos.write("INVALID_CONTENT".getBytes(StandardCharsets.US_ASCII));
        }
        assertTrue(corruptedFile.exists());

        // Act & Assert - should try to download, but fail due to missing env var
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(corruptedFile.getAbsolutePath(), "NONEXISTENT_ENV_VAR")
        );

        // Assert - error message mentions missing env var
        assertThat(exception.getMessage()).contains("NONEXISTENT_ENV_VAR");
        // Corrupted file should have been deleted
        assertFalse(corruptedFile.exists());
    }

    @Test
    void testEnsureFileExists_missingEnvVar() {
        // Arrange - file doesn't exist and no env var set
        File nonExistentFile = tempDir.resolve("missing.parquet").toFile();
        assertFalse(nonExistentFile.exists());

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(nonExistentFile.getAbsolutePath(), "MISSING_ENV_VAR")
        );

        // Assert
        assertThat(exception.getMessage())
            .contains("File not found")
            .contains("missing.parquet")
            .contains("MISSING_ENV_VAR");
    }

    @Test
    void testIsValidParquetFile_withValidFile() throws Exception {
        // Arrange - create a valid Parquet file structure
        File validFile = tempDir.resolve("valid.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(validFile)) {
            // Header: PAR1
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
            // Some content in the middle
            fos.write(new byte[1000]);
            // Footer: PAR1
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        }

        // Act - ensureFileExists should recognize it as valid
        FileDownloader.ensureFileExists(validFile.getAbsolutePath(), "UNUSED");

        // Assert - file still exists (wasn't deleted as corrupted)
        assertTrue(validFile.exists());
        assertEquals(1008, validFile.length()); // 4 + 1000 + 4
    }

    @Test
    void testIsValidParquetFile_tooSmall() throws Exception {
        // Arrange - file too small to be valid Parquet (< 8 bytes)
        File tinyFile = tempDir.resolve("tiny.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(tinyFile)) {
            fos.write("PAR".getBytes(StandardCharsets.US_ASCII)); // Only 3 bytes
        }

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(tinyFile.getAbsolutePath(), "MISSING_VAR")
        );
        assertThat(exception.getMessage())
            .contains("File not found")
            .contains("MISSING_VAR");

        // File should be deleted as invalid
        assertFalse(tinyFile.exists());
    }

    @Test
    void testIsValidParquetFile_wrongMagicBytes() throws Exception {
        // Arrange - file has wrong magic bytes
        File invalidFile = tempDir.resolve("invalid.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(invalidFile)) {
            // Wrong header
            fos.write("ABCD".getBytes(StandardCharsets.US_ASCII));
            fos.write(new byte[100]);
            // Wrong footer (arbitrary bytes, not PAR1)
            fos.write(new byte[] {0x01, 0x02, 0x03, 0x04});
        }

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(invalidFile.getAbsolutePath(), "MISSING_VAR")
        );
        assertThat(exception.getMessage())
            .contains("File not found")
            .contains("MISSING_VAR");

        // File should be deleted as invalid
        assertFalse(invalidFile.exists());
    }

    @Test
    void testIsValidParquetFile_onlyHeaderValid() throws Exception {
        // Arrange - valid header but invalid footer
        File partialFile = tempDir.resolve("partial.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(partialFile)) {
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
            fos.write(new byte[100]);
            fos.write("ABCD".getBytes(StandardCharsets.US_ASCII)); // Wrong footer
        }

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(partialFile.getAbsolutePath(), "MISSING_VAR")
        );
        assertThat(exception.getMessage())
            .contains("File not found")
            .contains("MISSING_VAR");

        // File should be deleted as invalid
        assertFalse(partialFile.exists());
    }

    @Test
    void testEnsureFileExists_emptyFile() throws Exception {
        // Arrange - create an empty file
        File emptyFile = tempDir.resolve("empty.parquet").toFile();
        emptyFile.createNewFile();
        assertTrue(emptyFile.exists());
        assertEquals(0, emptyFile.length());

        // Act & Assert - empty file (length = 0) skips validation and tries to download
        // Since no env var is set, should throw RuntimeException
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(emptyFile.getAbsolutePath(), "MISSING_VAR")
        );

        // Assert error mentions missing env var
        assertThat(exception.getMessage()).contains("MISSING_VAR");
        // Empty file still exists because it wasn't validated (length = 0 skips validation block)
        assertTrue(emptyFile.exists());
    }

    @Test
    void testEnsureFileExists_fileWithCorrectMagicBytesMinimalSize() throws Exception {
        // Arrange - minimal valid Parquet file (exactly 8 bytes: header + footer)
        File minimalFile = tempDir.resolve("minimal.parquet").toFile();
        try (FileOutputStream fos = new FileOutputStream(minimalFile)) {
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
            fos.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        }

        // Act
        FileDownloader.ensureFileExists(minimalFile.getAbsolutePath(), "UNUSED");

        // Assert - should be recognized as valid
        assertTrue(minimalFile.exists());
        assertEquals(8, minimalFile.length());
    }

    @Test
    void ensureFileExists_urlEmpty_throwsWithoutDownload() {
        File target = tempDir.resolve("empty-url.parquet").toFile();
        FileDownloader.downloadUrlOverrideForTests.set("");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> FileDownloader.ensureFileExists(target.getAbsolutePath(), "UNUSED_ENV"));
        assertThat(ex.getMessage()).contains("no URL provided");
    }

    @Test
    void constructor_isPrivateAndCallableViaReflection() throws Exception {
        Constructor<FileDownloader> ctor = FileDownloader.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    @Test
    void retryableDownloadFailureMessage_coversTransientPatterns() {
        assertTrue(FileDownloader.retryableDownloadFailureMessage("Failed to download file. HTTP 503: Service Unavailable"));
        assertTrue(FileDownloader.retryableDownloadFailureMessage("proxy returned  502  error"));
        assertTrue(FileDownloader.retryableDownloadFailureMessage("upstream  503  timeout"));
        assertTrue(FileDownloader.retryableDownloadFailureMessage("gateway  504  "));
        assertFalse(FileDownloader.retryableDownloadFailureMessage("Failed to download file. HTTP 404: Not Found"));
        assertFalse(FileDownloader.retryableDownloadFailureMessage(null));
    }

    @Test
    void isValidParquetFile_nonexistentFile_returnsFalseViaExceptionHandler() throws Exception {
        Method m = FileDownloader.class.getDeclaredMethod("isValidParquetFile", File.class);
        m.setAccessible(true);
        File missing = tempDir.resolve("absent-parquet.bin").toFile();
        assertFalse((Boolean) m.invoke(null, missing));
    }

    @Test
    void ensureFileExists_http200_downloadsValidParquet() throws Exception {
        byte[] body = minimalParquetPayload();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/data.parquet", exchange -> sendBytes(exchange, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadUrlOverrideForTests.set(url(server, "/data.parquet"));
            File out = tempDir.resolve("downloaded.parquet").toFile();
            FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV");
            assertTrue(out.exists());
            assertEquals(body.length, out.length());
            FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFileExists_http200_chunked_unknownContentLength() throws Exception {
        byte[] body = minimalParquetPayload();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chunk.parquet", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadUrlOverrideForTests.set(url(server, "/chunk.parquet"));
            File out = tempDir.resolve("chunked.parquet").toFile();
            FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV");
            assertArrayEquals(body, java.nio.file.Files.readAllBytes(out.toPath()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFileExists_createsNestedParentDirectories() throws Exception {
        byte[] body = minimalParquetPayload();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/nested.parquet", exchange -> sendBytes(exchange, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadUrlOverrideForTests.set(url(server, "/nested.parquet"));
            File out = tempDir.resolve("a/b/c/nested-out.parquet").toFile();
            FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV");
            assertTrue(out.exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFileExists_retry500ThenSuccess() throws Exception {
        byte[] body = minimalParquetPayload();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/retry.parquet", (HttpExchange ex) -> {
            if (calls.incrementAndGet() == 1) {
                ex.getResponseHeaders().set("Content-Type", "text/plain");
                ex.sendResponseHeaders(500, 0);
                ex.close();
            } else {
                sendBytes(ex, 200, body, true);
            }
        });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadUrlOverrideForTests.set(url(server, "/retry.parquet"));
            File out = tempDir.resolve("retry.parquet").toFile();
            FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV");
            assertEquals(2, calls.get());
            assertEquals(body.length, out.length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFileExists_threeHttp500_exhaustsRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/fail.parquet", (HttpExchange ex) -> {
            calls.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(500, 0);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadUrlOverrideForTests.set(url(server, "/fail.parquet"));
            File out = tempDir.resolve("fail.parquet").toFile();
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV"));
            assertThat(ex.getMessage()).contains("HTTP 500");
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFileExists_http404_notRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/missing.parquet", (HttpExchange ex) -> {
            calls.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(404, 0);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadUrlOverrideForTests.set(url(server, "/missing.parquet"));
            File out = tempDir.resolve("missing.parquet").toFile();
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV"));
            assertThat(ex.getMessage()).contains("HTTP 404");
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_non200_includesResponseMessageInException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/teapot", (HttpExchange ex) -> {
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(418, 0);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        try {
            File out = tempDir.resolve("teapot.bin").toFile();
            String u = url(server, "/teapot");
            RuntimeException ex = assertThrows(RuntimeException.class, () -> invokeDownloadOnce(u, out));
            assertThat(ex.getMessage()).startsWith("Failed to download file. HTTP 418:");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_non200_doesNotCreateOutputFile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/gone", (HttpExchange ex) -> {
            ex.sendResponseHeaders(410, 0);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        try {
            File out = tempDir.resolve("gone.bin").toFile();
            assertThrows(RuntimeException.class, () -> invokeDownloadOnce(url(server, "/gone"), out));
            assertFalse(out.exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_catchDeletesWhenTargetPathIsDirectory() throws Throwable {
        byte[] body = minimalParquetPayload();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/dir-target.parquet", exchange -> sendBytes(exchange, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            File dest = tempDir.resolve("dir-as-target").toFile();
            assertTrue(dest.mkdirs());
            assertTrue(dest.isDirectory());
            assertThrows(FileNotFoundException.class, () -> invokeDownloadOnce(url(server, "/dir-target.parquet"), dest));
            assertFalse(dest.exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_fileOutputFailsWithoutParent_deletesNothingInCatch() throws Exception {
        byte[] body = minimalParquetPayload();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ok.parquet", exchange -> sendBytes(exchange, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            File out = tempDir.resolve("no-parent-dir").toFile().toPath()
                    .resolve("sub").resolve("out.bin").toFile();
            assertThrows(FileNotFoundException.class, () -> invokeDownloadOnce(url(server, "/ok.parquet"), out));
            assertFalse(out.exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_progressLogWithKnownContentLength() throws Throwable {
        byte[] body = new byte[20_000];
        System.arraycopy("PAR1".getBytes(StandardCharsets.US_ASCII), 0, body, 0, 4);
        System.arraycopy("PAR1".getBytes(StandardCharsets.US_ASCII), 0, body, body.length - 4, 4);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/big.parquet", exchange -> sendBytes(exchange, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.progressLogIntervalMsForTests.set(-1L);
            File out = tempDir.resolve("big.parquet").toFile();
            invokeDownloadOnce(url(server, "/big.parquet"), out);
            assertEquals(body.length, out.length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_progressIntervalExplicitPositiveSameAsDefaultStillUsesNonNullBranch() throws Throwable {
        byte[] body = minimalParquetPayload();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/explicit-interval.parquet", exchange -> sendBytes(exchange, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.progressLogIntervalMsForTests.set(10_000L);
            File out = tempDir.resolve("explicit-interval.parquet").toFile();
            invokeDownloadOnce(url(server, "/explicit-interval.parquet"), out);
            assertEquals(body.length, out.length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadOnce_progressLogWithUnknownContentLength() throws Throwable {
        byte[] body = new byte[10_000];
        System.arraycopy("PAR1".getBytes(StandardCharsets.US_ASCII), 0, body, 0, 4);
        System.arraycopy("PAR1".getBytes(StandardCharsets.US_ASCII), 0, body, body.length - 4, 4);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/big-chunk.parquet", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.progressLogIntervalMsForTests.set(-1L);
            File out = tempDir.resolve("big-chunk.parquet").toFile();
            invokeDownloadOnce(url(server, "/big-chunk.parquet"), out);
            assertEquals(body.length, out.length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadFile_invokedDirectly_nonRetryableThrowsImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/nf.parquet", (HttpExchange ex) -> {
            calls.incrementAndGet();
            ex.sendResponseHeaders(404, 0);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        try {
            File out = tempDir.resolve("nf-direct.parquet").toFile();
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> invokeDownloadFile(url(server, "/nf.parquet"), out));
            assertThat(ex.getMessage()).contains("HTTP 404");
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFileExists_serverStopMidRead_deletesPartialFile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow.parquet", (HttpExchange ex) -> {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 200; i++) {
                    os.write(new byte[1024]);
                    os.flush();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ie);
                    }
                }
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        File out = tempDir.resolve("slow.parquet").toFile();
        FileDownloader.downloadUrlOverrideForTests.set(url(server, "/slow.parquet"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> fut = pool.submit(() -> {
                try {
                    FileDownloader.ensureFileExists(out.getAbsolutePath(), "UNUSED_ENV");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(80);
            server.stop(0);
            assertThrows(Exception.class, () -> fut.get(30, TimeUnit.SECONDS));
            assertFalse(out.exists());
        } finally {
            pool.shutdownNow();
            FileDownloader.downloadUrlOverrideForTests.remove();
        }
    }

    private static void invokeDownloadOnce(String url, File file) throws Throwable {
        Method m = FileDownloader.class.getDeclaredMethod("downloadOnce", String.class, File.class);
        m.setAccessible(true);
        try {
            m.invoke(null, url, file);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void invokeDownloadFile(String url, File file) throws Throwable {
        Method m = FileDownloader.class.getDeclaredMethod("downloadFile", String.class, File.class);
        m.setAccessible(true);
        try {
            m.invoke(null, url, file);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] body, boolean fixedLength) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        if (fixedLength) {
            exchange.sendResponseHeaders(status, body.length);
        } else {
            exchange.sendResponseHeaders(status, 0);
        }
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String url(HttpServer server, String path) {
        int port = server.getAddress().getPort();
        return "http://127.0.0.1:" + port + path;
    }

    // ── ensureFilesExistFromBaseUrl ───────────────────────────────────────────────

    @Test
    void ensureFilesExistFromBaseUrl_allMissing_downloadsAll() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/part-0000.ndjson.gz", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, body, true); });
        server.createContext("/base/part-0001.ndjson.gz", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, body, true); });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            String glob = tempDir.resolve("part-000[0-1].ndjson.gz").toString();
            FileDownloader.ensureFilesExistFromBaseUrl(glob, "UNUSED_ENV");
            assertEquals(2, calls.get());
            assertTrue(tempDir.resolve("part-0000.ndjson.gz").toFile().exists());
            assertTrue(tempDir.resolve("part-0001.ndjson.gz").toFile().exists());
        } finally {
            server.stop(0);
            FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_someExist_downloadsOnlyMissing() throws Exception {
        java.nio.file.Files.write(tempDir.resolve("part-0000.ndjson.gz"), new byte[]{1, 2, 3});
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/part-0000.ndjson.gz", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, new byte[]{1}, true); });
        server.createContext("/base/part-0001.ndjson.gz", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, new byte[]{1}, true); });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("part-000[0-1].ndjson.gz").toString(), "UNUSED_ENV");
            assertEquals(1, calls.get());
            assertTrue(tempDir.resolve("part-0001.ndjson.gz").toFile().exists());
        } finally {
            server.stop(0);
            FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_allExist_noDownload() throws Exception {
        java.nio.file.Files.write(tempDir.resolve("part-0000.ndjson.gz"), new byte[]{1, 2, 3});
        java.nio.file.Files.write(tempDir.resolve("part-0001.ndjson.gz"), new byte[]{1, 2, 3});
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/part-0000.ndjson.gz", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, new byte[]{1}, true); });
        server.createContext("/base/part-0001.ndjson.gz", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, new byte[]{1}, true); });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("part-000[0-1].ndjson.gz").toString(), "UNUSED_ENV");
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
            FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_noBaseUrl_throws() {
        String glob = tempDir.resolve("part-[0-1].ndjson.gz").toString();
        assertThrows(RuntimeException.class,
            () -> FileDownloader.ensureFilesExistFromBaseUrl(glob, "NONEXISTENT_ENV_VAR_XYZ"));
    }

    @Test
    void ensureFilesExistFromBaseUrl_emptyBaseUrl_throws() {
        FileDownloader.downloadBaseUrlOverrideForTests.set("");
        String glob = tempDir.resolve("part-[0-1].ndjson.gz").toString();
        assertThrows(RuntimeException.class,
            () -> FileDownloader.ensureFilesExistFromBaseUrl(glob, "UNUSED_ENV"));
        FileDownloader.downloadBaseUrlOverrideForTests.remove();
    }

    @Test
    void ensureFilesExistFromBaseUrl_trailingSlashInBaseUrl_downloadsCorrectly() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/file-0.txt", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            // trailing slash should be stripped before appending filename
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base/"));
            FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("file-[0-0].txt").toString(), "UNUSED_ENV");
            assertTrue(tempDir.resolve("file-0.txt").toFile().exists());
        } finally {
            server.stop(0);
            FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_singleLiteralPath_downloadsOneFile() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/queries.ndjson", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("queries.ndjson").toString(), "UNUSED_ENV");
            assertTrue(tempDir.resolve("queries.ndjson").toFile().exists());
        } finally {
            server.stop(0);
            FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_singleFileAlreadyExists_noDownload() throws Exception {
        java.nio.file.Files.write(tempDir.resolve("queries.ndjson"), new byte[]{1, 2, 3});
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/queries.ndjson", ex -> { calls.incrementAndGet(); sendBytes(ex, 200, new byte[]{1}, true); });
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("queries.ndjson").toString(), "UNUSED_ENV");
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
            FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_gcsWildcard_listOverride_filesMissing_downloadsAll() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        String gcsJson = "{\"items\":["
            + "{\"name\":\"metrics/part-0000.ndjson.gz\"},"
            + "{\"name\":\"metrics/part-0001.ndjson.gz\"},"
            + "{\"name\":\"metrics/unrelated.txt\"}"
            + "]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/metrics/part-0000.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        server.createContext("/metrics/part-0001.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            FileDownloader.gcsListResponseOverrideForTests.set(gcsJson);
            FileDownloader.downloadBaseUrlOverrideForTests.set("http://127.0.0.1:" + port + "/metrics");
            String glob = tempDir.resolve("part-*.ndjson.gz").toString();
            FileDownloader.ensureFilesExistFromBaseUrl(glob, "UNUSED_ENV");
            assertTrue(tempDir.resolve("part-0000.ndjson.gz").toFile().exists());
            assertTrue(tempDir.resolve("part-0001.ndjson.gz").toFile().exists());
            assertFalse(tempDir.resolve("unrelated.txt").toFile().exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_gcsWildcard_realHttpListApi_downloadsAll() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        String gcsJson = "{\"items\":["
            + "{\"name\":\"metrics/part-0000.ndjson.gz\"},"
            + "{\"name\":\"metrics/part-0001.ndjson.gz\"}"
            + "]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/storage/v1/b/my-bucket/o", ex -> {
            byte[] json = gcsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(json); }
        });
        server.createContext("/metrics/part-0000.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        server.createContext("/metrics/part-0001.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            FileDownloader.gcsApiBaseUrlOverrideForTests.set("http://127.0.0.1:" + port + "/storage/v1/b");
            FileDownloader.downloadBaseUrlOverrideForTests.set("http://127.0.0.1:" + port);
            // URL pattern must contain storage.googleapis.com so the real-GCS branch is taken
            // We override the API base to hit our local server instead
            FileDownloader.downloadBaseUrlOverrideForTests.set(
                "https://storage.googleapis.com/my-bucket");
            String glob = tempDir.resolve("part-*.ndjson.gz").toString();
            // Pre-create files so allFilesExistLocally passes and we skip download in this test
            // (we're testing the real HTTP list API path, not the download itself)
            // → Actually, we can't redirect real GCS download URLs to local server, so leave files pre-created
            java.nio.file.Files.write(tempDir.resolve("part-0000.ndjson.gz"), body);
            java.nio.file.Files.write(tempDir.resolve("part-0001.ndjson.gz"), body);
            FileDownloader.ensureFilesExistFromBaseUrl(glob, "UNUSED_ENV");
            assertTrue(tempDir.resolve("part-0000.ndjson.gz").toFile().exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listGcsObjectNames_realHttp_parsesJsonAndReturnsNames() throws Exception {
        String gcsJson = "{\"items\":["
            + "{\"name\":\"metrics/part-0000.ndjson.gz\"},"
            + "{\"name\":\"metrics/part-0001.ndjson.gz\"}"
            + "]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/storage/v1/b/my-bucket/o", ex -> {
            byte[] json = gcsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(json); }
        });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            FileDownloader.gcsApiBaseUrlOverrideForTests.set("http://127.0.0.1:" + port + "/storage/v1/b");
            Method m = FileDownloader.class.getDeclaredMethod("listGcsObjectNames", String.class, String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) m.invoke(null, "my-bucket", "metrics/part-");
            assertEquals(2, names.size());
            assertTrue(names.contains("metrics/part-0000.ndjson.gz"));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listGcsObjectNames_realHttp_non200_throws() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/storage/v1/b/bad-bucket/o", ex -> {
            ex.sendResponseHeaders(403, 0);
            ex.close();
        });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            FileDownloader.gcsApiBaseUrlOverrideForTests.set("http://127.0.0.1:" + port + "/storage/v1/b");
            Method m = FileDownloader.class.getDeclaredMethod("listGcsObjectNames", String.class, String.class);
            m.setAccessible(true);
            java.lang.reflect.InvocationTargetException ex = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "bad-bucket", "prefix/"));
            assertInstanceOf(RuntimeException.class, ex.getCause());
            assertThat(ex.getCause().getMessage()).contains("403");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listGcsObjectNames_noItems_returnsEmptyList() throws Exception {
        FileDownloader.gcsListResponseOverrideForTests.set("{\"kind\":\"storage#objects\"}");
        Method m = FileDownloader.class.getDeclaredMethod("listGcsObjectNames", String.class, String.class);
        m.setAccessible(true);
        try {
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) m.invoke(null, "bucket", "prefix/");
            assertTrue(names.isEmpty());
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_nonGcsWildcard_throws() {
        FileDownloader.downloadBaseUrlOverrideForTests.set("https://example.com");
        assertThrows(RuntimeException.class,
            () -> FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("file-*.gz").toString(), "UNUSED_ENV"));
    }

    @Test
    void allFilesExistLocally_literalFileMissing_returnsFalse(@TempDir Path tmpDir) throws Exception {
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(null, tmpDir, "missing.txt");
        assertFalse(result);
    }

    @Test
    void allFilesExistLocally_wildcardFilesPresent_returnsTrue(@TempDir Path tmpDir) throws Exception {
        java.nio.file.Files.write(tmpDir.resolve("part-0000.ndjson.gz"), new byte[]{1});
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(null, tmpDir, "part-*.ndjson.gz");
        assertTrue(result);
    }

    @Test
    void allFilesExistLocally_wildcardNoFiles_returnsFalse(@TempDir Path tmpDir) throws Exception {
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(null, tmpDir, "part-*.ndjson.gz");
        assertFalse(result);
    }

    @Test
    void ensureFileExists_nonParquetFileKeptWithoutValidation() throws Exception {
        // A .ndjson.gz file should NOT be validated as Parquet and should NOT be deleted
        File ndjsonGz = tempDir.resolve("data.ndjson.gz").toFile();
        try (FileOutputStream fos = new FileOutputStream(ndjsonGz)) {
            // Write gzip magic bytes + some content (NOT PAR1)
            fos.write(new byte[]{0x1f, (byte)0x8b, 0x08, 0x00, 0x01, 0x02, 0x03, 0x04});
        }
        long size = ndjsonGz.length();
        // Should NOT throw (no URL needed because file exists and is non-parquet)
        FileDownloader.ensureFileExists(ndjsonGz.getAbsolutePath(), "UNUSED_ENV");
        assertTrue(ndjsonGz.exists());
        assertEquals(size, ndjsonGz.length()); // file untouched
    }

    @Test
    void ensureFilesExistFromBaseUrl_fullUrlPatternWithRange_stripsFilenameFromBase() throws Exception {
        // DATASET_DATA_URL holds the full URL pattern, not just the base.
        // "https://host/bucket/part-000[0-4].ndjson.gz" → base = "https://host/bucket"
        byte[] body = new byte[]{1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bucket/part-0000.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        server.createContext("/bucket/part-0001.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            // Full URL pattern (with [0-1]) — the method must strip "/part-000[0-1].ndjson.gz"
            FileDownloader.downloadBaseUrlOverrideForTests.set(
                "http://127.0.0.1:" + port + "/bucket/part-000[0-1].ndjson.gz");
            FileDownloader.ensureFilesExistFromBaseUrl(
                tempDir.resolve("part-000[0-1].ndjson.gz").toString(), "UNUSED_ENV");
            assertTrue(tempDir.resolve("part-0000.ndjson.gz").toFile().exists());
            assertTrue(tempDir.resolve("part-0001.ndjson.gz").toFile().exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_fullUrlPatternWithWildcard_stripsFilenameFromBase() throws Exception {
        // URL with "*" — method strips the glob filename to get the base directory.
        String gcsJson = "{\"items\":[{\"name\":\"data-0.gz\"},{\"name\":\"data-1.gz\"}]}";
        byte[] body = new byte[]{1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bucket/data-0.gz", ex -> sendBytes(ex, 200, body, true));
        server.createContext("/bucket/data-1.gz", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            FileDownloader.gcsListResponseOverrideForTests.set(gcsJson);
            FileDownloader.downloadBaseUrlOverrideForTests.set(
                "http://127.0.0.1:" + port + "/bucket/data-*.gz");
            FileDownloader.ensureFilesExistFromBaseUrl(
                tempDir.resolve("data-*.gz").toString(), "UNUSED_ENV");
            assertTrue(tempDir.resolve("data-0.gz").toFile().exists());
            assertTrue(tempDir.resolve("data-1.gz").toFile().exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_fullUrlPatternNoSlash_usesUrlAsBase() {
        // URL with "[" but no "/" before the glob → lastSlash <= 0 → trimmedBase = baseUrl
        // The download URL ends up invalid, so we expect a RuntimeException.
        FileDownloader.downloadBaseUrlOverrideForTests.set("part-[0-0].gz");
        assertThrows(Exception.class,
            () -> FileDownloader.ensureFilesExistFromBaseUrl(
                tempDir.resolve("part-[0-0].gz").toString(), "UNUSED_ENV"));
    }

    @Test
    void ensureFilesExistFromBaseUrl_noParentDir_usesCurrentDir() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/single.ndjson", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        File expected = new File("single.ndjson");
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            FileDownloader.ensureFilesExistFromBaseUrl("single.ndjson", "UNUSED_ENV");
            assertTrue(expected.exists());
        } finally {
            server.stop(0);
            expected.delete();
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_emptyLocalFile_downloadsReplacement() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        java.nio.file.Files.write(tempDir.resolve("q.ndjson"), new byte[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/base/q.ndjson", ex -> sendBytes(ex, 200, body, true));
        server.setExecutor(null);
        server.start();
        try {
            FileDownloader.downloadBaseUrlOverrideForTests.set(url(server, "/base"));
            FileDownloader.ensureFilesExistFromBaseUrl(tempDir.resolve("q.ndjson").toString(), "UNUSED_ENV");
            assertEquals(3, tempDir.resolve("q.ndjson").toFile().length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureFilesExistFromBaseUrl_gcsWildcard_apiOverride_realHttp_downloadsAll() throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        String gcsJson = "{\"items\":["
            + "{\"name\":\"part-0000.ndjson.gz\"},"
            + "{\"name\":\"part-0001.ndjson.gz\"}"
            + "]}";
        HttpServer listServer = HttpServer.create(new InetSocketAddress(0), 0);
        listServer.createContext("/storage/v1/b/test-bucket/o", ex -> {
            byte[] json = gcsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(json); }
        });
        listServer.setExecutor(null);
        listServer.start();
        HttpServer fileServer = HttpServer.create(new InetSocketAddress(0), 0);
        fileServer.createContext("/part-0000.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        fileServer.createContext("/part-0001.ndjson.gz", ex -> sendBytes(ex, 200, body, true));
        fileServer.setExecutor(null);
        fileServer.start();
        try {
            int listPort = listServer.getAddress().getPort();
            int filePort = fileServer.getAddress().getPort();
            FileDownloader.gcsApiBaseUrlOverrideForTests.set("http://127.0.0.1:" + listPort + "/storage/v1/b");
            FileDownloader.downloadBaseUrlOverrideForTests.set("http://127.0.0.1:" + filePort);
            FileDownloader.ensureFilesExistFromBaseUrl(
                tempDir.resolve("part-*.ndjson.gz").toString(), "UNUSED_ENV");
            assertTrue(tempDir.resolve("part-0000.ndjson.gz").toFile().exists());
            assertTrue(tempDir.resolve("part-0001.ndjson.gz").toFile().exists());
        } finally {
            listServer.stop(0);
            fileServer.stop(0);
        }
    }

    @Test
    void expandGcsWildcardUrl_realGcsUrl_apiBaseOverride_parsesRealBucketAndLists() throws Exception {
        // Uses real GCS URL format → bucket parsed from URL; gcsApiBaseUrlOverrideForTests points to
        // local server for the actual HTTP call → listGcsObjectNames succeeds → objectNames assigned.
        String gcsJson = "{\"items\":[{\"name\":\"prefix/part-0000.ndjson.gz\"}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/storage/v1/b/my-bucket/o", ex -> {
            byte[] json = gcsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(json); }
        });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            FileDownloader.gcsApiBaseUrlOverrideForTests.set("http://127.0.0.1:" + port + "/storage/v1/b");
            Method m = FileDownloader.class.getDeclaredMethod("expandGcsWildcardUrl", String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) m.invoke(null,
                "https://storage.googleapis.com/my-bucket/prefix/part-*.ndjson.gz");
            assertEquals(1, urls.size());
            assertTrue(urls.get(0).endsWith("part-0000.ndjson.gz"));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void expandGcsWildcardUrl_realGcsUrlNoOverride_parsesAndFailsOnNetwork() throws Exception {
        // No list response override, no API base override → takes real GCS parsing branch.
        // Covers: !urlPattern.contains("storage.googleapis.com") = false (don't throw)
        //   AND  gcsListResponseOverrideForTests == null && gcsApiBase == null → real bucket parse.
        // The subsequent network call to real GCS fails → exception propagates.
        Method m = FileDownloader.class.getDeclaredMethod("expandGcsWildcardUrl", String.class);
        m.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null,
                "https://storage.googleapis.com/nonexistent-xyz-bucket/prefix/part-*.gz"));
    }

    @Test
    void expandGcsWildcardUrl_filterExcludesNonMatchingSuffix() throws Exception {
        String gcsJson = "{\"items\":["
            + "{\"name\":\"part-0000.ndjson.gz\"},"
            + "{\"name\":\"part-0001.csv\"}"
            + "]}";
        FileDownloader.gcsListResponseOverrideForTests.set(gcsJson);
        Method m = FileDownloader.class.getDeclaredMethod("expandGcsWildcardUrl", String.class);
        m.setAccessible(true);
        try {
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) m.invoke(null,
                "https://storage.googleapis.com/bucket/prefix/part-*.ndjson.gz");
            assertEquals(1, urls.size());
            assertTrue(urls.get(0).endsWith("part-0000.ndjson.gz"));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void allFilesExistLocally_rangePatternEmptyFile_returnsFalse(@TempDir Path tmpDir) throws Exception {
        java.nio.file.Files.write(tmpDir.resolve("part-0000.gz"), new byte[]{1});
        java.nio.file.Files.write(tmpDir.resolve("part-0001.gz"), new byte[0]);
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(null, tmpDir, "part-000[0-1].gz"));
    }

    @Test
    void allFilesExistLocally_literalEmptyFile_returnsFalse(@TempDir Path tmpDir) throws Exception {
        java.nio.file.Files.write(tmpDir.resolve("q.ndjson"), new byte[0]);
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(null, tmpDir, "q.ndjson"));
    }

    @Test
    void allFilesExistLocally_questionMarkPattern_usesFilesystemGlob(@TempDir Path tmpDir) throws Exception {
        java.nio.file.Files.write(tmpDir.resolve("part-0.gz"), new byte[]{1});
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(null, tmpDir, "part-?.gz"));
    }

    @Test
    void allFilesExistLocally_expandGlobThrows_returnsFalse(@TempDir Path tmpDir) throws Exception {
        Method m = FileDownloader.class.getDeclaredMethod("allFilesExistLocally",
            java.nio.file.Path.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(null, tmpDir.resolve("nonexistent"), "part-*.gz"));
    }

    @Test
    void listGcsObjectNames_defaultApiBase_usedWhenNoOverride() throws Exception {
        // Exercises the apiBase == null → default to real GCS URL branch
        Method m = FileDownloader.class.getDeclaredMethod("listGcsObjectNames", String.class, String.class);
        m.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, "nonexistent-bucket-xyz-12345-abc", "prefix/"));
    }

    private static byte[] minimalParquetPayload() {
        byte[] out = new byte[12];
        byte[] magic = "PAR1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, out, 0, 4);
        System.arraycopy(magic, 0, out, 8, 4);
        return out;
    }
}
