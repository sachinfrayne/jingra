package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.analysis.BenchmarkComparator;
import org.elasticsearch.jingra.analysis.ComparisonResult;
import org.elasticsearch.jingra.analysis.CsvExporter;
import org.elasticsearch.jingra.analysis.PlotGenerator;
import org.elasticsearch.jingra.analysis.ResultsQuerier;
import org.elasticsearch.jingra.config.AnalysisConfig;
import org.elasticsearch.jingra.config.ConfigLoader;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Command to analyze benchmark results and generate comparison reports.
 */
public final class AnalyzeCommand {
    private static final Logger logger = LoggerFactory.getLogger(AnalyzeCommand.class);

    private AnalyzeCommand() {}

    // Factory injection for testing
    static Function<Map<String, Object>, ElasticsearchEngine> resultsEngineFactory =
            AnalyzeCommand::createResultsEngine;

    static Function<String, PlotGenerator> plotGeneratorFactory = PlotGenerator::new;

    /**
     * Run analysis command with default factory.
     */
    public static void run(JingraConfig config) throws Exception {
        run(config, resultsEngineFactory);
    }

    /**
     * Run analysis command with injected factory (for testing).
     */
    static void run(
            JingraConfig config,
            Function<Map<String, Object>, ElasticsearchEngine> resultsEngineFactory
    ) throws Exception {
        ConfigLoader.validateForAnalysis(config);
        AnalysisConfig ac = config.getAnalysis();

        // Create results engine
        ElasticsearchEngine resultsEngine = resultsEngineFactory.apply(ac.getResultsCluster());

        try {
            logger.info("Connecting to results cluster...");
            if (!resultsEngine.connect()) {
                throw new RuntimeException("Failed to connect to results cluster");
            }

            // Query results
            String indexName = (String) ac.getResultsCluster().getOrDefault("index", "jingra-results");
            ResultsQuerier querier = new ResultsQuerier(resultsEngine, indexName);

            logger.info("Querying results for run_id: {}, engines: {}", ac.getRunId(), ac.getEngines());
            List<BenchmarkResult> allResults = querier.queryByRunId(ac.getRunId(), ac.getEngines());
            logger.info("Found {} results", allResults.size());

            if (allResults.isEmpty()) {
                logger.warn("No results found for run_id: {}", ac.getRunId());
                return;
            }

            // Group by engine and recall@N
            Map<String, List<BenchmarkResult>> byEngine = querier.groupByEngine(allResults);
            Map<String, List<BenchmarkResult>> byRecallAt = querier.groupByRecallLabel(allResults);

            // Verify we have the requested engines
            for (String engine : ac.getEngines()) {
                if (!byEngine.containsKey(engine)) {
                    logger.warn("No results found for engine: {}", engine);
                }
            }

            // Create output directory
            File outDir = new File(ac.getOutputDirectory());
            outDir.mkdirs();

            // Snapshot existing files before generating new ones
            Set<String> existingFiles = snapshotFiles(outDir);

            // Export results
            CsvExporter csvExporter = new CsvExporter(ac.getOutputDirectory());

            String baselineEngine = ac.getEngines().get(0);
            boolean multiEngine = ac.getEngines().size() >= 2;
            String targetEngine = multiEngine ? ac.getEngines().get(1) : null;

            if (multiEngine) {
                logger.info("Comparing {} vs {}", baselineEngine, targetEngine);
            } else {
                logger.info("Analyzing results for engine: {}", baselineEngine);
            }

            List<String> latencyMetrics = ac.getLatencyMetrics();
            logger.info("Including {} latency metric(s) as columns: {}", latencyMetrics.size(), latencyMetrics);

            // For each recall@N: export all results with all latency metrics as columns
            for (Map.Entry<String, List<BenchmarkResult>> entry : byRecallAt.entrySet()) {
                String recallAt = entry.getKey();
                logger.info("Processing {}", recallAt);

                List<BenchmarkResult> resultsForRecallAt = entry.getValue();

                csvExporter.exportAllResults(resultsForRecallAt, recallAt, latencyMetrics, baselineEngine, recallAt + "_full_results.csv");
                if (multiEngine) {
                    csvExporter.exportSpeedupSummary(resultsForRecallAt, recallAt, latencyMetrics, baselineEngine, recallAt + "_summary.csv");
                }

                logger.info("Exported {} results for {}", resultsForRecallAt.size(), recallAt);
            }

            // Summary comparison requires 2 engines
            if (multiEngine) {
                BenchmarkComparator comparator = new BenchmarkComparator(latencyMetrics.get(0));
                Map<String, ComparisonResult> maxRecallComparisons = new HashMap<>();

                for (String recallAt : byRecallAt.keySet()) {
                    List<BenchmarkResult> baseline = filterByEngine(byRecallAt.get(recallAt), baselineEngine);
                    List<BenchmarkResult> target = filterByEngine(byRecallAt.get(recallAt), targetEngine);

                    if (baseline.isEmpty() || target.isEmpty()) {
                        continue;
                    }

                    Map<String, BenchmarkResult> maxPoints = comparator.findMaxRecallByEngine(
                            Map.of(
                                    baselineEngine, baseline,
                                    targetEngine, target
                            )
                    );

                    // Max points exist for both engines: lists were non-empty above
                    BenchmarkResult baselineMax = Objects.requireNonNull(maxPoints.get(baselineEngine));
                    BenchmarkResult targetMax = Objects.requireNonNull(maxPoints.get(targetEngine));

                    List<ComparisonResult> singleComparison = comparator.compare(
                            List.of(baselineMax),
                            List.of(targetMax),
                            recallAt
                    );

                    if (!singleComparison.isEmpty()) {
                        maxRecallComparisons.put(recallAt, singleComparison.get(0));
                    }
                }

                if (!maxRecallComparisons.isEmpty()) {
                    csvExporter.exportSummaryComparison(maxRecallComparisons, "summary_comparison.csv");
                    csvExporter.exportThroughputComparison(maxRecallComparisons, "throughput_comparison.csv");
                }
            }

            // Generate plots
            if (ac.isGeneratePlots()) {
                logger.info("Generating plots...");
                PlotGenerator plotter = plotGeneratorFactory.apply(ac.getOutputDirectory());

                // Generate recall vs latency plots for each recall@N and latency metric
                for (Map.Entry<String, List<BenchmarkResult>> entry : byRecallAt.entrySet()) {
                    String recallAt = entry.getKey();

                    // Group by engine for this recall level
                    Map<String, List<BenchmarkResult>> engineResults = new HashMap<>();
                    for (BenchmarkResult result : entry.getValue()) {
                        engineResults.computeIfAbsent(result.getEngine(), k -> new ArrayList<>()).add(result);
                    }

                    // Generate plot for each latency metric
                    for (String latencyMetric : latencyMetrics) {
                        try {
                            plotter.generateRecallVsLatencyPlot(engineResults, recallAt, latencyMetric);
                        } catch (Exception e) {
                            logger.warn("Failed to generate plot for {} with {}: {}", recallAt, latencyMetric, e.getMessage());
                        }
                    }
                }

                // Generate throughput overview chart using actual recall values
                try {
                    plotter.generateThroughputOverview(byRecallAt);
                } catch (Exception e) {
                    logger.warn("Failed to generate throughput overview: {}", e.getMessage());
                }

                logger.info("Plot generation complete");
            }

            // Report generated files
            Set<String> generatedFiles = findGeneratedFiles(outDir, existingFiles);
            logger.info("Analysis complete! Output in: {}", ac.getOutputDirectory());
            if (!generatedFiles.isEmpty()) {
                logger.info("Generated {} file(s):", generatedFiles.size());
                for (String file : generatedFiles) {
                    Path filePath = outDir.toPath().resolve(file);
                    long sizeKB = filePath.toFile().length() / 1024;
                    logger.info("  - {} ({} KB)", file, sizeKB);
                }
            }

        } finally {
            resultsEngine.close();
        }
    }

    private static List<BenchmarkResult> filterByEngine(List<BenchmarkResult> results, String engine) {
        return results.stream()
                .filter(r -> engine.equals(r.getEngine()))
                .collect(Collectors.toList());
    }

    /**
     * Snapshot files currently in the output directory.
     */
    private static Set<String> snapshotFiles(File directory) {
        Set<String> files = new HashSet<>();
        if (directory.exists() && directory.isDirectory()) {
            File[] fileList = directory.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    if (file.isFile()) {
                        files.add(file.getName());
                    }
                }
            }
        }
        return files;
    }

    /**
     * Find files that were generated (new or modified) since the snapshot.
     */
    private static Set<String> findGeneratedFiles(File directory, Set<String> existingFiles) {
        Set<String> generated = new LinkedHashSet<>();
        if (directory.exists() && directory.isDirectory()) {
            File[] fileList = directory.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    if (file.isFile() && !existingFiles.contains(file.getName())) {
                        generated.add(file.getName());
                    }
                }
            }
        }
        return generated;
    }

    static ElasticsearchEngine createResultsEngine(Map<String, Object> config) {
        return new ElasticsearchEngine(config);
    }
}
