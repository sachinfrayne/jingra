package org.elasticsearch.jingra.output;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.elasticsearch.jingra.engine.ElasticsearchClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outputs benchmark results to an Elasticsearch cluster.
 */
public class ElasticsearchResultsSink implements ResultsSink {
    private static final String DEFAULT_METRICS_INDEX = "jingra-metrics";

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchResultsSink.class);
    private ElasticsearchClient client;
    private co.elastic.clients.transport.rest5_client.low_level.Rest5Client restClient;
    private final String url;
    private final String user;
    private final String password;
    private final String indexName;
    private final String metricsIndexName;
    private final boolean writeQueryMetrics;
    private final boolean insecureTls;

    public ElasticsearchResultsSink(Map<String, Object> config) {
        // Support both direct values and environment variable references
        String url;
        String user;
        String password;

        String urlEnv = getConfigString(config, "url_env", null);
        String userEnv = getConfigString(config, "user_env", null);
        String passwordEnv = getConfigString(config, "password_env", null);

        if (urlEnv != null) {
            // Read from environment variables
            url = System.getenv(urlEnv);
            user = userEnv != null ? System.getenv(userEnv) : null;
            password = passwordEnv != null ? System.getenv(passwordEnv) : null;
        } else {
            // Read direct values from config
            url = getConfigString(config, "url", null);
            user = getConfigString(config, "user", null);
            password = getConfigString(config, "password", null);
        }

        this.indexName = getConfigString(config, "index", "jingra-results");
        this.metricsIndexName = getConfigString(config, "metrics_index", DEFAULT_METRICS_INDEX);
        this.writeQueryMetrics = getConfigBoolean(config, "write_query_metrics", true);
        this.insecureTls = getConfigBoolean(config, "insecure_tls", false);

        if (url == null) {
            throw new IllegalArgumentException("Elasticsearch URL not provided in sink config (use 'url' or 'url_env')");
        }

        this.url = url;
        this.user = user;
        this.password = password;
        this.client = null;
        this.restClient = null;

        logger.info("Elasticsearch results sink configured: {} -> {}", url, indexName);
        logger.info(
                "Elasticsearch query metrics sink configured: {} -> {} (write_query_metrics={})",
                url,
                metricsIndexName,
                writeQueryMetrics);
    }

    private void ensureClientInitialized() {
        if (client == null) {
            initializeClient();
        }
    }

    private void initializeClient() {
        try {
            ElasticsearchClientFactory.ElasticsearchClientWrapper wrapper =
                    ElasticsearchClientFactory.createClient(url, user, password, insecureTls);
            this.client = wrapper.getClient();
            this.restClient = wrapper.getRestClient();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Elasticsearch client", e);
        }
    }

    @Override
    public void writeResult(BenchmarkResult result) {
        ensureClientInitialized();

        try {
            Map<String, Object> resultMap = result.toMap();
            IndexRequest<Map<String, Object>> request = IndexRequest.of(i -> i
                    .index(indexName)
                    .document(resultMap)
            );

            client.index(request);
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

        ensureClientInitialized();

        final String metricsIndex = metricsIndexName;
        final String timestamp = java.time.Instant.now().toString();
        final int batchSize = 100;

        // Add timestamp to all metrics
        for (Map<String, Object> queryMetric : queryMetrics) {
            queryMetric.put("@timestamp", timestamp);
        }

        int totalAttempted = queryMetrics.size();
        int totalWritten = 0;
        int totalFailed = 0;

        logger.info("Writing {} query metrics to index {} with batch size {}",
                totalAttempted, metricsIndex, batchSize);

        for (int start = 0; start < queryMetrics.size(); start += batchSize) {
            int end = Math.min(start + batchSize, queryMetrics.size());
            List<Map<String, Object>> batch = new ArrayList<>(queryMetrics.subList(start, end));

            try {
                int writtenThisBatch = writeBatchWithRetries(metricsIndex, batch, 10);
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
     * Bulk-indexes query metrics; {@link Thread#sleep} for backoff runs on the calling thread (eval completion path).
     */
    private int writeBatchWithRetries(String indexName,
                                      List<Map<String, Object>> batch,
                                      int maxAttempts) throws Exception {
        List<Map<String, Object>> remaining = new ArrayList<>(batch);
        int successCount = 0;

        for (int attempt = 1; attempt <= maxAttempts && !remaining.isEmpty(); attempt++) {
            if (attempt > 1) {
                long backoffMs = 500L * attempt;
                logger.warn("Retrying {} failed query metrics, attempt {}/{} after {} ms",
                        remaining.size(), attempt, maxAttempts, backoffMs);
                Thread.sleep(backoffMs);
            }

            BulkRequest request = buildBulkRequest(indexName, remaining);
            BulkResponse response;

            try {
                response = client.bulk(request);
            } catch (Exception e) {
                logger.error("Bulk request failed on attempt {}/{}: {}",
                        attempt, maxAttempts, e.getMessage());

                if (attempt == maxAttempts) {
                    throw e;
                }
                continue;
            }

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
                logger.warn("Bulk attempt {}/{} had {} retryable item failures",
                        attempt, maxAttempts, remaining.size());
            }
        }

        if (!remaining.isEmpty()) {
            logger.error("Exhausted retries for {} query metrics", remaining.size());
        }

        return successCount;
    }

    private BulkRequest buildBulkRequest(String indexName, List<Map<String, Object>> docs) {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (Map<String, Object> doc : docs) {
            bulkBuilder.operations(op -> op.index(idx -> idx
                    .index(indexName)
                    .document(doc)
            ));
        }

        return bulkBuilder.build();
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
        if (restClient != null) {
            restClient.close();
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
}
