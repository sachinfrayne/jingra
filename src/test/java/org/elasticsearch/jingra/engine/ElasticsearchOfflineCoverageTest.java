package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.Test;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.fasterxml.jackson.databind.JsonNode;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchOfflineCoverageTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_TEST_URL_NOT_SET__";
    private static final String UNREACHABLE_HTTP = "http://127.0.0.1:1";

    private ElasticsearchEngine engineWithoutUrl() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        return new ElasticsearchEngine(cfg);
    }

    @Test
    void connectFailsWhenUrlMissing() {
        ElasticsearchEngine e = engineWithoutUrl();
        try {
            assertFalse(e.connect());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void connectFailsWhenEndpointUnreachable() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", UNREACHABLE_HTTP);
        ElasticsearchEngine e = new ElasticsearchEngine(cfg);
        try {
            assertFalse(e.connect());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void operationsNoOpWhenNeverConnected() {
        ElasticsearchEngine e = engineWithoutUrl();
        assertFalse(e.connect());
        assertEquals("elasticsearch", e.getEngineName());
        assertEquals("es", e.getShortName());
        assertEquals("unknown", e.getVersion());
        assertFalse(e.createDataStore("i", "s"));
        assertFalse(e.dataStoreExists("i"));
        assertFalse(e.resetDataStore("i"));
        assertEquals(0, e.ingest(List.of(), "i", null));
        assertTrue(e.query("i", "q", new QueryParams()).getDocumentIds().isEmpty());
        assertEquals(0L, e.getDocumentCount("i"));
        assertTrue(e.getIndexMetadata("i").isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void createDataStoreRejectsWrappedSchemaBody() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected void createDataStoreOperation(String indexName, String schemaJson) {
                fail("createDataStoreOperation should not run for wrapped schema bodies");
            }

            @Override
            protected JsonNode loadSchemaTemplate(String schemaName) {
                try {
                    return objectMapper.readTree("{\"template\": {\"mappings\": {}}}");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        try {
            assertFalse(e.createDataStore("i", "wrapped"));
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void createDataStoreRejectsWrappedSchemaBody_nameOnly() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected void createDataStoreOperation(String indexName, String schemaJson) {
                fail("createDataStoreOperation should not run for wrapped schema bodies");
            }

            @Override
            protected JsonNode loadSchemaTemplate(String schemaName) {
                try {
                    return objectMapper.readTree("{\"name\": \"wrapped\"}");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        try {
            assertFalse(e.createDataStore("i", "wrapped"));
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void queryRejectsWrappedQueryBody() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected JsonNode loadQueryTemplateCached(String queryName) {
                try {
                    return objectMapper.readTree("{\"name\": \"wrapped\", \"template\": {\"query\": {}}}");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        try {
            assertTrue(e.query("i", "q", new QueryParams()).getDocumentIds().isEmpty());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void queryRejectsWrappedQueryBody_nameOnly() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected JsonNode loadQueryTemplateCached(String queryName) {
                try {
                    return objectMapper.readTree("{\"name\": \"wrapped\"}");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        try {
            assertTrue(e.query("i", "q", new QueryParams()).getDocumentIds().isEmpty());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void queryWritesFirstQueryDumpWhenEnabled() throws Exception {
        class DumpingEngine extends ElasticsearchEngine {
            int dumps;

            DumpingEngine() {
                super(Map.of("url_env", BOGUS_URL_ENV));
            }

            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean shouldWriteFirstQueryDump() {
                return true;
            }

            @Override
            protected void writeFirstQueryDumpIfConfigured(String engineShortName, String requestJson) {
                dumps++;
            }

            @Override
            protected JsonNode loadQueryTemplateCached(String queryName) {
                try {
                    return objectMapper.readTree("{\"query\":{\"term\":{\"f\":\"{{x}}\"}},\"size\":\"{{size}}\"}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected SearchResponse<Map> searchOperation(String indexName, String queryJson) {
                @SuppressWarnings("unchecked")
                SearchResponse<Map> r = (SearchResponse<Map>) (SearchResponse<?>) Mockito.mock(SearchResponse.class);
                @SuppressWarnings("unchecked")
                HitsMetadata<Map> hits = (HitsMetadata<Map>) (HitsMetadata<?>) Mockito.mock(HitsMetadata.class);
                Mockito.when(hits.hits()).thenReturn(List.of());
                Mockito.when(r.hits()).thenReturn(hits);
                Mockito.when(r.took()).thenReturn(1L);
                TotalHits total = new TotalHits.Builder().value(0L).relation(TotalHitsRelation.Eq).build();
                Mockito.when(hits.total()).thenReturn(total);
                return r;
            }
        }

        DumpingEngine e = new DumpingEngine();
        try {
            QueryParams params = new QueryParams(Map.of("x", "v", "size", 1));
            QueryResponse out = e.query("i", "q", params);
            assertNotNull(out);
            assertEquals(1, e.dumps);
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    // ── Data stream tests ──────────────────────────────────────────────────────────

    @Test
    void dataStream_dataStoreExists_callsDataStreamApi() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { called.set(true); return true; }
        };
        assertTrue(e.dataStoreExists("metrics"));
        assertTrue(called.get());
    }

    @Test
    void noDataStream_dataStoreExists_usesRegularIndex() {
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of()) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStoreExistsOperation(String n) throws Exception { called.set(true); return false; }
        };
        assertFalse(e.dataStoreExists("metrics"));
        assertTrue(called.get());
    }

    @Test
    void dataStream_createDataStore_appliesPolicyThenTemplateThendataStream() throws Exception {
        List<String> ops = new ArrayList<>();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) { ops.add("policy:" + n); }
            @Override protected void applyIndexTemplateOperation(String n, String j) { ops.add("template:" + n); }
            @Override protected void createDataStreamOperation(String n) { ops.add("stream:" + n); }
        };
        assertTrue(e.createDataStore("metrics", "metrics-schema"));
        assertEquals(List.of("policy:metrics-policy", "template:metrics-template", "stream:metrics"), ops);
    }

    @Test
    void dataStream_createDataStore_returnsFalseWhenAlreadyExists() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return true; }
        };
        assertFalse(e.createDataStore("metrics", "metrics-schema"));
    }

    @Test
    void dataStream_createDataStore_loadsIlmFilesForIndexName() throws Exception {
        List<String> loaded = new ArrayList<>();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { loaded.add(f); return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) {}
            @Override protected void applyIndexTemplateOperation(String n, String j) {}
            @Override protected void createDataStreamOperation(String n) {}
        };
        assertTrue(e.createDataStore("metrics", null));
        assertTrue(loaded.contains("metrics-policy.json"));
        assertTrue(loaded.contains("metrics-template.json"));
    }

    @Test
    void dataStream_createDataStore_returnsFalseOnOperationException() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) throws Exception {
                throw new Exception("failed to apply policy");
            }
        };
        assertFalse(e.createDataStore("metrics", null));
    }

    @Test
    void dataStream_createDataStore_returnsFalseWhenPolicyFileMissing() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return null; } // both null
        };
        assertFalse(e.createDataStore("metrics", null));
    }

    @Test
    void dataStream_createDataStore_returnsFalseWhenTemplateFileMissing() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) throws java.io.IOException {
                return calls.getAndIncrement() == 0 ? "{}" : null; // policy ok, template null
            }
        };
        assertFalse(e.createDataStore("metrics", null));
    }

    @Test
    void dataStream_resetDataStore_callsDeleteApi() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected void deleteDataStreamOperation(String n) { called.set(true); }
        };
        assertTrue(e.resetDataStore("metrics"));
        assertTrue(called.get());
    }

    @Test
    void dataStream_resetDataStore_404IsIdempotent() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected void deleteDataStreamOperation(String n) throws Exception {
                throw new Exception("404 data_stream_not_found");
            }
        };
        assertTrue(e.resetDataStore("metrics"));
    }

    @Test
    void noDataStream_createDataStore_usesRegularIndex() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of()) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStoreExistsOperation(String n) { return false; }
            @Override protected void createDataStoreOperation(String n, String s) { called.set(true); }
            @Override protected JsonNode loadSchemaTemplate(String n) {
                try { return AbstractBenchmarkEngine.objectMapper.readTree("{\"mappings\":{}}"); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }
        };
        e.createDataStore("metrics", "metrics-schema");
        assertTrue(called.get());
    }

    @Test
    void noDataStream_resetDataStore_usesRegularIndex() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of()) {
            @Override protected boolean hasClient() { return true; }
            @Override protected void resetDataStoreOperation(String n) { called.set(true); }
        };
        assertTrue(e.resetDataStore("metrics"));
        assertTrue(called.get());
    }

    @Test
    void renderDirectTemplateCoversStringNumberBooleanAndObjectParamTypes() throws Exception {
        JsonNode template = AbstractBenchmarkEngine.objectMapper.readTree(
                "{\"query\":{\"term\":{\"a\":\"{{s}}\",\"b\":\"{{n}}\",\"c\":\"{{t}}\",\"d\":\"{{o}}\"}}}");

        Map<String, Object> params = new HashMap<>();
        params.put("s", "x");
        params.put("n", 3);
        params.put("t", true);
        params.put("o", Map.of("k", "v"));

        var m = ElasticsearchEngine.class.getDeclaredMethod("renderDirectTemplate", JsonNode.class, Map.class);
        m.setAccessible(true);
        String rendered = (String) m.invoke(null, template, params);
        assertNotNull(rendered);
        assertTrue(rendered.contains("\"x\""));
        assertTrue(rendered.contains("3"));
        assertTrue(rendered.contains("true"));
        assertTrue(rendered.contains("\"k\""));
    }
}
