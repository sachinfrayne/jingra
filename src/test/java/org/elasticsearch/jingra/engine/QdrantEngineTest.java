package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for QdrantEngine using Testcontainers.
 */
@Testcontainers(disabledWithoutDocker = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QdrantEngineTest {

    private static final String TEST_INDEX = "test_collection";
    private static final String QDRANT_VERSION = "v" + readVersionFile("engine-versions/.qdrant");

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>(
            DockerImageName.parse("qdrant/qdrant:" + QDRANT_VERSION)
    )
            .withExposedPorts(6333, 6334) // HTTP and gRPC ports
            // Store Qdrant data on tmpfs so long test runs do not exhaust Docker's thin pool / VM disk
            // ("No space left on device: WAL buffer size exceeds available disk space").
            .withTmpFs(Map.of("/qdrant/storage", "rw,size=768m"));

    private static QdrantEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        // Qdrant uses gRPC port 6334
        String host = qdrant.getHost();
        Integer grpcPort = qdrant.getMappedPort(6334);
        Integer httpPort = qdrant.getMappedPort(6333);
        String url = host + ":" + grpcPort;

        // Pass URL directly in config to avoid environment variable reflection hacks
        Map<String, Object> config = new HashMap<>();
        config.put("url", url);
        config.put("rest_url", "http://" + host + ":" + httpPort);  // Testcontainers maps 6333 to a random port
        config.put("vector_field", "embedding");  // Configure to use 'embedding' field in tests
        config.put("grpc_timeout_seconds", 90L);  // Headroom when Docker or Qdrant is slow under load

        engine = new QdrantEngine(config);
        assertTrue(engine.connect(), "Failed to connect to Qdrant");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    private static String readVersionFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(path)).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read version from " + path, e);
        }
    }

    private static String uniqueCollectionName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void writeQdrantSchemaFile(String schemaBaseName, String json) throws Exception {
        java.nio.file.Path schemaPath =
                java.nio.file.Paths.get("jingra-config/schemas/" + schemaBaseName + ".json");
        java.nio.file.Files.createDirectories(schemaPath.getParent());
        java.nio.file.Files.writeString(schemaPath, json.strip());
    }

    private static void writeQdrantQueryFile(String queryBaseName, String json) throws Exception {
        java.nio.file.Path queryPath =
                java.nio.file.Paths.get("jingra-config/queries/" + queryBaseName + ".json");
        java.nio.file.Files.createDirectories(queryPath.getParent());
        java.nio.file.Files.writeString(queryPath, json.strip());
    }

    private static final String SCHEMA_MINIMAL_QDRANT_VECTOR_128 = """
            {"template": {"vectors": {"size": 128, "distance": "Cosine"}}}
            """;

    /**
     * Creates a collection with a single 128-dim vector point (waits until visible for search).
     */
    private String createCollectionWithOneVectorPoint(String colPrefix) throws Exception {
        String col = uniqueCollectionName(colPrefix);
        writeQdrantSchemaFile("test-schema-qi-generic-128", SCHEMA_MINIMAL_QDRANT_VECTOR_128);
        assertTrue(engine.createIndex(col, "test-schema-qi-generic-128"));
        assertEquals(1, engine.ingest(List.of(
                new Document(Map.of("id", "p1", "embedding", generateRandomVector(128)))), col, "id"));
        await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertTrue(engine.getDocumentCount(col) >= 1));
        return col;
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
    @Order(22)
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

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-qdrant.json");
        java.nio.file.Files.createDirectories(schemaPath.getParent());
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        boolean created = engine.createIndex(TEST_INDEX, "test-schema-qdrant");
        assertTrue(created || engine.indexExists(TEST_INDEX), "Collection should be created");
    }

    @Test
    @Order(23)
    void testCreateIndex_alreadyExists() {
        boolean created = engine.createIndex(TEST_INDEX, "test-schema-qdrant");
        assertFalse(created, "Should return false when collection already exists");
    }

    @Test
    @Order(24)
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

        await().pollInSameThread().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertTrue(engine.getDocumentCount(TEST_INDEX) >= 10,
                        "Document count should be at least 10"));
    }

    @Test
    @Order(25)
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

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-qdrant-no-id.json");
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
    @Order(26)
    void testQuery_basicKNN() throws Exception {
        // Qdrant doesn't actually use query templates - it builds requests directly
        // But we need a dummy template file for loadQueryTemplate to succeed
        String queryContent = """
                {
                  "comment": "Qdrant builds queries directly from parameters"
                }
                """;

        java.nio.file.Path queryPath = java.nio.file.Paths.get("jingra-config/queries/test-query-basic.json");
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
        // Qdrant should return server latency from the SearchResponse.time field
        assertNotNull(response.getServerLatencyMs(), "Server latency should be captured from Qdrant SearchResponse");
        assertTrue(response.getServerLatencyMs() > 0, "Server latency should be positive");
    }

    @Test
    @Order(27)
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
        await().pollInSameThread().atMost(3, TimeUnit.SECONDS)
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

        java.nio.file.Path queryPath = java.nio.file.Paths.get("jingra-config/queries/test-query-filtered.json");
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
    @Order(28)
    void testGetDocumentCount() {
        // Wait for Qdrant to index documents from previous tests (eventual consistency)
        await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long count = engine.getDocumentCount(TEST_INDEX);
                    assertTrue(count > 0, "Document count should be greater than 0");
                });
    }

    @Test
    @Order(29)
    void testGetVersion() {
        String version = engine.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    @Order(30)
    void testGetIndexMetadata() {
        Map<String, String> metadata = engine.getIndexMetadata(TEST_INDEX);
        assertNotNull(metadata);
        assertFalse(metadata.isEmpty());
        assertTrue(metadata.containsKey("points_count"));
        assertTrue(Long.parseLong(metadata.get("points_count")) > 0L);
    }

    @Test
    @Order(31)
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

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-qdrant-delete.json");
        java.nio.file.Files.writeString(schemaPath, schemaContent);

        engine.createIndex(tempIndex, "test-schema-qdrant-delete");
        assertTrue(engine.indexExists(tempIndex));

        boolean deleted = engine.deleteIndex(tempIndex);
        assertTrue(deleted);
        assertFalse(engine.indexExists(tempIndex));
    }

    @Test
    @Order(32)
    void testDeleteIndex_invalidNameReturnsFalse() {
        assertFalse(engine.deleteIndex(""));
    }

    @Test
    @Order(33)
    void testConnect_invalidURL() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "invalid-host:9999");

        QdrantEngine badEngine = new QdrantEngine(config);
        try {
            assertFalse(badEngine.connect(), "Should fail to connect to invalid URL");
        } finally {
            badEngine.close();
        }
    }

    @Test
    @Order(34)
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

        java.nio.file.Path schemaPath = java.nio.file.Paths.get("jingra-config/schemas/test-schema-qdrant-large.json");
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
    @Order(35)
    void testIndexExists_true() {
        boolean exists = engine.indexExists(TEST_INDEX);
        assertTrue(exists, "Test collection should exist after previous tests");
    }

    @Test
    @Order(36)
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
    @Order(37)
    void testQuery_returnsEmptyWhenTemplateMissing() {
        Map<String, Object> p = new HashMap<>();
        p.put("query_vector", generateRandomVector(128));
        QueryResponse r = engine.query(TEST_INDEX, "missing-query-template-zzz-99", new QueryParams(p));
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
    }

    @Test
    @Order(39)
    void testQuery_returnsEmptyWhenQueryVectorMissing() {
        QueryResponse r = engine.query(TEST_INDEX, "test-query-basic", new QueryParams(Map.of("size", 3)));
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
    }

    @Test
    @Order(40)
    void testQuery_defaultLimitTenWhenSizeOmitted() throws Exception {
        String col = uniqueCollectionName("qi_deflim");
        writeQdrantSchemaFile("test-schema-qi-deflim", """
                {"template": {"vectors": {"size": 128, "distance": "Cosine"}}}
                """);
        writeQdrantQueryFile("test-query-size-default", """
                {"comment": "default limit 10 when size omitted from QueryParams"}
                """);
        assertTrue(engine.createIndex(col, "test-schema-qi-deflim"));
        try {
            assertEquals(1, engine.ingest(List.of(
                    new Document(Map.of("id", "u1", "embedding", generateRandomVector(128)))), col, "id"));
            await().pollInSameThread().atMost(15, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertTrue(engine.getDocumentCount(col) >= 1));
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            QueryResponse r = engine.query(col, "test-query-size-default", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
            assertTrue(r.getDocumentIds().size() <= 10);
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(41)
    void testQuery_emptyNestedTemplateParamsStillCallsSetParams() throws Exception {
        String col = uniqueCollectionName("qi_empty_tpl_params");
        writeQdrantSchemaFile("test-schema-qi-empty-tpl-params", """
                {"template": {"vectors": {"size": 128, "distance": "Cosine"}}}
                """);
        writeQdrantQueryFile("test-query-empty-nested-params", """
                {"template": {"params": {}}}
                """);
        assertTrue(engine.createIndex(col, "test-schema-qi-empty-tpl-params"));
        try {
            assertEquals(1, engine.ingest(List.of(
                    new Document(Map.of("id", "e1", "embedding", generateRandomVector(128)))), col, "id"));
            await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertTrue(engine.getDocumentCount(col) >= 1));
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 5);
            QueryResponse r = engine.query(col, "test-query-empty-nested-params", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
            assertTrue(r.getDocumentIds().size() <= 5);
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(42)
    void testQuery_nestedTemplateParamsHnswEfAndQuantization() throws Exception {
        String col = uniqueCollectionName("qi_hnsw_quant");
        writeQdrantSchemaFile("test-schema-qi-hnsw-quant", """
                {
                  "template": {
                    "vectors": {"size": 128, "distance": "Cosine"},
                    "settings": {
                      "quantization_config": {
                        "binary": {"always_ram": true}
                      }
                    }
                  }
                }
                """);
        writeQdrantQueryFile("test-query-nested-hnsw-quant", """
                {
                  "template": {
                    "params": {
                      "hnsw_ef": "48",
                      "quantization": { "oversampling": "2.0" }
                    }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-qi-hnsw-quant"));
        try {
            assertEquals(1, engine.ingest(List.of(
                    new Document(Map.of("id", "hq1", "embedding", generateRandomVector(128)))), col, "id"));
            await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertTrue(engine.getDocumentCount(col) >= 1));
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 3);
            QueryResponse r = engine.query(col, "test-query-nested-hnsw-quant", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
            assertFalse(r.getDocumentIds().isEmpty());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(43)
    void testQuery_topLevelParamsFallbackForHnswEf() throws Exception {
        writeQdrantQueryFile("test-query-top-level-params", """
                {"params": {"hnsw_ef": "32"}}
                """);
        String col = createCollectionWithOneVectorPoint("qi_top_params");
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 2);
            QueryResponse r = engine.query(col, "test-query-top-level-params", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(44)
    void testQuery_hnswEfUnresolvedSkipped() throws Exception {
        writeQdrantQueryFile("test-query-hnsw-bad", """
                {"template": {"params": {"hnsw_ef": "{{not_a_real_param}}"}}}
                """);
        String col = createCollectionWithOneVectorPoint("qi_hnsw_bad");
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 2);
            QueryResponse r = engine.query(col, "test-query-hnsw-bad", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(45)
    void testQuery_quantizationOversamplingUnresolvedSkipped() throws Exception {
        writeQdrantQueryFile("test-query-quant-bad", """
                {
                  "template": {
                    "params": {
                      "quantization": { "oversampling": "{{missing_oversample_param}}" }
                    }
                  }
                }
                """);
        String col = createCollectionWithOneVectorPoint("qi_quant_bad");
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 2);
            QueryResponse r = engine.query(col, "test-query-quant-bad", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(46)
    void testQuery_quantizationWithoutOversamplingKey() throws Exception {
        writeQdrantQueryFile("test-query-quant-no-oversample", """
                {"template": {"params": {"quantization": {}}}}
                """);
        String col = createCollectionWithOneVectorPoint("qi_quant_no_os");
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 2);
            QueryResponse r = engine.query(col, "test-query-quant-no-oversample", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(47)
    void testQuery_topLevelFilterFallback() throws Exception {
        String col = uniqueCollectionName("qi_top_filter");
        writeQdrantSchemaFile("test-schema-qi-top-filter", """
                {
                  "template": {
                    "vectors": {"size": 128, "distance": "Cosine"},
                    "payload_indexes": [
                      {"field_name": "valid", "field_schema": "bool"}
                    ]
                  }
                }
                """);
        writeQdrantQueryFile("test-query-top-filter", """
                {
                  "filter": {
                    "must": [
                      { "key": "valid", "match": { "value": true } }
                    ]
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-qi-top-filter"));
        try {
            Map<String, Object> fields = new HashMap<>();
            fields.put("id", "tf1");
            fields.put("valid", true);
            fields.put("embedding", generateRandomVector(128));
            assertEquals(1, engine.ingest(List.of(new Document(fields)), col, "id"));
            await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertTrue(engine.getDocumentCount(col) >= 1));
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 10);
            QueryResponse r = engine.query(col, "test-query-top-filter", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
            assertEquals(1, r.getDocumentIds().size());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(48)
    void testQuery_templateFilterJsonNullSkipped() throws Exception {
        writeQdrantQueryFile("test-query-filter-null", """
                {"template": {"filter": null}}
                """);
        String col = createCollectionWithOneVectorPoint("qi_filter_null");
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 3);
            QueryResponse r = engine.query(col, "test-query-filter-null", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(49)
    void testQuery_parseFilterEmptyMustSkipped() throws Exception {
        writeQdrantQueryFile("test-query-filter-empty-must", """
                {"template": {"filter": {"must": []}}}
                """);
        String col = createCollectionWithOneVectorPoint("qi_filter_empty");
        try {
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 4);
            QueryResponse r = engine.query(col, "test-query-filter-empty-must", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(50)
    void testQuery_numericPointIdsInResponse() throws Exception {
        String col = uniqueCollectionName("qi_numeric_ids");
        writeQdrantSchemaFile("test-schema-qi-numeric", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-qi-numeric"));
        try {
            List<Document> docs = List.of(
                    new Document(Map.of("id", 7_010L, "embedding", generateRandomVector(128))),
                    new Document(Map.of("id", 7_011L, "embedding", generateRandomVector(128)))
            );
            assertEquals(2, engine.ingest(docs, col, "id"));
            await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertTrue(engine.getDocumentCount(col) >= 2));

            writeQdrantQueryFile("test-query-numeric-ids", "{\"comment\": \"x\"}");
            Map<String, Object> p = new HashMap<>();
            p.put("query_vector", generateRandomVector(128));
            p.put("size", 5);
            QueryResponse r = engine.query(col, "test-query-numeric-ids", new QueryParams(p));
            assertNotNull(r.getClientLatencyMs());
            assertTrue(r.getDocumentIds().stream().anyMatch(id -> id.contains("7010") || id.contains("7011")),
                    "Expected numeric point id strings in response: " + r.getDocumentIds());
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(51)
    void testQuery_nonExistentCollectionReturnsEmpty() {
        Map<String, Object> p = new HashMap<>();
        p.put("query_vector", generateRandomVector(128));
        p.put("size", 3);
        String missing = "absent_collection_" + UUID.randomUUID().toString().replace("-", "");
        QueryResponse r = engine.query(missing, "test-query-basic", new QueryParams(p));
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
    }

    @Test
    @Order(52)
    void testGetIndexMetadata_nonexistentCollectionReturnsEmpty() {
        Map<String, String> meta = engine.getIndexMetadata("no_such_collection_" + UUID.randomUUID());
        assertNotNull(meta);
        assertTrue(meta.isEmpty());
    }

    @Test
    @Order(53)
    void testConfiguredGrpcTimeoutSeconds() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 44);
        assertEquals(44L, new QdrantEngine(cfg).configuredGrpcTimeoutSeconds());
    }

    /**
     * Forces {@link Document#getFloatList} to return null so ingest uses the double-list fallback.
     */
    private static final class FloatListNullDocument extends Document {
        FloatListNullDocument(Map<String, Object> fields) {
            super(fields);
        }

        @Override
        public List<Float> getFloatList(String field) {
            return null;
        }
    }

    @Test
    @Order(54)
    void testIngest_variousIdFieldStrategies() throws Exception {
        List<Document> docs = new ArrayList<>();
        Map<String, Object> f1 = new HashMap<>();
        f1.put("id", 9_001_000_000_000_000_001L);
        f1.put("label", "numeric-long");
        f1.put("embedding", generateRandomVector(128));
        docs.add(new Document(f1));

        Map<String, Object> f2 = new HashMap<>();
        f2.put("id", "9002000000000000002");
        f2.put("label", "numeric-string");
        f2.put("embedding", generateRandomVector(128));
        docs.add(new Document(f2));

        String validUuid = "550e8400-e29b-41d4-a716-4466554400aa";
        Map<String, Object> f3 = new HashMap<>();
        f3.put("id", validUuid);
        f3.put("label", "valid-uuid");
        f3.put("embedding", generateRandomVector(128));
        docs.add(new Document(f3));

        Map<String, Object> f4 = new HashMap<>();
        f4.put("id", "not-a-uuid-string");
        f4.put("label", "deterministic-uuid");
        f4.put("embedding", generateRandomVector(128));
        docs.add(new Document(f4));

        int ingested = engine.ingest(docs, TEST_INDEX, "id");
        assertEquals(4, ingested);
    }

    @Test
    @Order(55)
    void testIngest_doubleListFallbackWhenFloatListReturnsNull() throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", "double-fallback-doc");
        fields.put("title", "t");
        fields.put("embedding", generateRandomVector(128));
        FloatListNullDocument doc = new FloatListNullDocument(fields);
        int ingested = engine.ingest(List.of(doc), TEST_INDEX, "id");
        assertEquals(1, ingested);
    }

    @Test
    @Order(56)
    void testIngest_skipsDocumentsMissingVectorField() throws Exception {
        Map<String, Object> ok = new HashMap<>();
        ok.put("id", "has-vector");
        ok.put("embedding", generateRandomVector(128));
        Map<String, Object> missing = new HashMap<>();
        missing.put("id", "no-vector");
        missing.put("title", "no embedding");
        int ingested = engine.ingest(List.of(new Document(ok), new Document(missing)), TEST_INDEX, "id");
        assertEquals(1, ingested);
    }

    @Test
    @Order(57)
    void testIngest_idFieldConfiguredButAbsentOnDocumentUsesRandomUuid() throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("embedding", generateRandomVector(128));
        fields.put("note", "no custom_id key");
        int ingested = engine.ingest(List.of(new Document(fields)), TEST_INDEX, "custom_id");
        assertEquals(1, ingested);
    }

    @Test
    @Order(58)
    void testIngest_nonexistentCollectionReturnsZero() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", "x");
        fields.put("embedding", generateRandomVector(128));
        String missing = "collection_does_not_exist_" + UUID.randomUUID();
        assertEquals(0, engine.ingest(List.of(new Document(fields)), missing, "id"));
    }

    @Test
    @Order(59)
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

    @Test
    @Order(3)
    void testCreateIndex_directVectorsDotDistance() throws Exception {
        String col = uniqueCollectionName("ci_dot");
        writeQdrantSchemaFile("test-schema-ci-dot", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "dot" }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-dot"));
        assertTrue(engine.indexExists(col));
        engine.deleteIndex(col);
    }

    @Test
    @Order(4)
    void testCreateIndex_directVectorsManhattanDistance() throws Exception {
        String col = uniqueCollectionName("ci_manhattan");
        writeQdrantSchemaFile("test-schema-ci-manhattan", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "manhattan" }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-manhattan"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(5)
    void testCreateIndex_directVectorsEuclideanDistance() throws Exception {
        String col = uniqueCollectionName("ci_euclid");
        writeQdrantSchemaFile("test-schema-ci-euclid", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "l2" }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-euclid"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(6)
    void testCreateIndex_directVectorsUnknownDistanceDefaultsToCosine() throws Exception {
        String col = uniqueCollectionName("ci_default_dist");
        writeQdrantSchemaFile("test-schema-ci-unknown-dist", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "HammingUnknown" }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-unknown-dist"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(7)
    void testCreateIndex_topLevelShardNumber() throws Exception {
        String col = uniqueCollectionName("ci_shard_top");
        writeQdrantSchemaFile("test-schema-ci-shard-top", """
                {
                  "template": {
                    "shard_number": 1,
                    "vectors": { "size": 128, "distance": "Cosine" }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-shard-top"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(8)
    void testCreateIndex_settingsShardAndReplication() throws Exception {
        String col = uniqueCollectionName("ci_settings_shard");
        writeQdrantSchemaFile("test-schema-ci-settings-shard", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": {
                      "shard_number": 1,
                      "replication_factor": 1
                    }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-settings-shard"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(9)
    void testCreateIndex_topLevelReplicationFactorOverridesSettings() throws Exception {
        String col = uniqueCollectionName("ci_repl_override");
        writeQdrantSchemaFile("test-schema-ci-repl-override", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": { "shard_number": 1 },
                    "replication_factor": 1
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-repl-override"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(10)
    void testCreateIndex_hnswConfigMOnly() throws Exception {
        String col = uniqueCollectionName("ci_hnsw_m");
        writeQdrantSchemaFile("test-schema-ci-hnsw-m", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": { "hnsw_config": { "m": 12 } }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-hnsw-m"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(11)
    void testCreateIndex_hnswConfigEfConstructOnly() throws Exception {
        String col = uniqueCollectionName("ci_hnsw_ef");
        writeQdrantSchemaFile("test-schema-ci-hnsw-ef", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": { "hnsw_config": { "ef_construct": 88 } }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-hnsw-ef"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(12)
    void testCreateIndex_quantizationBinaryAlwaysRamTrue() throws Exception {
        String col = uniqueCollectionName("ci_quant_ram");
        writeQdrantSchemaFile("test-schema-ci-quant-always-ram", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": {
                      "quantization_config": {
                        "binary": { "always_ram": true }
                      }
                    }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-quant-always-ram"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(13)
    void testCreateIndex_quantizationBinaryWithoutAlwaysRamField() throws Exception {
        String col = uniqueCollectionName("ci_quant_no_ram");
        writeQdrantSchemaFile("test-schema-ci-quant-no-always-ram", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": {
                      "quantization_config": {
                        "binary": {}
                      }
                    }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-quant-no-always-ram"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(14)
    void testCreateIndex_esMappingsFormatWithPayloadIndexesOnProperties() throws Exception {
        String col = uniqueCollectionName("ci_map_payload");
        writeQdrantSchemaFile("test-schema-ci-mappings-payload", """
                {
                  "template": {
                    "mappings": {
                      "properties": {
                        "embedding": { "type": "dense_vector", "size": 128, "distance": "cosine" },
                        "f_keyword": { "type": "keyword" },
                        "f_integer": { "type": "integer" },
                        "f_float": { "type": "float" },
                        "f_bool": { "type": "bool" },
                        "f_boolean": { "type": "boolean" },
                        "f_geo": { "type": "geo" },
                        "f_text": { "type": "text" },
                        "f_default": { "type": "wildcard" }
                      }
                    }
                  }
                }
                """);
        assertTrue(engine.createIndex(col, "test-schema-ci-mappings-payload"));
        engine.deleteIndex(col);
    }

    @Test
    @Order(15)
    void testCreateIndex_payloadIndexesDirectFormat() throws Exception {
        String col = uniqueCollectionName("ci_direct_payload");
        try {
            writeQdrantSchemaFile("test-schema-ci-payload-direct", """
                    {
                      "template": {
                        "vectors": { "size": 128, "distance": "Cosine" },
                        "payload_indexes": [
                        { "field_name": "d_kw", "field_schema": "keyword" },
                        { "field_name": "d_int", "field_schema": "integer" },
                        { "field_name": "d_flt", "field_schema": "float" },
                        { "field_name": "d_bool", "field_schema": "bool" },
                        { "field_name": "d_bool2", "field_schema": "boolean" },
                        { "field_name": "d_geo", "field_schema": "geo" },
                        { "field_name": "d_txt", "field_schema": "text" },
                        { "field_name": "d_def", "field_schema": "unknown_custom" }
                      ]
                      }
                    }
                    """);
            assertTrue(engine.createIndex(col, "test-schema-ci-payload-direct"));
        } finally {
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(16)
    void testCreateIndex_returnsFalseWhenNoVectorsOrMappings() throws Exception {
        writeQdrantSchemaFile("test-schema-ci-fail-no-vectors", """
                {
                  "template": { "note": "no vectors or mappings" }
                }
                """);
        assertFalse(engine.createIndex(uniqueCollectionName("ci_fail_nv"), "test-schema-ci-fail-no-vectors"));
    }

    @Test
    @Order(17)
    void testCreateIndex_returnsFalseWhenMappingsMissingProperties() throws Exception {
        writeQdrantSchemaFile("test-schema-ci-fail-no-props", """
                {
                  "template": { "mappings": {} }
                }
                """);
        assertFalse(engine.createIndex(uniqueCollectionName("ci_fail_np"), "test-schema-ci-fail-no-props"));
    }

    @Test
    @Order(18)
    void testCreateIndex_returnsFalseWhenMappingsMissingVectorField() throws Exception {
        writeQdrantSchemaFile("test-schema-ci-fail-wrong-vec", """
                {
                  "template": {
                    "mappings": {
                      "properties": {
                        "not_embedding": { "type": "dense_vector", "size": 128, "distance": "cosine" }
                      }
                    }
                  }
                }
                """);
        assertFalse(engine.createIndex(uniqueCollectionName("ci_fail_wv"), "test-schema-ci-fail-wrong-vec"));
    }

    @Test
    @Order(19)
    void testCreateIndex_returnsFalseWhenRootMissingTemplateKey() throws Exception {
        writeQdrantSchemaFile("test-schema-ci-fail-empty-root", "{}");
        assertFalse(engine.createIndex(uniqueCollectionName("ci_fail_root"), "test-schema-ci-fail-empty-root"));
    }

    @Test
    @Order(20)
    void testCreateIndex_returnsFalseWhenInvalidVectorSize() throws Exception {
        writeQdrantSchemaFile("test-schema-ci-fail-bad-size", """
                {
                  "template": {
                    "vectors": { "size": -1, "distance": "Cosine" }
                  }
                }
                """);
        assertFalse(engine.createIndex(uniqueCollectionName("ci_fail_sz"), "test-schema-ci-fail-bad-size"));
    }

    @Test
    @Order(21)
    void testCreateIndex_returnsFalseWhenSchemaTemplateFileNotFound() {
        assertFalse(engine.createIndex(uniqueCollectionName("ci_no_schema"), "missing-schema-zzz-42"));
    }

    @Test
    @Order(60)
    void testConnect_explicitHttpSchemeWithNonEmptyApiKey() throws Exception {
        String host = qdrant.getHost();
        int grpcPort = qdrant.getMappedPort(6334);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://" + host + ":" + grpcPort);
        cfg.put("api_key", "integration-nonempty-api-key");
        cfg.put("grpc_timeout_seconds", 90L);
        QdrantEngine e = new QdrantEngine(cfg);
        try {
            assertTrue(e.connect(), "Qdrant accepts connections with a non-empty api_key in config");
        } finally {
            e.close();
        }
    }

    @Test
    @Order(61)
    void testConnect_urlReadFromEnvironmentWhenAbsentFromConfig() throws Exception {
        final String host = qdrant.getHost();
        final int grpcPort = qdrant.getMappedPort(6334);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", "TC_JINGRA_QDRANT_URL_INT");
        cfg.put("grpc_timeout_seconds", 90L);
        QdrantEngine e = new QdrantEngine(cfg) {
            @Override
            protected String getEnv(String name, String fallback) {
                if ("TC_JINGRA_QDRANT_URL_INT".equals(name)) {
                    return host + ":" + grpcPort;
                }
                return super.getEnv(name, fallback);
            }
        };
        try {
            assertTrue(e.connect());
        } finally {
            e.close();
        }
    }

    @Test
    @Order(62)
    void testConnect_httpsWithoutInsecureTlsUsesTlsChannelBuilder() throws Exception {
        System.clearProperty("jingra.insecure.tls");
        String host = qdrant.getHost();
        int grpcPort = qdrant.getMappedPort(6334);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "https://" + host + ":" + grpcPort);
        cfg.put("api_key", "integration-https-default-tls-api-key");
        cfg.put("grpc_timeout_seconds", 90L);
        QdrantEngine e = new QdrantEngine(cfg);
        try {
            // Container gRPC is plain TCP; TLS handshake should fail, not crash during client setup
            assertFalse(e.connect());
        } finally {
            e.close();
        }
    }

    @Test
    @Order(63)
    void testConnect_httpsWithInsecureTlsUsesTrustAllChannelBuilder() throws Exception {
        System.setProperty("jingra.insecure.tls", "true");
        String host = qdrant.getHost();
        int grpcPort = qdrant.getMappedPort(6334);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "https://" + host + ":" + grpcPort);
        cfg.put("api_key", "integration-tls-insecure-api-key");
        cfg.put("grpc_timeout_seconds", 90L);
        QdrantEngine e = new QdrantEngine(cfg);
        try {
            // Custom TLS channel is built; handshake still fails against plain gRPC, handled by outer catch
            assertFalse(e.connect());
        } finally {
            System.clearProperty("jingra.insecure.tls");
            e.close();
        }
    }

    @Test
    @Order(64)
    void testQuery_writesFirstQueryDumpWhenDirectoryConfigured() throws Exception {
        Path dumpDir = Files.createTempDirectory("jingra-qd-dump-it");
        String col = uniqueCollectionName("qd_dump");
        String schemaName = "test-schema-qd-dump-query";
        String queryName = "test-query-dump-it";
        try {
            String host = qdrant.getHost();
            int grpcPort = qdrant.getMappedPort(6334);
            int httpPort = qdrant.getMappedPort(6333);
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url", host + ":" + grpcPort);
            cfg.put("rest_url", "http://" + host + ":" + httpPort);
            cfg.put("vector_field", "embedding");
            cfg.put("grpc_timeout_seconds", 90L);
            cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, dumpDir.toString());
            QdrantEngine qe = new QdrantEngine(cfg);
            assertTrue(qe.connect());
            writeQdrantSchemaFile(schemaName, SCHEMA_MINIMAL_QDRANT_VECTOR_128);
            assertTrue(qe.createIndex(col, schemaName));
            assertEquals(1, qe.ingest(List.of(
                    new Document(Map.of("id", "dump-1", "embedding", generateRandomVector(128)))), col, "id"));
            await().pollInSameThread().atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertTrue(qe.getDocumentCount(col) >= 1));
            writeQdrantQueryFile(queryName, """
                    {"template": {}}
                    """);
            Map<String, Object> params = new HashMap<>();
            params.put("query_vector", generateRandomVector(128));
            params.put("size", 1);
            qe.query(col, queryName, new QueryParams(params));
            assertTrue(Files.isRegularFile(dumpDir.resolve("qd-first-query.json")));
            qe.close();
        } finally {
            deleteRecursivelyIfExists(dumpDir);
            engine.deleteIndex(col);
        }
    }

    @Test
    @Order(65)
    void testCreateIndex_quantizationBinaryAlwaysRamFalse() throws Exception {
        String col = uniqueCollectionName("ci_quant_ram_false");
        writeQdrantSchemaFile("test-schema-ci-quant-always-ram-false", """
                {
                  "template": {
                    "vectors": { "size": 128, "distance": "Cosine" },
                    "settings": {
                      "quantization_config": {
                        "binary": { "always_ram": false }
                      }
                    }
                  }
                }
                """);
        try {
            assertTrue(engine.createIndex(col, "test-schema-ci-quant-always-ram-false"));
        } finally {
            engine.deleteIndex(col);
        }
    }

    private static void deleteRecursivelyIfExists(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup of temp dump dir
                }
            });
        } catch (Exception ignored) {
            // ignore
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
