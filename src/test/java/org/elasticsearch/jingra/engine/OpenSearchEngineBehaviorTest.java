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
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests for {@link OpenSearchEngine} branches that are hard to hit only via Testcontainers.
 * Uses same-package subclasses (see {@link OpenSearchEngine#hasClient()} and related hooks) because
 * {@code OpenSearchClient} cannot be mocked on recent JDKs with Mockito inline.
 */
class OpenSearchEngineBehaviorTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OS_OFFLINE_URL_ENV__";

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
        assertNull(e.getLastQueryJson());
        assertNull(e.getLastIndexName());
    }

    @Test
    void formatJsonForDisplay_prettyPrintsValidJson() {
        OpenSearchEngine e = new OpenSearchEngine(new HashMap<>());
        String out = e.formatJsonForDisplay("{\"x\":1}");
        assertTrue(out.contains("\n"));
        assertTrue(out.contains("\"x\""));
    }

    @Test
    void formatJsonForDisplay_returnsOriginalWhenInvalid() {
        OpenSearchEngine e = new OpenSearchEngine(new HashMap<>());
        String bad = "{ not valid json";
        assertEquals(bad, e.formatJsonForDisplay(bad));
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
        Path dir = Path.of("jingra-config/schemas/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-no-template-key.json");
        Files.writeString(f, "{\"not_template\": {}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean indexExistsOperation(String indexName) {
                    return false;
                }
            };
            assertFalse(e.createIndex("i", "behavior-os-no-template-key"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void createIndexFalseWhenPutThrows() throws Exception {
        Path dir = Path.of("jingra-config/schemas/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-put-fail.json");
        Files.writeString(f, "{\"template\": {\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}}");
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
        Path dir = Path.of("jingra-config/queries/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-bad-render.json");
        Files.writeString(f, "{\"no_template\": true}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            assertThrows(IllegalStateException.class,
                    () -> e.query("idx", "behavior-os-bad-render", new QueryParams()));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryOmitsHitsWithEmptyId() throws Exception {
        Path dir = Path.of("jingra-config/queries/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query-empty-id.json");
        Files.writeString(f, """
                {"template": {"query": {"match_all": {}}, "size": 2}}
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
        Path dir = Path.of("jingra-config/queries/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query.json");
        Files.writeString(f, """
                {"template": {"query": {"match_all": {}}, "size": 1}}
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

            String firstJson = e.getLastQueryJson();
            String firstIdx = e.getLastIndexName();
            assertNotNull(firstJson);
            assertEquals("my-index", firstIdx);

            secondRound.set(true);
            e.query("other", "behavior-os-query", new QueryParams());
            assertEquals(firstJson, e.getLastQueryJson());
            assertEquals("my-index", e.getLastIndexName());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryReturnsEmptyOnGenericFailure() throws Exception {
        Path dir = Path.of("jingra-config/queries/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-query-fail.json");
        Files.writeString(f, """
                {"template": {"query": {"match_all": {}}, "size": 1}}
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
    void getLastQueryJsonAndLastIndexNameGetters() throws Exception {
        Path dir = Path.of("jingra-config/queries/opensearch");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-os-getters.json");
        Files.writeString(f, "{\"template\": {\"query\": {\"match_all\": {}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected Response performRestRequest(Request request) throws IOException {
                    return jsonResponse("{\"hits\":{\"hits\":[]},\"took\":0}");
                }
            };
            e.query("getter-idx", "behavior-os-getters", new QueryParams());
            assertNotNull(e.getLastQueryJson());
            assertEquals("getter-idx", e.getLastIndexName());
        } finally {
            Files.deleteIfExists(f);
        }
    }
}
