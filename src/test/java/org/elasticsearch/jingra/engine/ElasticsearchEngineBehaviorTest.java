package org.elasticsearch.jingra.engine;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.core5.http.HttpHost;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.Test;

import co.elastic.clients.transport.rest5_client.low_level.Response;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline tests for {@link ElasticsearchEngine} branches not covered efficiently by Testcontainers.
 * Uses same-package subclasses ({@link ElasticsearchEngine#hasClient()} and operation hooks) because
 * {@code ElasticsearchClient} cannot be mocked on recent JDKs with Mockito inline.
 */
class ElasticsearchEngineBehaviorTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_ES_OFFLINE_URL_ENV__";

    abstract static class ConnectedHarness extends ElasticsearchEngine {
        ConnectedHarness(Map<String, Object> cfg) {
            super(cfg);
        }

        @Override
        protected boolean hasClient() {
            return true;
        }
    }

    private static void injectRestClient(ElasticsearchEngine engine, Rest5Client rest) throws Exception {
        Field fr = ElasticsearchEngine.class.getDeclaredField("restClient");
        fr.setAccessible(true);
        fr.set(engine, rest);
    }

    private static SearchResponse<Map> searchHitsWithIds(String... ids) {
        if (ids.length == 0) {
            return SearchResponse.of(s -> s
                    .timedOut(false)
                    .took(3L)
                    .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                    .hits(HitsMetadata.of(h -> h
                            .hits(List.of())
                            .total(TotalHits.of(t -> t.value(0).relation(TotalHitsRelation.Eq))))));
        }
        Hit<Map> first = Hit.<Map>of(h -> h.id(ids[0]).index("idx").source(Map.of()));
        if (ids.length == 1) {
            return SearchResponse.of(s -> s
                    .timedOut(false)
                    .took(3L)
                    .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                    .hits(HitsMetadata.of(h -> h
                            .hits(first)
                            .total(TotalHits.of(t -> t.value(1).relation(TotalHitsRelation.Eq))))));
        }
        List<Hit<Map>> tail = new ArrayList<>();
        for (int i = 1; i < ids.length; i++) {
            String id = ids[i];
            tail.add(Hit.<Map>of(h -> h.id(id).index("idx").source(Map.of())));
        }
        @SuppressWarnings("unchecked")
        Hit<Map>[] rest = tail.toArray(new Hit[0]);
        return SearchResponse.of(s -> s
                .timedOut(false)
                .took(3L)
                .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                .hits(HitsMetadata.of(h -> h
                        .hits(first, rest)
                        .total(TotalHits.of(t -> t.value(ids.length).relation(TotalHitsRelation.Eq))))));
    }

    static final class InsecureTlsProbe extends ElasticsearchEngine {
        InsecureTlsProbe(Map<String, Object> cfg) {
            super(cfg);
        }

        boolean probe() {
            return resolveInsecureTls();
        }
    }

    @Test
    void resolveInsecureTls_explicitFalseOverridesGlobalProperty() {
        System.setProperty("jingra.insecure.tls", "true");
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("insecure_tls", false);
            assertFalse(new InsecureTlsProbe(cfg).probe());
        } finally {
            System.clearProperty("jingra.insecure.tls");
        }
    }

    @Test
    void resolveInsecureTls_explicitTrueOverridesGlobalProperty() {
        System.setProperty("jingra.insecure.tls", "false");
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("insecure_tls", true);
            assertTrue(new InsecureTlsProbe(cfg).probe());
        } finally {
            System.clearProperty("jingra.insecure.tls");
        }
    }

    @Test
    void resolveInsecureTls_absentKeyUsesGlobalProperty() {
        System.setProperty("jingra.insecure.tls", "true");
        try {
            Map<String, Object> cfg = new HashMap<>();
            assertTrue(new InsecureTlsProbe(cfg).probe());
        } finally {
            System.clearProperty("jingra.insecure.tls");
        }
    }

    @Test
    void connectReturnsFalseWhenUrlMissing() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        assertFalse(new ElasticsearchEngine(cfg).connect());
    }

    @Test
    void connectReturnsFalseWhenUrlMalformed() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://[");
        assertFalse(new ElasticsearchEngine(cfg).connect());
    }

    @Test
    void connectReturnsFalseWhenUnreachable() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:1");
        assertFalse(new ElasticsearchEngine(cfg).connect());
    }

    @Test
    void connectAppliesBasicAuthWhenUserAndPasswordSet() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:1");
        cfg.put("user", "test-user");
        cfg.put("password", "test-secret");
        assertFalse(new ElasticsearchEngine(cfg).connect());
    }

    @Test
    void gettersWhenNeverConnected() {
        ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
        assertFalse(e.connect());
        assertEquals("elasticsearch", e.getEngineName());
        assertEquals("es", e.getShortName());
        assertEquals("unknown", e.getVersion());
    }

    @Test
    void createDataStoreReturnsFalseWhenClientNull() {
        assertFalse(new ElasticsearchEngine(new HashMap<>()).createDataStore("i", "any"));
    }

    @Test
    void dataStoreExistsReturnsFalseWhenClientNull() {
        assertFalse(new ElasticsearchEngine(new HashMap<>()).dataStoreExists("i"));
    }

    @Test
    void resetDataStoreReturnsFalseWhenClientNull() {
        assertFalse(new ElasticsearchEngine(new HashMap<>()).resetDataStore("i"));
    }

    @Test
    void ingestReturnsZeroWhenClientNull() {
        assertEquals(0, new ElasticsearchEngine(new HashMap<>()).ingest(List.of(new Document(Map.of("a", 1))), "i", null));
    }

    @Test
    void queryReturnsEmptyWhenClientNull() {
        QueryResponse r = new ElasticsearchEngine(new HashMap<>()).query("i", "q", new QueryParams());
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
        assertNull(r.getServerLatencyMs());
    }

    @Test
    void getDocumentCountZeroWhenClientNull() {
        assertEquals(0L, new ElasticsearchEngine(new HashMap<>()).getDocumentCount("i"));
    }

    @Test
    void getIndexMetadataEmptyWhenClientNull() {
        assertTrue(new ElasticsearchEngine(new HashMap<>()).getIndexMetadata("i").isEmpty());
    }

    @Test
    void closeSafeWhenRestClientNull() throws Exception {
        new ElasticsearchEngine(new HashMap<>()).close();
    }

    @Test
    void closeClosesRestClientWhenPresent() throws Exception {
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", 1)).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            e.close();
            assertFalse(rc.isRunning(), "Rest5Client should be closed");
        } finally {
            if (rc.isRunning()) {
                rc.close();
            }
        }
    }

    @Test
    void dataStoreExistsReturnsFalseWhenOperationThrows() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                throw new RuntimeException("boom");
            }
        };
        assertFalse(e.dataStoreExists("x"));
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
    void resetDataStoreTrueOn404() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void resetDataStoreOperation(String indexName) {
                ErrorResponse er = ErrorResponse.of(b -> b.status(404)
                        .error(ErrorCause.of(x -> x.type("index_not_found_exception").reason("nf"))));
                throw new ElasticsearchException("failed", er);
            }
        };
        assertTrue(e.resetDataStore("missing"));
    }

    @Test
    void resetDataStoreFalseOnNon404ElasticsearchException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void resetDataStoreOperation(String indexName) {
                ErrorResponse er = ErrorResponse.of(b -> b.status(500)
                        .error(ErrorCause.of(x -> x.type("internal_server_error").reason("err"))));
                throw new ElasticsearchException("failed", er);
            }
        };
        assertFalse(e.resetDataStore("x"));
    }

    @Test
    void resetDataStoreFalseOnGenericException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void resetDataStoreOperation(String indexName) throws Exception {
                throw new IOException("io");
            }
        };
        assertFalse(e.resetDataStore("x"));
    }

    @Test
    void createDataStoreFalseWhenIndexAlreadyExists() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return true;
            }
        };
        assertFalse(e.createDataStore("exists", "any"));
    }

    @Test
    void createDataStoreFalseWhenSchemaMissing() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return false;
            }
        };
        assertFalse(e.createDataStore("i", "__schema_file_does_not_exist__"));
    }

    @Test
    void createDataStoreFalseWhenSchemaHasNoTemplateField() throws Exception {
        Path dir = Path.of("jingra-config/schemas");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-es-no-template-key.json");
        Files.writeString(f, "{\"template\": {\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean dataStoreExistsOperation(String indexName) {
                    return false;
                }
            };
            // Wrapped schemas are rejected under the direct-only contract.
            assertFalse(e.createDataStore("i", "behavior-es-no-template-key"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void createDataStoreOperationSendsRawJsonWithoutDeserializing() throws Exception {
        // The typed CreateIndexRequest.withJson() deserializes JSON through the client model,
        // which rejects unknown fields like 'bits' in DenseVectorIndexOptions when the client
        // version is behind the server. createDataStoreOperation must use the raw REST client instead.
        com.sun.net.httpserver.HttpServer fakeEs =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        fakeEs.createContext("/", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            byte[] ok = "{}".getBytes();
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        fakeEs.start();
        Rest5Client rc = Rest5Client.builder(
                new HttpHost("http", "127.0.0.1", fakeEs.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            String schemaWithBits =
                    "{\"mappings\":{\"properties\":{\"embedding\":{\"type\":\"dense_vector\","
                    + "\"index_options\":{\"type\":\"bbq_disk\",\"bits\":2}}}}}";
            // Must not throw JsonpMappingException for unknown 'bits' in index_options
            assertDoesNotThrow(() -> e.createDataStoreOperation("test-bits-idx", schemaWithBits));
            assertNotNull(receivedBody.get(), "Expected HTTP request to reach fake server");
            assertTrue(receivedBody.get().contains("\"bits\""),
                    "Raw JSON body must contain 'bits' field unchanged — no typed round-trip");
        } finally {
            rc.close();
            fakeEs.stop(0);
        }
    }

    @Test
    void createDataStoreFalseWhenCreateThrows() throws Exception {
        Path dir = Path.of("jingra-config/schemas");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-es-create-fail.json");
        Files.writeString(f, "{\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean dataStoreExistsOperation(String indexName) {
                    return false;
                }

                @Override
                protected void createDataStoreOperation(String indexName, String schemaJson) throws Exception {
                    throw new IOException("create failed");
                }
            };
            assertFalse(e.createDataStore("i", "behavior-es-create-fail"));
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
        Path f = dir.resolve("behavior-es-bad-render.json");
        Files.writeString(f, "{\"query\": {\"term\": {\"x\": \"{{v}}\"}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            List<Object> cyclic = new ArrayList<>();
            cyclic.add(cyclic);
            QueryParams qp = new QueryParams(Map.of("v", cyclic));
            assertThrows(IllegalStateException.class,
                    () -> e.query("idx", "behavior-es-bad-render", qp));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryIncludesEmptyStringIdsInOrder() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-es-query-empty-id.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 2}
                """);
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected SearchResponse<Map> searchOperation(String indexName, String queryJson) {
                    return searchHitsWithIds("", "real");
                }
            };
            QueryResponse r = e.query("idx", "behavior-es-query-empty-id", new QueryParams());
            assertEquals(List.of("", "real"), r.getDocumentIds());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryStoresLastQueryJsonOnceAndReturnsHits() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-es-query.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 1}
                """);
        try {
            AtomicBoolean second = new AtomicBoolean(false);
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected SearchResponse<Map> searchOperation(String indexName, String queryJson) {
                    if (!second.get()) {
                        return searchHitsWithIds("doc1");
                    }
                    return searchHitsWithIds("doc2");
                }
            };

            QueryResponse r1 = e.query("my-index", "behavior-es-query", new QueryParams());
            assertEquals(List.of("doc1"), r1.getDocumentIds());
            assertNotNull(r1.getClientLatencyMs());
            assertEquals(3L, r1.getServerLatencyMs().longValue());

            second.set(true);
            e.query("other", "behavior-es-query", new QueryParams());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void queryReturnsEmptyOnGenericFailure() throws Exception {
        Path dir = Path.of("jingra-config/queries");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-es-query-fail.json");
        Files.writeString(f, """
                {"query": {"match_all": {}}, "size": 1}
                """);
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected SearchResponse<Map> searchOperation(String indexName, String queryJson) throws Exception {
                    throw new IOException("search failed");
                }
            };
            QueryResponse r = e.query("idx", "behavior-es-query-fail", new QueryParams());
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
                return "9.9.9";
            }
        };
        assertEquals("9.9.9", e.getVersion());
    }

    @Test
    void firstElasticsearchDenseVectorType_nullMapping() {
        assertNull(ElasticsearchEngine.firstElasticsearchDenseVectorType(null));
    }

    @Test
    void firstElasticsearchDenseVectorType_emptyProperties() {
        TypeMapping tm = TypeMapping.of(m -> m.properties(Map.of()));
        assertNull(ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_keywordOnly() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("id", Property.of(p -> p.keyword(k -> k))));
        assertNull(ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_denseTopLevel() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("emb", Property.of(p -> p.denseVector(d -> d.dims(8)))));
        assertEquals("dense_vector", ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_nestedUnderObject() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("block", Property.of(p -> p.object(o -> o
                .properties("vec", Property.of(p2 -> p2.denseVector(d -> d.dims(4))))))));
        assertEquals("dense_vector", ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_nestedFieldWithInnerDenseVector() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("collapse", Property.of(p -> p.nested(n -> n
                .properties("vec", Property.of(p2 -> p2.denseVector(d -> d.dims(3))))))));
        assertEquals("dense_vector", ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_emptyObjectThenDenseTopLevel() {
        TypeMapping tm = TypeMapping.of(m -> m
                .properties("blank", Property.of(p -> p.object(o -> o)))
                .properties("emb", Property.of(p -> p.denseVector(d -> d.dims(2)))));
        assertEquals("dense_vector", ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_emptyNestedThenDenseTopLevel() {
        TypeMapping tm = TypeMapping.of(m -> m
                .properties("blank", Property.of(p -> p.nested(n -> n)))
                .properties("emb", Property.of(p -> p.denseVector(d -> d.dims(2)))));
        assertEquals("dense_vector", ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_deepObjectChain() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("l1", Property.of(p -> p.object(o -> o
                .properties("l2", Property.of(p2 -> p2.object(o2 -> o2
                        .properties("vec", Property.of(p3 -> p3.denseVector(d -> d.dims(1)))))))))));
        assertEquals("dense_vector", ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstElasticsearchDenseVectorType_mappingPropertiesNull() {
        TypeMapping tm = mock(TypeMapping.class);
        when(tm.properties()).thenReturn(null);
        assertNull(ElasticsearchEngine.firstElasticsearchDenseVectorType(tm));
    }

    @Test
    void firstDenseVectorInPropertyMap_nullMap() {
        assertNull(ElasticsearchEngine.firstDenseVectorInPropertyMap(null));
    }

    @Test
    void getIndexMetadataWhenIndexKeyMissing() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("e", Property.of(p -> p.denseVector(d -> d.dims(2)))));
        GetIndexResponse resp = GetIndexResponse.of(r -> r.indices("other-idx", IndexState.of(is -> is.mappings(tm))));
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
        GetIndexResponse resp = GetIndexResponse.of(r -> r.indices("x", IndexState.of(is -> is.aliases(Map.of()))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        assertTrue(e.getIndexMetadata("x").isEmpty());
    }

    @Test
    void getIndexMetadataFindsDenseVectorNestedUnderObject() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("block", Property.of(p -> p.object(o -> o
                .properties("vec", Property.of(p2 -> p2.denseVector(d -> d.dims(4))))))));
        GetIndexResponse resp = GetIndexResponse.of(r -> r.indices("my-idx", IndexState.of(is -> is.mappings(tm))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        Map<String, String> meta = e.getIndexMetadata("my-idx");
        assertEquals("dense_vector", meta.get("vector_type"));
    }

    @Test
    void getIndexMetadataKeywordOnlyLeavesVectorTypeAbsent() {
        TypeMapping tm = TypeMapping.of(m -> m.properties("t", Property.of(p -> p.text(t -> t))));
        GetIndexResponse resp = GetIndexResponse.of(r -> r.indices("plain", IndexState.of(is -> is.mappings(tm))));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected GetIndexResponse getIndexResponseOperation(String indexName) {
                return resp;
            }
        };
        assertTrue(e.getIndexMetadata("plain").isEmpty());
    }

    @Test
    void bulkIndexMapsThrowsWhenClientNull() {
        assertThrows(IllegalStateException.class,
                () -> new ElasticsearchEngine(new HashMap<>()).bulkIndexMaps("i", List.of(Map.of("a", 1))));
    }

    @Test
    void bulkIndexMapsBuildsOperationsAndReturnsResponse() throws Exception {
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return ok;
            }
        };
        assertSame(ok, e.bulkIndexMaps("target-idx", List.of(Map.of("k", "v"), Map.of("n", 2))));
        assertNotNull(captured.get());
        assertEquals(2, captured.get().operations().size());
    }

    @Test
    void bulkIndexMapsEmptyDocumentsSkipsBulkRequest() throws Exception {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                throw new AssertionError("bulk should not run for empty document list");
            }
        };
        BulkResponse r = e.bulkIndexMaps("target-idx", List.of());
        assertFalse(r.errors());
        assertTrue(r.items().isEmpty());
    }

    @Test
    void awaitIndexReadyThrowsIllegalStateWhenNoClient() {
        ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
        assertThrows(IllegalStateException.class, () -> e.awaitIndexReady("my-index"));
    }

    @Test
    void awaitIndexReadyReturnsImmediatelyWhenCurrentIsZero() throws Exception {
        AtomicReference<String> capturedIndex = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected int mergesCurrentOperation(String indexName) {
                capturedIndex.set(indexName);
                return 0;
            }
            @Override protected long getPollIntervalMs() { return 0L; }
        };
        e.awaitIndexReady("my-index");
        assertEquals("my-index", capturedIndex.get());
    }

    @Test
    void awaitIndexReadyPollsUntilCurrentIsZero() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected int mergesCurrentOperation(String indexName) {
                return callCount.incrementAndGet() <= 2 ? 3 : 0;
            }
            @Override protected long getPollIntervalMs() { return 0L; }
        };
        e.awaitIndexReady("idx");
        assertEquals(3, callCount.get(), "Expected 2 non-zero polls then 1 zero");
    }

    @Test
    void awaitIndexReadyWrapsExceptionsAsRuntimeException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected int mergesCurrentOperation(String indexName) throws Exception {
                throw new IOException("stats failed");
            }
            @Override protected long getPollIntervalMs() { return 0L; }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> e.awaitIndexReady("idx"));
        assertInstanceOf(IOException.class, ex.getCause());
        assertTrue(ex.getMessage().contains("awaitIndexReady failed"));
    }

    @Test
    void awaitIndexReadyRethrowsUncheckedRuntimeException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected int mergesCurrentOperation(String indexName) {
                throw new RuntimeException("merge stats unavailable");
            }

            @Override
            protected long getPollIntervalMs() {
                return 0L;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> e.awaitIndexReady("idx"));
        assertEquals("merge stats unavailable", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void getPollIntervalMsDefaultsThirtySeconds() throws Exception {
        Method m = ElasticsearchEngine.class.getDeclaredMethod("getPollIntervalMs");
        m.setAccessible(true);
        assertEquals(30_000L, m.invoke(new ElasticsearchEngine(new HashMap<>())));
    }

    @Test
    void awaitIndexReady_pollsMergeStatsViaRestClient() throws Exception {
        AtomicInteger statsCalls = new AtomicInteger();
        com.sun.net.httpserver.HttpServer fakeEs =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        fakeEs.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                byte[] out;
                if ("GET".equals(exchange.getRequestMethod()) && path.contains("/_stats/merges")) {
                    int n = statsCalls.incrementAndGet();
                    String body = n >= 2
                            ? "{\"indices\":{\"idx\":{\"primaries\":{\"merges\":{\"current\":0}}}}}"
                            : "{\"indices\":{\"idx\":{\"primaries\":{\"merges\":{\"current\":2}}}}}";
                    out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
            } finally {
                exchange.close();
            }
        });
        fakeEs.start();
        int port = fakeEs.getAddress().getPort();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", port)).build();
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override protected long getPollIntervalMs() { return 0L; }
            };
            injectRestClient(e, rc);
            assertDoesNotThrow(() -> e.awaitIndexReady("idx"));
            assertEquals(2, statsCalls.get());
        } finally {
            rc.close();
            fakeEs.stop(0);
        }
    }

    @Test
    void mergesCurrentOperation_returnsZeroOnNullBody() throws Exception {
        Rest5Client mockRest = mock(Rest5Client.class);
        Response mockResp = mock(Response.class);
        when(mockResp.getEntity()).thenReturn(null);
        when(mockRest.performRequest(any())).thenReturn(mockResp);

        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
        injectRestClient(e, mockRest);

        Method m = ElasticsearchEngine.class.getDeclaredMethod("mergesCurrentOperation", String.class);
        m.setAccessible(true);
        assertEquals(0, m.invoke(e, "idx"));
    }

    @Test
    void query_executesEsqlTemplateViaRestClient() throws Exception {
        Path esqlDir = Path.of("jingra-config/queries");
        Files.createDirectories(esqlDir);
        Path esqlFile = esqlDir.resolve("behavior-es-esql.esql");
        Files.writeString(esqlFile, "FROM metrics | LIMIT {{k}}");
        String esqlResponse = """
                {
                  "took": 7,
                  "columns": [{"name": "avg_load_1m", "type": "double"}],
                  "values": [[1.5]]
                }
                """;
        java.util.concurrent.atomic.AtomicReference<String> requestBody = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer fakeEs =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        fakeEs.createContext("/", exchange -> {
            try {
                if ("POST".equals(exchange.getRequestMethod())
                        && exchange.getRequestURI().getPath().endsWith("/_query")) {
                    requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
                    byte[] out = esqlResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } finally {
                exchange.close();
            }
        });
        fakeEs.start();
        int port = fakeEs.getAddress().getPort();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", port)).build();
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            injectRestClient(e, rc);
            QueryResponse r = e.query("idx", "behavior-es-esql", new QueryParams(Map.of("k", 10)));
            assertTrue(r.getDocumentIds().isEmpty());
            assertNotNull(r.getClientLatencyMs());
            assertEquals(7L, r.getServerLatencyMs().longValue());
            assertNotNull(r.getAggregateValues());
            assertEquals(1.5, ((Number) r.getAggregateValues().get("avg_load_1m")).doubleValue(), 1e-9);
            assertNotNull(requestBody.get());
            assertTrue(requestBody.get().contains("LIMIT 10"), requestBody.get());
        } finally {
            rc.close();
            fakeEs.stop(0);
            Files.deleteIfExists(esqlFile);
        }
    }

    @Test
    void query_esqlReturnsEmptyAggregatesWhenNoValues() throws Exception {
        Path esqlDir = Path.of("jingra-config/queries");
        Files.createDirectories(esqlDir);
        Path esqlFile = esqlDir.resolve("behavior-es-esql-empty.esql");
        Files.writeString(esqlFile, "FROM metrics | LIMIT 0");
        String esqlResponse = """
                {"took": 1, "columns": [{"name": "c", "type": "long"}], "values": []}
                """;
        com.sun.net.httpserver.HttpServer fakeEs =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        fakeEs.createContext("/", exchange -> {
            try {
                if ("POST".equals(exchange.getRequestMethod())
                        && exchange.getRequestURI().getPath().endsWith("/_query")) {
                    byte[] out = esqlResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } finally {
                exchange.close();
            }
        });
        fakeEs.start();
        int port = fakeEs.getAddress().getPort();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", port)).build();
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            injectRestClient(e, rc);
            QueryResponse r = e.query("idx", "behavior-es-esql-empty", new QueryParams());
            assertTrue(r.getDocumentIds().isEmpty());
            assertNotNull(r.getClientLatencyMs());
            assertNotNull(r.getAggregateValues());
            assertTrue(r.getAggregateValues().isEmpty());
        } finally {
            rc.close();
            fakeEs.stop(0);
            Files.deleteIfExists(esqlFile);
        }
    }

    @Test
    void query_esqlOmitsServerLatencyWhenTookMissing() throws Exception {
        Path esqlFile = Path.of("jingra-config/queries/behavior-es-esql-no-took.esql");
        Files.createDirectories(esqlFile.getParent());
        Files.writeString(esqlFile, "FROM metrics | LIMIT 0");
        String esqlResponse = """
                {"columns": [{"name": "c", "type": "long"}], "values": [[1]]}
                """;
        com.sun.net.httpserver.HttpServer fakeEs =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        fakeEs.createContext("/", exchange -> {
            try {
                if ("POST".equals(exchange.getRequestMethod())
                        && exchange.getRequestURI().getPath().endsWith("/_query")) {
                    byte[] out = esqlResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } finally {
                exchange.close();
            }
        });
        fakeEs.start();
        Rest5Client rc = Rest5Client.builder(
                new HttpHost("http", "127.0.0.1", fakeEs.getAddress().getPort())).build();
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            injectRestClient(e, rc);
            QueryResponse r = e.query("idx", "behavior-es-esql-no-took", new QueryParams());
            assertNull(r.getServerLatencyMs());
            assertEquals(1, ((Number) r.getAggregateValues().get("c")).intValue());
        } finally {
            rc.close();
            fakeEs.stop(0);
            Files.deleteIfExists(esqlFile);
        }
    }

    @Test
    void query_esqlUsesShorterRowWhenFewerValuesThanColumns() throws Exception {
        Path esqlFile = Path.of("jingra-config/queries/behavior-es-esql-partial-row.esql");
        Files.createDirectories(esqlFile.getParent());
        Files.writeString(esqlFile, "FROM metrics | LIMIT 0");
        String esqlResponse = """
                {
                  "took": 2,
                  "columns": [{"name": "a", "type": "long"}, {"name": "b", "type": "long"}],
                  "values": [[1]]
                }
                """;
        com.sun.net.httpserver.HttpServer fakeEs =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        fakeEs.createContext("/", exchange -> {
            try {
                if ("POST".equals(exchange.getRequestMethod())
                        && exchange.getRequestURI().getPath().endsWith("/_query")) {
                    byte[] out = esqlResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } finally {
                exchange.close();
            }
        });
        fakeEs.start();
        Rest5Client rc = Rest5Client.builder(
                new HttpHost("http", "127.0.0.1", fakeEs.getAddress().getPort())).build();
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
            injectRestClient(e, rc);
            QueryResponse r = e.query("idx", "behavior-es-esql-partial-row", new QueryParams());
            assertEquals(2L, r.getServerLatencyMs().longValue());
            assertEquals(1, r.getAggregateValues().size());
            assertEquals(1, ((Number) r.getAggregateValues().get("a")).intValue());
            assertFalse(r.getAggregateValues().containsKey("b"));
        } finally {
            rc.close();
            fakeEs.stop(0);
            Files.deleteIfExists(esqlFile);
        }
    }

    @Test
    void searchDelegatesToSearchOperation() throws Exception {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected SearchResponse<Map> searchOperation(String indexName, String queryJson) {
                return searchHitsWithIds("delegated-id");
            }
        };
        SearchResponse<Map> r = e.search("idx", "{\"query\":{\"match_all\":{}}}");
        assertEquals(1, r.hits().hits().size());
        assertEquals("delegated-id", r.hits().hits().get(0).id());
    }

    @Test
    void query_esqlSkipsAggregateFlatteningWhenColumnsOrValuesMissing() throws Exception {
        for (String[] spec : new String[][]{
                {"behavior-es-esql-no-cols", "{\"took\": 1, \"values\": [[1]]}"},
                {"behavior-es-esql-no-values", "{\"took\": 1, \"columns\": [{\"name\": \"c\", \"type\": \"long\"}]}"}
        }) {
            Path esqlFile = Path.of("jingra-config/queries/" + spec[0] + ".esql");
            Files.createDirectories(esqlFile.getParent());
            Files.writeString(esqlFile, "FROM metrics | LIMIT 0");
            com.sun.net.httpserver.HttpServer fakeEs =
                    com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
            fakeEs.createContext("/", exchange -> {
                try {
                    if ("POST".equals(exchange.getRequestMethod())
                            && exchange.getRequestURI().getPath().endsWith("/_query")) {
                        byte[] out = spec[1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, out.length);
                        exchange.getResponseBody().write(out);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                } finally {
                    exchange.close();
                }
            });
            fakeEs.start();
            Rest5Client rc = Rest5Client.builder(
                    new HttpHost("http", "127.0.0.1", fakeEs.getAddress().getPort())).build();
            try {
                ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {};
                injectRestClient(e, rc);
                QueryResponse r = e.query("idx", spec[0], new QueryParams());
                assertTrue(r.getAggregateValues().isEmpty());
            } finally {
                rc.close();
                fakeEs.stop(0);
                Files.deleteIfExists(esqlFile);
            }
        }
    }

    @Test
    void connectUsesUrlFromEnvWhenConfigUrlMissing() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", "JINGRA_TEST_ES_URL");
        ElasticsearchEngine e = new ElasticsearchEngine(cfg) {
            @Override
            protected String getEnv(String name, String fallback) {
                if ("JINGRA_TEST_ES_URL".equals(name)) {
                    return "http://127.0.0.1:1";
                }
                return super.getEnv(name, fallback);
            }
        };
        assertFalse(e.connect());
    }

    @Test
    void ingestOmitsDocumentIdWhenIdFieldMissing() throws Exception {
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return ok;
            }
        };
        Document doc = new Document(Map.of("other", "x"));
        assertEquals(1, e.ingest(List.of(doc), "idx", "id"));
        assertEquals(1, captured.get().operations().size());
    }

    // ── Data stream HTTP operations ───────────────────────────────────────────────

    @Test
    void dataStreamExistsOperation_returns_true_on_200() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_data_stream/metrics", ex -> {
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("dataStreamExistsOperation", String.class);
            m.setAccessible(true);
            assertTrue((Boolean) m.invoke(e, "metrics"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void dataStreamExistsOperation_returns_false_on_404() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_data_stream/missing", ex -> {
            ex.sendResponseHeaders(404, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("dataStreamExistsOperation", String.class);
            m.setAccessible(true);
            assertFalse((Boolean) m.invoke(e, "missing"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void applyIlmPolicyOperation_sendsCorrectRequest() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_ilm/policy/test-policy", ex -> {
            method.set(ex.getRequestMethod());
            body.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("applyIlmPolicyOperation", String.class, String.class);
            m.setAccessible(true);
            m.invoke(e, "test-policy", "{\"policy\":{}}");
            assertEquals("PUT", method.get());
            assertTrue(body.get().contains("policy"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void applyIndexTemplateOperation_sendsCorrectRequest() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_index_template/test-template", ex -> {
            method.set(ex.getRequestMethod());
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("applyIndexTemplateOperation", String.class, String.class);
            m.setAccessible(true);
            m.invoke(e, "test-template", "{\"index_patterns\":[\"metrics\"]}");
            assertEquals("PUT", method.get());
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void createDataStreamOperation_sendsCorrectRequest() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_data_stream/metrics", ex -> {
            method.set(ex.getRequestMethod());
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("createDataStreamOperation", String.class);
            m.setAccessible(true);
            m.invoke(e, "metrics");
            assertEquals("PUT", method.get());
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void deleteDataStreamOperation_sendsCorrectRequest() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_data_stream/metrics", ex -> {
            method.set(ex.getRequestMethod());
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write("{}".getBytes()); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("deleteDataStreamOperation", String.class);
            m.setAccessible(true);
            m.invoke(e, "metrics");
            assertEquals("DELETE", method.get());
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void applyIlmPolicyOperation_throwsOnNon2xx() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_ilm/policy/bad-policy", ex -> {
            byte[] msg = "{\"error\":\"bad\"}".getBytes();
            ex.sendResponseHeaders(400, msg.length); ex.getResponseBody().write(msg); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("applyIlmPolicyOperation", String.class, String.class);
            m.setAccessible(true);
            Exception ex2 = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(e, "bad-policy", "{}"));
            assertTrue(ex2.getCause().getMessage().contains("400"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void applyIndexTemplateOperation_throwsOnNon2xx() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_index_template/bad-tmpl", ex -> {
            byte[] msg = "{\"error\":\"bad\"}".getBytes();
            ex.sendResponseHeaders(400, msg.length); ex.getResponseBody().write(msg); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("applyIndexTemplateOperation", String.class, String.class);
            m.setAccessible(true);
            Exception ex2 = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(e, "bad-tmpl", "{}"));
            assertTrue(ex2.getCause().getMessage().contains("400"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void createDataStreamOperation_throwsOnNon2xx() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_data_stream/bad-stream", ex -> {
            byte[] msg = "{\"error\":\"bad\"}".getBytes();
            ex.sendResponseHeaders(400, msg.length); ex.getResponseBody().write(msg); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("createDataStreamOperation", String.class);
            m.setAccessible(true);
            Exception ex2 = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(e, "bad-stream"));
            assertTrue(ex2.getCause().getMessage().contains("400"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void deleteDataStreamOperation_throwsOnNon2xx() throws Exception {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        srv.createContext("/_data_stream/bad-stream", ex -> {
            byte[] msg = "{\"error\":\"bad\"}".getBytes();
            ex.sendResponseHeaders(500, msg.length); ex.getResponseBody().write(msg); ex.close();
        });
        srv.setExecutor(null); srv.start();
        Rest5Client rc = Rest5Client.builder(new HttpHost("http", "127.0.0.1", srv.getAddress().getPort())).build();
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
            injectRestClient(e, rc);
            Method m = ElasticsearchEngine.class.getDeclaredMethod("deleteDataStreamOperation", String.class);
            m.setAccessible(true);
            Exception ex2 = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(e, "bad-stream"));
            assertTrue(ex2.getCause().getMessage().contains("500"));
        } finally { rc.close(); srv.stop(0); }
    }

    @Test
    void loadIlmFile_readsFromFilesystem() throws Exception {
        Path ilmDir = Path.of("jingra-config/ilm");
        Files.createDirectories(ilmDir);
        Path pf = ilmDir.resolve("test-ilm-real.json");
        Files.writeString(pf, "{\"policy\":{}}");
        try {
            Method m = ElasticsearchEngine.class.getDeclaredMethod("loadIlmFile", String.class);
            m.setAccessible(true);
            Object result = m.invoke(new ElasticsearchEngine(new HashMap<>()), "test-ilm-real.json");
            assertNotNull(result);
            assertTrue(result.toString().contains("policy"));
        } finally { Files.deleteIfExists(pf); }
    }

    @Test
    void loadIlmFile_returnsNullWhenNotFound() throws Exception {
        Method m = ElasticsearchEngine.class.getDeclaredMethod("loadIlmFile", String.class);
        m.setAccessible(true);
        ElasticsearchEngine e = new ElasticsearchEngine(new HashMap<>());
        Object result = m.invoke(e, "nonexistent-xyz-policy.json");
        assertNull(result);
    }

    // ── create() / data-stream operation type ────────────────────────────────────

    @Test
    void create_dataStream_usesBulkCreateOperation() throws Exception {
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(Map.of("data_stream", true)) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return BulkResponse.of(b -> b.errors(false).took(1).items(List.of(
                        BulkResponseItem.of(i -> i.operationType(OperationType.Create).index("metrics").status(201)))));
            }
        };
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(1, e.create(List.of(doc), "metrics", null));
        assertNotNull(captured.get());
        assertTrue(captured.get().operations().get(0).isCreate(), "data stream must use create op");
        assertFalse(captured.get().operations().get(0).isIndex());
    }

    @Test
    void ingest_dataStream_delegatesToCreate() throws Exception {
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(Map.of("data_stream", true)) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return BulkResponse.of(b -> b.errors(false).took(1).items(List.of(
                        BulkResponseItem.of(i -> i.operationType(OperationType.Create).index("metrics").status(201)))));
            }
        };
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(1, e.ingest(List.of(doc), "metrics", null));
        assertNotNull(captured.get());
        assertTrue(captured.get().operations().get(0).isCreate(), "ingest on data stream must delegate to create");
    }

    @Test
    void ingest_regularIndex_usesIndexOperation() throws Exception {
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return BulkResponse.of(b -> b.errors(false).took(1).items(List.of(
                        BulkResponseItem.of(i -> i.operationType(OperationType.Index).index("idx").status(201)))));
            }
        };
        Document doc = new Document(Map.of("field", "value"));
        assertEquals(1, e.ingest(List.of(doc), "idx", null));
        assertNotNull(captured.get());
        assertTrue(captured.get().operations().get(0).isIndex(), "regular index must use index op");
        assertFalse(captured.get().operations().get(0).isCreate());
    }

    @Test
    void ingest_withIdField_setsDocumentId() throws Exception {
        AtomicReference<BulkRequest> captured = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                captured.set(request);
                return BulkResponse.of(b -> b.errors(false).took(1).items(List.of(
                        BulkResponseItem.of(i -> i.operationType(OperationType.Index).index("idx").id("doc-1").status(200)))));
            }
        };
        Document doc = new Document(Map.of("my_id", "doc-1", "field", "value"));
        assertEquals(1, e.ingest(List.of(doc), "idx", "my_id"));
        assertNotNull(captured.get());
        assertEquals("doc-1", captured.get().operations().get(0).index().id());
    }

    @Test
    void create_bulkErrors_throwsWhenFailOnPartialDefault() throws Exception {
        BulkResponseItem errItem = BulkResponseItem.of(b -> b
                .operationType(OperationType.Create).index("metrics").status(400)
                .error(e -> e.type("mapper_exception").reason("bad field")));
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(errItem)));
        ConnectedHarness e = new ConnectedHarness(Map.of("data_stream", true)) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = List.of(new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z")));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> e.create(docs, "metrics", null));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void create_bulkErrors_returnsPartialCountWhenFailOnPartialFalse() throws Exception {
        BulkResponseItem okItem = BulkResponseItem.of(b -> b.operationType(OperationType.Create).index("metrics").status(201));
        BulkResponseItem errItem = BulkResponseItem.of(b -> b
                .operationType(OperationType.Create).index("metrics").status(400)
                .error(e -> e.type("mapper_exception").reason("bad")));
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(okItem, errItem)));
        ConnectedHarness e = new ConnectedHarness(Map.of("data_stream", true, "ingest_fail_on_partial_errors", false)) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = List.of(
                new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z")),
                new Document(Map.of("@timestamp", "2024-01-01T00:01:00Z")));
        assertEquals(1, e.create(docs, "metrics", null));
    }

    @Test
    void create_bulkErrors_suppressesLogAfterFiveErrors() throws Exception {
        // 6 error items → errorCount > 5 branch hit → log is skipped for item 6+
        List<BulkResponseItem> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            items.add(BulkResponseItem.of(b -> b
                    .operationType(OperationType.Create).index("metrics").status(400)
                    .error(e -> e.type("mapper_exception").reason("bad"))));
        }
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(items));
        ConnectedHarness e = new ConnectedHarness(Map.of("data_stream", true)) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            docs.add(new Document(Map.of("@timestamp", "2024-01-01T00:0" + i + ":00Z")));
        }
        RuntimeException ex = assertThrows(RuntimeException.class, () -> e.create(docs, "metrics", null));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void ingestBulkErrorsWithNullItemErrorStillCountsAsFailure() throws Exception {
        BulkResponseItem okItem = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").status(201));
        BulkResponseItem nullErrorItem = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").status(400));
        BulkResponse br = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(okItem, nullErrorItem)));
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected BulkResponse bulkOperation(BulkRequest request) {
                return br;
            }
        };
        List<Document> docs = List.of(new Document(Map.of("a", 1)), new Document(Map.of("a", 2)));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> e.ingest(docs, "idx", null));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    // ── Low-level operations (real implementations via mock REST client) ──────────────

    @Test
    void applyComponentTemplateOperation_realImplementation_putsToComponentTemplateEndpoint() throws Exception {
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatusCode()).thenReturn(200);
        Rest5Client mockRest = mock(Rest5Client.class);
        when(mockRest.performRequest(any())).thenReturn(mockResponse);

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of()) {
            @Override protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);
        // should not throw
        e.applyComponentTemplateOperation("metrics-otel@custom", "{\"template\":{\"settings\":{}}}");
    }

    @Test
    void otlpOperation_realImplementation_postsToOtlpEndpoint() throws Exception {
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatusCode()).thenReturn(200);
        Rest5Client mockRest = mock(Rest5Client.class);
        when(mockRest.performRequest(any())).thenReturn(mockResponse);

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of()) {
            @Override
            protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);

        // Pass empty protobuf bytes (valid for the test — the mock always returns 200)
        assertEquals(0, e.otlpOperation(new byte[0]));
    }

    // ── OTLP ingest mode ──────────────────────────────────────────────────────────

    @Test
    void ingest_otlpMode_callsOtlpOperation() throws Exception {
        AtomicReference<byte[]> capturedOtlp = new AtomicReference<>();
        ConnectedHarness e = new ConnectedHarness(Map.of("ingest_mode", "otlp", "data_stream", true)) {
            @Override
            protected int otlpOperation(byte[] protoBytes) {
                capturedOtlp.set(protoBytes);
                return 0;
            }
        };
        List<Document> docs = List.of(new Document(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z",
                "host.name", "host-0001",
                "metrics.system.memory.utilization", 0.5)));
        int count = e.ingest(docs, "metrics-jingra.otel-benchmark", null);
        assertEquals(1, count);
        assertNotNull(capturedOtlp.get(), "otlpOperation must have been called");
        assertTrue(capturedOtlp.get().length > 0, "proto bytes must be non-empty");
        // metric name "system.memory.utilization" must appear as raw UTF-8 bytes in the proto
        assertTrue(containsUtf8(capturedOtlp.get(), "system.memory.utilization"));
    }

    @Test
    void ingest_otlpMode_batchesLargeInputs() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        ConnectedHarness e = new ConnectedHarness(Map.of(
                "ingest_mode", "otlp", "otlp_batch_size", 2)) {
            @Override
            protected int otlpOperation(byte[] protoBytes) {
                callCount.incrementAndGet();
                return 0;
            }
        };
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            docs.add(new Document(Map.of(
                    "@timestamp", "2026-06-13T09:00:00Z",
                    "host.name", "host-" + i,
                    "metrics.system.memory.utilization", 0.5)));
        }
        int count = e.ingest(docs, "metrics-jingra.otel-benchmark", null);
        assertEquals(5, count);
        assertEquals(3, callCount.get(), "5 docs with batch_size=2 → 3 calls");
    }

    @Test
    void ingest_otlpMode_noClient_returnsZero() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("ingest_mode", "otlp"));
        List<Document> docs = List.of(new Document(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z",
                "metrics.system.memory.utilization", 0.5)));
        assertEquals(0, e.ingest(docs, "metrics-jingra.otel-benchmark", null));
    }

    @Test
    void ingest_otlpMode_otlpFailure_throwsRuntime() {
        ConnectedHarness e = new ConnectedHarness(Map.of("ingest_mode", "otlp")) {
            @Override
            protected int otlpOperation(byte[] protoBytes) throws Exception {
                throw new Exception("connection refused");
            }
        };
        List<Document> docs = List.of(new Document(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z",
                "host.name", "h",
                "metrics.system.memory.utilization", 0.5)));
        assertThrows(RuntimeException.class, () -> e.ingest(docs, "idx", null));
    }

    /** Returns true if {@code needle} appears as raw UTF-8 bytes anywhere in {@code haystack}. */
    private static boolean containsUtf8(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
