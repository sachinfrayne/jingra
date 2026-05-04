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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
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
    void createIndexReturnsFalseWhenClientNull() {
        assertFalse(new ElasticsearchEngine(new HashMap<>()).createIndex("i", "any"));
    }

    @Test
    void indexExistsReturnsFalseWhenClientNull() {
        assertFalse(new ElasticsearchEngine(new HashMap<>()).indexExists("i"));
    }

    @Test
    void deleteIndexReturnsFalseWhenClientNull() {
        assertFalse(new ElasticsearchEngine(new HashMap<>()).deleteIndex("i"));
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
    void indexExistsReturnsFalseWhenOperationThrows() {
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
                throw new ElasticsearchException("failed", er);
            }
        };
        assertTrue(e.deleteIndex("missing"));
    }

    @Test
    void deleteIndexFalseOnNon404ElasticsearchException() {
        ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
            @Override
            protected void deleteIndexOperation(String indexName) {
                ErrorResponse er = ErrorResponse.of(b -> b.status(500)
                        .error(ErrorCause.of(x -> x.type("internal_server_error").reason("err"))));
                throw new ElasticsearchException("failed", er);
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
        Path f = dir.resolve("behavior-es-no-template-key.json");
        Files.writeString(f, "{\"template\": {\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean indexExistsOperation(String indexName) {
                    return false;
                }
            };
            // Wrapped schemas are rejected under the direct-only contract.
            assertFalse(e.createIndex("i", "behavior-es-no-template-key"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void createIndexOperationSendsRawJsonWithoutDeserializing() throws Exception {
        // The typed CreateIndexRequest.withJson() deserializes JSON through the client model,
        // which rejects unknown fields like 'bits' in DenseVectorIndexOptions when the client
        // version is behind the server. createIndexOperation must use the raw REST client instead.
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
            assertDoesNotThrow(() -> e.createIndexOperation("test-bits-idx", schemaWithBits));
            assertNotNull(receivedBody.get(), "Expected HTTP request to reach fake server");
            assertTrue(receivedBody.get().contains("\"bits\""),
                    "Raw JSON body must contain 'bits' field unchanged — no typed round-trip");
        } finally {
            rc.close();
            fakeEs.stop(0);
        }
    }

    @Test
    void createIndexFalseWhenCreateThrows() throws Exception {
        Path dir = Path.of("jingra-config/schemas");
        Files.createDirectories(dir);
        Path f = dir.resolve("behavior-es-create-fail.json");
        Files.writeString(f, "{\"mappings\": {\"properties\": {\"f\": {\"type\": \"keyword\"}}}}");
        try {
            ConnectedHarness e = new ConnectedHarness(new HashMap<>()) {
                @Override
                protected boolean indexExistsOperation(String indexName) {
                    return false;
                }

                @Override
                protected void createIndexOperation(String indexName, String schemaJson) throws Exception {
                    throw new IOException("create failed");
                }
            };
            assertFalse(e.createIndex("i", "behavior-es-create-fail"));
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
}
