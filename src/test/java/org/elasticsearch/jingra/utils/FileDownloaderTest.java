package org.elasticsearch.jingra.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileDownloader utility class.
 */
class FileDownloaderTest {

    @TempDir
    Path tempDir;

    private String testEnvVar;
    private String originalEnvValue;

    @BeforeEach
    void setup() {
        testEnvVar = "TEST_DOWNLOAD_URL";
    }

    @AfterEach
    void cleanup() {
        // Clean up any test files
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
            // Wrong footer
            fos.write("EFGH".getBytes(StandardCharsets.US_ASCII));
        }

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () ->
            FileDownloader.ensureFileExists(invalidFile.getAbsolutePath(), "MISSING_VAR")
        );

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

    /**
     * Note: Testing actual HTTP downloads would require:
     * 1. Mocking HttpURLConnection (complex, requires PowerMock or similar)
     * 2. Running a local test HTTP server
     * 3. Using a real public URL (unreliable for tests)
     *
     * The current tests cover:
     * - File existence checks
     * - Parquet validation logic (magic bytes)
     * - Error handling for missing env vars
     * - Corrupted file cleanup
     *
     * Integration tests with Testcontainers could provide an HTTP server
     * for more realistic download testing if needed.
     */
}
