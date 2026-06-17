package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.MetricsgenConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    protected long getPollIntervalMs()       { return 5_000L;  }
    protected long getStabilityWindowMs()   { return 30_000L; }
    protected long elapsedMs(long since)    { return System.currentTimeMillis() - since; }

    @Override
    public void awaitIndexReady(String indexName) {
        if (!isConnected()) return;
        logger.info("Waiting for Prometheus TSDB compaction to settle after load...");
        long startMs    = System.currentTimeMillis();
        long lastChange = startMs;
        long lastCount  = -1;
        while (true) {
            try {
                long count = queryCompactionCount();
                if (count != lastCount) {
                    logger.info("TSDB compactions: {} (elapsed {}m)", count,
                            (System.currentTimeMillis() - startMs) / 60_000);
                    lastCount  = count;
                    lastChange = System.currentTimeMillis();
                } else if (elapsedMs(lastChange) > getStabilityWindowMs()) {
                    if (count > 0) {
                        logger.info("TSDB compactions stable at {} after {}m — Prometheus ready.",
                                count, (System.currentTimeMillis() - startMs) / 60_000);
                    } else {
                        logger.info("No TSDB compactions — small dataset stays in head block. Prometheus ready.");
                    }
                    return;
                }
                Thread.sleep(getPollIntervalMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for Prometheus TSDB compaction", ie);
            } catch (Exception e) {
                logger.warn("Error polling TSDB compaction status, proceeding anyway", e);
                return;
            }
        }
    }

    // ── Custom load (metricsgenreceiver) ─────────────────────────────────────────

    /** Prometheus transform block: merges resource attrs into datapoint labels. */
    private static final String PROMETHEUS_TRANSFORM_BLOCK =
            "  transform:\n" +
            "    metric_statements:\n" +
            "      - context: resource\n" +
            "        statements:\n" +
            "          - delete_key(attributes, \"host.ip\")\n" +
            "          - delete_key(attributes, \"host.mac\")\n" +
            "      - context: datapoint\n" +
            "        statements:\n" +
            "          - merge_maps(attributes, resource.attributes, \"insert\")";

    @Override
    public boolean supportsCustomLoad() {
        return true;
    }

    @Override
    public int customLoad(JingraConfig config, DatasetConfig dataset, String indexName) throws Exception {
        MetricsgenConfig cfg = config.getLoad().getMetricsgen();
        String otlpEndpoint = baseUrl + "/api/v1/otlp";
        String otelYaml = MetricsgenLoader.renderOtelConfig(
                cfg, otlpEndpoint, "otlphttp/prometheus", PROMETHEUS_TRANSFORM_BLOCK);

        Path tmpConfig = Files.createTempFile("metricsgen-", ".yaml");
        try {
            Files.writeString(tmpConfig, otelYaml);
            logger.info("Running metricsgenreceiver → {} (scale={}, window={})...",
                    otlpEndpoint, cfg.scaleOrDefault(), cfg.startNowMinusOrDefault());

            ScheduledExecutorService poller = startProgressPoller();
            try {
                MetricsgenLoader.ProcessResult result = runMetricsgenreceiver(tmpConfig, cfg);
                if (result.exitCode() != 0) {
                    throw new RuntimeException(
                            "metricsgenreceiver exited with code " + result.exitCode()
                                    + "\n" + result.stderr());
                }
                int dp   = MetricsgenLoader.parseDatapoints(result.stderr());
                double rate = MetricsgenLoader.parseRate(result.stderr());
                if (dp > 0) {
                    logger.info("Ingested {} data points ({} dp/s)", dp, String.format("%.0f", rate));
                }
                return dp;
            } finally {
                poller.shutdownNow();
                poller.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.deleteIfExists(tmpConfig);
        }
    }

    /**
     * Launches metricsgenreceiver with the given config file and returns its result.
     * Override in tests to inject canned stderr without a real subprocess.
     */
    protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(Path configFile, MetricsgenConfig cfg) throws Exception {
        Path binary = MetricsgenLoader.resolveBinary(cfg.versionOrDefault());
        return MetricsgenLoader.runBinary(binary, configFile);
    }

    /** Initial delay for the progress poller, in milliseconds. Override in tests to fire immediately. */
    protected long progressPollerInitialDelayMs() { return 10_000L; }
    /** Poll interval for the progress poller, in milliseconds. */
    protected long progressPollerIntervalMs()     { return 10_000L; }

    /**
     * Called periodically during custom load to log ingest progress.
     * Protected so tests can invoke it directly without waiting for the scheduler.
     */
    protected void logProgressPoll() {
        try {
            String body = instantQueryOperation("prometheus_tsdb_head_series");
            long series = parseInstantQueryLong(body);
            logger.info("  Prometheus: {} series in head", series);
        } catch (Exception ignored) {}
    }

    private ScheduledExecutorService startProgressPoller() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metricsgen-progress-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::logProgressPoll,
                progressPollerInitialDelayMs(), progressPollerIntervalMs(), TimeUnit.MILLISECONDS);
        return scheduler;
    }

    @SuppressWarnings("unchecked")
    private long parseInstantQueryLong(String body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            Map<String, Object> data   = (Map<String, Object>) parsed.get("data");
            if (data == null) return 0L;
            List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
            if (result == null || result.isEmpty()) return 0L;
            List<Object> value = (List<Object>) result.get(0).get("value");
            if (value == null || value.size() < 2) return 0L;
            return Long.parseLong(String.valueOf(value.get(1)));
        } catch (Exception e) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private long queryCompactionCount() throws Exception {
        String body = instantQueryOperation("prometheus_tsdb_compactions_total");
        Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
        Map<String, Object> data   = (Map<String, Object>) parsed.get("data");
        if (data == null) return 0;
        List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
        if (result == null || result.isEmpty()) return 0;
        List<Object> value = (List<Object>) result.get(0).get("value");
        if (value == null || value.size() < 2) return 0;
        return Long.parseLong(String.valueOf(value.get(1)));
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
