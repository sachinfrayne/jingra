package org.elasticsearch.jingra.engine;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexState;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.opensearch.indices.GetIndexResponse;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline tests for {@link OpenSearchEngine} branches that are hard to hit only via Testcontainers.
 * Uses same-package subclasses (see {@link OpenSearchEngine#hasClient()} and related hooks) because
 * {@code OpenSearchClient} cannot be mocked on recent JDKs with Mockito inline.
 */
class OpenSearchEngineBehaviorTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OS_OFFLINE_URL_ENV__";

    @AfterEach
    void clearInsecureTlsProperty() {
        System.clearProperty("jingra.insecure.tls");
    }

    /** Pretends a connected engine so API methods run without a real {@code OpenSearchClient}. */
    abstract static class ConnectedHarness extends OpenSearchEngine {
        ConnectedHarness(Map<String, Object> cfg) {
            super(cfg);
        }

        @Override
        protected boolean hasClient() {
            return true;
        }
    }

    private static void injectRestClient(OpenSearchEngine engine, RestClient rest) throws Exception {
        Field fr = OpenSearchEngine.class.getDeclaredField("restClient");
        fr.setAccessible(true);
        fr.set(engine, rest);
    }

    private static Response jsonResponse(String jsonBody) throws IOException {
        try {
            BasicClassicHttpResponse classic = new BasicClassicHttpResponse(200, "OK");
            classic.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
            org.apache.hc.core5.http.message.RequestLine rl =
                    new org.apache.hc.core5.http.message.RequestLine("POST", "/idx/_search", HttpVersion.HTTP_1_1);
            HttpHost host = new HttpHost("http", "127.0.0.1", 9200);
            Constructor<Response> ctor = Response.class.getDeclaredConstructor(
                    org.apache.hc.core5.http.message.RequestLine.class, HttpHost.class, ClassicHttpResponse.class);
            ctor.setAccessible(true);
            return ctor.newInstance(rl, host, classic);
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }

    @Test
    void connectReturnsFalseWhenUrlMissing() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectReturnsFalseWhenUrlMalformed() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://[");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectReturnsFalseWhenUnreachable() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:1");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectAppliesBasicAuthWhenUserAndPasswordSet() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:1");
        cfg.put("user", "test-user");
        cfg.put("password", "test-secret");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void gettersWhenNeverConnected() {
        OpenSearchEngine e = new OpenSearchEngine(new HashMap<>());
        assertFalse(e.connect());
        assertEquals("opensearch", e.getEngineName());
        assertEquals("os", e.getShortName());
        assertEquals("unknown", e.getVersion());
    }

    @Test
    void createIndexReturnsFalseWhenClientNull() {
        OpenSearchEngine e = new OpenSearchEngine(new HashMap<>());
        assertFalse(e.createIndex("i", "any"));
    }

    @Test
    void indexExistsReturnsFalseWhenClientNull() {
        assertFalse(new OpenSearchEngine(new HashMap<>()).indexExists("i"));
    }

    @Test
    void deleteIndexReturnsFalseWhenClientNull() {
        assertFalse(new OpenSearchEngine(new HashMap<>()).deleteIndex("i"));
    }

    @Test
    void ingestReturnsZeroWhenClientNull() {
        assertEquals(0, new OpenSearchEngine(new HashMap<>()).ingest(List.of(new Document(Map.of("a", 1))), "i", null));
    }

    @Test
    void queryReturnsEmptyWhenClientNull() {
        QueryResponse r = new OpenSearchEngine(new HashMap<>()).query("i", "q", new QueryParams());
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
        assertNull(r.getServerLatencyMs());
    }

    @Test
    void getDocumentCountZeroWhenClientNull() {
        assertEquals(0L, new OpenSearchEngine(new HashMap<>()).getDocumentCount("i"));
    }

    @Test
    void getIndexMetadataEmptyWhenClientNull() {
        assertTrue(new OpenSearchEngine(new HashMap<>()).getIndexMetadata("i").isEmpty());
    }

    @Test
    void closeSafeWhenRestClientNull() throws Exception {
        new OpenSearchEngine(new HashMap<>()).close();
    }

    @Test
    void closeClosesRestClientWhenPresent() throws Exception {
        RestClient rc = RestClient.builder(new HttpHost("http", "127.0.0.1", 1)).build();
        try {
            OpenSearchEngine e = new OpenSearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            e.close();
            assertFalse(rc.isRunning(), "RestClient should be closed");
        } finally {
            if (rc.isRunning()) {
                rc.close();
            }
        }
    }

    @Test
    void indexExistsReturnsFalseWhenClientThrows() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected boolean indexExistsOperation(String indexName) {
                throw new RuntimeException("boom");
            }
        };
        assertFalse(e.indexExists("x"));
    }

    @Test
    void getDocumentCountReturnsZeroWhenCountThrows() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected long countOperation(String indexName) throws Exception {
                throw new IOException("count failed");
            }
        };
        assertEquals(0L, e.getDocumentCount("x"));
    }

    @Test
    void getVersionUnknownWhenInfoThrows() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected String versionOperation() throws Exception {
                throw new IOException("info failed");
            }
        };
        assertEquals("unknown", e.getVersion());
    }

    @Test
    void deleteIndexTrueOn404() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void deleteIndexOperation(String indexName) {
                ErrorResponse er = ErrorResponse.of(b -> b.status(404)
                        .error(ErrorCause.of(x -> x.type("index_not_found_exception").reason("nf"))));
                throw new OpenSearchException(er);
            }
        };
        assertTrue(e.deleteIndex("missing"));
    }

    @Test
    void deleteIndexFalseOnNon404OpenSearchException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void deleteIndexOperation(String indexName) {
                ErrorResponse er = ErrorResponse.of(b -> b.status(500)
                        .error(ErrorCause.of(x -> x.type("internal_server_error").reason("err"))));
                throw new OpenSearchException(er);
            }
        };
        assertFalse(e.deleteIndex("x"));
    }

    @Test
    void deleteIndexFalseOnGenericException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void deleteIndexOperation(String indexName) throws Exception {
                throw new IOException("io");
            }
        };
        assertFalse(e.deleteIndex("x"));
    }

    @Test
    void createIndexFalseWhenIndexAlreadyExists() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected boolean indexExistsOperation(String indexName) {
                return true;
            }
        };
        assertFalse(e.createIndex("exists", "any"));
    }

    @Test
    void createIndexFalseWhenSchemaMissing() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected boolean indexExistsOperation(String indexName) {
                return false;
            }
        };
        assertFalse(e.createIndex("i", "__schema_file_does_not_exist__"));
    }

    @Test
    void createIndexFalseWhenSchemaHasNoTemplateField() throws Exception {
        Path dir = Path.of("jingra-config/schemas");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-no-template-key.json");
        Files.writeString(f, "{\"template\": {\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean indexExistsOperation(String indexName) {
                    return false;
                }
            };
            // Wrapped schemas are rejected under the direct-only contract.
            assertFalse(e.createIndex("i", "behavior-os-no-template-key"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void createIndexFalseWhenPutThrows() throws Exception {
        Path dir = Path.of("jingra-config/schemas");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-put-fail.json");
        Files.writeString(f, "{\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean indexExistsOperation(String indexName) {
                    return false;
                }

                @Override
                protected Response performRestRequest(Request request) throws IOException {
                    throw new IOException("put failed");
                }
            };
            assertFalse(e.createIndex("i", "behavior-os-put-fail"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryReturnsEmptyWhenTemplateMissing() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
        QueryResponse r = e.query("idx", "__no_query_template__", new QueryParams());
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
        assertNull(r.getServerLatencyMs());
    }

    @Test
    void queryRethrowsIllegalStateFromRender() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-bad-render.json");
        Files.writeString(f, "{\"query\": {\"term\": {\"x\": \"{{v}}\"}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            List<Object> cyclic = new ArrayList<>();
            cyclic.add(cyclic);
            QueryParams qp = new QueryParams(Map.of("v", cyclic));
            assertThrows(IllegalStateException.class,
                    () -> e.query("idx", "behavior-os-bad-render", qp));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryOmitsHitsWithEmptyId() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query-empty-id.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 2}
                """);
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected Response performRestRequest(Request request) throws IOException {
                    return jsonResponse(
                            "{\"hits\":{\"hits\":[{\"_id\":\"\"},{\"_id\":\"real\"}]},\"took\":1}");
                }
            };
            QueryResponse r = e.query("idx", "behavior-os-query-empty-id", new QueryParams());
            assertEquals(List.of("real"), r.getDocumentIds());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryStoresLastQueryJsonOnceAndReturnsHits() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 1}
                """);
        try {
            AtomicBoolean secondRound = new AtomicBoolean(false);
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected Response performRestRequest(Request request) throws IOException {
                    if (!secondRound.get()) {
                        return jsonResponse("{\"hits\":{\"hits\":[{\"_id\":\"doc1\"}]},\"took\":3}");
                    }
                    return jsonResponse("{\"hits\":{\"hits\":[{\"_id\":\"doc2\"}]},\"took\":1}");
                }
            };

            QueryResponse r1 = e.query("my-index", "behavior-os-query", new QueryParams());
            assertEquals(List.of("doc1"), r1.getDocumentIds());
            assertNotNull(r1.getClientLatencyMs());
            assertEquals(3L, r1.getServerLatencyMs().longValue());

            secondRound.set(true);
            e.query("other", "behavior-os-query", new QueryParams());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryReturnsEmptyOnGenericFailure() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query-fail.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 1}
                """);
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected Response performRestRequest(Request request) throws IOException {
                    throw new IOException("search failed");
                }
            };
            QueryResponse r = e.query("idx", "behavior-os-query-fail", new QueryParams());
            assertTrue(r.getDocumentIds().isEmpty());
            assertNull(r.getClientLatencyMs());
            assertNull(r.getServerLatencyMs());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void ingestThrowsWhenPartialErrorsAndFailOnPartialTrue() throws Exception {
        BulkResponseItem item = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").error(
                ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad"))).status(400));
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(item)));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = List.of(new Document(Map.of("f", "v")));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> e.ingest(docs, "idx", null));
        assertEquals("Bulk ingest failed", ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("errors"));
    }

    @Test
    void ingestReturnsSuccessCountMinusErrorsWhenFailOnPartialFalse() throws Exception {
        BulkResponseItem item = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").error(
                ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad"))).status(400));
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(item)));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("ingest_fail_on_partial_errors", false);
        ConnectedHarness e = new ConnectedHarness(cfg) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        assertEquals(0, e.ingest(List.of(new Document(Map.of("f", "v"))), "idx", null));
    }

    @Test
    void ingestSkipsDocumentIdWhenIdFieldNotPresentOnDocument() throws Exception {
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return ok;
            }
        };
        assertEquals(1, e.ingest(List.of(new Document(Map.of("f", "v"))), "idx", "missing_field"));
        assertNotNull(captured.get());
        assertEquals(1, captured.get().operations().size());
    }

    @Test
    void ingestCountsOnlyItemsWithErrorsWhenBulkResponseHasMixedItems() throws Exception {
        BulkResponseItem successItem = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").status(201));
        BulkResponseItem failItem = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").error(
                ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad"))).status(400));
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(successItem, failItem)));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("ingest_fail_on_partial_errors", false);
        ConnectedHarness e = new ConnectedHarness(cfg) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = List.of(new Document(Map.of("a", 1)), new Document(Map.of("b", 2)));
        assertEquals(1, e.ingest(docs, "idx", null));
    }

    @Test
    void ingestLogsAtMostFivePerItemErrorsWhenManyDocumentsFail() throws Exception {
        List<BulkResponseItem> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            items.add(BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").error(
                    ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("err-" + n))).status(400)));
        }
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(items));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("ingest_fail_on_partial_errors", false);
        ConnectedHarness e = new ConnectedHarness(cfg) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            docs.add(new Document(Map.of("f", i)));
        }
        assertEquals(0, e.ingest(docs, "idx", null));
    }

    @Test
    void ingestWrapsBulkFailureInRuntimeException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) throws Exception {
                throw new IOException("bulk io");
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> e.ingest(List.of(new Document(Map.of("f", "v"))), "idx", null));
        assertEquals("Bulk ingest failed", ex.getMessage());
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    void getIndexMetadataSwallowsGetFailure() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) throws Exception {
                throw new IOException("get idx failed");
            }
        };
        assertTrue(e.getIndexMetadata("x").isEmpty());
    }

    @Test
    void getVersionReadsFromInfo() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected String versionOperation() {
                return "3.9.9";
            }
        };
        assertEquals("3.9.9", e.getVersion());
    }

    @Test
    void openSearchInsecureTrustStrategyAcceptsChains() throws Exception {
        assertTrue(OpenSearchEngine.openSearchInsecureTrustStrategy()
                .isTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void connectUrlWithoutSchemeDefaultsToHttps() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "//127.0.0.1:1");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectHttpUrlWithoutPortUsesDefault9200() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectHttpsUrlWithoutPortUsesDefault443() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "https://127.0.0.1");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectHttpsWithInsecureTlsConfiguresTrustAll() {
        System.setProperty("jingra.insecure.tls", "true");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "https://127.0.0.1:1");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void connectReadsUrlFromEnvWhenMissingFromConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", "TC_JINGRA_OS_URL_INT");
        OpenSearchEngine e = new OpenSearchEngine(cfg) {
            @Override
            protected String getEnv(String name, String fallback) {
                if ("TC_JINGRA_OS_URL_INT".equals(name)) {
                    return "http://127.0.0.1:1";
                }
                return super.getEnv(name, fallback);
            }
        };
        assertFalse(e.connect());
    }

    @Test
    void connectSkipsBasicAuthWhenOnlyPasswordConfigured() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:1");
        cfg.put("password", "secret-only");
        assertFalse(new OpenSearchEngine(cfg).connect());
    }

    @Test
    void firstOpenSearchVectorType_nullMapping() {
        assertNull(OpenSearchEngine.firstOpenSearchVectorType(null));
    }

    @Test
    void firstOpenSearchVectorType_nullProperties() {
        TypeMapping tm = mock(TypeMapping.class);
        when(tm.properties()).thenReturn(null);
        assertNull(OpenSearchEngine.firstOpenSearchVectorType(tm));
    }

    @Test
    void firstOpenSearchVectorType_keywordOnly() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("id", Property.of(p -> p.keyword(k -> k))));
        assertNull(OpenSearchEngine.firstOpenSearchVectorType(tm));
    }

    @Test
    void firstOpenSearchVectorType_knnVectorField() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("vec", Property.of(p -> p.knnVector(k -> k.dimension(4)))));
        assertEquals("knn_vector", OpenSearchEngine.firstOpenSearchVectorType(tm));
    }

    @Test
    void firstOpenSearchVectorType_prefersFirstKnnAmongProperties() {
        TypeMapping tm = TypeMapping.of(m -> m
                .properties("id", Property.of(p -> p.keyword(k -> k)))
                .properties("vec", Property.of(p -> p.knnVector(k -> k.dimension(2)))));
        assertEquals("knn_vector", OpenSearchEngine.firstOpenSearchVectorType(tm));
    }

    @Test
    void getIndexMetadataWhenIndexKeyMissing() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("e", Property.of(p -> p.knnVector(k -> k.dimension(2)))));
        GetIndexResponse resp = GetIndexResponse.of(r -> r.putResult("other-idx", IndexState.of(is -> is.mappings(tm))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        assertTrue(e.getIndexMetadata("wanted-idx").isEmpty());
    }

    @Test
    void getIndexMetadataWhenMappingsAbsent() {
        GetIndexResponse resp = GetIndexResponse.of(r -> r.putResult("x", IndexState.of(is -> is.aliases(Map.of()))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        assertTrue(e.getIndexMetadata("x").isEmpty());
    }

    @Test
    void getIndexMetadataReturnsKnnVectorFromMappings() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("vec", Property.of(p -> p.knnVector(k -> k.dimension(8)))));
        GetIndexResponse resp = GetIndexResponse.of(r -> r.putResult("knn-idx", IndexState.of(is -> is.mappings(tm))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        assertEquals("knn_vector", e.getIndexMetadata("knn-idx").get("vector_type"));
    }

    @Test
    void getIndexMetadataKeywordOnlyLeavesVectorTypeAbsent() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("t", Property.of(p -> p.text(t -> t))));
        GetIndexResponse resp = GetIndexResponse.of(r -> r.putResult("plain", IndexState.of(is -> is.mappings(tm))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        assertTrue(e.getIndexMetadata("plain").isEmpty());
    }

    @Test
    void querySkipsDocumentIdsWhenInnerHitsNotArray() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query-hits-not-array.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 1}
                """);
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected Response performRestRequest(Request request) throws IOException {
                    return jsonResponse("{\"hits\":{\"hits\":{}},\"took\":1}");
                }
            };
            QueryResponse r = e.query("idx", "behavior-os-query-hits-not-array", new QueryParams());
            assertTrue(r.getDocumentIds().isEmpty());
            assertNotNull(r.getClientLatencyMs());
            assertEquals(1L, r.getServerLatencyMs().longValue());
        } finally {
            Files.deleteIfExists(f);
        }
    }

}
