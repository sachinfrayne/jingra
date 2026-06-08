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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        assertFalse(e.createIndex("i", "s"));
        assertFalse(e.indexExists("i"));
        assertFalse(e.deleteIndex("i"));
        assertEquals(0, e.ingest(List.of(), "i", null));
        assertTrue(e.query("i", "q", new QueryParams()).getDocumentIds().isEmpty());
        assertEquals(0L, e.getDocumentCount("i"));
        assertTrue(e.getIndexMetadata("i").isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void createIndexRejectsWrappedSchemaBody() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean indexExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected void createIndexOperation(String indexName, String schemaJson) {
                fail("createIndexOperation should not run for wrapped schema bodies");
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
            assertFalse(e.createIndex("i", "wrapped"));
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void createIndexRejectsWrappedSchemaBody_nameOnly() throws Exception {
        ElasticsearchEngine e = new ElasticsearchEngine(Map.of("url_env", BOGUS_URL_ENV)) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean indexExistsOperation(String indexName) {
                return false;
            }

            @Override
            protected void createIndexOperation(String indexName, String schemaJson) {
                fail("createIndexOperation should not run for wrapped schema bodies");
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
            assertFalse(e.createIndex("i", "wrapped"));
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
