package org.elasticsearch.jingra.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.elasticsearch.jingra.model.BenchmarkResult;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Exports benchmark comparison results to CSV files.
 */
public class CsvExporter {
    private final String outputDirectory;

    public CsvExporter(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Ascending recall order with null recalls after non-null (stable tie-break for two nulls).
     * Package-private for direct unit tests of all branches.
     */
    static int compareRecallForSort(Double recallA, Double recallB) {
        if (recallA == null && recallB == null) {
            return 0;
        }
        if (recallA == null) {
            return 1;
        }
        if (recallB == null) {
            return -1;
        }
        return Double.compare(recallA, recallB);
    }

    /**
     * Export all benchmark results with speedup comparison.
     * Each row is one result from one engine with one param configuration.
     * Speedup is calculated for matching recall levels across engines.
     * Results are sorted by recall ascending.
     *
     * @param results list of benchmark results
     * @param recallAtN recall label (e.g., "recall@100")
     * @param latencyMetrics list of latency metrics to extract as separate columns
     * @param baselineEngine name of baseline engine for speedup calculation
     * @param filename output filename
     * @throws IOException if export fails
     */
    public void exportAllResults(
            List<BenchmarkResult> results,
            String recallAtN,
            List<String> latencyMetrics,
            String baselineEngine,
            String filename
    ) throws IOException {
        ensureDirectoryExists();
        Path outputPath = Paths.get(outputDirectory, filename);

        // Sort results by recall ascending
        List<BenchmarkResult> sortedResults = new java.util.ArrayList<>(results);
        sortedResults.sort(
                (a, b) -> compareRecallForSort(a.getMetricAsDouble("recall"), b.getMetricAsDouble("recall")));

        // Build header with all latency metric columns
        List<String> headers = new java.util.ArrayList<>();
        headers.add("RecallAtN");
        headers.add("Engine");
        headers.add("ParamKey");
        headers.add("Recall");
        headers.add("Recall Rounded");

        // Add column for each latency metric
        for (String metric : latencyMetrics) {
            headers.add(formatColumnName(metric));
        }

        headers.add("Throughput");
        headers.add("Speedup");

        // Build index for speedup lookup: rounded_recall -> engine -> best result
        // Use regular throughput for consistency with what's displayed in CSV
        Map<String, Map<String, BenchmarkResult>> speedupIndex = buildSpeedupIndex(sortedResults);

        try (FileWriter writer = new FileWriter(outputPath.toFile());
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(headers.toArray(new String[0]))
                     .build())) {

            for (BenchmarkResult result : sortedResults) {
                List<Object> record = new java.util.ArrayList<>();

                Double recall = result.getMetricAsDouble("recall");
                String recallRounded = formatRecallRounded(recall);

                record.add(recallAtN);
                record.add(result.getEngine());
                record.add(result.getParamKey());
                record.add(formatNullableDouble(recall));
                record.add(recallRounded);

                // Extract each latency metric with fallback
                for (String metric : latencyMetrics) {
                    Double latency = extractLatencyWithFallback(result, metric);
                    record.add(formatNullableDouble(latency));
                }

                // Throughput uses first latency metric's logic
                Double throughput = extractThroughput(result, latencyMetrics.get(0));
                record.add(formatNullableDouble(throughput));

                // Calculate speedup for the faster engine (neutral, not baseline-centric)
                String speedup = calculateSpeedup(result, recallRounded, speedupIndex);
                record.add(speedup);

                printer.printRecord(record);
            }
        }
    }

    /**
     * Export speedup summary containing rows for aligned recall values where both engines have data.
     * For each speedup comparison, includes BOTH the faster engine's row (with speedup value)
     * and the slower engine's row (without speedup value).
     * Results are sorted by recall ascending.
     *
     * @param results list of benchmark results
     * @param recallAtN recall label (e.g., "recall@100")
     * @param latencyMetrics list of latency metrics to extract as separate columns
     * @param baselineEngine name of baseline engine for speedup calculation
     * @param filename output filename
     * @throws IOException if export fails
     */
    public void exportSpeedupSummary(
            List<BenchmarkResult> results,
            String recallAtN,
            List<String> latencyMetrics,
            String baselineEngine,
            String filename
    ) throws IOException {
        ensureDirectoryExists();
        Path outputPath = Paths.get(outputDirectory, filename);

        // Sort results by recall ascending
        List<BenchmarkResult> sortedResults = new java.util.ArrayList<>(results);
        sortedResults.sort(
                (a, b) -> compareRecallForSort(a.getMetricAsDouble("recall"), b.getMetricAsDouble("recall")));

        // Build header with all latency metric columns
        List<String> headers = new java.util.ArrayList<>();
        headers.add("RecallAtN");
        headers.add("Engine");
        headers.add("ParamKey");
        headers.add("Recall");
        headers.add("Recall Rounded");

        // Add column for each latency metric
        for (String metric : latencyMetrics) {
            headers.add(formatColumnName(metric));
        }

        headers.add("Throughput");
        headers.add("Speedup");

        // Build index for speedup lookup
        Map<String, Map<String, BenchmarkResult>> speedupIndex = buildSpeedupIndex(sortedResults);

        try (FileWriter writer = new FileWriter(outputPath.toFile());
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(headers.toArray(new String[0]))
                     .build())) {

            for (BenchmarkResult result : sortedResults) {
                Double recall = result.getMetricAsDouble("recall");
                String recallRounded = formatRecallRounded(recall);

                // Check if this result is part of a speedup comparison (exactly 2 engines at this recall level)
                Map<String, BenchmarkResult> enginesAtRecall = speedupIndex.get(recallRounded);
                if (enginesAtRecall == null || enginesAtRecall.size() != 2) {
                    continue; // Need exactly 2 engines for comparison
                }

                // Check if current result is in the index (i.e., it was selected as best for its engine)
                BenchmarkResult indexedResult = enginesAtRecall.get(result.getEngine());
                if (indexedResult == null || !result.getParamKey().equals(indexedResult.getParamKey())) {
                    continue; // Not the indexed row for this engine
                }

                // Calculate speedup (will be empty for slower engine, but we include both rows)
                String speedup = calculateSpeedup(result, recallRounded, speedupIndex);

                // Build record
                List<Object> record = new java.util.ArrayList<>();
                record.add(recallAtN);
                record.add(result.getEngine());
                record.add(result.getParamKey());
                record.add(formatNullableDouble(recall));
                record.add(recallRounded);

                // Extract each latency metric with fallback
                for (String metric : latencyMetrics) {
                    Double latency = extractLatencyWithFallback(result, metric);
                    record.add(formatNullableDouble(latency));
                }

                // Throughput uses first latency metric's logic
                Double throughput = extractThroughput(result, latencyMetrics.get(0));
                record.add(formatNullableDouble(throughput));
                record.add(speedup);

                printer.printRecord(record);
            }
        }
    }

    /**
     * Build index: rounded_recall -> engine -> best result (highest throughput).
     * When multiple results from same engine have same rounded recall, pick highest throughput.
     */
    private Map<String, Map<String, BenchmarkResult>> buildSpeedupIndex(
            List<BenchmarkResult> results
    ) {
        Map<String, Map<String, BenchmarkResult>> index = new java.util.HashMap<>();

        for (BenchmarkResult result : results) {
            Double recall = result.getMetricAsDouble("recall");
            if (recall == null) continue;

            String recallRounded = formatRecallRounded(recall);

            // Use regular throughput (not server_throughput) to match what's displayed in CSV
            Double throughput = result.getMetricAsDouble("throughput");
            if (throughput == null) continue;

            Map<String, BenchmarkResult> engineMap = index.computeIfAbsent(recallRounded, k -> new java.util.HashMap<>());
            String engine = result.getEngine();

            // Keep result with highest throughput for this engine at this recall level
            BenchmarkResult existing = engineMap.get(engine);
            if (existing == null) {
                engineMap.put(engine, result);
            } else {
                Double existingThroughput = existing.getMetricAsDouble("throughput");
                if (existingThroughput == null || throughput > existingThroughput) {
                    engineMap.put(engine, result);
                }
            }
        }

        return index;
    }

    /**
     * Calculate speedup: faster_throughput / slower_throughput
     * Engine-neutral: whichever engine is faster gets the speedup value.
     * Only calculates when:
     * - Exactly 2 engines present at this recall level
     * - Current result is the one selected for the index (highest throughput for this engine)
     * - Current result is the faster of the two engines
     */
    private String calculateSpeedup(
            BenchmarkResult result,
            String recallRounded,
            Map<String, Map<String, BenchmarkResult>> speedupIndex
    ) {
        Map<String, BenchmarkResult> enginesAtRecall = speedupIndex.get(recallRounded);
        if (enginesAtRecall == null || enginesAtRecall.size() != 2) {
            return ""; // Need exactly 2 engines for comparison
        }

        // Check if current result is in the index (i.e., it was selected as best for its engine)
        BenchmarkResult indexedResult = enginesAtRecall.get(result.getEngine());
        if (indexedResult == null || !result.getParamKey().equals(indexedResult.getParamKey())) {
            return ""; // Not the indexed row for this engine
        }

        // Get throughput for current result
        Double currentThroughput = result.getMetricAsDouble("throughput");
        if (currentThroughput == null || currentThroughput == 0) {
            return "";
        }

        // Find the other engine's result
        BenchmarkResult otherResult = null;
        for (Map.Entry<String, BenchmarkResult> entry : enginesAtRecall.entrySet()) {
            if (!entry.getKey().equals(result.getEngine())) {
                otherResult = entry.getValue();
                break;
            }
        }

        if (otherResult == null) {
            return "";
        }

        Double otherThroughput = otherResult.getMetricAsDouble("throughput");
        if (otherThroughput == null || otherThroughput == 0) {
            return "";
        }

        // Only show speedup on the FASTER engine's row
        if (currentThroughput <= otherThroughput) {
            return ""; // This engine is slower or equal
        }

        // Speedup = faster_throughput / slower_throughput
        double speedup = currentThroughput / otherThroughput;
        return String.valueOf(Math.round(speedup));
    }

    /**
     * Format recall as rounded string (2 decimal places).
     */
    private String formatRecallRounded(Double recall) {
        if (recall == null) return "N/A";
        return String.format("%.2f", recall);
    }

    /**
     * Format metric name into column header.
     * Examples: "server_latency_median" -> "Server_Latency_Median"
     *           "latency_avg" -> "Latency_Avg"
     */
    private String formatColumnName(String metricName) {
        String[] parts = metricName.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("_");
            sb.append(parts[i].substring(0, 1).toUpperCase());
            if (parts[i].length() > 1) {
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Export detailed comparison CSV with separate rows for baseline and target.
     * Columns: RecallAtN, ParamKey, Engine, Recall, Latency, Throughput, RecallDiff, LatencySpeedup, ThroughputSpeedup
     *
     * @param comparisons list of comparison results
     * @param filename output filename
     * @throws IOException if export fails
     */
    public void exportDetailedComparison(List<ComparisonResult> comparisons, String filename) throws IOException {
        ensureDirectoryExists();
        Path outputPath = Paths.get(outputDirectory, filename);

        try (FileWriter writer = new FileWriter(outputPath.toFile());
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("RecallAtN", "ParamKey", "Engine", "Recall", "Latency", "Throughput",
                             "RecallDiff", "LatencySpeedup", "ThroughputSpeedup")
                     .build())) {

            for (ComparisonResult comparison : comparisons) {
                // Baseline row
                printer.printRecord(
                        comparison.getRecallAtN(),
                        comparison.getParamKey(),
                        comparison.getBaselineEngine(),
                        formatDouble(comparison.getBaselineRecall()),
                        formatNullableDouble(comparison.getBaselineLatency()),
                        formatNullableDouble(comparison.getBaselineThroughput()),
                        "",  // RecallDiff only on target row
                        "",  // LatencySpeedup only on target row
                        ""   // ThroughputSpeedup only on target row
                );

                // Target row with speedups
                printer.printRecord(
                        comparison.getRecallAtN(),
                        comparison.getParamKey(),
                        comparison.getTargetEngine(),
                        formatDouble(comparison.getTargetRecall()),
                        formatNullableDouble(comparison.getTargetLatency()),
                        formatNullableDouble(comparison.getTargetThroughput()),
                        formatDouble(comparison.getRecallDiff()),
                        formatSpeedup(comparison.getLatencySpeedup()),
                        formatSpeedup(comparison.getThroughputSpeedup())
                );
            }
        }
    }

    /**
     * Export summary comparison CSV with one row per recall@N.
     * Shows max recall points for each engine.
     *
     * @param comparisons map of recall@N to comparison result
     * @param filename output filename
     * @throws IOException if export fails
     */
    public void exportSummaryComparison(Map<String, ComparisonResult> comparisons, String filename) throws IOException {
        ensureDirectoryExists();
        Path outputPath = Paths.get(outputDirectory, filename);

        try (FileWriter writer = new FileWriter(outputPath.toFile());
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("RecallAtN", "BaselineEngine", "BaselineRecall", "BaselineLatency",
                             "TargetEngine", "TargetRecall", "TargetLatency", "LatencySpeedup",
                             "BaselineThroughput", "TargetThroughput", "ThroughputSpeedup")
                     .build())) {

            for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
                ComparisonResult c = entry.getValue();
                printer.printRecord(
                        c.getRecallAtN(),
                        c.getBaselineEngine(),
                        formatDouble(c.getBaselineRecall()),
                        formatNullableDouble(c.getBaselineLatency()),
                        c.getTargetEngine(),
                        formatDouble(c.getTargetRecall()),
                        formatNullableDouble(c.getTargetLatency()),
                        formatSpeedup(c.getLatencySpeedup()),
                        formatNullableDouble(c.getBaselineThroughput()),
                        formatNullableDouble(c.getTargetThroughput()),
                        formatSpeedup(c.getThroughputSpeedup())
                );
            }
        }
    }

    /**
     * Export throughput comparison CSV focused on QPS metrics.
     *
     * @param comparisons map of recall@N to comparison result
     * @param filename output filename
     * @throws IOException if export fails
     */
    public void exportThroughputComparison(Map<String, ComparisonResult> comparisons, String filename) throws IOException {
        ensureDirectoryExists();
        Path outputPath = Paths.get(outputDirectory, filename);

        try (FileWriter writer = new FileWriter(outputPath.toFile());
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("RecallAtN", "BaselineEngine", "BaselineThroughput",
                             "TargetEngine", "TargetThroughput", "ThroughputSpeedup")
                     .build())) {

            for (Map.Entry<String, ComparisonResult> entry : comparisons.entrySet()) {
                ComparisonResult c = entry.getValue();
                printer.printRecord(
                        c.getRecallAtN(),
                        c.getBaselineEngine(),
                        formatNullableDouble(c.getBaselineThroughput()),
                        c.getTargetEngine(),
                        formatNullableDouble(c.getTargetThroughput()),
                        formatSpeedup(c.getThroughputSpeedup())
                );
            }
        }
    }

    private void ensureDirectoryExists() throws IOException {
        Path dir = Paths.get(outputDirectory);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private String formatDouble(double value) {
        return String.format("%.4f", value);
    }

    private String formatNullableDouble(Double value) {
        if (value == null) {
            return "N/A";
        }
        return formatDouble(value);
    }

    private String formatSpeedup(double speedup) {
        if (Double.isNaN(speedup)) {
            return "N/A";
        }
        if (Double.isInfinite(speedup)) {
            return "Infinity";
        }
        return formatDouble(speedup);
    }

    /**
     * Extract latency metric with fallback from server_* to client metric.
     * E.g., "server_latency_median" falls back to "latency_median" if not found.
     */
    private Double extractLatencyWithFallback(BenchmarkResult result, String metricName) {
        Double value = result.getMetricAsDouble(metricName);

        if (value == null && metricName.startsWith("server_")) {
            // Fallback: server_latency_median -> latency_median
            String clientMetric = metricName.substring("server_".length());
            value = result.getMetricAsDouble(clientMetric);
        }

        return value;
    }

    /**
     * Extract throughput based on latency metric type.
     * Uses server_throughput if metric is server_latency_*, otherwise uses throughput.
     */
    private Double extractThroughput(BenchmarkResult result, String latencyMetric) {
        if (latencyMetric.startsWith("server_")) {
            Double serverThroughput = result.getMetricAsDouble("server_throughput");
            if (serverThroughput != null) {
                return serverThroughput;
            }
        }
        return result.getMetricAsDouble("throughput");
    }
}
