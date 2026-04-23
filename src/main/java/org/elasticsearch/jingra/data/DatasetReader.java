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

    @FunctionalInterface
    interface BatchConsumer {
        void accept(List<Document> batch) throws IOException;
    }
}
