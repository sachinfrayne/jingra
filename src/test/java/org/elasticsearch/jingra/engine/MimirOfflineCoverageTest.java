package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    void connect_stripsTrailingSlash() {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080/")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"3.1.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(capturedUri.get().startsWith("http://localhost:8080//"),
                "trailing slash should be stripped before building URL");
    }

    @Test
    void connectFailsWhenBuildInfoThrows() {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) throws Exception {
                throw new Exception("connection refused");
            }
        };
        assertFalse(e.connect());
    }

    @Test
    void connect_usesUrlFromEnv() {
        MimirEngine e = new MimirEngine(Map.of("url_env", "MIMIR_URL")) {
            @Override
            protected String getEnv(String envVarName, String fallback) {
                return "MIMIR_URL".equals(envVarName) ? "http://mimir:8080" : fallback;
            }

            @Override
            protected String buildInfoOperation(String url) {
                return "3.1.0";
            }
        };
        assertTrue(e.connect());
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

    @Test
    void ingest_returnsZeroForEmptyList_whenConnected() {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "3.1.0"; }
        };
        assertTrue(e.connect());
        assertEquals(0, e.ingest(List.of(), "m", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_returnsZeroWhenOtlpWriteThrows() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "3.1.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) throws Exception {
                throw new Exception("network error");
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(0, e.ingest(List.of(doc), "m", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpSendString_delegatesToHttpClient() throws Exception {
        java.net.http.HttpClient mockClient = Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockResp = Mockito.mock(java.net.http.HttpResponse.class);
        Mockito.doReturn(mockResp).when(mockClient).send(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.when(mockResp.statusCode()).thenReturn(200);
        Mockito.when(mockResp.body()).thenReturn("{\"data\":{\"version\":\"3.1.0\"}}");

        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected java.net.http.HttpClient buildHttpClient() { return mockClient; }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.close());
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

    // ─── resetDataStore ────────────────────────────────────────────────────────

    @Test
    void resetDataStore_returnsTrue_withoutHttpCalls() {
        // Mimir starts each demo run fresh; data clearing is a no-op in MimirEngine.
        // Verify that resetDataStore returns true and makes no HTTP requests.
        AtomicReference<String> capturedUri = new AtomicReference<>();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override
            protected String buildInfoOperation(String url) { return "3.1.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"data\":{\"version\":\"3.1.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        String uriAfterConnect = capturedUri.get();
        assertTrue(e.resetDataStore("m")); // no HTTP call beyond connect expected
        assertEquals(uriAfterConnect, capturedUri.get(), "resetDataStore must not make additional HTTP calls");
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void resetDataStore_returnsFalseWhenDisconnected() {
        MimirEngine e = new MimirEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
        assertFalse(e.resetDataStore("m"));
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

    // ─── customLoad ────────────────────────────────────────────────────────────

    @AfterEach
    void clearOverrides() {
        MetricsgenLoader.binaryPathOverrideForTests.remove();
    }

    @Test
    void supportsCustomLoad_returnsTrue() {
        assertTrue(connectedEngine().supportsCustomLoad());
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

    @Test
    void customLoad_runsBinaryAndReturnsDatapointCount() throws Exception {
        String fakeStderr = "{\"datapoints\":50000,\"data_points_per_second\":25000.0}\n";
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override protected String buildInfoOperation(String url) { return "2.15.0"; }
            @Override protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(
                    Path binary, Path configFile, Map<String, String> envVars) {
                assertTrue(envVars.containsKey("MIMIR_URL"), "should have MIMIR_URL");
                assertTrue(envVars.containsKey("MIMIR_ORG_ID"), "should have MIMIR_ORG_ID");
                return new MetricsgenLoader.ProcessResult(0, fakeStderr);
            }
        };
        assertTrue(e.connect());
        int dp = e.customLoad(buildCustomLoadConfig(), null, "metrics");
        assertEquals(50_000, dp);
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void customLoad_zerodatapoints_logsSilently() throws Exception {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override protected String buildInfoOperation(String url) { return "2.15.0"; }
            @Override protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(
                    Path binary, Path configFile, Map<String, String> envVars) {
                return new MetricsgenLoader.ProcessResult(0, "no metrics here\n");
            }
        };
        assertTrue(e.connect());
        int dp = e.customLoad(buildCustomLoadConfig(), null, "metrics");
        assertEquals(0, dp);
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void customLoad_throwsWhenBinaryExitsNonZero() {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override protected String buildInfoOperation(String url) { return "2.15.0"; }
            @Override protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(
                    Path binary, Path configFile, Map<String, String> envVars) {
                return new MetricsgenLoader.ProcessResult(1, "fatal: cannot connect\n");
            }
        };
        assertTrue(e.connect());
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> e.customLoad(buildCustomLoadConfig(), null, "metrics"));
        assertTrue(ex.getMessage().contains("exited with code 1"), ex.getMessage());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void runMetricsgenreceiver_usesMetricsgenLoaderWithRealBinary(@TempDir Path tmpDir) throws Exception {
        byte[] script = ("#!/bin/sh\necho '{\"datapoints\":100}' >&2\n").getBytes();
        Path bin = tmpDir.resolve("metricsgenreceiver");
        Files.write(bin, script);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));

        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override protected String buildInfoOperation(String url) { return "2.15.0"; }
        };
        assertTrue(e.connect());
        Path cfgFile = tmpDir.resolve("otelcol.yaml");
        Files.writeString(cfgFile, "placeholder: true\n");
        MetricsgenLoader.ProcessResult result = e.runMetricsgenreceiver(
                bin, cfgFile, Map.of("MIMIR_URL", "http://localhost:8080", "MIMIR_ORG_ID", "anonymous"));
        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("datapoints"), result.stderr());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void progressPoller_runnableInvokesHasAnySeriesOperation() {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override protected boolean hasAnySeriesOperation() { called.set(true); return true; }
        };
        Runnable poller = e.progressPoller();
        poller.run();
        assertTrue(called.get());
    }

    @Test
    void progressPoller_silentOnException() {
        MimirEngine e = new MimirEngine(Map.of("url", "http://localhost:8080")) {
            @Override protected boolean hasAnySeriesOperation() throws Exception {
                throw new Exception("mimir unavailable");
            }
        };
        assertDoesNotThrow(() -> e.progressPoller().run());
    }
}
