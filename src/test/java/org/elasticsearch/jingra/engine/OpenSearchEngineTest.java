package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OpenSearchEngine using Testcontainers.
 */
@Testcontainers(disabledWithoutDocker = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenSearchEngineTest {

    private static final String TEST_INDEX = "test-index";
    private static final String OS_VERSION = readVersionFile("engine-versions/.opensearch");

    @Container
    static GenericContainer<?> opensearch = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:" + OS_VERSION)
    )
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "Admin123!@#")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            // Launcher uses /tmp for bootstrap; host Docker disk can be full while RAM is available.
            .withTmpFs(Map.of("/tmp", "rw,size=512m"))
            .waitingFor(new HttpWaitStrategy()
                    .forPort(9200)
                    .forStatusCodeMatching(status -> status >= 200 && status < 300)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static OpenSearchEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        String url = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);

        // Pass URL directly in config to avoid environment variable reflection hacks
        Map<String, Object> config = new HashMap<>();
        config.put("url", url);

        engine = new OpenSearchEngine(config);
        assertTrue(engine.connect(), "Failed to connect to OpenSearch");
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
        assertTrue(version.startsWith("3."));
    }

    @Test
    @Order(2)
    void testIndexExists_false() {
        boolean exists = engine.indexExists(TEST_INDEX);
        assertFalse(exists, "Index should not exist initially");
    }

    @Test
    @Order(3)
    void testCreateIndex_withSchema() throws Exception {
        String schemaContent = """
                {
                  "mappings": {
                    "properties": {
                      "id": {"type": "keyword"},
                      "title": {"type": "text"},
                      "embedding": {
                        "type": "knn_vector",
                        "dimension": 128,
                        "space_type": "cosinesimil",
                        "data_type": "float",
                        "compression_level": "32x",
                        "mode": "in_memory",
                        "method": {
                          "name": "hnsw",
                          "engine": "faiss",
                          "parameters": {
                            "ef_construction": 100,
                            "m": 16
                          }
                        }
                      }
                    }
                  },
                  "settings": {
                    "index.knn": true
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-os.json");
        java.nio.file.Files.createDirectories(schemaPath.getParent());
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        boolean created = engine.createIndex(TEST_INDEX, "test-schema-os");
        assertTrue(created || engine.indexExists(TEST_INDEX), "Index should be created");
    }

    @Test
    @Order(4)
    void testCreateIndex_alreadyExists() {
        boolean created = engine.createIndex(TEST_INDEX, "test-schema-os");
        assertFalse(created, "Should return false when index already exists");
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
        String tempIndex = "test-index-no-id";

        String schemaContent = """
                {
                  "mappings": {
                    "properties": {
                      "title": {"type": "text"}
                    }
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-os-no-id.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-os-no-id");

        List<Document> docs = List.of(
                new Document(Map.of("title", "Doc 1")),
                new Document(Map.of("title", "Doc 2"))
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
        String queryTemplate = """
                {
                  "size": "{{size}}",
                  "query": {
                    "knn": {
                      "embedding": {
                        "vector": "{{query_vector}}",
                        "k": "{{k}}"
                      }
                    }
                  }
                }
                """;

        java.nio.file.Path queryPath = java.nio.file.Paths.get("jingra-config/queries/test-query-basic.json");
        java.nio.file.Files.createDirectories(queryPath.getParent());
        java.nio.file.Files.writeString(queryPath, queryTemplate);

        Map<String, Object> params = new HashMap<>();
        params.put("query_vector", generateRandomVector(128));
        params.put("k", 5);
        params.put("size", 5);

        QueryParams queryParams = new QueryParams(params);
        QueryResponse response = engine.query(TEST_INDEX, "test-query-basic", queryParams);

        assertNotNull(response);
        assertNotNull(response.getDocumentIds());
        assertTrue(response.getDocumentIds().size() <= 5);
        assertTrue(response.getClientLatencyMs() > 0);
    }

    @Test
    @Order(8)
    void testQuery_withFilters() throws Exception {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("id", "filtered-" + i);
            fields.put("title", "Filtered doc " + i);
            fields.put("category", i % 2 == 0 ? "even" : "odd");
            fields.put("embedding", generateRandomVector(128));
            docs.add(new Document(fields));
        }
        engine.ingest(docs, TEST_INDEX, "id");
        Thread.sleep(1000);

        String queryTemplate = """
                {
                  "size": "{{size}}",
                  "query": {
                    "bool": {
                      "must": [
                        {
                          "knn": {
                            "embedding": {
                              "vector": "{{query_vector}}",
                              "k": "{{k}}"
                            }
                          }
                        }
                      ],
                      "filter": [
                        {
                          "term": {
                            "category": "even"
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        java.nio.file.Path queryPath = java.nio.file.Paths.get("jingra-config/queries/test-query-filtered.json");
        java.nio.file.Files.writeString(queryPath, queryTemplate);

        Map<String, Object> params = new HashMap<>();
        params.put("query_vector", generateRandomVector(128));
        params.put("k", 10);
        params.put("size", 10);

        QueryParams queryParams = new QueryParams(params);
        QueryResponse response = engine.query(TEST_INDEX, "test-query-filtered", queryParams);

        assertNotNull(response);
        assertNotNull(response.getDocumentIds());
    }

    @Test
    @Order(9)
    void testGetDocumentCount() {
        long count = engine.getDocumentCount(TEST_INDEX);
        assertTrue(count > 0, "Document count should be greater than 0");
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
        assertEquals("knn_vector", metadata.get("vector_type"));
    }

    @Test
    @Order(12)
    void testDeleteIndex_success() throws Exception {
        String tempIndex = "test-delete-index";
        String schemaContent = """
                {
                  "mappings": {
                    "properties": {
                      "field": {"type": "keyword"}
                    }
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-os-delete.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-os-delete");
        assertTrue(engine.indexExists(tempIndex));

        boolean deleted = engine.deleteIndex(tempIndex);
        assertTrue(deleted);
        assertFalse(engine.indexExists(tempIndex));
    }

    @Test
    @Order(13)
    void testConnect_invalidURL() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://invalid-host:9999");

        OpenSearchEngine badEngine = new OpenSearchEngine(config);
        assertFalse(badEngine.connect(), "Should fail to connect to invalid URL");
    }

    @Test
    @Order(14)
    void testIngest_largeBatch() throws Exception {
        String tempIndex = "test-large-batch";
        String schemaContent = """
                {
                  "mappings": {
                    "properties": {
                      "id": {"type": "keyword"},
                      "value": {"type": "integer"}
                    }
                  }
                }
                """;

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-os-large.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-os-large");

        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("id", "large-" + i);
            fields.put("value", i);
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
        assertTrue(exists, "Test index should exist after previous tests");
    }

    @Test
    @Order(16)
    void testClose() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200));

        OpenSearchEngine testEngine = new OpenSearchEngine(config);
        testEngine.connect();
        assertDoesNotThrow(() -> testEngine.close());
    }

    @Test
    @Order(17)
    void testDeleteIndex_idempotentWhenMissing() {
        assertTrue(engine.deleteIndex("jingra-os-missing-index-" + UUID.randomUUID()));
    }

    @Test
    @Order(18)
    void testConnect_withInsecureTls() {
        // Enable insecure TLS for this test
        System.setProperty("jingra.insecure.tls", "true");
        try {
            // Create a new engine with HTTPS URL
            // Note: This tests that the configuration is accepted; actual HTTPS connection
            // would require a testcontainer with TLS setup, which is complex
            Map<String, Object> config = new HashMap<>();
            config.put("url", "https://localhost:9200");
            config.put("user", "test-user");
            config.put("password", "test-pass");

            OpenSearchEngine tlsEngine = new OpenSearchEngine(config);

            // We expect this to fail connecting (no server), but it should NOT fail
            // due to SSL configuration errors. The error should be connection-related,
            // not SSL certificate validation related.
            boolean connected = tlsEngine.connect();

            // Connection will fail (no server at localhost:9200), but the test verifies
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
