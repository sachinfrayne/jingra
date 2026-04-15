package org.elasticsearch.jingra.analysis;

import org.elasticsearch.jingra.model.BenchmarkResult;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates benchmark visualization plots using XChart.
 * Creates recall vs latency scatter plots and throughput bar charts.
 */
public class PlotGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PlotGenerator.class);

    private final String outputDirectory;

    // Color palette (matching Python visualization)
    private static final Color ES_COLOR = new Color(244, 78, 152);  // #F04E98 Elastic pink
    private static final Color QDRANT_COLOR = new Color(0, 94, 184);  // #005EB8 Blue
    private static final Color MAX_LINE_COLOR = new Color(102, 187, 106);  // #66BB6A Green

    public PlotGenerator(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Generate recall vs latency scatter plot for a specific recall@N.
     *
     * @param resultsByEngine results grouped by engine
     * @param recallAtN recall label (e.g., "recall@100")
     * @param latencyMetric the latency metric to plot on X-axis (e.g., "latency_avg")
     * @throws IOException if plot generation fails
     */
    public void generateRecallVsLatencyPlot(
            Map<String, List<BenchmarkResult>> resultsByEngine,
            String recallAtN,
            String latencyMetric
    ) throws IOException {
        ensureDirectoryExists();

        // Generate both linear and log-scale versions
        generateRecallVsLatencyPlotWithScale(resultsByEngine, recallAtN, latencyMetric, false);
        generateRecallVsLatencyPlotWithScale(resultsByEngine, recallAtN, latencyMetric, true);
    }

    /**
     * Generate recall vs latency scatter plot with specified scale type.
     */
    private void generateRecallVsLatencyPlotWithScale(
            Map<String, List<BenchmarkResult>> resultsByEngine,
            String recallAtN,
            String latencyMetric,
            boolean logScale
    ) throws IOException {
        // Create chart
        String title = formatTitle(recallAtN, latencyMetric) + (logScale ? " (log scale)" : "");
        XYChart chart = new XYChartBuilder()
                .width(800)
                .height(500)
                .title(title)
                .xAxisTitle(formatAxisLabel(latencyMetric))
                .yAxisTitle("Recall")
                .build();

        // Customize styling
        customizeChart(chart, logScale);

        double maxRecall = 0.0;
        boolean hasData = false;

        // Plot each engine's data
        for (Map.Entry<String, List<BenchmarkResult>> entry : resultsByEngine.entrySet()) {
            String engine = entry.getKey();
            List<BenchmarkResult> results = entry.getValue();

            // Extract data points
            List<Double> latencies = new ArrayList<>();
            List<Double> recalls = new ArrayList<>();

            for (BenchmarkResult result : results) {
                Double latency = extractLatency(result, latencyMetric);
                Double recall = result.getMetricAsDouble("recall");

                if (latency != null && recall != null && latency > 0) {  // latency must be > 0 for log scale
                    latencies.add(latency);
                    recalls.add(recall);
                    maxRecall = Math.max(maxRecall, recall);
                }
            }

            if (!latencies.isEmpty()) {
                // Sort by latency for proper line plotting
                List<DataPoint> points = new ArrayList<>();
                for (int i = 0; i < latencies.size(); i++) {
                    points.add(new DataPoint(latencies.get(i), recalls.get(i)));
                }
                points.sort(Comparator.comparingDouble(p -> p.x));

                double[] xData = points.stream().mapToDouble(p -> p.x).toArray();
                double[] yData = points.stream().mapToDouble(p -> p.y).toArray();

                // Add series
                XYSeries series = chart.addSeries(engine, xData, yData);
                series.setMarker(SeriesMarkers.CIRCLE);
                series.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
                series.setMarkerColor(getEngineColor(engine));
                series.setLineColor(getEngineColor(engine));

                hasData = true;
            }
        }

        if (!hasData) {
            logger.warn("No data to plot for {} ({})", recallAtN, logScale ? "log" : "linear");
            return;
        }

        // Save plot
        String scaleSuffix = logScale ? "_log" : "";
        String filename = String.format("recall_vs_%s_%s%s.png", latencyMetric, sanitize(recallAtN), scaleSuffix);
        Path outputPath = Paths.get(outputDirectory, filename);
        BitmapEncoder.saveBitmap(chart, outputPath.toString(), BitmapEncoder.BitmapFormat.PNG);
        logger.info("Generated plot: {}", outputPath);
    }

    /**
     * Generate throughput overview bar chart comparing engines across actual recall values.
     *
     * @param resultsByRecallAt all results grouped by recall@N
     * @throws IOException if plot generation fails
     */
    public void generateThroughputOverview(
            Map<String, List<BenchmarkResult>> resultsByRecallAt
    ) throws IOException {
        if (resultsByRecallAt.isEmpty()) {
            logger.warn("No data for throughput overview");
            return;
        }

        ensureDirectoryExists();

        // Collect all results and group by rounded recall value and engine
        // Map: rounded recall -> engine -> max throughput result
        Map<String, Map<String, BenchmarkResult>> recallToEngineResults = new HashMap<>();

        for (List<BenchmarkResult> results : resultsByRecallAt.values()) {
            for (BenchmarkResult result : results) {
                Double recall = result.getMetricAsDouble("recall");
                Double throughput = result.getMetricAsDouble("throughput");

                if (recall == null || throughput == null) {
                    continue;
                }

                // Round recall to 2 decimal places (0.93, 0.85, etc.)
                String recallRounded = String.format("%.2f", recall);
                String engine = result.getEngine();

                // Keep the result with highest throughput for this engine at this recall level
                recallToEngineResults
                        .computeIfAbsent(recallRounded, k -> new HashMap<>())
                        .merge(engine, result, (existing, newResult) -> {
                            Double existingThroughput = existing.getMetricAsDouble("throughput");
                            Double newThroughput = newResult.getMetricAsDouble("throughput");
                            if (existingThroughput == null) return newResult;
                            if (newThroughput == null) return existing;
                            return newThroughput > existingThroughput ? newResult : existing;
                        });
            }
        }

        if (recallToEngineResults.isEmpty()) {
            logger.warn("No valid throughput data for overview");
            return;
        }

        // Sort recall values numerically
        List<String> sortedRecallValues = recallToEngineResults.keySet().stream()
                .sorted((a, b) -> Double.compare(Double.parseDouble(a), Double.parseDouble(b)))
                .collect(Collectors.toList());

        // Collect all unique engines across all recall levels
        Set<String> allEngines = recallToEngineResults.values().stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Build throughput series - each engine must have a value for each recall level
        Map<String, List<Double>> engineThroughputs = new LinkedHashMap<>();

        for (String recallValue : sortedRecallValues) {
            Map<String, BenchmarkResult> engineResults = recallToEngineResults.get(recallValue);

            for (String engine : allEngines) {
                BenchmarkResult result = engineResults.get(engine);
                Double throughput = result != null ? result.getMetricAsDouble("throughput") : null;

                if (throughput != null && throughput > 0) {
                    engineThroughputs.computeIfAbsent(engine, k -> new ArrayList<>()).add(throughput);
                } else {
                    // Add 0 for missing data to maintain alignment across all recall levels
                    engineThroughputs.computeIfAbsent(engine, k -> new ArrayList<>()).add(0.0);
                }
            }
        }

        // Check if we have any valid data
        boolean hasData = engineThroughputs.values().stream()
                .anyMatch(list -> list.stream().anyMatch(v -> v > 0));

        if (!hasData) {
            logger.warn("No valid throughput data for overview");
            return;
        }

        // Create bar chart
        CategoryChart chart = new CategoryChartBuilder()
                .width(800)
                .height(500)
                .title("Throughput Comparison")
                .xAxisTitle("Recall")
                .yAxisTitle("Throughput (QPS)")
                .build();

        // Customize styling
        customizeCategoryChart(chart);

        // Add series for each engine
        for (Map.Entry<String, List<Double>> entry : engineThroughputs.entrySet()) {
            String engine = entry.getKey();
            List<Double> throughputs = entry.getValue();

            chart.addSeries(engine, sortedRecallValues, throughputs)
                    .setFillColor(getEngineColor(engine));
        }

        // Save plot
        String filename = "throughput_overview.png";
        Path outputPath = Paths.get(outputDirectory, filename);
        BitmapEncoder.saveBitmap(chart, outputPath.toString(), BitmapEncoder.BitmapFormat.PNG);
        logger.info("Generated throughput overview: {}", outputPath);
    }

    /**
     * Extract latency with fallback from server_* to client metric.
     */
    private Double extractLatency(BenchmarkResult result, String metricName) {
        Double value = result.getMetricAsDouble(metricName);

        if (value == null && metricName.startsWith("server_")) {
            // Fallback: server_latency_median -> latency_median
            String clientMetric = metricName.substring("server_".length());
            value = result.getMetricAsDouble(clientMetric);
        }

        return value;
    }

    /**
     * Customize chart styling to match the reference image.
     */
    private void customizeChart(XYChart chart, boolean logScale) {
        Styler styler = chart.getStyler();
        styler.setLegendPosition(Styler.LegendPosition.InsideNE);
        styler.setMarkerSize(6);
        styler.setChartBackgroundColor(Color.WHITE);
        styler.setPlotBackgroundColor(Color.WHITE);

        // Set X-axis to logarithmic scale if requested
        if (logScale) {
            chart.getStyler().setXAxisLogarithmic(true);
        }
    }

    /**
     * Customize category chart styling for bar charts.
     */
    private void customizeCategoryChart(CategoryChart chart) {
        Styler styler = chart.getStyler();
        styler.setLegendPosition(Styler.LegendPosition.InsideNE);
        styler.setChartBackgroundColor(Color.WHITE);
        styler.setPlotBackgroundColor(Color.WHITE);
    }

    /**
     * Get color for engine.
     */
    private Color getEngineColor(String engine) {
        String lowerEngine = engine.toLowerCase();
        if (lowerEngine.contains("elasticsearch") || lowerEngine.contains("es")) {
            return ES_COLOR;
        } else if (lowerEngine.contains("qdrant") || lowerEngine.contains("qd")) {
            return QDRANT_COLOR;
        } else if (lowerEngine.contains("opensearch") || lowerEngine.contains("os")) {
            return new Color(0, 94, 184);  // Blue for OpenSearch
        }
        return Color.GRAY;
    }

    /**
     * Format chart title.
     */
    private String formatTitle(String recallAtN, String latencyMetric) {
        String metricPart = latencyMetric.replace("_", " ").replace("latency", "Latency");
        return String.format("Recall vs %s — %s", metricPart, recallAtN);
    }

    /**
     * Format axis label.
     */
    private String formatAxisLabel(String metric) {
        if (metric.contains("server")) {
            return metric.replace("_", " ").replace("server", "Server").replace("latency", "Latency") + " (ms)";
        }
        return metric.replace("_", " ").replace("latency", "Latency") + " (ms)";
    }

    /**
     * Sanitize filename.
     */
    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    private void ensureDirectoryExists() throws IOException {
        Path dir = Paths.get(outputDirectory);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * Simple data point holder for sorting.
     */
    private static class DataPoint {
        final double x;
        final double y;

        DataPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
