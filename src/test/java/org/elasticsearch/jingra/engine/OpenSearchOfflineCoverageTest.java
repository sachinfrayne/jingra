package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.hc.core5.http.HttpEntity;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenSearchOfflineCoverageTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_TEST_URL_NOT_SET__";
    private static final String UNREACHABLE_HTTP = "http://127.0.0.1:1";

    private OpenSearchEngine engineWithoutUrl() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        return new OpenSearchEngine(cfg);
    }

    @Test
    void connectFailsWhenUrlMissing() {
        OpenSearchEngine e = engineWithoutUrl();
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
        OpenSearchEngine e = new OpenSearchEngine(cfg);
        try {
            assertFalse(e.connect());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void operationsNoOpWhenNeverConnected() {
        OpenSearchEngine e = engineWithoutUrl();
        assertFalse(e.connect());
        assertEquals("opensearch", e.getEngineName());
        assertEquals("os", e.getShortName());
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
        OpenSearchEngine e = new OpenSearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected org.opensearch.client.Response performRestRequest(org.opensearch.client.Request request) throws java.io.IOException {
                fail("performRestRequest should not run for wrapped schema bodies");
                return null;
            }

            @Override
            protected JsonNode loadSchemaTemplate(String schemaName) {
                try {
                    return objectMapper.readTree("{\"name\": \"wrapped\", \"template\": {\"mappings\": {}}}");
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
    void createDataStoreRejectsWrappedSchemaBody_templateOnly() throws Exception {
        OpenSearchEngine e = new OpenSearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected org.opensearch.client.Response performRestRequest(org.opensearch.client.Request request) throws java.io.IOException {
                fail("performRestRequest should not run for wrapped schema bodies");
                return null;
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
        OpenSearchEngine e = new OpenSearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean dataStoreExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected org.opensearch.client.Response performRestRequest(org.opensearch.client.Request request) throws java.io.IOException {
                fail("performRestRequest should not run for wrapped schema bodies");
                return null;
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
        OpenSearchEngine e = new OpenSearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected JsonNode loadQueryTemplateCached(String queryName) {
                try {
                    return objectMapper.readTree("{\"template\": {\"query\": {}}}");
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
        OpenSearchEngine e = new OpenSearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
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
        class DumpingEngine extends OpenSearchEngine {
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
                    return objectMapper.readTree("{\"query\":{\"term\":{\"f\":\"{{x}}\"}}}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected org.opensearch.client.Response performRestRequest(org.opensearch.client.Request request) throws java.io.IOException {
                org.opensearch.client.Response resp = Mockito.mock(org.opensearch.client.Response.class);
                HttpEntity ent = Mockito.mock(HttpEntity.class);
                Mockito.when(ent.getContent()).thenReturn(
                        new ByteArrayInputStream("{\"hits\":{\"hits\":[]},\"took\":1}".getBytes()));
                Mockito.when(resp.getEntity()).thenReturn(ent);
                return resp;
            }
        }

        DumpingEngine e = new DumpingEngine();
        try {
            QueryParams params = new QueryParams(Map.of("x", "v"));
            QueryResponse out = e.query("i", "q", params);
            assertNotNull(out);
            assertEquals(1, e.dumps);
        } finally {
            assertDoesNotThrow(e::close);
        }
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

        var m = OpenSearchEngine.class.getDeclaredMethod("renderDirectTemplate", JsonNode.class, Map.class);
        m.setAccessible(true);
        String rendered = (String) m.invoke(null, template, params);
        assertNotNull(rendered);
        assertTrue(rendered.contains("\"x\""));
        assertTrue(rendered.contains("3"));
        assertTrue(rendered.contains("true"));
        assertTrue(rendered.contains("\"k\""));
    }
}
