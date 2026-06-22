package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parallel ingest tuning for the {@code load} command.
 */
public class LoadConfig {

    @JsonProperty("batch_size")
    private Integer batchSize;

    @JsonProperty("threads")
    private Integer threads;

    @JsonProperty("queue_capacity")
    private Integer queueCapacity;

    @JsonProperty("await_index_ready")
    private boolean awaitIndexReady = false;

    @JsonProperty("force_merge")
    private boolean forceMerge = false;

    @JsonProperty("metricsgen")
    private MetricsgenConfig metricsgen;

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getThreads() {
        return threads;
    }

    public void setThreads(Integer threads) {
        this.threads = threads;
    }

    public Integer getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(Integer queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int batchSizeOrDefault() {
        return batchSize != null && batchSize > 0 ? batchSize : 10_000;
    }

    public int threadsOrDefault() {
        return threads != null && threads > 0 ? threads : 10;
    }

    public int queueCapacityOrDefault() {
        return queueCapacity != null && queueCapacity > 0 ? queueCapacity : 20;
    }

    public boolean isAwaitIndexReady() {
        return awaitIndexReady;
    }

    public void setAwaitIndexReady(boolean awaitIndexReady) {
        this.awaitIndexReady = awaitIndexReady;
    }

    public boolean isForceMerge() {
        return forceMerge;
    }

    public void setForceMerge(boolean forceMerge) {
        this.forceMerge = forceMerge;
    }

    public MetricsgenConfig getMetricsgen() {
        return metricsgen;
    }

    public void setMetricsgen(MetricsgenConfig metricsgen) {
        this.metricsgen = metricsgen;
    }
}
