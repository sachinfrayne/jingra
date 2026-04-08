package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Evaluation configuration.
 */
public class EvaluationConfig {

    @JsonProperty("warmup_workers")
    private Integer warmupWorkers = 16;

    @JsonProperty("measurement_workers")
    private Integer measurementWorkers = 16;

    @JsonProperty("warmup_rounds")
    private Integer warmupRounds = 3;

    @JsonProperty("measurement_rounds")
    private Integer measurementRounds = 1;

    @JsonProperty("run_id")
    private String runId;

    public Integer getWarmupWorkers() {
        return warmupWorkers;
    }

    public void setWarmupWorkers(Integer warmupWorkers) {
        this.warmupWorkers = warmupWorkers;
    }

    public Integer getMeasurementWorkers() {
        return measurementWorkers;
    }

    public void setMeasurementWorkers(Integer measurementWorkers) {
        this.measurementWorkers = measurementWorkers;
    }

    public Integer getWarmupRounds() {
        return warmupRounds;
    }

    public void setWarmupRounds(Integer warmupRounds) {
        this.warmupRounds = warmupRounds;
    }

    public Integer getMeasurementRounds() {
        return measurementRounds;
    }

    public void setMeasurementRounds(Integer measurementRounds) {
        this.measurementRounds = measurementRounds;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
