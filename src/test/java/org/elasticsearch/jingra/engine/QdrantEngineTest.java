package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for QdrantEngine using Testcontainers.
 * Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QdrantEngineTest {

    private static final String TEST_INDEX = "test_collection";
    private static final String QDRANT_VERSION = "v" + readVersionFile("engine-versions/.qdrant");

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>(
            DockerImageName.parse("qdrant/qdrant:" + QDRANT_VERSION)
    )
            .withExposedPorts(6333, 6334); // HTTP and gRPC ports

    private static QdrantEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        // Qdrant uses gRPC port 6334
        String host = qdrant.getHost();
        Integer grpcPort = qdrant.getMappedPort(6334);
        String url = host + ":" + grpcPort;

        // Pass URL directly in config to avoid environment variable reflection hacks
        Map<String, Object> config = new HashMap<>();
        config.put("url", url);
        config.put("vector_field", "embedding");  // Configure to use 'embedding' field in tests

        engine = new QdrantEngine(config);
        assertTrue(engine.connect(), "Failed to connect to Qdrant");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Read version from engine-versions directory.
     */
    private static String readVersionFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(path)).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read version from " + path, e);
        }
    }


    @Test
    @Order(1)
    void testConnect_success() {
        assertNotNull(engine);
        String version = engine.getVersion();
        assertNotNull(version);
        // Qdrant version format: "1.12.5"
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    @Order(2)
    void testIndexExists_false() {
        boolean exists = engine.indexExists(TEST_INDEX);
        assertFalse(exists, "Collection should not exist initially");
    }

    @Test
    @Order(3)
    void testCreateIndex_withSchema() throws Exception {
        // Qdrant schema format is different - it's about collection config
        String schemaContent = """
                {
                  "template": {
                    "vectors": {
                      "size": 128,
                      "distance": "Cosine"
                    },
                    "on_disk_payload": false
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/qdrant/test-schema-qdrant.json");
        java.nio.file.Files.createDirectories(schemaPath.getParent());
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        boolean created = engine.createIndex(TEST_INDEX, "test-schema-qdrant");
        assertTrue(created || engine.indexExists(TEST_INDEX), "Collection should be created");
    }

    @Test
    @Order(4)
    void testCreateIndex_alreadyExists() {
        boolean created = engine.createIndex(TEST_INDEX, "test-schema-qdrant");
        assertFalse(created, "Should return false when collection already exists");
    }

    @Test
    @Order(5)
    void testIngest_singleBatch() throws Exception {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("id", "doc-" + i);
            fields.put("title", "Test document " + i);
            fields.put("embedding", generateRandomVector(128));
            docs.add(new Document(fields));
        }

        int ingested = engine.ingest(docs, TEST_INDEX, "id");
        assertEquals(10, ingested, "Should ingest 10 documents");

        Thread.sleep(1000);

        long count = engine.getDocumentCount(TEST_INDEX);
        assertTrue(count >= 10, "Document count should be at least 10");
    }

    @Test
    @Order(6)
    void testIngest_withoutIdField() throws Exception {
        String tempIndex = "test_collection_no_id";

        String schemaContent = """
                {
                  "template": {
                    "vectors": {
                      "size": 128,
                      "distance": "Cosine"
                    }
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/qdrant/test-schema-qdrant-no-id.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-qdrant-no-id");

        // Qdrant needs vectors, so we need embedding field
        List<Document> docs = List.of(
                new Document(Map.of("title", "Doc 1", "embedding", generateRandomVector(128))),
                new Document(Map.of("title", "Doc 2", "embedding", generateRandomVector(128)))
        );

        int ingested = engine.ingest(docs, tempIndex, null);
        assertEquals(2, ingested);

        Thread.sleep(1000);

        long count = engine.getDocumentCount(tempIndex);
        assertEquals(2, count);

        engine.deleteIndex(tempIndex);
    }

    @Test
    @Order(7)
    void testQuery_basicKNN() throws Exception {
        // Qdrant doesn't actually use query templates - it builds requests directly
        // But we need a dummy template file for loadQueryTemplate to succeed
        String queryContent = """
                {
                  "comment": "Qdrant builds queries directly from parameters"
                }
                """;

        java.nio.file.Path queryPath = java.nio.file.Paths.get("jingra-config/queries/qdrant/test-query-basic.json");
        java.nio.file.Files.createDirectories(queryPath.getParent());
        java.nio.file.Files.writeString(queryPath, queryContent);

        Map<String, Object> params = new HashMap<>();
        params.put("query_vector", generateRandomVector(128));
        params.put("size", 5);

        QueryParams queryParams = new QueryParams(params);
        QueryResponse response = engine.query(TEST_INDEX, "test-query-basic", queryParams);

        assertNotNull(response);
        assertNotNull(response.getDocumentIds());
        assertEquals(5, response.getDocumentIds().size());
        assertNotNull(response.getClientLatencyMs());
        assertTrue(response.getClientLatencyMs() > 0);
    }

    @Test
    @Order(8)
    void testQuery_withFilters() throws Exception {
        // Ingest documents with boolean "valid" field - some true, some false
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("id", "filtered-" + i);
            fields.put("title", "Filtered doc " + i);
            fields.put("valid", i % 2 == 0);  // even IDs have valid=true, odd have valid=false
            fields.put("embedding", generateRandomVector(128));
            docs.add(new Document(fields));
        }
        engine.ingest(docs, TEST_INDEX, "id");

        // Wait for indexing
        await().atMost(3, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertTrue(engine.getDocumentCount(TEST_INDEX) > 0));

        // Create query template with filter for valid=true (matching k8s config format)
        String queryContent = """
                {
                  "name": "test_filtered_query",
                  "template": {
                    "vector": "{{query_vector}}",
                    "filter": {
                      "must": [
                        {
                          "key": "valid",
                          "match": {
                            "value": true
                          }
                        }
                      ]
                    },
                    "limit": "{{size}}",
                    "with_payload": true,
                    "with_vector": false
                  }
                }
                """;

        java.nio.file.Path queryPath = java.nio.file.Paths.get("jingra-config/queries/qdrant/test-query-filtered.json");
        java.nio.file.Files.writeString(queryPath, queryContent);

        Map<String, Object> params = new HashMap<>();
        params.put("query_vector", generateRandomVector(128));
        params.put("size", 10);

        QueryParams queryParams = new QueryParams(params);
        QueryResponse response = engine.query(TEST_INDEX, "test-query-filtered", queryParams);

        assertNotNull(response);
        assertNotNull(response.getDocumentIds());

        // Verify we got results (should only match valid=true documents)
        // We have 5 documents with valid=true (IDs 0,2,4,6,8), should get up to 5 results
        assertTrue(response.getDocumentIds().size() <= 5,
                "Should return at most 5 documents (only those with valid=true)");
        assertTrue(response.getDocumentIds().size() > 0,
                "Should return at least some matching documents");
    }

    @Test
    @Order(9)
    void testGetDocumentCount() {
        // Wait for Qdrant to index documents from previous tests (eventual consistency)
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long count = engine.getDocumentCount(TEST_INDEX);
                    assertTrue(count > 0, "Document count should be greater than 0");
                });
    }

    @Test
    @Order(10)
    void testGetVersion() {
        String version = engine.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    @Order(11)
    void testGetIndexMetadata() {
        Map<String, String> metadata = engine.getIndexMetadata(TEST_INDEX);
        assertNotNull(metadata);
        assertFalse(metadata.isEmpty());
    }

    @Test
    @Order(12)
    void testDeleteIndex_success() throws Exception {
        String tempIndex = "test_delete_collection";
        String schemaContent = """
                {
                  "template": {
                    "vectors": {
                      "size": 128,
                      "distance": "Cosine"
                    }
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/qdrant/test-schema-qdrant-delete.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-qdrant-delete");
        assertTrue(engine.indexExists(tempIndex));

        boolean deleted = engine.deleteIndex(tempIndex);
        assertTrue(deleted);
        assertFalse(engine.indexExists(tempIndex));
    }

    @Test
    @Order(13)
    void testConnect_invalidURL() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "invalid-host:9999");

        QdrantEngine badEngine = new QdrantEngine(config);
        assertFalse(badEngine.connect(), "Should fail to connect to invalid URL");
    }

    @Test
    @Order(14)
    void testIngest_largeBatch() throws Exception {
        String tempIndex = "test_large_batch";
        String schemaContent = """
                {
                  "template": {
                    "vectors": {
                      "size": 128,
                      "distance": "Cosine"
                    }
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/qdrant/test-schema-qdrant-large.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-qdrant-large");

        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("id", "large-" + i);
            fields.put("value", i);
            fields.put("embedding", generateRandomVector(128));
            docs.add(new Document(fields));
        }

        int ingested = engine.ingest(docs, tempIndex, "id");
        assertEquals(1000, ingested);

        Thread.sleep(2000);

        long count = engine.getDocumentCount(tempIndex);
        assertEquals(1000, count);

        engine.deleteIndex(tempIndex);
    }

    @Test
    @Order(15)
    void testIndexExists_true() {
        boolean exists = engine.indexExists(TEST_INDEX);
        assertTrue(exists, "Test collection should exist after previous tests");
    }

    @Test
    @Order(16)
    void testClose() throws Exception {
        String host = qdrant.getHost();
        Integer grpcPort = qdrant.getMappedPort(6334);
        String url = host + ":" + grpcPort;

        Map<String, Object> config = new HashMap<>();
        config.put("url", url);

        QdrantEngine testEngine = new QdrantEngine(config);
        testEngine.connect();
        assertDoesNotThrow(() -> testEngine.close());
    }

    @Test
    @Order(17)
    void testConnect_withInsecureTls() {
        // Enable insecure TLS for this test
        System.setProperty("jingra.insecure.tls", "true");
        try {
            // Create a new engine with HTTPS URL
            // Note: This tests that the configuration is accepted; actual HTTPS connection
            // would require a testcontainer with TLS setup, which is complex
            Map<String, Object> config = new HashMap<>();
            config.put("url", "https://localhost:6334");
            config.put("api_key", "test-key");

            QdrantEngine tlsEngine = new QdrantEngine(config);

            // We expect this to fail connecting (no server), but it should NOT fail
            // due to SSL configuration errors. The error should be connection-related,
            // not SSL certificate validation related.
            boolean connected = tlsEngine.connect();

            // Connection will fail (no server at localhost:6334), but the test verifies
            // that insecure TLS configuration doesn't throw SSL-specific exceptions
            assertFalse(connected, "Connection should fail (no server), but not due to SSL config");

            tlsEngine.close();
        } catch (Exception e) {
            // Should not throw SSL certificate validation exceptions
            String message = e.getMessage();
            assertFalse(message != null && message.contains("PKIX path building failed"),
                    "Should not fail with certificate validation error when insecure TLS is enabled");
            assertFalse(message != null && message.contains("unable to find valid certification path"),
                    "Should not fail with certificate path error when insecure TLS is enabled");
        } finally {
            System.clearProperty("jingra.insecure.tls");
        }
    }

    private static List<Double> generateRandomVector(int dims) {
        Random random = new Random();
        List<Double> vector = new ArrayList<>();
        for (int i = 0; i < dims; i++) {
            vector.add(random.nextDouble());
        }
        return vector;
    }
}
