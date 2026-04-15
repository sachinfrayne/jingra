package org.elasticsearch.jingra.analysis;

import java.util.Objects;

/**
 * Represents a comparison between baseline and target engine results for a specific parameter combination.
 */
public class ComparisonResult {
    private final String recallAtN;
    private final String paramKey;

    private final String baselineEngine;
    private final double baselineRecall;
    private final Double baselineLatency;
    private final Double baselineThroughput;

    private final String targetEngine;
    private final double targetRecall;
    private final Double targetLatency;
    private final Double targetThroughput;

    private final double recallDiff;
    private final double latencySpeedup;
    private final double throughputSpeedup;

    private ComparisonResult(Builder builder) {
        this.recallAtN = Objects.requireNonNull(builder.recallAtN, "recallAtN");
        this.paramKey = Objects.requireNonNull(builder.paramKey, "paramKey");
        this.baselineEngine = Objects.requireNonNull(builder.baselineEngine, "baselineEngine");
        this.baselineRecall = builder.baselineRecall;
        this.baselineLatency = builder.baselineLatency;
        this.baselineThroughput = builder.baselineThroughput;
        this.targetEngine = Objects.requireNonNull(builder.targetEngine, "targetEngine");
        this.targetRecall = builder.targetRecall;
        this.targetLatency = builder.targetLatency;
        this.targetThroughput = builder.targetThroughput;

        // Calculate comparisons
        this.recallDiff = targetRecall - baselineRecall;
        this.latencySpeedup = calculateLatencySpeedup(baselineLatency, targetLatency);
        this.throughputSpeedup = calculateThroughputSpeedup(baselineThroughput, targetThroughput);
    }

    private double calculateLatencySpeedup(Double baseline, Double target) {
        if (baseline == null || target == null) {
            return Double.NaN;
        }
        if (target == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return baseline / target;
    }

    private double calculateThroughputSpeedup(Double baseline, Double target) {
        if (baseline == null || target == null) {
            return Double.NaN;
        }
        if (baseline == 0.0) {
            return 0.0;  // Division by zero baseline doesn't make sense, return 0
        }
        return target / baseline;
    }

    public String getRecallAtN() {
        return recallAtN;
    }

    public String getParamKey() {
        return paramKey;
    }

    public String getBaselineEngine() {
        return baselineEngine;
    }

    public double getBaselineRecall() {
        return baselineRecall;
    }

    public Double getBaselineLatency() {
        return baselineLatency;
    }

    public Double getBaselineThroughput() {
        return baselineThroughput;
    }

    public String getTargetEngine() {
        return targetEngine;
    }

    public double getTargetRecall() {
        return targetRecall;
    }

    public Double getTargetLatency() {
        return targetLatency;
    }

    public Double getTargetThroughput() {
        return targetThroughput;
    }

    public double getRecallDiff() {
        return recallDiff;
    }

    public double getLatencySpeedup() {
        return latencySpeedup;
    }

    public double getThroughputSpeedup() {
        return throughputSpeedup;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String recallAtN;
        private String paramKey;
        private String baselineEngine;
        private double baselineRecall;
        private Double baselineLatency;
        private Double baselineThroughput;
        private String targetEngine;
        private double targetRecall;
        private Double targetLatency;
        private Double targetThroughput;

        public Builder recallAtN(String recallAtN) {
            this.recallAtN = recallAtN;
            return this;
        }

        public Builder paramKey(String paramKey) {
            this.paramKey = paramKey;
            return this;
        }

        public Builder baselineEngine(String baselineEngine) {
            this.baselineEngine = baselineEngine;
            return this;
        }

        public Builder baselineRecall(double baselineRecall) {
            this.baselineRecall = baselineRecall;
            return this;
        }

        public Builder baselineLatency(Double baselineLatency) {
            this.baselineLatency = baselineLatency;
            return this;
        }

        public Builder baselineThroughput(Double baselineThroughput) {
            this.baselineThroughput = baselineThroughput;
            return this;
        }

        public Builder targetEngine(String targetEngine) {
            this.targetEngine = targetEngine;
            return this;
        }

        public Builder targetRecall(double targetRecall) {
            this.targetRecall = targetRecall;
            return this;
        }

        public Builder targetLatency(Double targetLatency) {
            this.targetLatency = targetLatency;
            return this;
        }

        public Builder targetThroughput(Double targetThroughput) {
            this.targetThroughput = targetThroughput;
            return this;
        }

        public ComparisonResult build() {
            return new ComparisonResult(this);
        }
    }
}
