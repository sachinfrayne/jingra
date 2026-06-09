package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PrometheusOfflineCoverageTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_TEST_URL_NOT_SET__";
    private static final String UNREACHABLE_HTTP = "http://127.0.0.1:1";

    private PrometheusEngine connectedEngine() {
        return new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) {
                return "2.45.0";
            }
        };
    }

    private void cleanupJingraConfig() throws Exception {
        Path root = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR);
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    void connectFailsWhenUrlMissing() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
    }

    @Test
    void connectFailsWhenEndpointUnreachable() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", UNREACHABLE_HTTP));
        assertFalse(e.connect());
    }

    @Test
    void operationsNoOpWhenNeverConnected() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
        assertEquals("prometheus", e.getEngineName());
        assertEquals("prom", e.getShortName());
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

    @Test
    void connectSucceeds_stripsTrailingSlash() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090/")) {
            @Override
            protected String buildInfoOperation(String url) {
                assertFalse(url.endsWith("/"), "trailing slash should be stripped");
                return "3.0.0";
            }
        };
        assertTrue(e.connect());
    }

    @Test
    void connectSucceeds_withUrlFromEnv() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url_env", "PROMETHEUS_URL")) {
            @Override
            protected String getEnv(String envVarName, String fallback) {
                return "PROMETHEUS_URL".equals(envVarName) ? "http://prom:9090" : fallback;
            }

            @Override
            protected String buildInfoOperation(String url) {
                return "3.12.0";
            }
        };
        assertTrue(e.connect());
    }

    @Test
    void getVersion_returnsVersionWhenConnected() throws Exception {
        PrometheusEngine e = connectedEngine();
        assertTrue(e.connect());
        assertEquals("2.45.0", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void getVersion_returnsUnknownWhenBuildInfoFails() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            private int callCount;

            @Override
            protected String buildInfoOperation(String url) throws Exception {
                if (callCount++ == 0) return "2.45.0"; // first call (connect) succeeds
                throw new Exception("connection lost");
            }
        };
        assertTrue(e.connect());
        assertEquals("unknown", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void supportsIndexLifecycle_returnsFalse() {
        assertFalse(connectedEngine().supportsIndexLifecycle());
    }

    @Test
    void dataStoreExists_returnsTrueWhenSeriesExist() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected boolean hasAnySeriesOperation() { return true; }
        };
        assertTrue(e.connect());
        assertTrue(e.dataStoreExists("metrics"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void dataStoreExists_returnsFalseWhenNoSeries() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected boolean hasAnySeriesOperation() { return false; }
        };
        assertTrue(e.connect());
        assertFalse(e.dataStoreExists("metrics"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void dataStoreExists_returnsFalseOnException() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected boolean hasAnySeriesOperation() throws Exception {
                throw new Exception("admin API unreachable");
            }
        };
        assertTrue(e.connect());
        assertFalse(e.dataStoreExists("metrics"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void resetDataStore_returnsFalseWhenDisconnected() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
        assertFalse(e.resetDataStore("metrics"));
    }

    @Test
    void resetDataStore_returnsTrueWhenBothOpsSucceed() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void deleteSeriesOperation() {}
            @Override protected void cleanTombstonesOperation() {}
        };
        assertTrue(e.connect());
        assertTrue(e.resetDataStore("metrics"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void resetDataStore_returnsFalseWhenDeleteSeriesFails() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void deleteSeriesOperation() throws Exception {
                throw new Exception("admin APIs disabled");
            }
        };
        assertTrue(e.connect());
        assertFalse(e.resetDataStore("metrics"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void resetDataStore_returnsFalseWhenCleanTombstonesFails() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void deleteSeriesOperation() {}
            @Override protected void cleanTombstonesOperation() throws Exception {
                throw new Exception("clean_tombstones failed");
            }
        };
        assertTrue(e.connect());
        assertFalse(e.resetDataStore("metrics"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_returnsZeroForEmptyList_whenConnected() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }
        };
        assertTrue(e.connect());
        assertEquals(0, e.ingest(List.of(), "metrics", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_returnsCountOnSuccess() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) {
                capturedBody.set(body);
                return 204;
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of(
                "@timestamp", "2024-01-01T00:00:00Z",
                "host.name", "host0",
                "system.cpu.utilization", 0.25,
                "state", "user"
        ));
        int count = e.ingest(List.of(doc), "metrics", null);
        assertEquals(1, count);
        assertNotNull(capturedBody.get());
        assertTrue(capturedBody.get().length > 0);
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_returnsZeroOnHttpError() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) { return 500; }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(0, e.ingest(List.of(doc), "metrics", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_returnsZeroOnStatusBelow200() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) { return 100; }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(0, e.ingest(List.of(doc), "metrics", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_returnsZeroWhenOtlpWriteThrows() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) throws Exception {
                throw new Exception("network error");
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(0, e.ingest(List.of(doc), "metrics", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_documentWithNullTimestamp_usesCurrentTime() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) {
                callCount.incrementAndGet();
                return 204;
            }
        };
        assertTrue(e.connect());
        // Document without @timestamp — parseTimestamp gets null from fields.get("@timestamp")
        Document doc = new Document(Map.of("metric_value", 42.0));
        assertEquals(1, e.ingest(List.of(doc), "metrics", null));
        assertEquals(1, callCount.get());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_documentWithInvalidTimestamp_usesCurrentTime() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) {
                callCount.incrementAndGet();
                return 204;
            }
        };
        assertTrue(e.connect());
        // HashMap allows null but we just use a non-ISO string to trigger the catch branch
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "not-a-valid-timestamp");
        fields.put("metric_value", 1.0);
        assertEquals(1, e.ingest(List.of(new Document(fields)), "metrics", null));
        assertEquals(1, callCount.get());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_twoOldTimestampDocuments_secondUsesOffsetCache() throws Exception {
        // Two documents in one batch: first call computes offset, second hits the cached-offset branch
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected int otlpWriteOperation(byte[] body) { return 204; }
        };
        assertTrue(e.connect());
        Document doc1 = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        Document doc2 = new Document(Map.of("@timestamp", "2024-01-01T00:00:10Z", "val", 2.0));
        assertEquals(2, e.ingest(List.of(doc1, doc2), "m", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_recentTimestampSkipsRebase() throws Exception {
        // Recent @timestamp → ageMs < 30min threshold → offset = 0 (no rebase applied)
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected int otlpWriteOperation(byte[] body) { return 204; }
        };
        assertTrue(e.connect());
        String nowIso = java.time.Instant.now().toString();
        Document doc = new Document(Map.of("@timestamp", nowIso, "val", 1.0));
        assertEquals(1, e.ingest(List.of(doc), "m", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_documentWithBooleanAndArrayFields_labelsAndSkipped() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) {
                capturedBody.set(body);
                return 204;
            }
        };
        assertTrue(e.connect());
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "2024-01-01T00:00:00Z");
        fields.put("active", true);            // boolean → label
        fields.put("tags", List.of("a", "b")); // list → skipped
        fields.put("nullfield", null);          // null → skipped
        fields.put("cpu_usage", 0.5);           // numeric → metric
        assertEquals(1, e.ingest(List.of(new Document(fields)), "metrics", null));
        assertNotNull(capturedBody.get());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void ingest_documentWithNoMetricFields_writesEmptyRequest() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected int otlpWriteOperation(byte[] body) {
                callCount.incrementAndGet();
                return 204;
            }
        };
        assertTrue(e.connect());
        // Document with only string fields — no metric values → empty OTLP request
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "host", "h1"));
        assertEquals(1, e.ingest(List.of(doc), "metrics", null));
        assertEquals(1, callCount.get());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void query_rendersTemplateAndReturnsIds() throws Exception {
        AtomicReference<String> capturedPromql = new AtomicReference<>();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) {
                return "topk({{size}}, avg_over_time(system_cpu_utilization[5m]))";
            }

            @Override
            protected String instantQueryOperation(String promql) {
                capturedPromql.set(promql);
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                        + "\"result\":[{\"metric\":{\"host\":\"h1\"},\"value\":[1609459200,\"0.5\"]}]}}";
            }
        };
        assertTrue(e.connect());
        QueryParams params = new QueryParams(Map.of("size", 10));
        QueryResponse resp = e.query("metrics", "cpu-stats", params);
        assertEquals("topk(10, avg_over_time(system_cpu_utilization[5m]))", capturedPromql.get());
        assertEquals(1, resp.getDocumentIds().size());
        assertNotNull(resp.getClientLatencyMs());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void query_handlesNullDataInResponse() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            protected String instantQueryOperation(String promql) {
                return "{\"status\":\"success\"}"; // no "data" key
            }
        };
        assertTrue(e.connect());
        QueryResponse resp = e.query("m", "q", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void query_handlesNullResultInResponse() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            protected String instantQueryOperation(String promql) {
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\"}}"; // no "result"
            }
        };
        assertTrue(e.connect());
        assertTrue(e.query("m", "q", new QueryParams()).getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void query_handlesNullMetricInResult() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            protected String instantQueryOperation(String promql) {
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                        + "\"result\":[{\"value\":[1609459200,\"1\"]}]}}"; // no "metric" key
            }
        };
        assertTrue(e.connect());
        List<String> ids = e.query("m", "q", new QueryParams()).getDocumentIds();
        assertEquals(1, ids.size());
        assertEquals("", ids.get(0));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void query_handlesInvalidJsonResponse() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            protected String instantQueryOperation(String promql) {
                return "not-valid-json";
            }
        };
        assertTrue(e.connect());
        // parseQueryResultIds catches parse exception → empty list
        assertTrue(e.query("m", "q", new QueryParams()).getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void query_returnsEmptyOnException() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) {
                throw new IllegalArgumentException("template not found");
            }
        };
        assertTrue(e.connect());
        QueryResponse resp = e.query("m", "missing", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void loadPromqlTemplate_loadsFromFilesystem() throws Exception {
        Path queryFile = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR, "queries", "test-prom.promql");
        try {
            Files.createDirectories(queryFile.getParent());
            Files.writeString(queryFile, "rate(http_requests_total[5m])", StandardCharsets.UTF_8);

            PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
                @Override
                protected String buildInfoOperation(String url) { return "2.45.0"; }

                @Override
                protected String instantQueryOperation(String promql) {
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
                }
            };
            assertTrue(e.connect());
            QueryResponse resp = e.query("m", "test-prom", new QueryParams());
            assertNotNull(resp);
        } finally {
            cleanupJingraConfig();
        }
    }

    @Test
    void loadPromqlTemplate_loadsFromClasspath() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected InputStream openPromqlClasspathStream(String resourcePath) {
                if (resourcePath.contains("classpath-prom")) {
                    return new ByteArrayInputStream(
                            "topk(5, avg_over_time(sys_cpu[5m]))".getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }

            @Override
            protected String instantQueryOperation(String promql) {
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
            }
        };
        assertTrue(e.connect());
        QueryResponse resp = e.query("m", "classpath-prom", new QueryParams());
        assertNotNull(resp);
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void loadPromqlTemplate_throwsWhenNotFound() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected InputStream openPromqlClasspathStream(String resourcePath) {
                return null; // not found on classpath
            }
        };
        assertTrue(e.connect());
        // query() catches the IllegalArgumentException from loadPromqlTemplate
        QueryResponse resp = e.query("m", "nonexistent-query", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void loadPromqlTemplate_classpathIoError_returnsNull() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected InputStream openPromqlClasspathStream(String resourcePath) {
                return new InputStream() {
                    @Override
                    public int read() throws java.io.IOException {
                        throw new java.io.IOException("simulated read error");
                    }
                };
            }
        };
        assertTrue(e.connect());
        // IOException in readAllBytes → returns null → falls through to throw
        QueryResponse resp = e.query("m", "some-query", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void loadPromqlTemplate_classpathCloseError_noReadError() throws Exception {
        // close() throws but read succeeded → error = closeEx → returns null → throw
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected InputStream openPromqlClasspathStream(String resourcePath) {
                byte[] data = "up".getBytes(StandardCharsets.UTF_8);
                return new ByteArrayInputStream(data) {
                    @Override
                    public void close() throws java.io.IOException {
                        throw new java.io.IOException("simulated close error");
                    }
                };
            }
        };
        assertTrue(e.connect());
        QueryResponse resp = e.query("m", "some-query", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void loadPromqlTemplate_classpathBothReadAndCloseError_preservesReadError() throws Exception {
        // read() throws AND close() throws → error stays as read error (not overwritten)
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected InputStream openPromqlClasspathStream(String resourcePath) {
                return new InputStream() {
                    @Override
                    public int read() throws java.io.IOException {
                        throw new java.io.IOException("read error");
                    }

                    @Override
                    public void close() throws java.io.IOException {
                        throw new java.io.IOException("close error");
                    }
                };
            }
        };
        assertTrue(e.connect());
        QueryResponse resp = e.query("m", "some-query", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    // ─── Tests that exercise the real buildInfoOperation / otlpWriteOperation /
    //     instantQueryOperation bodies (via httpSendString/httpSendVoid overrides) ───

    @Test
    void buildInfoOperation_parsesVersionFromResponse() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn(
                        "{\"status\":\"success\",\"data\":{\"version\":\"3.12.0\"}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertEquals("3.12.0", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void buildInfoOperation_returnsUnknownWhenDataIsNull() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\"}"); // no "data"
                return resp;
            }
        };
        assertTrue(e.connect()); // connect calls buildInfoOperation which returns "unknown" → sets baseUrl
        assertEquals("unknown", e.getVersion());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void buildInfoOperation_throwsOnNon200Status() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            private int callCount;

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                if (callCount++ == 0) {
                    Mockito.when(resp.statusCode()).thenReturn(200);
                    Mockito.when(resp.body()).thenReturn(
                            "{\"status\":\"success\",\"data\":{\"version\":\"3.0.0\"}}");
                } else {
                    Mockito.when(resp.statusCode()).thenReturn(503);
                }
                return resp;
            }
        };
        assertTrue(e.connect()); // first call (connect) succeeds
        assertEquals("unknown", e.getVersion()); // second call returns 503 → throw → "unknown"
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void otlpWriteOperation_returnsStatusCode() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                // buildInfoOperation is overridden, so this is only called from otlpWriteOperation
                Mockito.when(resp.statusCode()).thenReturn(204);
                Mockito.when(resp.body()).thenReturn("");
                return resp;
            }
        };
        assertTrue(e.connect());
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(1, e.ingest(List.of(doc), "m", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void otlpWriteOperation_logsWarningOnStatusAbove299() throws Exception {
        // Exercises the `resp.statusCode() >= 300` branch of the error-logging if inside otlpWriteOperation
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(400);
                Mockito.when(resp.body()).thenReturn("out of bounds");
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
    void otlpWriteOperation_logsWarningOnStatusBelow200() throws Exception {
        // Exercises the `resp.statusCode() < 200` branch of the error-logging if inside otlpWriteOperation
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
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
    void instantQueryOperation_returnsBodyOnSuccess() throws Exception {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                capturedUri.set(req.uri().toString());
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                                + "\"result\":[{\"metric\":{},\"value\":[1,\"1\"]}]}}");
                return resp;
            }
        };
        assertTrue(e.connect());
        QueryResponse resp = e.query("m", "up", new QueryParams());
        assertEquals(1, resp.getDocumentIds().size());
        assertNotNull(capturedUri.get());
        assertTrue(capturedUri.get().contains("/api/v1/query"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void instantQueryOperation_throwsOnNon200Status() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected String loadPromqlTemplate(String queryName) { return "up"; }

            @Override
            @SuppressWarnings("unchecked")
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(500);
                return resp;
            }
        };
        assertTrue(e.connect()); // connect uses buildInfoOperation (overridden), no httpSend call
        QueryResponse resp = e.query("m", "up", new QueryParams()); // httpSend returns 500 → throw → empty
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    // ─── Tests that cover real httpSendString / httpSendVoid bodies
    //     (via buildHttpClient override to inject a mock HttpClient) ───

    @Test
    @SuppressWarnings("unchecked")
    void httpSendString_delegatesToHttpClient() throws Exception {
        java.net.http.HttpClient mockClient = Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockResp = Mockito.mock(java.net.http.HttpResponse.class);
        Mockito.doReturn(mockResp).when(mockClient).send(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.when(mockResp.statusCode()).thenReturn(200);
        Mockito.when(mockResp.body()).thenReturn("{\"data\":{\"version\":\"2.45.0\"}}");

        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected java.net.http.HttpClient buildHttpClient() { return mockClient; }
        };
        // connect() → buildInfoOperation() → real httpSendString() → mockClient.send() → success
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpSendVoid_delegatesToHttpClient() throws Exception {
        java.net.http.HttpClient mockClient = Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockStringResp = Mockito.mock(java.net.http.HttpResponse.class);
        // Both sends (buildInfoOperation + otlpWriteOperation) use httpSendString
        Mockito.doReturn(mockStringResp)
                .when(mockClient).send(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.when(mockStringResp.statusCode()).thenReturn(200).thenReturn(204);
        Mockito.when(mockStringResp.body())
                .thenReturn("{\"data\":{\"version\":\"2.45.0\"}}") // buildInfoOperation response
                .thenReturn(""); // otlpWriteOperation response (status 204, body empty)

        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected java.net.http.HttpClient buildHttpClient() { return mockClient; }
        };
        assertTrue(e.connect());
        // ingest() → buildOtlpRequest() → otlpWriteOperation() → real httpSendString() → mockClient.send()
        Document doc = new Document(Map.of("@timestamp", "2024-01-01T00:00:00Z", "val", 1.0));
        assertEquals(1, e.ingest(List.of(doc), "m", null));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpSendVoid_delegatesToHttpClient_voidPath() throws Exception {
        // httpSendVoid is still used by deleteSeriesOperation and cleanTombstonesOperation.
        // Verify it delegates to httpClient.send() with a discarding body handler.
        java.net.http.HttpClient mockClient = Mockito.mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockStringResp = Mockito.mock(java.net.http.HttpResponse.class);
        java.net.http.HttpResponse<Void> mockVoidResp = Mockito.mock(java.net.http.HttpResponse.class);
        // connect → httpSendString (buildInfoOperation); resetDataStore → httpSendVoid (deleteSeriesOperation, cleanTombstonesOperation)
        Mockito.doReturn(mockStringResp).doReturn(mockVoidResp).doReturn(mockVoidResp)
                .when(mockClient).send(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.when(mockStringResp.statusCode()).thenReturn(200);
        Mockito.when(mockStringResp.body()).thenReturn("{\"data\":{\"version\":\"2.45.0\"}}");
        Mockito.when(mockVoidResp.statusCode()).thenReturn(204);

        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected java.net.http.HttpClient buildHttpClient() { return mockClient; }
        };
        assertTrue(e.connect());
        // resetDataStore() → deleteSeriesOperation() + cleanTombstonesOperation() → real httpSendVoid() → mockClient.send()
        assertTrue(e.resetDataStore("m"));
        assertDoesNotThrow(() -> e.close());
    }

    // ─── Tests for file IOException catch branch and openPromqlClasspathStream body ───

    @Test
    void loadPromqlTemplate_fileExistsAsDirectory_warnsAndFallsThrough() throws Exception {
        // A directory at the .promql path: exists() → true, but Files.readString() → IOException
        Path queryDir = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR, "queries", "dir-as-promql.promql");
        Files.createDirectories(queryDir);
        try {
            PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
                @Override
                protected String buildInfoOperation(String url) { return "2.45.0"; }

                @Override
                protected InputStream openPromqlClasspathStream(String resourcePath) {
                    return null;
                }
            };
            assertTrue(e.connect());
            // file.exists() → true, readString → IOException (is a directory) → warn → fall through to classpath (null) → throw
            QueryResponse resp = e.query("m", "dir-as-promql", new QueryParams());
            assertTrue(resp.getDocumentIds().isEmpty());
            assertDoesNotThrow(() -> e.close());
        } finally {
            cleanupJingraConfig();
        }
    }

    // ─── awaitIndexReady ─────────────────────────────────────────────────────────

    @Test
    void awaitIndexReady_noOpWhenNotConnected() {
        PrometheusEngine e = new PrometheusEngine(Map.of("url_env", BOGUS_URL_ENV));
        assertFalse(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_returnsWhenCompactionsStable() throws Exception {
        // elapsedMs always reports > stability → inner if true → returns immediately
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected long elapsedMs(long since)  { return 1L; } // always > 0 (stability)
            @Override protected String instantQueryOperation(String promql) {
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                        + "\"result\":[{\"metric\":{},\"value\":[1700000000,\"5\"]}]}}";
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_waitsForCountToRiseAboveZero() throws Exception {
        // First call returns 0 (no compactions yet), second returns 1 (stable immediately)
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) {
                int call = calls.getAndIncrement();
                long count = call == 0 ? 0 : 1;
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                        + "\"result\":[{\"metric\":{},\"value\":[1700000000,\"" + count + "\"]}]}}";
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
        assertTrue(calls.get() >= 2);
    }

    @Test
    void awaitIndexReady_proceedsOnQueryException() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                throw new Exception("TSDB metrics unavailable");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_throwsOnInterrupt() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 60_000L; }
            @Override protected long getStabilityWindowMs() { return 60_000L; }
            @Override protected String instantQueryOperation(String promql) {
                // count=0, never stable → will sleep → we interrupt it
                return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                        + "\"result\":[{\"metric\":{},\"value\":[1700000000,\"0\"]}]}}";
            }
        };
        assertTrue(e.connect());
        Thread testThread = Thread.currentThread();
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            testThread.interrupt();
        }).start();
        assertThrows(RuntimeException.class, () -> e.awaitIndexReady("metrics"));
        Thread.interrupted(); // clear interrupt flag
    }

    // ─── queryCompactionCount branches ───────────────────────────────────────────

    @Test
    void awaitIndexReady_countStableAtZero_elseIfFalse_thenThrows() throws Exception {
        // count stays at 0 across two calls → count == lastCount == 0
        // → else if (count > 0) is FALSE (count ≤ 0 branch)
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected long elapsedMs(long since)  { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() < 2)
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                            + "\"result\":[{\"metric\":{},\"value\":[1700000000,\"0\"]}]}}";
                throw new Exception("done");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_stableCountButWindowNotYetExpired_loopsThenExits() throws Exception {
        // elapsedMs always reports 0 → inner if false → loop continues → then throws
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected long elapsedMs(long since)  { return 0L; } // 0 > 0 = false → not stable
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() < 2)
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                            + "\"result\":[{\"metric\":{},\"value\":[1700000000,\"3\"]}]}}";
                throw new Exception("exit");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
        assertTrue(calls.get() >= 3);
    }

    @Test
    void awaitIndexReady_queryCompactionCount_nullResult_returnsZero() throws Exception {
        // "data" present but no "result" key → result == null → returns 0
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() == 0)
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\"}}"; // no "result"
                throw new Exception("done");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_queryCompactionCount_nullData_returnsZero() throws Exception {
        // "data" key missing → count = 0 → never stable → exception branch exits
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() == 0) return "{\"status\":\"success\"}"; // null data → count=0
                throw new Exception("done");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_queryCompactionCount_emptyResult_returnsZero() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() == 0)
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
                throw new Exception("done");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_queryCompactionCount_missingValue_returnsZero() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() == 0)
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                            + "\"result\":[{\"metric\":{}}]}}"; // no "value" key
                throw new Exception("done");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void awaitIndexReady_queryCompactionCount_valueTooShort_returnsZero() throws Exception {
        // value array present but has only 1 element → size < 2 → returns 0
        AtomicInteger calls = new AtomicInteger();
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "3.12.0"; }
            @Override protected long getPollIntervalMs()     { return 0L; }
            @Override protected long getStabilityWindowMs() { return 0L; }
            @Override protected String instantQueryOperation(String promql) throws Exception {
                if (calls.getAndIncrement() == 0)
                    return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                            + "\"result\":[{\"metric\":{},\"value\":[1700000000]}]}}"; // only 1 element
                throw new Exception("done");
            }
        };
        assertTrue(e.connect());
        assertDoesNotThrow(() -> e.awaitIndexReady("metrics"));
    }

    @Test
    void elapsedMs_defaultImplementation_returnsNonNegative() throws Exception {
        PrometheusEngine e = connectedEngine();
        assertTrue(e.connect());
        java.lang.reflect.Method m = PrometheusEngine.class.getDeclaredMethod("elapsedMs", long.class);
        m.setAccessible(true);
        long result = (long) m.invoke(e, System.currentTimeMillis());
        assertTrue(result >= 0);
    }

    @Test
    void getPollIntervalMs_and_getStabilityWindowMs_defaultValues() throws Exception {
        PrometheusEngine e = connectedEngine();
        assertTrue(e.connect());
        java.lang.reflect.Method poll = PrometheusEngine.class.getDeclaredMethod("getPollIntervalMs");
        poll.setAccessible(true);
        assertEquals(5_000L, poll.invoke(e));
        java.lang.reflect.Method stability = PrometheusEngine.class.getDeclaredMethod("getStabilityWindowMs");
        stability.setAccessible(true);
        assertEquals(30_000L, stability.invoke(e));
    }

    @Test
    void openPromqlClasspathStream_returnsNullForNonexistentResource() throws Exception {
        // No override of openPromqlClasspathStream — exercises the real getClass().getResourceAsStream() body
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override
            protected String buildInfoOperation(String url) { return "2.45.0"; }
        };
        assertTrue(e.connect());
        // No file, no classpath resource → openPromqlClasspathStream returns null → throw → empty response
        QueryResponse resp = e.query("m", "nonexistent-query-xyz-abc-999", new QueryParams());
        assertTrue(resp.getDocumentIds().isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    // ─── Tests that cover real hasAnySeriesOperation / deleteSeriesOperation /
    //     cleanTombstonesOperation bodies (via httpSendString/httpSendVoid overrides) ───

    @Test
    @SuppressWarnings("unchecked")
    void hasAnySeriesOperation_returnsTrueWhenDataNonEmpty() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\",\"data\":[\"cpu_total\"]}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.dataStoreExists("m")); // delegates to hasAnySeriesOperation
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasAnySeriesOperation_returnsFalseWhenDataEmpty() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\",\"data\":[]}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.dataStoreExists("m"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasAnySeriesOperation_throwsOnNon200() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(503);
                return resp;
            }
        };
        assertTrue(e.connect());
        // non-200 from hasAnySeriesOperation → throws → dataStoreExists catches → returns false
        assertFalse(e.dataStoreExists("m"));
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasAnySeriesOperation_returnsFalseWhenDataAbsent() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }

            @Override
            protected java.net.http.HttpResponse<String> httpSendString(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(200);
                // No "data" key → body.get("data") == null → values == null → returns false
                Mockito.when(resp.body()).thenReturn("{\"status\":\"success\"}");
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.dataStoreExists("m")); // values == null branch
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteSeriesOperation_succeeds() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void cleanTombstonesOperation() {}

            @Override
            protected java.net.http.HttpResponse<Void> httpSendVoid(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.resetDataStore("m")); // deleteSeriesOperation → real httpSendVoid → 204 → success
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteSeriesOperation_throwsOnNon204() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void cleanTombstonesOperation() {}

            @Override
            protected java.net.http.HttpResponse<Void> httpSendVoid(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(500);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.resetDataStore("m")); // deleteSeriesOperation → 500 → throws → resetDataStore returns false
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanTombstonesOperation_throwsOnNon204() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void deleteSeriesOperation() {}

            @Override
            protected java.net.http.HttpResponse<Void> httpSendVoid(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(500);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertFalse(e.resetDataStore("m")); // cleanTombstonesOperation → 500 → throws → resetDataStore returns false
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanTombstonesOperation_succeedsOnStatus204() throws Exception {
        PrometheusEngine e = new PrometheusEngine(Map.of("url", "http://localhost:9090")) {
            @Override protected String buildInfoOperation(String url) { return "2.45.0"; }
            @Override protected void deleteSeriesOperation() {}

            @Override
            protected java.net.http.HttpResponse<Void> httpSendVoid(java.net.http.HttpRequest req)
                    throws Exception {
                java.net.http.HttpResponse<Void> resp =
                        (java.net.http.HttpResponse<Void>) Mockito.mock(java.net.http.HttpResponse.class);
                Mockito.when(resp.statusCode()).thenReturn(204);
                return resp;
            }
        };
        assertTrue(e.connect());
        assertTrue(e.resetDataStore("m")); // cleanTombstonesOperation → 204 → returns normally → resetDataStore returns true
        assertDoesNotThrow(() -> e.close());
    }
}
