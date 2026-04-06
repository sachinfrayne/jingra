package org.elasticsearch.jingra.data;

import org.elasticsearch.jingra.model.Document;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
}
