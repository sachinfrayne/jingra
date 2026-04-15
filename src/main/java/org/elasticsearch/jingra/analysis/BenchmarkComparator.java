package org.elasticsearch.jingra.analysis;

import org.elasticsearch.jingra.model.BenchmarkResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares benchmark results between baseline and target engines.
 * Handles latency metric fallback (server_latency_* -> latency_* when server not available).
 */
public class BenchmarkComparator {
    private final String latencyMetric;

    public BenchmarkComparator(String latencyMetric) {
        this.latencyMetric = latencyMetric;
    }

    /**
     * Compare results between baseline and target engines for a specific recall@N.
     * Matches results by param_key and creates ComparisonResult for each matched pair.
     *
     * @param baselineResults results from baseline engine
     * @param targetResults results from target engine
     * @param recallAtN recall label (e.g., "recall@100")
     * @return list of comparison results
     */
    public List<ComparisonResult> compare(
            List<BenchmarkResult> baselineResults,
            List<BenchmarkResult> targetResults,
            String recallAtN
    ) {
        List<ComparisonResult> comparisons = new ArrayList<>();

        // Match results by param_key
        Map<String, Pair> matched = matchByParamKey(baselineResults, targetResults);

        for (Map.Entry<String, Pair> entry : matched.entrySet()) {
            String paramKey = entry.getKey();
            BenchmarkResult baseline = entry.getValue().baseline;
            BenchmarkResult target = entry.getValue().target;

            ComparisonResult comparison = ComparisonResult.builder()
                    .recallAtN(recallAtN)
                    .paramKey(paramKey)
                    .baselineEngine(baseline.getEngine())
                    .baselineRecall(getRecall(baseline))
                    .baselineLatency(getLatency(baseline))
                    .baselineThroughput(getThroughput(baseline))
                    .targetEngine(target.getEngine())
                    .targetRecall(getRecall(target))
                    .targetLatency(getLatency(target))
                    .targetThroughput(getThroughput(target))
                    .build();

            comparisons.add(comparison);
        }

        return comparisons;
    }

    /**
     * For each engine, picks the benchmark row with the highest {@code recall} metric.
     * Engines whose result list is empty are omitted from the returned map.
     *
     * @param resultsByEngine map of engine name to that engine's results for one recall@N slice
     * @return map of engine name to the row with maximum recall (ties keep the first such row)
     */
    public Map<String, BenchmarkResult> findMaxRecallByEngine(Map<String, List<BenchmarkResult>> resultsByEngine) {
        Map<String, BenchmarkResult> maxByEngine = new HashMap<>();

        for (Map.Entry<String, List<BenchmarkResult>> entry : resultsByEngine.entrySet()) {
            List<BenchmarkResult> results = entry.getValue();
            if (results.isEmpty()) {
                continue;
            }

            BenchmarkResult best = results.get(0);
            double bestRecall = getRecall(best);
            for (int i = 1; i < results.size(); i++) {
                BenchmarkResult candidate = results.get(i);
                double recall = getRecall(candidate);
                if (recall > bestRecall) {
                    bestRecall = recall;
                    best = candidate;
                }
            }
            maxByEngine.put(entry.getKey(), best);
        }

        return maxByEngine;
    }

    /**
     * Public API to extract latency from a result using configured metric with fallback.
     * Allows external callers (like CsvExporter) to use the same latency extraction logic.
     *
     * @param result the benchmark result
     * @return the latency value, or null if not available
     */
    public Double extractLatency(BenchmarkResult result) {
        return getLatency(result);
    }

    /**
     * Public API to extract throughput from a result using configured logic.
     * Allows external callers (like CsvExporter) to use the same throughput extraction logic.
     *
     * @param result the benchmark result
     * @return the throughput value, or null if not available
     */
    public Double extractThroughput(BenchmarkResult result) {
        return getThroughput(result);
    }

    /**
     * Extract latency from result using configured metric with fallback.
     * If metric is "server_latency_X" and not found, falls back to "latency_X".
     */
    private Double getLatency(BenchmarkResult result) {
        Double value = result.getMetricAsDouble(latencyMetric);

        if (value == null && latencyMetric.startsWith("server_")) {
            // Fallback: server_latency_median -> latency_median
            String clientMetric = latencyMetric.substring("server_".length());
            value = result.getMetricAsDouble(clientMetric);
        }

        return value;
    }

    /**
     * Extract throughput from result.
     * Uses server_throughput if latency metric is server_latency_*, otherwise uses throughput.
     */
    private Double getThroughput(BenchmarkResult result) {
        if (latencyMetric.startsWith("server_")) {
            Double serverThroughput = result.getMetricAsDouble("server_throughput");
            if (serverThroughput != null) {
                return serverThroughput;
            }
        }
        return result.getMetricAsDouble("throughput");
    }

    /**
     * Extract recall metric from result.
     */
    private double getRecall(BenchmarkResult result) {
        Double recall = result.getMetricAsDouble("recall");
        return recall != null ? recall : 0.0;
    }

    /**
     * Match results by param_key.
     * Only includes pairs where both baseline and target have the same param_key.
     */
    private Map<String, Pair> matchByParamKey(
            List<BenchmarkResult> baseline,
            List<BenchmarkResult> target
    ) {
        Map<String, Pair> matched = new HashMap<>();

        // Build map of param_key -> result for baseline
        Map<String, BenchmarkResult> baselineMap = new HashMap<>();
        for (BenchmarkResult result : baseline) {
            baselineMap.put(result.getParamKey(), result);
        }

        // Match with target results
        for (BenchmarkResult targetResult : target) {
            String paramKey = targetResult.getParamKey();
            BenchmarkResult baselineResult = baselineMap.get(paramKey);

            if (baselineResult != null) {
                matched.put(paramKey, new Pair(baselineResult, targetResult));
            }
        }

        return matched;
    }

    /**
     * Simple pair holder for matched baseline and target results.
     */
    private static class Pair {
        final BenchmarkResult baseline;
        final BenchmarkResult target;

        Pair(BenchmarkResult baseline, BenchmarkResult target) {
            this.baseline = baseline;
            this.target = target;
        }
    }
}
