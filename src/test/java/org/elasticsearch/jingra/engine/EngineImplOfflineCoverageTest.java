package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises engine implementations without a live cluster: missing URL config, failed connect,
 * and all {@code client == null} guard branches. Complements Testcontainers integration tests.
 */
class EngineImplOfflineCoverageTest {

    /** Unlikely to be set in any environment; forces {@code url == null} after env lookup. */
    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_TEST_URL_NOT_SET__";

    /** Fast-failing HTTP endpoint for connect failure path (nothing listens on port 1). */
    private static final String UNREACHABLE_HTTP = "http://127.0.0.1:1";

    @Nested
    class ElasticsearchOffline {
        private ElasticsearchEngine engineWithoutUrl() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url_env", BOGUS_URL_ENV);
            return new ElasticsearchEngine(cfg);
        }

        @Test
        void connectFailsWhenUrlMissing() {
            assertFalse(engineWithoutUrl().connect());
        }

        @Test
        void connectFailsWhenEndpointUnreachable() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url", UNREACHABLE_HTTP);
            assertFalse(new ElasticsearchEngine(cfg).connect());
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
    }

    @Nested
    class OpenSearchOffline {
        private OpenSearchEngine engineWithoutUrl() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url_env", BOGUS_URL_ENV);
            return new OpenSearchEngine(cfg);
        }

        @Test
        void connectFailsWhenUrlMissing() {
            assertFalse(engineWithoutUrl().connect());
        }

        @Test
        void connectFailsWhenEndpointUnreachable() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url", UNREACHABLE_HTTP);
            assertFalse(new OpenSearchEngine(cfg).connect());
        }

        @Test
        void operationsNoOpWhenNeverConnected() {
            OpenSearchEngine e = engineWithoutUrl();
            assertFalse(e.connect());
            assertEquals("opensearch", e.getEngineName());
            assertEquals("os", e.getShortName());
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
    }

    @Nested
    class QdrantOffline {
        private QdrantEngine engineWithoutUrl() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url_env", BOGUS_URL_ENV);
            return new QdrantEngine(cfg);
        }

        @Test
        void connectFailsWhenUrlMissing() {
            assertFalse(engineWithoutUrl().connect());
        }

        @Test
        void connectFailsWhenEndpointUnreachable() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url", "127.0.0.1:1");
            assertFalse(new QdrantEngine(cfg).connect());
        }

        @Test
        void grpcTimeoutSecondsFromConfig() {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url_env", BOGUS_URL_ENV);
            cfg.put("grpc_timeout_seconds", 7);
            QdrantEngine e = new QdrantEngine(cfg);
            assertFalse(e.connect());
        }

        @Test
        void operationsNoOpWhenNeverConnected() {
            QdrantEngine e = engineWithoutUrl();
            assertFalse(e.connect());
            assertEquals("qdrant", e.getEngineName());
            assertEquals("qd", e.getShortName());
            assertEquals("unknown", e.getVersion());
            assertFalse(e.createIndex("c", "s"));
            assertFalse(e.indexExists("c"));
            assertFalse(e.deleteIndex("c"));
            assertEquals(0, e.ingest(List.of(new Document(Map.of("x", 1))), "c", null));
            assertTrue(e.query("c", "q", new QueryParams()).getDocumentIds().isEmpty());
            assertEquals(0L, e.getDocumentCount("c"));
            assertTrue(e.getIndexMetadata("c").isEmpty());
            assertDoesNotThrow(() -> e.close());
        }
    }
}
