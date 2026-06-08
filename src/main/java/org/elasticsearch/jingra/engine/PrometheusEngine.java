package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus engine implementation. Ingests metric documents via remote_write and
 * queries via the Prometheus instant-query HTTP API with PromQL templates.
 */
public class PrometheusEngine extends AbstractBenchmarkEngine {

    private String baseUrl;
    private final HttpClient httpClient;

    // Lazily computed once; maps old dataset timestamps into a recent window so Prometheus accepts them.
    // Long.MIN_VALUE = sentinel "not yet computed".
    private final AtomicLong ingestTimestampOffset = new AtomicLong(Long.MIN_VALUE);

    public PrometheusEngine(Map<String, Object> config) {
        super(config);
        this.httpClient = buildHttpClient();
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    protected boolean isConnected() {
        return baseUrl != null;
    }

    @Override
    public boolean connect() {
        String url = getConfigString("url", null);
        if (url == null) {
            String urlEnv = getConfigString("url_env", "PROMETHEUS_URL");
            url = getEnv(urlEnv, null);
        }
        if (url == null) {
            logger.error("Prometheus URL not set in config or environment: {}",
                    getConfigString("url_env", "PROMETHEUS_URL"));
            return false;
        }
        String resolvedUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        try {
            String version = buildInfoOperation(resolvedUrl);
            this.baseUrl = resolvedUrl;
            logger.info("Connected to Prometheus {} at {}", version, resolvedUrl);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to Prometheus at {}", resolvedUrl, e);
            return false;
        }
    }

    protected HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<Void> httpSendVoid(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.discarding());
    }

    protected String buildInfoOperation(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url + "/api/v1/status/buildinfo"))
                .GET()
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("buildinfo returned HTTP " + resp.statusCode());
        }
        Map<String, Object> body = objectMapper.readValue(resp.body(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return data != null ? String.valueOf(data.get("version")) : "unknown";
    }

    @Override
    public String getVersion() {
        if (!isConnected()) return "unknown";
        try {
            return buildInfoOperation(baseUrl);
        } catch (Exception e) {
            logger.warn("Failed to get Prometheus version", e);
            return "unknown";
        }
    }

    @Override
    public boolean supportsIndexLifecycle() {
        return false;
    }

    @Override
    public boolean dataStoreExists(String indexName) {
        if (!isConnected()) return false;
        try {
            return hasAnySeriesOperation();
        } catch (Exception e) {
            logger.warn("Failed to check Prometheus series existence", e);
            return false;
        }
    }

    @Override
    public boolean resetDataStore(String indexName) {
        if (!isConnected()) return false;
        try {
            deleteSeriesOperation();
            cleanTombstonesOperation();
            return true;
        } catch (Exception e) {
            logger.error("Failed to clear Prometheus data", e);
            return false;
        }
    }

    // URL-encoded form of: match[]={__name__!=""}
    private static final String DELETE_ALL_SERIES_BODY = "match%5B%5D=%7B__name__%21%3D%22%22%7D";

    @SuppressWarnings("unchecked")
    protected boolean hasAnySeriesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/label/__name__/values"))
                .GET()
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("label values returned HTTP " + resp.statusCode());
        }
        Map<String, Object> body = objectMapper.readValue(resp.body(), new TypeReference<>() {});
        List<String> values = (List<String>) body.get("data");
        return values != null && !values.isEmpty();
    }

    protected void deleteSeriesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/admin/tsdb/delete_series"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(DELETE_ALL_SERIES_BODY))
                .build();
        HttpResponse<Void> resp = httpSendVoid(req);
        if (resp.statusCode() != 204) {
            throw new IOException("delete_series returned HTTP " + resp.statusCode()
                    + "; ensure Prometheus is started with --web.enable-admin-api");
        }
    }

    protected void cleanTombstonesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/admin/tsdb/clean_tombstones"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> resp = httpSendVoid(req);
        if (resp.statusCode() != 204) {
            throw new IOException("clean_tombstones returned HTTP " + resp.statusCode());
        }
    }

    @Override
    public int ingest(List<Document> documents, String indexName, String idField) {
        if (!isConnected()) return 0;
        if (documents.isEmpty()) return 0;
        try {
            ExportMetricsServiceRequest request = buildOtlpRequest(documents);
            int status = otlpWriteOperation(request.toByteArray());
            if (status < 200 || status >= 300) {
                logger.warn("OTLP write returned HTTP {}", status);
                return 0;
            }
            return documents.size();
        } catch (Exception e) {
            logger.error("Failed to ingest documents into Prometheus", e);
            return 0;
        }
    }

    protected int otlpWriteOperation(byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/otlp/v1/metrics"))
                .header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            logger.warn("OTLP write HTTP {}: {}", resp.statusCode(), resp.body());
        }
        return resp.statusCode();
    }

    @Override
    public QueryResponse query(String indexName, String queryName, QueryParams params) {
        if (!isConnected()) return new QueryResponse(List.of(), null, null);
        try {
            String template = loadPromqlTemplate(queryName);
            String promql = renderEsqlTemplate(template, params.getAll());
            long start = System.nanoTime();
            String responseBody = instantQueryOperation(promql);
            double clientLatencyMs = (System.nanoTime() - start) / 1_000_000.0;
            List<String> ids = parseQueryResultIds(responseBody);
            return new QueryResponse(ids, clientLatencyMs, null);
        } catch (Exception e) {
            logger.error("Failed to execute PromQL query '{}'", queryName, e);
            return new QueryResponse(List.of(), null, null);
        }
    }

    protected String instantQueryOperation(String promql) throws Exception {
        String encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8);
        String body = "query=" + encoded;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/query"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("instant query returned HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseQueryResultIds(String responseBody) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(responseBody, new TypeReference<>() {});
            Map<String, Object> data = (Map<String, Object>) parsed.get("data");
            if (data == null) return List.of();
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null) return List.of();
            List<String> ids = new ArrayList<>(results.size());
            for (Map<String, Object> r : results) {
                Map<String, String> metric = (Map<String, String>) r.get("metric");
                ids.add(metric != null ? metric.toString() : "");
            }
            return ids;
        } catch (Exception e) {
            logger.warn("Failed to parse PromQL query response", e);
            return List.of();
        }
    }

    protected String loadPromqlTemplate(String queryName) {
        String filename = queryName + ".promql";
        File file = new File(JINGRA_CONFIG_DIR + "/" + queriesPath + "/" + filename);
        if (file.exists()) {
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warn("Failed to load PromQL template from file: {}", file.getAbsolutePath(), e);
            }
        }
        String resourcePath = "/" + queriesPath + "/" + filename;
        String fromClasspath = readPromqlClasspathTemplate(resourcePath);
        if (fromClasspath != null) {
            return fromClasspath;
        }
        throw new IllegalArgumentException("PromQL template '" + queryName + "' not found");
    }

    private String readPromqlClasspathTemplate(String resourcePath) {
        InputStream is = openPromqlClasspathStream(resourcePath);
        if (is == null) {
            return null;
        }
        String content = null;
        IOException error = null;
        try {
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            error = e;
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                if (error == null) {
                    error = e;
                }
            }
        }
        if (error != null) {
            logger.warn("Failed to load PromQL template from classpath: {}", resourcePath, error);
            return null;
        }
        return content;
    }

    protected InputStream openPromqlClasspathStream(String resourcePath) {
        return getClass().getResourceAsStream(resourcePath);
    }

    @Override
    public long getDocumentCount(String indexName) {
        return 0;
    }

    @Override
    public Map<String, String> getIndexMetadata(String indexName) {
        return new HashMap<>();
    }

    @Override
    public String getEngineName() {
        return "prometheus";
    }

    @Override
    public String getShortName() {
        return "prom";
    }

    // ─── OTLP metrics encoding ────────────────────────────────────────────────

    private ExportMetricsServiceRequest buildOtlpRequest(List<Document> documents) {
        Map<String, List<NumberDataPoint>> pointsByMetric = new LinkedHashMap<>();

        for (Document doc : documents) {
            Map<String, Object> fields = doc.getFields();
            long timeNano = parseTimestamp(fields.get("@timestamp")) * 1_000_000L;

            List<KeyValue> attributes = new ArrayList<>();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if ("@timestamp".equals(key) || val == null) continue;
                if (val instanceof String || val instanceof Boolean) {
                    attributes.add(KeyValue.newBuilder()
                            .setKey(key)
                            .setValue(AnyValue.newBuilder().setStringValue(String.valueOf(val)).build())
                            .build());
                }
            }

            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if ("@timestamp".equals(key) || !(val instanceof Number)) continue;
                String metricName = key.replace('.', '_').replace('-', '_');
                NumberDataPoint dp = NumberDataPoint.newBuilder()
                        .addAllAttributes(attributes)
                        .setTimeUnixNano(timeNano)
                        .setAsDouble(((Number) val).doubleValue())
                        .build();
                pointsByMetric.computeIfAbsent(metricName, k -> new ArrayList<>()).add(dp);
            }
        }

        List<Metric> metrics = new ArrayList<>();
        for (Map.Entry<String, List<NumberDataPoint>> entry : pointsByMetric.entrySet()) {
            metrics.add(Metric.newBuilder()
                    .setName(entry.getKey())
                    .setGauge(Gauge.newBuilder().addAllDataPoints(entry.getValue()).build())
                    .build());
        }

        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addAllMetrics(metrics)
                                .build())
                        .build())
                .build();
    }

    private long parseTimestamp(Object ts) {
        if (ts == null) return System.currentTimeMillis();
        try {
            long ms = Instant.parse(ts.toString()).toEpochMilli();
            long offset = ingestTimestampOffset.get();
            if (offset == Long.MIN_VALUE) {
                long nowMs = System.currentTimeMillis();
                long ageMs = nowMs - ms;
                long computed = ageMs > 30 * 60 * 1000L ? nowMs - 180_000L - ms : 0L;
                ingestTimestampOffset.compareAndSet(Long.MIN_VALUE, computed);
                offset = ingestTimestampOffset.get();
            }
            return ms + offset;
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
