package org.elasticsearch.jingra.data;

import org.apache.parquet.schema.MessageType;
import org.elasticsearch.jingra.model.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ParquetReaderTest {

    private static final String TEXT_DATA_PATH    = "src/test/resources/parquet/test_text_data.parquet";
    private static final String TEXT_QUERIES_PATH = "src/test/resources/parquet/test_text_queries.parquet";
    private static final String VECTOR_DATA_PATH  = "src/test/resources/parquet/test_vector_data.parquet";
    private static final String VECTOR_QUERIES_PATH = "src/test/resources/parquet/test_vector_queries.parquet";
    private static final String HYBRID_DATA_PATH = "src/test/resources/parquet/test_hybrid_data.parquet";
    private static final String HYBRID_QUERIES_PATH = "src/test/resources/parquet/test_hybrid_queries.parquet";

    // ===== readAll =====

    @Test
    void readAll_returnsAllRows() throws Exception {
        assertEquals(100, new ParquetReader(TEXT_DATA_PATH).readAll().size());
    }

    @Test
    void readAll_parsesFieldsCorrectly() throws Exception {
        List<Document> docs = new ParquetReader(TEXT_DATA_PATH).readAll();
        Document first = docs.get(0);
        assertEquals("001", first.getString("id"));
        assertEquals("Wireless Bluetooth Headphones Pro", first.getString("title"));
    }

    @Test
    void readAll_parsesArrayField() throws Exception {
        List<Document> docs = new ParquetReader(VECTOR_DATA_PATH).readAll();
        Object embedding = docs.get(0).get("embedding");
        assertInstanceOf(List.class, embedding);
        assertEquals(10, ((List<?>) embedding).size());
    }

    @Test
    void readAll_withLimit_respectsLimit() throws Exception {
        assertEquals(3, new ParquetReader(TEXT_DATA_PATH).readAll(3).size());
    }

    @Test
    void readAll_withNegativeLimit_returnsAll() throws Exception {
        assertEquals(100, new ParquetReader(TEXT_DATA_PATH).readAll(-1).size());
    }

    @Test
    void readAll_withZeroLimit_returnsAll() throws Exception {
        assertEquals(100, new ParquetReader(TEXT_DATA_PATH).readAll(0).size());
    }

    // ===== getRowCount =====

    @Test
    void getRowCount_returnsCorrectCount() throws Exception {
        assertEquals(100, new ParquetReader(TEXT_DATA_PATH).getRowCount());
    }

    // ===== getSchema =====

    @Test
    void getSchema_returnsNonEmptyMessageType() throws Exception {
        MessageType schema = new ParquetReader(TEXT_DATA_PATH).getSchema();
        assertNotNull(schema);
        assertFalse(schema.getFields().isEmpty());
    }

    @Test
    void getSchema_includesExpectedColumnsForTextData() throws Exception {
        MessageType schema = new ParquetReader(TEXT_DATA_PATH).getSchema();
        Set<String> names = schema.getFields().stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue(names.contains("id"));
        assertTrue(names.contains("title"));
    }

    @Test
    void getSchema_includesEmbeddingForVectorQueries() throws Exception {
        MessageType schema = new ParquetReader(VECTOR_QUERIES_PATH).getSchema();
        Set<String> names = schema.getFields().stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue(names.contains("embedding"), () -> "unexpected columns: " + names);
    }

    @Test
    void getSchema_includesQueryTextAndEmbeddingForHybridQueries() throws Exception {
        MessageType schema = new ParquetReader(HYBRID_QUERIES_PATH).getSchema();
        Set<String> names = schema.getFields().stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue(names.contains("query_text"), () -> "unexpected columns: " + names);
        assertTrue(names.contains("embedding"), () -> "unexpected columns: " + names);
    }

    @Test
    void getSchema_includesTitleAndEmbeddingForHybridData() throws Exception {
        MessageType schema = new ParquetReader(HYBRID_DATA_PATH).getSchema();
        Set<String> names = schema.getFields().stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue(names.contains("title"), () -> "unexpected columns: " + names);
        assertTrue(names.contains("embedding"), () -> "unexpected columns: " + names);
    }

    // ===== readInBatches =====

    @Test
    void readInBatches_deliversAllDocuments() throws Exception {
        List<Document> collected = new ArrayList<>();
        new ParquetReader(TEXT_DATA_PATH).readInBatches(2, collected::addAll);
        assertEquals(100, collected.size());
    }

    @Test
    void readInBatches_respectsBatchSize() throws Exception {
        List<Integer> sizes = new ArrayList<>();
        new ParquetReader(TEXT_DATA_PATH).readInBatches(2, batch -> sizes.add(batch.size()));
        // 100 docs / batch size 2 → 50 full batches of 2
        assertEquals(50, sizes.size());
        assertTrue(sizes.stream().allMatch(s -> s == 2));
    }

    @Test
    void readInBatches_withConversionThreadsHint_deliversAllDocuments() throws Exception {
        List<Document> collected = new ArrayList<>();
        new ParquetReader(TEXT_DATA_PATH).readInBatches(3, 4, collected::addAll);
        assertEquals(100, collected.size());
    }

    // ===== vector field =====

    @Test
    void readAll_vectorField_parsedAsFloatList() throws Exception {
        List<Document> queries = new ParquetReader(VECTOR_QUERIES_PATH).readAll();
        assertEquals(10, queries.size());

        List<Float> embedding = queries.get(0).getFloatList("embedding");
        assertNotNull(embedding);
        assertEquals(10, embedding.size());
        assertEquals(1.0f, embedding.get(0), 1e-6f);
        assertEquals(0.0f, embedding.get(1), 1e-6f);
    }

    @Test
    void readAll_groundTruthField_parsedAsStringList() throws Exception {
        List<Document> queries = new ParquetReader(VECTOR_QUERIES_PATH).readAll();

        @SuppressWarnings("unchecked")
        List<String> groundTruth = (List<String>) queries.get(0).get("ground_truth");
        assertNotNull(groundTruth);
        assertEquals(10, groundTruth.size());
        assertEquals("001", groundTruth.get(0));
    }

    // ===== consistency: ground truth IDs exist in data =====

    @Test
    void textGroundTruthIdsExistInTextData() throws Exception {
        Set<String> docIds = new ParquetReader(TEXT_DATA_PATH).readAll().stream()
                .map(d -> d.getString("id"))
                .collect(Collectors.toSet());
        for (Document query : new ParquetReader(TEXT_QUERIES_PATH).readAll()) {
            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) query.get("ground_truth");
            for (String id : groundTruth) {
                assertTrue(docIds.contains(id),
                        "Ground truth ID '" + id + "' not found in " + TEXT_DATA_PATH);
            }
        }
    }

    @Test
    void vectorGroundTruthIdsExistInVectorData() throws Exception {
        Set<String> docIds = new ParquetReader(VECTOR_DATA_PATH).readAll().stream()
                .map(d -> d.getString("id"))
                .collect(Collectors.toSet());
        for (Document query : new ParquetReader(VECTOR_QUERIES_PATH).readAll()) {
            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) query.get("ground_truth");
            for (String id : groundTruth) {
                assertTrue(docIds.contains(id),
                        "Ground truth ID '" + id + "' not found in " + VECTOR_DATA_PATH);
            }
        }
    }

    @Test
    void hybridGroundTruthIdsExistInHybridData() throws Exception {
        Set<String> docIds = new ParquetReader(HYBRID_DATA_PATH).readAll().stream()
                .map(d -> d.getString("id"))
                .collect(Collectors.toSet());
        for (Document query : new ParquetReader(HYBRID_QUERIES_PATH).readAll()) {
            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) query.get("ground_truth");
            for (String id : groundTruth) {
                assertTrue(docIds.contains(id),
                        "Ground truth ID '" + id + "' not found in " + HYBRID_DATA_PATH);
            }
        }
    }

    @Test
    void readAll_hybridQueries_matchNdjsonFixtureShape() throws Exception {
        List<Document> queries = new ParquetReader(HYBRID_QUERIES_PATH).readAll();
        assertEquals(10, queries.size());
        assertEquals("coffee", queries.get(0).getString("query_text"));
        assertEquals(10, queries.get(0).getFloatList("embedding").size());
    }

    // ===== thread-count validation =====

    @Test
    void readInBatches_invalidThreadCount_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ParquetReader(TEXT_DATA_PATH).readInBatches(5, 0, batch -> {}));
        assertTrue(ex.getMessage().contains("conversionThreads must be >= 1"));
    }

    @Test
    void readInBatches_negativeThreadCount_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ParquetReader(TEXT_DATA_PATH).readInBatches(5, -1, batch -> {}));
        assertTrue(ex.getMessage().contains("conversionThreads must be >= 1"));
    }

    // ===== executor shutdown paths =====

    static final class AwaitNeverTerminatesPool extends ThreadPoolExecutor {
        volatile boolean shutdownNowInvoked;

        AwaitNeverTerminatesPool(int threads) {
            super(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowInvoked = true;
            return super.shutdownNow();
        }
    }

    static final class InterruptOnAwaitPool extends ThreadPoolExecutor {
        private volatile boolean firstAwait = true;

        InterruptOnAwaitPool(int threads) {
            super(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (firstAwait) {
                firstAwait = false;
                throw new InterruptedException("test interrupt");
            }
            return super.awaitTermination(timeout, unit);
        }
    }

    static final class ParquetReaderWithInjectablePool extends ParquetReader {
        private final IntFunction<java.util.concurrent.ExecutorService> poolFactory;

        ParquetReaderWithInjectablePool(String filePath,
                IntFunction<java.util.concurrent.ExecutorService> poolFactory) {
            super(filePath);
            this.poolFactory = poolFactory;
        }

        @Override
        java.util.concurrent.ExecutorService createConversionPool(int conversionThreads) {
            return poolFactory.apply(conversionThreads);
        }
    }

    @Test
    void readInBatches_whenAwaitTerminationReturnsFalse_callsShutdownNow() throws Exception {
        AwaitNeverTerminatesPool pool = new AwaitNeverTerminatesPool(2);
        ParquetReaderWithInjectablePool reader =
                new ParquetReaderWithInjectablePool(TEXT_DATA_PATH, n -> pool);
        List<Document> docs = new ArrayList<>();
        reader.readInBatches(5, 2, docs::addAll);
        assertEquals(100, docs.size());
        assertTrue(pool.shutdownNowInvoked);
    }

    @Test
    void readInBatches_whenAwaitTerminationInterrupted_rethrowsIOExceptionAndSetsInterruptFlag()
            throws Exception {
        InterruptOnAwaitPool pool = new InterruptOnAwaitPool(2);
        ParquetReaderWithInjectablePool reader =
                new ParquetReaderWithInjectablePool(TEXT_DATA_PATH, n -> pool);
        try {
            IOException ex = assertThrows(IOException.class,
                    () -> reader.readInBatches(5, 2, batch -> {}));
            assertEquals("Interrupted while waiting for conversion pool shutdown", ex.getMessage());
            assertInstanceOf(InterruptedException.class, ex.getCause());
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
