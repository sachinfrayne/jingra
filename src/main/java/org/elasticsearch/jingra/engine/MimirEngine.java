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
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MimirEngine extends AbstractBenchmarkEngine {

    private static final String DEFAULT_ORG_ID = "anonymous";

    private String baseUrl;
    private final HttpClient httpClient;
    private final AtomicLong ingestTimestampOffset = new AtomicLong(Long.MIN_VALUE);

    public MimirEngine(Map<String, Object> config) {
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

    protected String orgId() {
        return getConfigString("org_id", DEFAULT_ORG_ID);
    }

    @Override
    public boolean connect() {
        String url = getConfigString("url", null);
        if (url == null) {
            String urlEnv = getConfigString("url_env", "MIMIR_URL");
            url = getEnv(urlEnv, null);
        }
        if (url == null) {
            logger.error("Mimir URL not set in config or environment: {}",
                    getConfigString("url_env", "MIMIR_URL"));
            return false;
        }
        String resolvedUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        try {
            String version = buildInfoOperation(resolvedUrl);
            this.baseUrl = resolvedUrl;
            logger.info("Connected to Mimir {} at {}", version, resolvedUrl);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to Mimir at {}", resolvedUrl, e);
            return false;
        }
    }

    protected HttpResponse<String> httpSendString(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    protected String buildInfoOperation(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url + "/prometheus/api/v1/status/buildinfo"))
                .header("X-Scope-OrgID", orgId())
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
            logger.warn("Failed to get Mimir version", e);
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
            logger.warn("Failed to check Mimir series existence", e);
            return false;
        }
    }

    @Override
    public boolean resetDataStore(String indexName) {
        if (!isConnected()) return false;
        // Mimir's admin TSDB API requires additional configuration; demos always start fresh
        // via docker-compose down -v, so data clearing is not needed.
        return true;
    }

    @SuppressWarnings("unchecked")
    protected boolean hasAnySeriesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prometheus/api/v1/label/__name__/values"))
                .header("X-Scope-OrgID", orgId())
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
            logger.error("Failed to ingest documents into Mimir", e);
            return 0;
        }
    }

    protected int otlpWriteOperation(byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/otlp/v1/metrics"))
                .header("Content-Type", "application/x-protobuf")
                .header("X-Scope-OrgID", orgId())
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
        String bodyStr = "query=" + encoded;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prometheus/api/v1/query"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Scope-OrgID", orgId())
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("instant query returned HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    // ── Custom load (metricsgenreceiver) ─────────────────────────────────────────

    @Override
    public boolean supportsCustomLoad() {
        return true;
    }

    @Override
    public int customLoad(JingraConfig config, DatasetConfig dataset, String indexName) throws Exception {
        MetricsgenConfig cfg = config.getLoad().getMetricsgen();
        Map<String, String> envVars = new HashMap<>(MetricsgenLoader.buildEnvVars(cfg));
        envVars.put("MIMIR_URL",    baseUrl);
        envVars.put("MIMIR_ORG_ID", orgId());

        Path binary     = MetricsgenLoader.resolveBinary(cfg.versionOrDefault());
        Path otelConfig = Path.of(cfg.otelColConfigOrDefault());
        logger.info("Running metricsgenreceiver → {} (scale={}, window={}, config={})...",
                baseUrl, cfg.scaleOrDefault(), cfg.startNowMinusOrDefault(), otelConfig);

        ScheduledExecutorService poller = startProgressPoller();
        try {
            MetricsgenLoader.ProcessResult result = runMetricsgenreceiver(binary, otelConfig, envVars);
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
    }

    /**
     * Launches metricsgenreceiver. Override in tests to inject canned output.
     */
    protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(Path binary, Path configFile,
                                                                    Map<String, String> envVars)
            throws Exception {
        return MetricsgenLoader.runBinary(binary, configFile, envVars);
    }

    /** Initial delay for the progress poller, in milliseconds. Override in tests. */
    protected long progressPollerInitialDelayMs() { return 10_000L; }
    /** Poll interval for the progress poller, in milliseconds. */
    protected long progressPollerIntervalMs()     { return 10_000L; }

    /**
     * Returns the {@link Runnable} fired on each poller tick. Override in tests.
     */
    protected Runnable progressPoller() {
        return () -> {
            try {
                boolean hasSeries = hasAnySeriesOperation();
                logger.info("  Mimir: series present={}", hasSeries);
            } catch (Exception ignored) {}
        };
    }

    private ScheduledExecutorService startProgressPoller() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metricsgen-progress-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(progressPoller(),
                progressPollerInitialDelayMs(), progressPollerIntervalMs(), TimeUnit.MILLISECONDS);
        return scheduler;
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
        return "mimir";
    }

    @Override
    public String getShortName() {
        return "mim";
    }
}
