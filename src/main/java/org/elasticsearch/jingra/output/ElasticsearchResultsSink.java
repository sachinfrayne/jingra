package org.elasticsearch.jingra.output;

import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.utils.RetryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outputs benchmark results to an Elasticsearch cluster via {@link ElasticsearchEngine}
 * configured with the sink's URL and credentials (same write path as load ingest).
 */
public class ElasticsearchResultsSink implements ResultsSink {
    private static final String DEFAULT_METRICS_INDEX = "jingra-metrics";

    /**
     * Same defaults as {@link org.elasticsearch.jingra.cli.LoadCommand} ingest loop
     * ({@code maxIngestRetries} / {@code ingestRetryBackoffMs}).
     */
    private static final int DEFAULT_METRICS_BULK_ROUNDS = 10;

    /** Documents per bulk request for query metrics (keeps request size bounded). */
    private static final int QUERY_METRICS_BATCH_SIZE = 100;

    /**
     * Max transport-layer retries per Elasticsearch call ({@link RetryHelper#executeWithRetry}).
     * Load ingest uses unlimited retries for data integrity; the sink uses a bounded default so transient
     * HTTP/2 issues (e.g. stream reset) cannot stall the benchmark for minutes on a single bulk.
     * Override with config key {@link #CONFIG_SINK_TRANSPORT_MAX_RETRIES}.
     */
    private static final int DEFAULT_SINK_TRANSPORT_MAX_RETRIES = 9;

    static final String CONFIG_SINK_TRANSPORT_MAX_RETRIES = "sink_transport_max_retries";

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchResultsSink.class);

    private ElasticsearchEngine engine;

    private final String url;
    private final String user;
    private final String password;
    private final String indexName;
    private final String metricsIndexName;
    private final boolean writeQueryMetrics;
    private final boolean insecureTls;
    private final int sinkTransportMaxRetries;

    public ElasticsearchResultsSink(Map<String, Object> config) {
        String url;
        String user;
        String password;

        String urlEnv = getConfigString(config, "url_env", null);
        String userEnv = getConfigString(config, "user_env", null);
        String passwordEnv = getConfigString(config, "password_env", null);

        if (urlEnv != null) {
            url = System.getenv(urlEnv);
            user = userEnv != null ? System.getenv(userEnv) : null;
            password = passwordEnv != null ? System.getenv(passwordEnv) : null;
        } else {
            url = getConfigString(config, "url", null);
            user = getConfigString(config, "user", null);
            password = getConfigString(config, "password", null);
        }

        this.indexName = getConfigString(config, "index", "jingra-results");
        this.metricsIndexName = getConfigString(config, "metrics_index", DEFAULT_METRICS_INDEX);
        this.writeQueryMetrics = getConfigBoolean(config, "write_query_metrics", true);
        this.insecureTls = getConfigBoolean(config, "insecure_tls", false);
        this.sinkTransportMaxRetries = getConfigInt(config, CONFIG_SINK_TRANSPORT_MAX_RETRIES, DEFAULT_SINK_TRANSPORT_MAX_RETRIES);

        if (url == null) {
            throw new IllegalArgumentException("Elasticsearch URL not provided in sink config (use 'url' or 'url_env')");
        }

        this.url = url;
        this.user = user;
        this.password = password;

        logger.info("Elasticsearch results sink configured: {} -> {}", url, indexName);
        logger.info(
                "Elasticsearch query metrics sink configured: {} -> {} (write_query_metrics={})",
                url,
                metricsIndexName,
                writeQueryMetrics);
    }

    /**
     * Engine config for this sink's cluster (URL/credentials only; not the benchmark dataset config).
     */
    private Map<String, Object> buildEngineConfig() {
        Map<String, Object> m = new HashMap<>();
        m.put("url", url);
        m.put("user", user);
        m.put("password", password);
        m.put("insecure_tls", insecureTls);
        return m;
    }

    /**
     * Same-package tests may substitute a stub engine (or override this factory).
     */
    protected ElasticsearchEngine newEngine(Map<String, Object> engineConfig) {
        return new ElasticsearchEngine(engineConfig);
    }

    private void ensureEngineInitialized() {
        if (engine != null) {
            return;
        }
        try {
            engine = newEngine(buildEngineConfig());
            if (!engine.connect()) {
                throw new RuntimeException("Failed to connect to Elasticsearch results sink");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Elasticsearch client", e);
        }
    }

    @Override
    public void writeResult(BenchmarkResult result) {
        try {
            ensureEngineInitialized();
            RetryHelper.executeWithRetry(
                    () -> engine.ingest(List.of(new Document(result.toMap())), indexName, null),
                    sinkTransportMaxRetries,
                    1000L
            );
            logger.debug("Wrote result to Elasticsearch: {}/{}", indexName, result.getParamKey());
        } catch (Exception e) {
            logger.error("Failed to write result to Elasticsearch", e);
            throw new RuntimeException("Failed to write result to Elasticsearch", e);
        }
    }

    @Override
    public void writeQueryMetricsBatch(List<Map<String, Object>> queryMetrics) {
        if (!writeQueryMetrics) {
            logger.debug("writeQueryMetrics is disabled, skipping {} query metrics",
                    queryMetrics == null ? 0 : queryMetrics.size());
            return;
        }

        if (queryMetrics == null || queryMetrics.isEmpty()) {
            return;
        }

        ensureEngineInitialized();

        final String metricsIndex = metricsIndexName;
        final String timestamp = java.time.Instant.now().toString();

        for (Map<String, Object> queryMetric : queryMetrics) {
            queryMetric.put("@timestamp", timestamp);
        }

        int totalAttempted = queryMetrics.size();
        int totalWritten = 0;
        int totalFailed = 0;

        logger.info("Writing {} query metrics to index {} with batch size {}",
                totalAttempted, metricsIndex, QUERY_METRICS_BATCH_SIZE);

        for (int start = 0; start < queryMetrics.size(); start += QUERY_METRICS_BATCH_SIZE) {
            int end = Math.min(start + QUERY_METRICS_BATCH_SIZE, queryMetrics.size());
            List<Map<String, Object>> batch = new ArrayList<>(queryMetrics.subList(start, end));

            try {
                int writtenThisBatch = writeBatchWithRetries(metricsIndex, batch, DEFAULT_METRICS_BULK_ROUNDS);
                totalWritten += writtenThisBatch;
                int failedThisBatch = batch.size() - writtenThisBatch;
                totalFailed += failedThisBatch;

                if (failedThisBatch > 0) {
                    logger.error("Batch {}-{} complete: {} succeeded, {} failed",
                            start, end, writtenThisBatch, failedThisBatch);
                } else {
                    logger.info("Batch {}-{} complete: {} succeeded, {} failed",
                            start, end, writtenThisBatch, failedThisBatch);
                }
            } catch (Exception e) {
                totalFailed += batch.size();
                logger.error("Batch {}-{} failed: {}",
                        start, end, e.getMessage(), e);
            }
        }

        if (totalFailed > 0) {
            logger.warn("Query metrics write finished with some failures: attempted={}, succeeded={}, failed={} ({} success rate)",
                    totalAttempted, totalWritten, totalFailed,
                    String.format("%.2f%%", 100.0 * totalWritten / totalAttempted));
        } else {
            logger.info("Successfully wrote all {} query metrics to {}",
                    totalWritten, metricsIndex);
        }
    }

    /**
     * Matches {@link org.elasticsearch.jingra.cli.LoadCommand#run}'s {@code ingestRetryBackoffMs} (1000).
     */
    long ingestRetryBackoffMs() {
        return 1000L;
    }

    /**
     * Transport retry budget for {@link RetryHelper} around bulk / ingest (see {@link #DEFAULT_SINK_TRANSPORT_MAX_RETRIES}).
     */
    int bulkTransportMaxRetries() {
        return sinkTransportMaxRetries;
    }

    /**
     * Delegates to {@link ElasticsearchEngine#bulkIndexMaps(String, List)} with {@link RetryHelper} (bounded by {@link #sinkTransportMaxRetries}).
     */
    private BulkResponse bulkOnceWithTransportRetry(String indexName, List<Map<String, Object>> docs) throws Exception {
        return RetryHelper.executeWithRetry(
                () -> engine.bulkIndexMaps(indexName, docs),
                bulkTransportMaxRetries(),
                ingestRetryBackoffMs()
        );
    }

    private int writeBatchWithRetries(String indexName,
                                    List<Map<String, Object>> batch,
                                    int maxRounds) throws Exception {
        if (batch.isEmpty()) {
            return 0;
        }
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1");
        }

        List<Map<String, Object>> remaining = new ArrayList<>(batch);
        int successCount = 0;

        for (int round = 0; round < maxRounds && !remaining.isEmpty(); round++) {
            if (round > 0) {
                long delayMs = RetryHelper.computeRetryDelayMs(round - 1, ingestRetryBackoffMs());
                logger.warn("Retrying {} failed query metrics, round {}/{} after {} ms",
                        remaining.size(), round + 1, maxRounds, delayMs);
                try {
                    Thread.sleep(Math.max(0L, delayMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during metrics bulk backoff", ie);
                }
            }

            BulkResponse response = bulkOnceWithTransportRetry(indexName, remaining);

            if (!response.errors()) {
                successCount += remaining.size();
                return successCount;
            }

            List<Map<String, Object>> retryableDocs = new ArrayList<>();
            int batchSuccesses = 0;

            for (int i = 0; i < response.items().size(); i++) {
                BulkResponseItem item = response.items().get(i);
                Map<String, Object> originalDoc = remaining.get(i);

                if (item.error() == null) {
                    batchSuccesses++;
                    continue;
                }

                String errorType = item.error().type();
                String reason = item.error().reason();
                int status = item.status();

                if (isRetryableBulkStatus(status) || isRetryableBulkErrorType(errorType)) {
                    retryableDocs.add(originalDoc);
                } else {
                    logger.error("Permanent bulk failure: status={}, type={}, reason={}",
                            status, errorType, reason);
                }
            }

            successCount += batchSuccesses;
            remaining = retryableDocs;

            if (!remaining.isEmpty()) {
                logger.warn("Bulk round {} had {} retryable item failures", round + 1, remaining.size());
            }
        }

        if (!remaining.isEmpty()) {
            logger.error("Exhausted retries for {} query metrics", remaining.size());
        }

        return successCount;
    }

    private boolean isRetryableBulkStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private boolean isRetryableBulkErrorType(String errorType) {
        if (errorType == null) {
            return false;
        }

        switch (errorType) {
            case "es_rejected_execution_exception":
            case "unavailable_shards_exception":
            case "cluster_block_exception":
            case "timeout_exception":
            case "receive_timeout_transport_exception":
                return true;
            default:
                return false;
        }
    }

    @Override
    public void close() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    private String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getConfigBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }
}
