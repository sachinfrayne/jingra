package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MimirOfflineCoverageTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_MIMIR_URL_NOT_SET__";

    private MimirEngine connectedEngine() {
        return new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) {
                return "2.15.0";
            }
        };
    }

    // ─── Engine identity ───────────────────────────────────────────────────────

    @Test
    void engineName_isMimir() {
        assertEquals("mimir", connectedEngine().getEngineName());
    }

    @Test
    void shortName_isMim() {
        assertEquals("mim", connectedEngine().getShortName());
    }

    @Test
    void supportsIndexLifecycle_returnsFalse() {
        assertFalse(connectedEngine().supportsIndexLifecycle());
    }

    // ─── Connection ────────────────────────────────────────────────────────────

    @Test
    void connectFailsWhenUrlMissing() {
        MimirEngine e = new MimirEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
    }

    @Test
    void connect_usesPrometheusCompatibleBuildInfoPath() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"2.15.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertNotNull(capturedUri.get());
        assertTrue(capturedUri.get().contains("/prometheus/api/v1/status/buildinfo"),
                "expected /prometheus/ prefix, got: " + capturedUri.get());
    }

    @Test
    void connect_includesXScopeOrgIdHeader() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedReq.set(req);
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"2.15.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(capturedReq.get().headers().firstValue("X-Scope-OrgID").isPresent(),
                "X-Scope-OrgID header missing from buildinfo request");
    }

    @Test
    void connect_defaultOrgIdIsAnonymous() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedReq.set(req);
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"2.15.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertEquals("anonymous",
                capturedReq.get().headers().firstValue("X-Scope-OrgID").orElse(null));
    }

    @Test
    void connect_usesConfiguredOrgId() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080", "org_id", "my-tenant")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedReq.set(req);
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"2.15.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertEquals("my-tenant",
                capturedReq.get().headers().firstValue("X-Scope-OrgID").orElse(null));
    }

    // ─── OTLP ingest ───────────────────────────────────────────────────────────

    @Test
    void ingest_usesCorrectOtlpPath() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) throws Exception {
                // captured via httpSendString below
                return 204;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                Mockito.when(resp.body()).thenReturn("");
                return resp;
            }
        };
        assertTrue(e.connect());
        // trigger a real otlpWriteOperation by NOT overriding it, instead capture via httpSendString
        MimirEngine e2 = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                Mockito.when(resp.body()).thenReturn("");
                return resp;
            }
        };
        assertTrue(e2.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        e2.ingest(List.of(doc), "m", null);
        assertNotNull(capturedUri.get());
        assertTrue(capturedUri.get().contains("/otlp/v1/metrics"),
                "expected /otlp/v1/metrics, got: " + capturedUri.get());
        assertFalse(capturedUri.get().contains("/api/v1/otlp"),
                "should NOT use Prometheus-style /api/v1/otlp path, got: " + capturedUri.get());
    }

    @Test
    void ingest_includesXScopeOrgIdHeader() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080", "org_id", "tenant1")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                if (req.uri().toString().contains("/otlp/")) {
                    capturedReq.set(req);
                }
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                Mockito.when(resp.body()).thenReturn("");
                return resp;
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(1, e.ingest(List.of(doc), "m", null));
        assertNotNull(capturedReq.get(), "OTLP request was not captured");
        assertEquals("tenant1",
                capturedReq.get().headers().firstValue("X-Scope-OrgID").orElse(null));
    }

    // ─── Instant query ─────────────────────────────────────────────────────────

    @Test
    void query_usesPrometheusCompatibleQueryPath() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            protected String instantQueryOperation(String promql) throws Exception {
                // not used here; use httpSendString override instead
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
            }
        };
        assertTrue(e.connect());

        MimirEngine e2 = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
                return resp;
            }
        };
        assertTrue(e2.connect());
        e2.query("m", "up", new QueryParams());
        assertNotNull(capturedUri.get());
        assertTrue(capturedUri.get().contains("/prometheus/api/v1/query"),
                "expected /prometheus/api/v1/query, got: " + capturedUri.get());
    }

    @Test
    void query_includesXScopeOrgIdHeader() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080", "org_id", "org42")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                if (req.uri().toString().contains("/query")) {
                    capturedReq.set(req);
                }
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        e.query("m", "up", new QueryParams());
        assertNotNull(capturedReq.get(), "query request was not captured");
        assertEquals("org42",
                capturedReq.get().headers().firstValue("X-Scope-OrgID").orElse(null));
    }

    // ─── hasAnySeriesOperation ──────────────────────────────────────────────────

    @Test
    void dataStoreExists_usesPrometheusCompatibleLabelPath() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\",\"data\":[\"cpu\"]}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.dataStoreExists("m"));
        assertTrue(capturedUri.get().contains("/prometheus/api/v1/label/__name__/values"),
                "expected /prometheus/ prefix, got: " + capturedUri.get());
    }

    @Test
    void dataStoreExists_includesXScopeOrgIdHeader() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080", "org_id", "dev")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                if (req.uri().toString().contains("/label/")) {
                    capturedReq.set(req);
                }
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\",\"data\":[]}");
                return resp;
            }
        };
        assertTrue(e.connect());
        e.dataStoreExists("m");
        assertNotNull(capturedReq.get());
        assertEquals("dev",
                capturedReq.get().headers().firstValue("X-Scope-OrgID").orElse(null));
    }

    // ─── deleteSeriesOperation / cleanTombstonesOperation ──────────────────────

    @Test
    void resetDataStore_usesPrometheusCompatibleDeletePath() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<Void> httpSendVoid(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.resetDataStore("m"));
        assertNotNull(capturedUri.get());
        assertTrue(capturedUri.get().contains("/prometheus/api/v1/admin/tsdb/"),
                "expected /prometheus/ prefix for admin ops, got: " + capturedUri.get());
    }

    @Test
    void resetDataStore_includesXScopeOrgIdHeader() {
        AtomicReference<HttpRequest> capturedReq = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080", "org_id", "prod")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<Void> httpSendVoid(HttpRequest req) throws Exception {
                capturedReq.set(req);
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.resetDataStore("m"));
        assertNotNull(capturedReq.get());
        assertEquals("prod",
                capturedReq.get().headers().firstValue("X-Scope-OrgID").orElse(null));
    }

    // ─── operationsNoOpWhenNeverConnected ───────────────────────────────────────

    @Test
    void operationsNoOpWhenNeverConnected() {
        MimirEngine e = new MimirEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
        assertEquals("mimir", e.getEngineName());
        assertEquals("mim", e.getShortName());
        assertEquals("unknown", e.getVersion());
        assertTrue(e.createDataStore("i", "s"));
        assertFalse(e.dataStoreExists("i"));
        assertFalse(e.resetDataStore("i"));
        assertEquals(0, e.ingest(List.of(), "i", null));
        assertEquals(0, e.ingest(List.of(new Document()), "i", null));
        assertTrue(e.query("i", "q", new QueryParams()).getDocumentIds().isEmpty());
        assertEquals(0L, e.getDocumentCount("i"));
        assertTrue(e.getIndexMetadata("i").isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    // ─── getVersion ────────────────────────────────────────────────────────────

    @Test
    void getVersion_returnsVersionFromBuildInfo() throws Exception {
        MimirEngine e = connectedEngine();
        assertTrue(e.connect());
        assertEquals("2.15.0", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildInfoOperation_parsesVersionFromResponse() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\",\"data\":{\"version\":\"2.15.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertEquals("2.15.0", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildInfoOperation_throwsOnNon200Status() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            private int callCount;

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                if (callCount++ == 0) {
                    Mockito.when(resp.statusCode()).thenReturn(200);
                    Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"2.15.0\"}}");
                } else {
                    Mockito.when(resp.statusCode()).thenReturn(503);
                }
                return resp;
            }
        };
        assertTrue(e.connect());
        assertEquals("unknown", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    // ─── Error paths in overridden operations ──────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void buildInfoOperation_returnsUnknownWhenDataIsNull() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\"}"); // no "data" key
                return resp;
            }
        };
        assertTrue(e.connect());
        assertEquals("unknown", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void otlpWrite_logsWarningOnStatusAbove299() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(400);
                Mockito.when(resp.body()).thenReturn("bad request");
                return resp;
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(0, e.ingest(List.of(doc), "m", null)); // 400 → ingest returns 0
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void otlpWrite_logsWarningOnStatusBelow200() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(100);
                Mockito.when(resp.body()).thenReturn("continue");
                return resp;
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(0, e.ingest(List.of(doc), "m", null)); // 100 → ingest returns 0
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void instantQuery_throwsOnNon200_returnsEmptyResponse() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(500);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.query("m", "up", new QueryParams()).getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasAnySeriesOperation_throwsOnNon200() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(503);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.dataStoreExists("m")); // throws → caught → returns false
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasAnySeriesOperation_returnsFalseWhenDataAbsent() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\"}"); // no "data" key
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.dataStoreExists("m")); // data == null → false
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteSeriesOperation_throwsOnNon204() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }
            @Override protected void cleanTombstonesOperation() {}

            @Override
            protected java.net.http.HttpResponse<Void> httpSendVoid(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(500);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.resetDataStore("m")); // throws → resetDataStore returns false
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanTombstonesOperation_throwsOnNon204() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.15.0"; }
            @Override protected void deleteSeriesOperation() {}

            @Override
            protected java.net.http.HttpResponse<Void> httpSendVoid(HttpRequest req) throws Exception {
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(500);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.resetDataStore("m")); // cleanTombstonesOperation throws → returns false
        assertDoesNotThrow(() -> e.close());
    }

    // ─── EngineFactory ─────────────────────────────────────────────────────────

    @Test
    void engineFactory_createsMimirEngine() {
        var config = Mockito.mock(org.elasticsearch.jingra.config.JingraConfig.class);
        Mockito.when(config.getEngine()).thenReturn("mimir");
        Mockito.when(config.getEngineConfig()).thenReturn(Map.of("url_env", BOGUS_URL_ENV));
        BenchmarkEngine engine = EngineFactory.create(config);
        assertInstanceOf(MimirEngine.class, engine);
        assertEquals("mimir", engine.getEngineName());
    }
}
