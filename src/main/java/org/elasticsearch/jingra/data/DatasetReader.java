package org.elasticsearch.jingra.data;

import org.elasticsearch.jingra.model.Document;

import java.io.IOException;
import java.util.List;

/**
 * Common interface for reading dataset files (Parquet, NDJSON, etc.).
 */
public interface DatasetReader {

    /**
     * Read all documents from the file.
     */
    List<Document> readAll() throws IOException;

    /**
     * Read documents with an optional limit (-1 for all).
     */
    List<Document> readAll(int limit) throws IOException;

    /**
     * Read documents in batches (single-threaded conversion).
     */
    void readInBatches(int batchSize, BatchConsumer consumer) throws IOException;

    /**
     * Read documents in batches with a conversion-thread hint.
     * Implementations that do not benefit from parallel conversion may ignore {@code conversionThreads}.
     */
    void readInBatches(int batchSize, int conversionThreads, BatchConsumer consumer) throws IOException;

    /**
     * Return the total number of records in the file.
     */
    long getRowCount() throws IOException;

    /**
     * Scan the dataset and return the maximum {@code @timestamp} value found, or
     * {@link java.util.Optional#empty()} if the dataset contains no timestamp fields.
     * Used by {@code LoadCommand} to rebase timestamps to "now" at ingest time so that
     * a dataset generated once remains valid for any future benchmark run.
     * Implementations that do not contain {@code @timestamp} fields may return empty.
     */
    default java.util.Optional<java.time.Instant> findMaxTimestamp() throws IOException {
        return java.util.Optional.empty();
    }

    @FunctionalInterface
    interface BatchConsumer {
        void accept(List<Document> batch) throws IOException;
    }
}
