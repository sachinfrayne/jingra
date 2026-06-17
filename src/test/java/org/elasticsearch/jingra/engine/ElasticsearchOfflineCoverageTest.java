package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import com.fasterxml.jackson.databind.JsonNode;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchOfflineCoverageTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_TEST_URL_NOT_SET__";

    @AfterEach
    void clearOverrides() {
        MetricsgenLoader.binaryPathOverrideForTests.remove();
    }

    private static void injectRestClient(ElasticsearchEngine engine, Rest5Client rest) throws Exception {
        Field fr = ElasticsearchEngine.class.getDeclaredField("restClient");
        fr.setAccessible(true);
        fr.set(engine, rest);
    }

    private static Response mockResponse(int status, String jsonBody) throws Exception {
        Response resp = Mockito.mock(Response.class);
        Mockito.when(resp.getStatusCode()).thenReturn(status);
        if (jsonBody != null) {
            org.apache.hc.core5.http.HttpEntity entity = Mockito.mock(org.apache.hc.core5.http.HttpEntity.class);
            Mockito.when(entity.getContent()).thenReturn(
                    new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8)));
            Mockito.when(resp.getEntity()).thenReturn(entity);
        }
        return resp;
    }
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
        assertEquals(0, e.create(List.of(), "i", null));
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
    void dataStream_createDataStore_usesExplicitPolicyAndTemplateNamesFromDataset() throws Exception {
        List<String> ops = new ArrayList<>();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) { ops.add("policy:" + n); }
            @Override protected void applyIndexTemplateOperation(String n, String j) { ops.add("template:" + n); }
            @Override protected void createDataStreamOperation(String n) { ops.add("stream:" + n); }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIndexName("metrics");
        ds.setIlmPolicy("custom-policy");
        ds.setIndexTemplate("custom-template");
        assertTrue(e.createDataStore("metrics", ds));
        assertEquals(List.of("policy:custom-policy", "template:custom-template", "stream:metrics"), ops);
    }

    @Test
    void dataStream_createDataStore_withDataset_returnsFalseWhenNotConnected() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return false; }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIndexName("metrics"); ds.setIlmPolicy("p"); ds.setIndexTemplate("t");
        assertFalse(e.createDataStore("metrics", ds));
    }

    @Test
    void dataStream_createDataStore_withDataset_returnsFalseWhenAlreadyExists() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return true; }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("p"); ds.setIndexTemplate("t");
        assertFalse(e.createDataStore("metrics", ds));
    }

    @Test
    void dataStream_createDataStore_withDataset_returnsFalseWhenPolicyMissing() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return null; }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("p"); ds.setIndexTemplate("t");
        assertFalse(e.createDataStore("metrics", ds));
    }

    @Test
    void dataStream_createDataStore_withDataset_returnsFalseWhenTemplateMissing() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) throws java.io.IOException {
                return calls.getAndIncrement() == 0 ? "{}" : null;
            }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("p"); ds.setIndexTemplate("t");
        assertFalse(e.createDataStore("metrics", ds));
    }

    @Test
    void dataStream_createDataStore_withDataset_returnsFalseOnException() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) throws Exception {
                throw new Exception("network error");
            }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("p"); ds.setIndexTemplate("t");
        assertFalse(e.createDataStore("metrics", ds));
    }

    @Test
    void dataStream_createDataStore_withNullDataset_whenDataStreamTrue_fallsBackToStringOverload() throws Exception {
        // isDataStream=true, dataset=null → condition false (dataset != null is false) → string overload
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) { called.set(true); }
            @Override protected void applyIndexTemplateOperation(String n, String j) {}
            @Override protected void createDataStreamOperation(String n) {}
        };
        // null DatasetConfig with data_stream=true → falls through to String overload →
        // String overload also routes to data stream path → policy IS still applied
        assertTrue(e.createDataStore("metrics", (org.elasticsearch.jingra.config.DatasetConfig) null));
        assertTrue(called.get(), "data stream path should still run when dataset is null");
    }

    @Test
    void dataStream_createDataStore_withNullDataset_fallsBackToStringOverload() throws Exception {
        // isDataStream=true but dataset=null → condition false → falls through to string overload
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", false)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStoreExistsOperation(String n) { return false; }
            @Override protected void createDataStoreOperation(String n, String s) { called.set(true); }
            @Override protected JsonNode loadSchemaTemplate(String n) {
                try { return AbstractBenchmarkEngine.objectMapper.readTree("{\"mappings\":{}}"); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }
        };
        // Passing null DatasetConfig when isDataStream=false → ternary null branch → createDataStore(name, null)
        e.createDataStore("metrics", (org.elasticsearch.jingra.config.DatasetConfig) null);
        assertTrue(called.get());
    }

    @Test
    void noDataStream_createDataStore_withDataset_usesRegularIndexPath() throws Exception {
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
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setSchemaName("metrics-schema");
        e.createDataStore("metrics", ds);
        assertTrue(called.get());
    }

    @Test
    void benchmarkEngine_createDataStore_defaultWithDataset_delegatesToStringOverload() {
        // Exercises the BenchmarkEngine default createDataStore(String, DatasetConfig)
        // via a stub engine that only implements the interface minimum
        org.elasticsearch.jingra.engine.BenchmarkEngine stub = new org.elasticsearch.jingra.engine.BenchmarkEngine() {
            @Override public boolean connect() { return true; }
            @Override public boolean dataStoreExists(String i) { return false; }
            @Override public boolean resetDataStore(String i) { return true; }
            @Override public int ingest(java.util.List<org.elasticsearch.jingra.model.Document> d, String i, String f) { return 0; }
            @Override public org.elasticsearch.jingra.model.QueryResponse query(String i, String q, org.elasticsearch.jingra.model.QueryParams p) { return new org.elasticsearch.jingra.model.QueryResponse(java.util.List.of(), null, null); }
            @Override public long getDocumentCount(String i) { return 0; }
            @Override public java.util.Map<String,String> getIndexMetadata(String i) { return java.util.Map.of(); }
            @Override public String getEngineName() { return "stub"; }
            @Override public String getShortName() { return "s"; }
            @Override public String getVersion() { return "0"; }
            @Override public java.util.Map<String,Object> getSchemaTemplate(String s) { return java.util.Map.of(); }
            @Override public void close() {}
        };
        // dataset with schemaName — default delegates to createDataStore(String, String)
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setSchemaName("s");
        assertTrue(stub.createDataStore("i", ds)); // default returns true

        // dataset=null — covers null branch
        assertTrue(stub.createDataStore("i", (org.elasticsearch.jingra.config.DatasetConfig) null));
    }

    @Test
    void dataStream_createDataStore_withDatasetNullFieldsSkipsBothAndCreatesStream() throws Exception {
        List<String> ops = new ArrayList<>();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected void applyIlmPolicyOperation(String n, String j) { ops.add("policy:" + n); }
            @Override protected void applyIndexTemplateOperation(String n, String j) { ops.add("template:" + n); }
            @Override protected void createDataStreamOperation(String n) { ops.add("stream:" + n); }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIndexName("metrics");
        // ilmPolicy and indexTemplate both null → skip both, just create the data stream
        assertTrue(e.createDataStore("metrics", ds));
        assertEquals(List.of("stream:metrics"), ops);
    }

    @Test
    void dataStream_createDataStore_withNullPolicy_templateMissing_returnsFalse() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return null; }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIndexTemplate("metrics-template"); // policy null, template set but file missing
        assertFalse(e.createDataStore("metrics", ds));
    }

    @Test
    void dataStream_createDataStore_withComponentTemplate_appliesItBeforeCreatingStream() throws Exception {
        List<String> ops = new ArrayList<>();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) { ops.add("policy:" + n); }
            @Override protected void applyComponentTemplateOperation(String n, String j) { ops.add("ctmpl:" + n); }
            @Override protected void createDataStreamOperation(String n) { ops.add("stream:" + n); }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("metrics-policy");
        ds.setComponentTemplate("metrics-otel@custom");
        assertTrue(e.createDataStore("metrics-jingra.otel-benchmark", ds));
        assertEquals(List.of(
                "policy:metrics-policy",
                "ctmpl:metrics-otel@custom",
                "stream:metrics-jingra.otel-benchmark"), ops);
    }

    @Test
    void dataStream_createDataStore_withComponentTemplateMissingFile_returnsFalse() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) {
                // policy loads OK, component template file not found
                return f.contains("metrics-policy") ? "{}" : null;
            }
            @Override protected void applyIlmPolicyOperation(String n, String j) {}
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("metrics-policy");
        ds.setComponentTemplate("metrics-otel@custom");
        assertFalse(e.createDataStore("metrics-jingra.otel-benchmark", ds));
    }

    @Test
    void applyComponentTemplateOperation_isOverridable() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) {}
            @Override protected void applyComponentTemplateOperation(String n, String j) {
                called.set(true);
                assertEquals("metrics-otel@custom", n);
            }
            @Override protected void createDataStreamOperation(String n) {}
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("p");
        ds.setComponentTemplate("metrics-otel@custom");
        assertTrue(e.createDataStore("idx", ds));
        assertTrue(called.get());
    }

    @Test
    void dataStream_createDataStore_withNullTemplate_skipTemplateAppliesPolicyAndCreatesStream() throws Exception {
        List<String> ops = new ArrayList<>();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return "{}"; }
            @Override protected void applyIlmPolicyOperation(String n, String j) { ops.add("policy:" + n); }
            @Override protected void applyIndexTemplateOperation(String n, String j) { ops.add("template:" + n); }
            @Override protected void createDataStreamOperation(String n) { ops.add("stream:" + n); }
        };
        org.elasticsearch.jingra.config.DatasetConfig ds = new org.elasticsearch.jingra.config.DatasetConfig();
        ds.setIlmPolicy("metrics-policy");
        // index_template not set → skip template, use whatever existing template matches in ES
        assertTrue(e.createDataStore("metrics-hostmetricsreceiver.otel-default", ds));
        assertEquals(List.of("policy:metrics-policy", "stream:metrics-hostmetricsreceiver.otel-default"), ops);
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
        assertTrue(e.createDataStore("metrics", (String) null));
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
        assertFalse(e.createDataStore("metrics", (String) null));
    }

    @Test
    void dataStream_createDataStore_returnsFalseWhenPolicyFileMissing() {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("data_stream", true)) {
            @Override protected boolean hasClient() { return true; }
            @Override protected boolean dataStreamExistsOperation(String n) { return false; }
            @Override protected String loadIlmFile(String f) { return null; } // both null
        };
        assertFalse(e.createDataStore("metrics", (String) null));
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
        assertFalse(e.createDataStore("metrics", (String) null));
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

    // ── connect: baseUrl trailing slash ─────────────────────────────────────────

    @Test
    void connect_stripsTrailingSlashFromUrl() {
        // connect() sets baseUrl = url.endsWith("/") ? url.substring(...) : url
        // Test the true-branch (trailing slash stripped) by using a URL ending with "/"
        // on an unreachable port — createClient() succeeds synchronously, baseUrl is set,
        // then client.info() throws, returning false.
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url", "http://127.0.0.1:1/"));
        assertFalse(e.connect()); // fails at client.info(), but exercises the ternary
        assertDoesNotThrow(e::close);
    }

    // ── logEsProgress ────────────────────────────────────────────────────────────

    @Test
    void logEsProgress_logsDocCountWithoutThrowing() throws Exception {
        String catJson = "[{\"docs.count\":\"1000\",\"store.size\":\"5mb\"}]";
        Response resp = mockResponse(200, catJson);
        Rest5Client mockRest = Mockito.mock(Rest5Client.class);
        Mockito.when(mockRest.performRequest(Mockito.any())).thenReturn(resp);

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);
        assertDoesNotThrow(() -> e.logEsProgress("my-index"));
        assertDoesNotThrow(e::close);
    }

    @Test
    void logEsProgress_silentOnException() throws Exception {
        Rest5Client mockRest = Mockito.mock(Rest5Client.class);
        Mockito.when(mockRest.performRequest(Mockito.any()))
               .thenThrow(new RuntimeException("connection refused"));

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);
        assertDoesNotThrow(() -> e.logEsProgress("my-index")); // must swallow
        assertDoesNotThrow(e::close);
    }

    @Test
    void logEsProgress_emptyResultSilent() throws Exception {
        Response resp = mockResponse(200, "[]");
        Rest5Client mockRest = Mockito.mock(Rest5Client.class);
        Mockito.when(mockRest.performRequest(Mockito.any())).thenReturn(resp);

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);
        assertDoesNotThrow(() -> e.logEsProgress("my-index"));
        assertDoesNotThrow(e::close);
    }

    // ── forceMergeOperation ───────────────────────────────────────────────────────

    @Test
    void forceMergeOperation_postsToCorrectEndpointAndSucceeds() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        Response okResp = mockResponse(200, null);
        Rest5Client mockRest = Mockito.mock(Rest5Client.class);
        Mockito.when(mockRest.performRequest(Mockito.any())).thenAnswer(inv -> {
            co.elastic.clients.transport.rest5_client.low_level.Request req = inv.getArgument(0);
            capturedPath.set(req.getEndpoint());
            return okResp;
        });

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);
        assertDoesNotThrow(() -> e.forceMergeOperation("my-index"));
        assertTrue(capturedPath.get().contains("_forcemerge"), capturedPath.get());
        assertDoesNotThrow(e::close);
    }

    @Test
    void forceMergeOperation_throwsOnHttpError() throws Exception {
        Response errResp = mockResponse(500, null);
        Rest5Client mockRest = Mockito.mock(Rest5Client.class);
        Mockito.when(mockRest.performRequest(Mockito.any())).thenReturn(errResp);

        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override protected boolean hasClient() { return true; }
        };
        injectRestClient(e, mockRest);
        assertThrows(Exception.class, () -> e.forceMergeOperation("my-index"));
        assertDoesNotThrow(e::close);
    }

    // ── runMetricsgenreceiver (real path) ─────────────────────────────────────────

    @Test
    void runMetricsgenreceiver_usesMetricsgenLoaderWithRealBinary(@TempDir Path tmpDir)
            throws Exception {
        byte[] scriptContent = ("#!/bin/sh\necho '{\"datapoints\":500}' >&2\n").getBytes();
        Path script = tmpDir.resolve("metricsgenreceiver");
        Files.write(script, scriptContent);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

        MetricsgenLoader.binaryPathOverrideForTests.set(script);
        try {
            ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url", "http://localhost:9200")) {
                @Override public boolean connect() {
                    try {
                        Field f = ElasticsearchEngine.class.getDeclaredField("baseUrl");
                        f.setAccessible(true);
                        f.set(this, "http://localhost:9200");
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                    return true;
                }
            };
            assertTrue(e.connect());
            org.elasticsearch.jingra.config.MetricsgenConfig cfg =
                    new org.elasticsearch.jingra.config.MetricsgenConfig();
            cfg.setVersion("1.0.7");
            Path cfgFile = tmpDir.resolve("config.yaml");
            Files.writeString(cfgFile, "placeholder: true\n");
            MetricsgenLoader.ProcessResult result = e.runMetricsgenreceiver(cfgFile, cfg);
            assertEquals(0, result.exitCode());
            assertTrue(result.stderr().contains("datapoints"), result.stderr());
            assertDoesNotThrow(e::close);
        } finally {
            MetricsgenLoader.binaryPathOverrideForTests.remove();
        }
    }

    // ── esProgressPoller ────────────────────────────────────────────────────────

    @Test
    void esProgressPoller_runnableInvokesLogEsProgress() {
        AtomicBoolean called = new AtomicBoolean();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override protected void logEsProgress(String indexName) {
                called.set(true);
            }
        };
        Runnable poller = e.esProgressPoller("test-index");
        poller.run();
        assertTrue(called.get());
        assertDoesNotThrow(e::close);
    }

    // ── BenchmarkEngine.customLoad default ────────────────────────────────────────

    @Test
    void benchmarkEngine_customLoad_defaultThrowsUnsupportedOperationException() {
        // Engine that returns supportsCustomLoad=true but doesn't override customLoad
        org.elasticsearch.jingra.engine.BenchmarkEngine e =
                new org.elasticsearch.jingra.testing.MockBenchmarkEngine() {
                    @Override public boolean supportsCustomLoad() { return true; }
                };
        assertThrows(UnsupportedOperationException.class,
                () -> e.customLoad(new org.elasticsearch.jingra.config.JingraConfig(), null, "idx"));
    }

    // ── customLoad ───────────────────────────────────────────────────────────────

    @Test
    void supportsCustomLoad_returnsTrue() {
        ElasticsearchEngine e = engineWithoutUrl();
        assertTrue(e.supportsCustomLoad());
        assertDoesNotThrow(e::close);
    }

    @Test
    void customLoad_runsBinaryForceMergesAndReturnsDatapointCount() throws Exception {
        String fakeStderr = "{\"datapoints\":100000,\"data_points_per_second\":50000.0}\n";
        AtomicBoolean forceMergeCalled = new AtomicBoolean();
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url", "http://localhost:9200")) {
            @Override
            public boolean connect() {
                // Set baseUrl directly via reflection-free approach: connect() normally stores baseUrl;
                // we expose it by overriding connect() to also set the private field.
                try {
                    var f = ElasticsearchEngine.class.getDeclaredField("baseUrl");
                    f.setAccessible(true);
                    f.set(this, "http://localhost:9200");
                } catch (Exception ex) { throw new RuntimeException(ex); }
                return true;
            }
            @Override
            protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(
                    java.nio.file.Path configFile, org.elasticsearch.jingra.config.MetricsgenConfig cfg) {
                assertTrue(java.nio.file.Files.exists(configFile));
                return new MetricsgenLoader.ProcessResult(0, fakeStderr);
            }
            @Override
            protected void forceMergeOperation(String indexName) {
                forceMergeCalled.set(true);
            }
        };
        assertTrue(e.connect());
        int dp = e.customLoad(buildCustomLoadConfig(), null, "metrics-demo.otel-default");
        assertEquals(100_000, dp);
        assertTrue(forceMergeCalled.get(), "forceMergeOperation should be called after ingest");
        assertDoesNotThrow(e::close);
    }

    @Test
    void customLoad_zerodatapoints_logsWithoutRateWhenDpIsZero() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url", "http://localhost:9200")) {
            @Override public boolean connect() {
                try {
                    var f = ElasticsearchEngine.class.getDeclaredField("baseUrl");
                    f.setAccessible(true);
                    f.set(this, "http://localhost:9200");
                } catch (Exception ex) { throw new RuntimeException(ex); }
                return true;
            }
            @Override
            protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(
                    java.nio.file.Path configFile, org.elasticsearch.jingra.config.MetricsgenConfig cfg) {
                return new MetricsgenLoader.ProcessResult(0, "no datapoints here\n");
            }
            @Override protected void forceMergeOperation(String indexName) {}
        };
        assertTrue(e.connect());
        int dp = e.customLoad(buildCustomLoadConfig(), null, "idx");
        assertEquals(0, dp); // covers if (dp > 0) false branch
        assertDoesNotThrow(e::close);
    }

    @Test
    void customLoad_throwsWhenBinaryExitsNonZero() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url", "http://localhost:9200")) {
            @Override
            public boolean connect() {
                try {
                    var f = ElasticsearchEngine.class.getDeclaredField("baseUrl");
                    f.setAccessible(true);
                    f.set(this, "http://localhost:9200");
                } catch (Exception ex) { throw new RuntimeException(ex); }
                return true;
            }
            @Override
            protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(
                    java.nio.file.Path configFile, org.elasticsearch.jingra.config.MetricsgenConfig cfg) {
                return new MetricsgenLoader.ProcessResult(1, "fatal: connection refused\n");
            }
            @Override
            protected void forceMergeOperation(String indexName) {}
        };
        assertTrue(e.connect());
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> e.customLoad(buildCustomLoadConfig(), null, "idx"));
        assertTrue(ex.getMessage().contains("exited with code 1"), ex.getMessage());
        assertDoesNotThrow(e::close);
    }

    private static org.elasticsearch.jingra.config.JingraConfig buildCustomLoadConfig() {
        org.elasticsearch.jingra.config.JingraConfig cfg = new org.elasticsearch.jingra.config.JingraConfig();
        org.elasticsearch.jingra.config.LoadConfig load = new org.elasticsearch.jingra.config.LoadConfig();
        org.elasticsearch.jingra.config.MetricsgenConfig mg = new org.elasticsearch.jingra.config.MetricsgenConfig();
        mg.setScale(10);
        mg.setStartNowMinus("5m");
        load.setMetricsgen(mg);
        cfg.setLoad(load);
        return cfg;
    }
}
