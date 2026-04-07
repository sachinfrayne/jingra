package org.elasticsearch.jingra.data;

import org.elasticsearch.jingra.model.Document;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ParquetReader's Avro-to-Java conversion logic with real parquet file.
 */
class ParquetReaderTest {

    private static final String TEST_PARQUET = "src/test/resources/test_data.parquet";

    @Test
    void testReadParquetFile() throws Exception {
        // Verify test file exists
        File testFile = new File(TEST_PARQUET);
        assertTrue(testFile.exists(), "Test parquet file should exist: " + TEST_PARQUET);

        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Get row count
        long rowCount = reader.getRowCount();
        assertEquals(10, rowCount, "Test file should have 10 rows");
    }

    @Test
    void testConvertEmbeddingField() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Read all documents
        List<Document> documents = reader.readAll();

        assertNotNull(documents);
        assertEquals(10, documents.size());

        // Check first document
        Document doc = documents.get(0);
        assertNotNull(doc);

        // Verify catalog_id exists and is a number
        assertTrue(doc.containsField("catalog_id"));
        Object catalogId = doc.get("catalog_id");
        assertTrue(catalogId instanceof Number, "catalog_id should be a Number, got: " + catalogId.getClass());

        // Critical test: search_catalog_embedding should be a List of Floats, NOT GenericRecords
        assertTrue(doc.containsField("search_catalog_embedding"), "Document should have search_catalog_embedding field");

        Object embedding = doc.get("search_catalog_embedding");
        assertNotNull(embedding, "search_catalog_embedding should not be null");

        assertTrue(embedding instanceof List,
                "search_catalog_embedding should be a List, but was: " + embedding.getClass().getName());

        @SuppressWarnings("unchecked")
        List<Object> embeddingList = (List<Object>) embedding;

        assertTrue(embeddingList.size() > 0, "search_catalog_embedding should not be empty");

        // Check that all items are Floats (NOT GenericRecords)
        for (int i = 0; i < embeddingList.size(); i++) {
            Object item = embeddingList.get(i);
            assertNotNull(item, "Embedding item " + i + " should not be null");

            assertFalse(item.getClass().getName().contains("GenericRecord"),
                    "Item " + i + " should NOT be a GenericRecord, but was: " + item.getClass().getName());

            assertTrue(item instanceof Float || item instanceof Double,
                    "Item " + i + " should be Float or Double, but was: " + item.getClass().getName());
        }

        System.out.println("✅ Successfully converted " + embeddingList.size() + " vector elements");
        System.out.println("   First 3 values: " + embeddingList.subList(0, Math.min(3, embeddingList.size())));
    }

    @Test
    void testAllDocumentsConvert() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<Document> documents = reader.readAll();

        // Verify all documents can be converted without GenericRecord leaking through
        for (int docIdx = 0; docIdx < documents.size(); docIdx++) {
            Document doc = documents.get(docIdx);

            Object embedding = doc.get("search_catalog_embedding");
            assertNotNull(embedding, "Doc " + docIdx + " should have search_catalog_embedding");

            assertTrue(embedding instanceof List,
                    "Doc " + docIdx + " embedding should be List");

            @SuppressWarnings("unchecked")
            List<Object> embeddingList = (List<Object>) embedding;

            for (Object item : embeddingList) {
                assertFalse(item.getClass().getName().contains("GenericRecord"),
                        "Doc " + docIdx + " should not contain GenericRecord objects");
            }
        }

        System.out.println("✅ All " + documents.size() + " documents converted successfully");
    }

    @Test
    void testSerializableToJson() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<Document> documents = reader.readAll();
        Document doc = documents.get(0);

        // Try to serialize to JSON using Jackson (same as what Elasticsearch client does)
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        try {
            String json = mapper.writeValueAsString(doc);
            assertNotNull(json);
            assertTrue(json.contains("search_catalog_embedding"));
            assertTrue(json.contains("catalog_id"));
            System.out.println("✅ Successfully serialized to JSON");
            System.out.println("   JSON length: " + json.length() + " bytes");
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            fail("Failed to serialize document to JSON. This is the same error Elasticsearch client encounters: " + e.getMessage());
        }
    }

    // ===== Edge Case Tests =====

    @Test
    void testReadAll_withLimit() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Read only 3 documents with limit
        List<Document> documents = reader.readAll(3);

        assertNotNull(documents);
        assertEquals(3, documents.size(), "Should only read 3 documents when limit=3");

        // Verify each document has expected fields
        for (Document doc : documents) {
            assertTrue(doc.containsField("catalog_id"));
            assertTrue(doc.containsField("search_catalog_embedding"));
        }
    }

    @Test
    void testReadAll_withZeroLimit() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Read with limit=0 is treated as "no limit" (reads all)
        // Implementation: if (limit > 0 && count >= limit) break;
        // So limit=0 doesn't trigger the break condition
        List<Document> documents = reader.readAll(0);

        assertNotNull(documents);
        assertEquals(10, documents.size(), "Should read all documents when limit=0 (no limit)");
    }

    @Test
    void testReadAll_withNegativeLimit() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Negative limit should read all documents
        List<Document> documents = reader.readAll(-1);

        assertNotNull(documents);
        assertEquals(10, documents.size(), "Should read all 10 documents when limit=-1");
    }

    @Test
    void testReadInBatches_withSmallFile() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Use batch size larger than file size
        int batchSize = 100; // File has only 10 rows
        List<List<Document>> batches = new ArrayList<>();

        reader.readInBatches(batchSize, batch -> {
            batches.add(new ArrayList<>(batch)); // Copy to avoid mutation
        });

        // Should have only 1 batch since file is smaller than batch size
        assertEquals(1, batches.size(), "Should have 1 batch when batch size > file size");
        assertEquals(10, batches.get(0).size(), "Single batch should contain all 10 documents");
    }

    @Test
    void testReadInBatches_withBatchSizeOne() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Use batch size of 1 - should create 10 batches
        int batchSize = 1;
        List<List<Document>> batches = new ArrayList<>();

        reader.readInBatches(batchSize, batch -> {
            batches.add(new ArrayList<>(batch));
        });

        // Should have 10 batches, each with 1 document
        assertEquals(10, batches.size(), "Should have 10 batches when batch size=1");
        for (List<Document> batch : batches) {
            assertEquals(1, batch.size(), "Each batch should have 1 document");
        }
    }

    @Test
    void testReadInBatches_withExactDivision() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Use batch size that divides evenly: 5 documents per batch, 2 batches total
        int batchSize = 5;
        List<List<Document>> batches = new ArrayList<>();
        int[] totalDocs = {0};

        reader.readInBatches(batchSize, batch -> {
            batches.add(new ArrayList<>(batch));
            totalDocs[0] += batch.size();
        });

        assertEquals(2, batches.size(), "Should have 2 batches (10 rows / 5 per batch)");
        assertEquals(5, batches.get(0).size(), "First batch should have 5 documents");
        assertEquals(5, batches.get(1).size(), "Second batch should have 5 documents");
        assertEquals(10, totalDocs[0], "Total documents should be 10");
    }

    @Test
    void testReadInBatches_withUnevenDivision() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Use batch size that doesn't divide evenly: 3 documents per batch
        // Should create 4 batches: [3, 3, 3, 1]
        int batchSize = 3;
        List<List<Document>> batches = new ArrayList<>();
        int[] totalDocs = {0};

        reader.readInBatches(batchSize, batch -> {
            batches.add(new ArrayList<>(batch));
            totalDocs[0] += batch.size();
        });

        assertEquals(4, batches.size(), "Should have 4 batches (3+3+3+1)");
        assertEquals(3, batches.get(0).size(), "First batch should have 3 documents");
        assertEquals(3, batches.get(1).size(), "Second batch should have 3 documents");
        assertEquals(3, batches.get(2).size(), "Third batch should have 3 documents");
        assertEquals(1, batches.get(3).size(), "Fourth batch should have 1 document (remainder)");
        assertEquals(10, totalDocs[0], "Total documents should be 10");
    }

    @Test
    void testGetSchema() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Get schema without reading data
        org.apache.parquet.schema.MessageType schema = reader.getSchema();

        assertNotNull(schema, "Schema should not be null");
        assertTrue(schema.getFieldCount() > 0, "Schema should have fields");

        // Verify expected fields exist in schema
        assertNotNull(schema.getType("catalog_id"), "Schema should have catalog_id field");
        assertNotNull(schema.getType("search_catalog_embedding"), "Schema should have embedding field");

        System.out.println("✅ Schema has " + schema.getFieldCount() + " fields");
    }

    @Test
    void testGetRowCount_efficientMetadataRead() throws Exception {
        // This tests that getRowCount() reads only metadata, not full data
        // (Can't measure performance here, but validates the method works)
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        long rowCount = reader.getRowCount();

        assertEquals(10, rowCount, "Row count should be 10");
        // Note: If this method read all data, it would be much slower for large files
        // This is a smoke test that the metadata-only read works
    }

    @Test
    void testDocumentsHaveConsistentFields() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);
        List<Document> documents = reader.readAll();

        // All documents should have the same fields (assuming homogeneous schema)
        Document firstDoc = documents.get(0);
        int expectedFieldCount = firstDoc.getFields().size();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            assertEquals(expectedFieldCount, doc.getFields().size(),
                    "Document " + i + " should have same field count as first document");

            // Check that all documents have catalog_id and embedding
            assertTrue(doc.containsField("catalog_id"),
                    "Document " + i + " should have catalog_id");
            assertTrue(doc.containsField("search_catalog_embedding"),
                    "Document " + i + " should have search_catalog_embedding");
        }
    }

    // ===== Multi-threaded Conversion Tests =====

    @Test
    void testReadInBatches_withParallelConversion_producesCorrectResults() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Read with single-threaded (baseline)
        List<List<Document>> singleThreadedBatches = new ArrayList<>();
        reader.readInBatches(5, 1, batch -> {
            singleThreadedBatches.add(new ArrayList<>(batch));
        });

        // Read with multi-threaded conversion
        List<List<Document>> multiThreadedBatches = new ArrayList<>();
        reader.readInBatches(5, 4, batch -> {
            multiThreadedBatches.add(new ArrayList<>(batch));
        });

        // Should have same number of batches
        assertEquals(singleThreadedBatches.size(), multiThreadedBatches.size(),
                "Single-threaded and multi-threaded should produce same number of batches");

        // Each batch should have same number of documents
        for (int i = 0; i < singleThreadedBatches.size(); i++) {
            assertEquals(singleThreadedBatches.get(i).size(), multiThreadedBatches.get(i).size(),
                    "Batch " + i + " should have same size in both modes");
        }

        // Documents should be equivalent (same fields, same values)
        // Note: Order within batch might differ with multi-threading, so we compare sets
        for (int batchIdx = 0; batchIdx < singleThreadedBatches.size(); batchIdx++) {
            List<Document> singleBatch = singleThreadedBatches.get(batchIdx);
            List<Document> multiBatch = multiThreadedBatches.get(batchIdx);

            // Extract catalog_ids to compare (they should be unique)
            List<Object> singleIds = singleBatch.stream()
                    .map(doc -> doc.get("catalog_id"))
                    .sorted()
                    .toList();
            List<Object> multiIds = multiBatch.stream()
                    .map(doc -> doc.get("catalog_id"))
                    .sorted()
                    .toList();

            assertEquals(singleIds, multiIds,
                    "Batch " + batchIdx + " should contain same documents (by catalog_id)");
        }

        System.out.println("✅ Multi-threaded conversion produces same results as single-threaded");
    }

    @Test
    void testReadInBatches_withParallelConversion_noDataLoss() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<Document> allDocs = new ArrayList<>();
        int[] batchCount = {0};

        // Read with 4 conversion threads
        reader.readInBatches(3, 4, batch -> {
            allDocs.addAll(batch);
            batchCount[0]++;
        });

        // Should have processed all 10 documents
        assertEquals(10, allDocs.size(), "Should process all 10 documents");

        // Should have 4 batches (3+3+3+1)
        assertEquals(4, batchCount[0], "Should have 4 batches");

        // All documents should be valid (have required fields)
        for (int i = 0; i < allDocs.size(); i++) {
            Document doc = allDocs.get(i);
            assertTrue(doc.containsField("catalog_id"), "Doc " + i + " should have catalog_id");
            assertTrue(doc.containsField("search_catalog_embedding"), "Doc " + i + " should have embedding");
        }

        System.out.println("✅ No data loss with parallel conversion");
    }

    @Test
    void testReadInBatches_withOneThread_behavesLikeSingleThreaded() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<List<Document>> batches = new ArrayList<>();

        // Using 1 conversion thread should behave like the old single-threaded code
        reader.readInBatches(5, 1, batch -> {
            batches.add(new ArrayList<>(batch));
        });

        assertEquals(2, batches.size(), "Should have 2 batches");
        assertEquals(5, batches.get(0).size(), "First batch should have 5 docs");
        assertEquals(5, batches.get(1).size(), "Second batch should have 5 docs");

        // All documents should be properly converted
        for (List<Document> batch : batches) {
            for (Document doc : batch) {
                Object embedding = doc.get("search_catalog_embedding");
                assertTrue(embedding instanceof List, "Embedding should be List");
                assertFalse(embedding.getClass().getName().contains("GenericRecord"),
                        "Should not contain GenericRecord");
            }
        }

        System.out.println("✅ Single conversion thread works correctly");
    }

    @Test
    void testReadInBatches_withManyThreads_handlesSmallBatches() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<Document> allDocs = new ArrayList<>();

        // Use more threads than batch size (stress test)
        reader.readInBatches(2, 8, batch -> {
            allDocs.addAll(batch);
        });

        // Should still process all documents correctly
        assertEquals(10, allDocs.size(), "Should process all 10 documents even with thread count > batch size");

        // All documents should be valid
        for (Document doc : allDocs) {
            assertTrue(doc.containsField("catalog_id"));
            assertTrue(doc.containsField("search_catalog_embedding"));
        }

        System.out.println("✅ Handles case where thread count > batch size");
    }

    @Test
    void testReadInBatches_parallelConversion_preservesDataIntegrity() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<Document> docs = new ArrayList<>();

        // Read with parallel conversion
        reader.readInBatches(5, 4, batch -> {
            docs.addAll(batch);
        });

        // Verify embedding vectors are still properly converted
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Object embedding = doc.get("search_catalog_embedding");

            assertNotNull(embedding, "Doc " + i + " embedding should not be null");
            assertTrue(embedding instanceof List, "Doc " + i + " embedding should be List");

            @SuppressWarnings("unchecked")
            List<Object> embeddingList = (List<Object>) embedding;

            // Each element should be a number, not a GenericRecord
            for (int j = 0; j < embeddingList.size(); j++) {
                Object element = embeddingList.get(j);
                assertNotNull(element, "Embedding[" + j + "] should not be null");
                assertFalse(element.getClass().getName().contains("GenericRecord"),
                        "Embedding[" + j + "] should not be GenericRecord");
                assertTrue(element instanceof Number,
                        "Embedding[" + j + "] should be a Number");
            }
        }

        System.out.println("✅ Parallel conversion preserves data integrity");
    }

    @Test
    void testReadInBatches_defaultThreadCount_usesDefaultBehavior() throws Exception {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        List<Document> docs = new ArrayList<>();

        // Call without specifying thread count - should use default (backward compatible)
        reader.readInBatches(5, batch -> {
            docs.addAll(batch);
        });

        assertEquals(10, docs.size(), "Should process all documents with default thread count");

        // Verify correctness
        for (Document doc : docs) {
            assertTrue(doc.containsField("catalog_id"));
            assertTrue(doc.containsField("search_catalog_embedding"));
        }

        System.out.println("✅ Default thread count (backward compatibility) works");
    }

    // ===== Error Handling & Edge Cases for Multi-threading =====

    @Test
    void testReadInBatches_invalidThreadCount_throwsIllegalArgumentException() {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            reader.readInBatches(5, 0, batch -> {});
        });

        assertTrue(ex.getMessage().contains("conversionThreads must be >= 1"),
                "Should reject invalid thread count");

        System.out.println("✅ Invalid thread count rejected");
    }

    @Test
    void testReadInBatches_negativeThreadCount_throwsIllegalArgumentException() {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            reader.readInBatches(5, -1, batch -> {});
        });

        assertTrue(ex.getMessage().contains("conversionThreads must be >= 1"),
                "Should reject negative thread count");

        System.out.println("✅ Negative thread count rejected");
    }

    @Test
    void testReadInBatches_consumerThrowsException_propagatesError() {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Consumer that throws after first batch
        IOException expectedException = new IOException("Simulated consumer failure");

        IOException thrown = assertThrows(IOException.class, () -> {
            reader.readInBatches(5, 4, batch -> {
                throw expectedException;
            });
        });

        assertEquals(expectedException, thrown, "Should propagate consumer exception");
        System.out.println("✅ Consumer exception propagated correctly");
    }

    @Test
    void testReadInBatches_multiThreaded_cleansUpExecutorOnError() {
        ParquetReader reader = new ParquetReader(TEST_PARQUET);

        // Consumer that throws after processing some docs
        final int[] batchCount = {0};

        assertThrows(IOException.class, () -> {
            reader.readInBatches(3, 4, batch -> {
                batchCount[0]++;
                if (batchCount[0] >= 2) {
                    throw new IOException("Simulated failure");
                }
            });
        });

        // Verify we processed at least one batch before failing
        assertTrue(batchCount[0] >= 1, "Should process at least one batch before error");

        // The executor should be cleaned up even after exception
        // (This test mainly ensures the finally block executes)
        System.out.println("✅ Executor cleanup on error works");
    }

    /**
     * {@link ParquetReader#createConversionPool(int)} returns this pool; awaitTermination always false so shutdownNow runs.
     */
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

    /**
     * awaitTermination throws InterruptedException to exercise the catch path in readInBatches finally.
     */
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
        private final IntFunction<ExecutorService> poolFactory;

        ParquetReaderWithInjectablePool(String filePath, IntFunction<ExecutorService> poolFactory) {
            super(filePath);
            this.poolFactory = poolFactory;
        }

        @Override
        ExecutorService createConversionPool(int conversionThreads) {
            return poolFactory.apply(conversionThreads);
        }
    }

    @Test
    void readInBatches_whenAwaitTerminationReturnsFalse_callsShutdownNow() throws Exception {
        AwaitNeverTerminatesPool pool = new AwaitNeverTerminatesPool(2);
        ParquetReaderWithInjectablePool reader = new ParquetReaderWithInjectablePool(TEST_PARQUET, n -> pool);
        List<Document> docs = new ArrayList<>();
        reader.readInBatches(5, 2, batch -> docs.addAll(batch));
        assertEquals(10, docs.size());
        assertTrue(pool.shutdownNowInvoked, "shutdownNow should run when awaitTermination returns false");
    }

    @Test
    void readInBatches_whenAwaitTerminationInterrupted_rethrowsIOExceptionAndSetsInterruptFlag() throws Exception {
        InterruptOnAwaitPool pool = new InterruptOnAwaitPool(2);
        ParquetReaderWithInjectablePool reader = new ParquetReaderWithInjectablePool(TEST_PARQUET, n -> pool);
        try {
            IOException ex = assertThrows(IOException.class,
                    () -> reader.readInBatches(5, 2, batch -> {}));
            assertEquals("Interrupted while waiting for conversion pool shutdown", ex.getMessage());
            assertInstanceOf(InterruptedException.class, ex.getCause());
            assertTrue(Thread.interrupted(), "thread should still be interrupted after readInBatches");
        } finally {
            Thread.interrupted();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
