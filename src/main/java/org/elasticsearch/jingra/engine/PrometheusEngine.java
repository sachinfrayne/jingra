package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PrometheusEngine extends AbstractBenchmarkEngine {

    private String baseUrl;
    private final HttpClient httpClient;
    private final AtomicLong ingestTimestampOffset = new AtomicLong(Long.MIN_VALUE);

    private static final String DELETE_ALL_SERIES_BODY = "match%5B%5D=%7B__name__%21%3D%22%22%7D";

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
            ExportMetricsServiceRequest request = OtlpEncoder.buildRequest(documents, ingestTimestampOffset);
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
            List<String> ids = PromqlResponseParser.parseResultIds(responseBody, objectMapper, logger);
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
}
