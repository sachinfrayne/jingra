package org.elasticsearch.jingra.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Calculates benchmark metrics from query results.
 */
public class MetricsCalculator {

    private final List<QueryResult> results;
    /**
     * When set (typically sum of wall-clock times for measurement rounds), {@link #calculateThroughput()}
     * uses {@code queries / wall_time} (correct under parallel workers). Otherwise throughput uses the
     * sum-of-client-latencies model (misleading when queries run in parallel).
     */
    private final Long wallClockMsForThroughput;

    public MetricsCalculator(List<QueryResult> results) {
        this(results, null);
    }

    public MetricsCalculator(List<QueryResult> results, Long wallClockMsForThroughput) {
        this.results = results;
        this.wallClockMsForThroughput = wallClockMsForThroughput;
    }

    /**
     * Calculate average precision across all queries.
     * Precision = |relevant ∩ retrieved| / |retrieved|
     */
    public double calculatePrecision() {
        if (results.isEmpty()) return 0.0;

        double sum = 0.0;
        for (QueryResult result : results) {
            sum += calculateQueryPrecision(result);
        }
        return sum / results.size();
    }

    /**
     * Calculate average recall across all queries.
     * Recall = |relevant ∩ retrieved| / |relevant|
     */
    public double calculateRecall() {
        if (results.isEmpty()) return 0.0;

        double sum = 0.0;
        for (QueryResult result : results) {
            sum += calculateQueryRecall(result);
        }
        return sum / results.size();
    }

    /**
     * Calculate F1 score.
     * F1 = 2 * (precision * recall) / (precision + recall)
     */
    public double calculateF1() {
        double precision = calculatePrecision();
        double recall = calculateRecall();

        if (precision + recall == 0) return 0.0;
        return 2 * (precision * recall) / (precision + recall);
    }

    /**
     * Calculate Mean Reciprocal Rank (MRR).
     * MRR = average(1 / rank_of_first_relevant_item)
     */
    public double calculateMRR() {
        if (results.isEmpty()) return 0.0;

        double sum = 0.0;
        for (QueryResult result : results) {
            sum += calculateQueryRR(result);
        }
        return sum / results.size();
    }

    /**
     * Calculate average client latency.
     */
    public double calculateLatencyAvg() {
        return results.stream()
                .filter(r -> r.clientLatencyMs != null)
                .mapToDouble(r -> r.clientLatencyMs)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate median client latency.
     */
    public double calculateLatencyMedian() {
        return calculatePercentile(
                results.stream()
                        .filter(r -> r.clientLatencyMs != null)
                        .mapToDouble(r -> r.clientLatencyMs)
                        .sorted()
                        .toArray(),
                50
        );
    }

    /**
     * Calculate client latency percentile.
     */
    public double calculateLatencyPercentile(int percentile) {
        return calculatePercentile(
                results.stream()
                        .filter(r -> r.clientLatencyMs != null)
                        .mapToDouble(r -> r.clientLatencyMs)
                        .sorted()
                        .toArray(),
                percentile
        );
    }

    /**
     * Check if server latencies are available.
     */
    public boolean hasServerLatencies() {
        return results.stream().anyMatch(r -> r.serverLatencyMs != null);
    }

    /**
     * Calculate average server latency.
     */
    public double calculateServerLatencyAvg() {
        return results.stream()
                .filter(r -> r.serverLatencyMs != null)
                .mapToDouble(r -> r.serverLatencyMs)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate median server latency.
     */
    public double calculateServerLatencyMedian() {
        return calculatePercentile(
                results.stream()
                        .filter(r -> r.serverLatencyMs != null)
                        .mapToDouble(r -> r.serverLatencyMs)
                        .sorted()
                        .toArray(),
                50
        );
    }

    /**
     * Calculate server latency percentile.
     */
    public double calculateServerLatencyPercentile(int percentile) {
        return calculatePercentile(
                results.stream()
                        .filter(r -> r.serverLatencyMs != null)
                        .mapToDouble(r -> r.serverLatencyMs)
                        .sorted()
                        .toArray(),
                percentile
        );
    }

    /**
     * Queries per second. Uses wall-clock time when {@link #wallClockMsForThroughput} is set;
     * otherwise uses sum of per-query client latencies (parallelism distorts the latter).
     */
    public double calculateThroughput() {
        if (results.isEmpty()) return 0.0;

        if (wallClockMsForThroughput != null && wallClockMsForThroughput > 0) {
            return results.size() * 1000.0 / wallClockMsForThroughput;
        }

        double totalTimeMs = results.stream()
                .filter(r -> r.clientLatencyMs != null)
                .mapToDouble(r -> r.clientLatencyMs)
                .sum();

        if (totalTimeMs == 0) return 0.0;

        return (results.size() * 1000.0) / totalTimeMs;
    }

    /**
     * Legacy throughput model: {@code num_queries / sum(client_latencies)}. Exposed when wall-clock
     * throughput is used so comparisons remain possible.
     */
    public double calculateThroughputAggregateLatencyModel() {
        if (results.isEmpty()) return 0.0;

        double totalTimeMs = results.stream()
                .filter(r -> r.clientLatencyMs != null)
                .mapToDouble(r -> r.clientLatencyMs)
                .sum();

        if (totalTimeMs == 0) return 0.0;

        return (results.size() * 1000.0) / totalTimeMs;
    }

    /**
     * Calculate precision for a single query.
     */
    private double calculateQueryPrecision(QueryResult result) {
        if (result.retrieved.isEmpty()) return 0.0;

        Set<String> groundTruthSet = new HashSet<>(result.groundTruth);
        long relevantRetrieved = result.retrieved.stream()
                .filter(groundTruthSet::contains)
                .count();

        return (double) relevantRetrieved / result.retrieved.size();
    }

    /**
     * Calculate recall for a single query.
     */
    private double calculateQueryRecall(QueryResult result) {
        if (result.groundTruth.isEmpty()) return 0.0;

        Set<String> retrievedSet = new HashSet<>(result.retrieved);
        long relevantRetrieved = result.groundTruth.stream()
                .filter(retrievedSet::contains)
                .count();

        return (double) relevantRetrieved / result.groundTruth.size();
    }

    /**
     * Calculate reciprocal rank for a single query.
     */
    private double calculateQueryRR(QueryResult result) {
        if (result.retrieved.isEmpty() || result.groundTruth.isEmpty()) {
            return 0.0;
        }

        Set<String> groundTruthSet = new HashSet<>(result.groundTruth);

        // Find rank of first relevant item (1-indexed)
        for (int i = 0; i < result.retrieved.size(); i++) {
            if (groundTruthSet.contains(result.retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }

        return 0.0; // No relevant items found
    }

    /**
     * Calculate percentile from sorted array.
     */
    private double calculatePercentile(double[] sortedValues, int percentile) {
        if (sortedValues.length == 0) return 0.0;

        double index = (percentile / 100.0) * (sortedValues.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sortedValues[lower];
        }

        // Linear interpolation
        double weight = index - lower;
        return sortedValues[lower] * (1 - weight) + sortedValues[upper] * weight;
    }

    /**
     * Query execution result.
     */
    static class QueryResult {
        final List<String> groundTruth;
        final List<String> retrieved;
        final Double clientLatencyMs;
        final Long serverLatencyMs;

        QueryResult(List<String> groundTruth, List<String> retrieved,
                    Double clientLatencyMs, Long serverLatencyMs) {
            this.groundTruth = groundTruth;
            this.retrieved = retrieved;
            this.clientLatencyMs = clientLatencyMs;
            this.serverLatencyMs = serverLatencyMs;
        }
    }
}
