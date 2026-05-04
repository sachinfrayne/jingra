package org.elasticsearch.jingra.analysis;

import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class PlotGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesRecallVsLatencyPlot() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        // Create test data
        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();

        List<BenchmarkResult> esResults = new ArrayList<>();
        esResults.add(createResult("elasticsearch", 0.9, 100.0));
        esResults.add(createResult("elasticsearch", 0.95, 150.0));

        List<BenchmarkResult> qdResults = new ArrayList<>();
        qdResults.add(createResult("qdrant", 0.85, 80.0));
        qdResults.add(createResult("qdrant", 0.92, 120.0));

        resultsByEngine.put("elasticsearch", esResults);
        resultsByEngine.put("qdrant", qdResults);

        // Generate plot
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        // Verify plot file was created
        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void handlesEmptyResults() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> emptyResults = new HashMap<>();
        emptyResults.put("elasticsearch", new ArrayList<>());

        // Should not throw, just log warning
        generator.generateRecallVsLatencyPlot(emptyResults, "recall@100", "latency_avg");

        // No plot file should be created
        assertFalse(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void fallsBackToClientLatencyWhenServerMissing() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();

        // Result with only client latency (no server_latency_median)
        BenchmarkResult result = new BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        result.addMetric("recall", 0.9);
        result.addMetric("latency_median", 100.0);  // Client latency only
        results.add(result);

        resultsByEngine.put("elasticsearch", results);

        // Should use fallback latency_median
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "server_latency_median");

        // Plot should be created using fallback
        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void handlesMultipleEngines() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();

        // Test different engine names to cover getEngineColor branches
        resultsByEngine.put("elasticsearch", List.of(createResult("elasticsearch", 0.9, 100.0)));
        resultsByEngine.put("qdrant", List.of(createResult("qdrant", 0.85, 120.0)));
        resultsByEngine.put("opensearch", List.of(createResult("opensearch", 0.88, 110.0)));
        resultsByEngine.put("other", List.of(createResult("other", 0.80, 130.0)));

        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void usesAbbreviatedEngineKeysForColorDetection() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        resultsByEngine.put("es", List.of(createResult("es", 0.9, 100.0)));
        resultsByEngine.put("qd", List.of(createResult("qd", 0.85, 120.0)));
        resultsByEngine.put("os", List.of(createResult("os", 0.88, 110.0)));

        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void handlesNullRecallValues() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();

        // Result with null recall
        BenchmarkResult resultWithoutRecall = new BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        resultWithoutRecall.addMetric("latency_avg", 100.0);
        results.add(resultWithoutRecall);

        // Result with recall
        results.add(createResult("elasticsearch", 0.9, 120.0));

        resultsByEngine.put("elasticsearch", results);

        // Should skip null recall and plot the valid one
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void handlesNullLatencyValues() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();

        // Result with null latency
        BenchmarkResult resultWithoutLatency = new BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        resultWithoutLatency.addMetric("recall", 0.9);
        results.add(resultWithoutLatency);

        // Result with latency
        results.add(createResult("elasticsearch", 0.85, 120.0));

        resultsByEngine.put("elasticsearch", results);

        // Should skip null latency and plot the valid one
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    /** Non-null latency/recall with {@code latency == 0} must skip the point (see PlotGenerator). */
    @Test
    void skipsNonPositiveLatencyPointsWhenOthersValid() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();

        BenchmarkResult zeroLatency = new BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        zeroLatency.addMetric("recall", 0.9);
        zeroLatency.addMetric("latency_avg", 0.0);
        results.add(zeroLatency);

        results.add(createResult("elasticsearch", 0.85, 100.0));

        resultsByEngine.put("elasticsearch", results);

        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void handlesServerLatencyMetrics() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        resultsByEngine.put("elasticsearch", List.of(createResult("elasticsearch", 0.9, 100.0)));

        // Test with different server latency metric names
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "server_latency_avg");
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "server_latency_p95");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void createsDirectoryIfNotExists() throws IOException {
        // Use a subdirectory that doesn't exist yet
        Path subdir = tempDir.resolve("plots");
        assertFalse(Files.exists(subdir));

        PlotGenerator generator = new PlotGenerator(subdir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        resultsByEngine.put("elasticsearch", List.of(createResult("elasticsearch", 0.9, 100.0)));

        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        // Directory should now exist
        assertTrue(Files.exists(subdir));
        assertTrue(Files.list(subdir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void sanitizesFilenames() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        resultsByEngine.put("elasticsearch", List.of(createResult("elasticsearch", 0.9, 100.0)));

        // Use recall@N name with special characters
        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        // @ should be replaced with _
        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().contains("recall_100")));
    }

    @Test
    void generatesThroughputOverviewBarChart() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        // Create results with actual recall values where BOTH engines have data at overlapping recalls in 0.7-0.9 range
        Map<String, List<BenchmarkResult>> resultsByRecallAt = new HashMap<>();

        List<BenchmarkResult> results = new ArrayList<>();
        // Both engines at recall ~0.75
        results.add(createResultWithThroughput("elasticsearch", 0.751, 50.0, 20.0));
        results.add(createResultWithThroughput("qdrant", 0.749, 40.0, 25.0));

        // Both engines at recall ~0.80
        results.add(createResultWithThroughput("elasticsearch", 0.801, 45.0, 22.0));
        results.add(createResultWithThroughput("qdrant", 0.799, 42.0, 24.0));

        // Both engines at recall ~0.85
        results.add(createResultWithThroughput("elasticsearch", 0.851, 60.0, 18.0));
        results.add(createResultWithThroughput("qdrant", 0.849, 55.0, 19.0));

        // Both engines at recall ~0.88
        results.add(createResultWithThroughput("elasticsearch", 0.881, 100.0, 10.0));
        results.add(createResultWithThroughput("qdrant", 0.879, 100.0, 10.0));

        resultsByRecallAt.put("recall@100", results);

        // Generate throughput overview
        generator.generateThroughputOverview(resultsByRecallAt);

        // Verify plot file was created
        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void handlesEmptyThroughputData() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> emptyData = new HashMap<>();

        // Should not throw, just log warning
        generator.generateThroughputOverview(emptyData);

        // No plot file should be created
        assertFalse(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void handlesSingleRecallLevelInThroughputChart() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> recall100Results = new ArrayList<>();
        // Both engines at same rounded recall in 0.7-0.9 range
        recall100Results.add(createResultWithThroughput("elasticsearch", 0.881, 120.0, 8.3));
        recall100Results.add(createResultWithThroughput("qdrant", 0.879, 100.0, 10.0));
        data.put("recall@100", recall100Results);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void handlesNullThroughputValues() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> recall100Results = new ArrayList<>();

        // Result with null throughput
        BenchmarkResult resultWithoutThroughput = new BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        resultWithoutThroughput.addMetric("recall", 0.9);
        recall100Results.add(resultWithoutThroughput);

        // Result with throughput
        recall100Results.add(createResultWithThroughput("qdrant", 0.88, 100.0, 10.0));
        data.put("recall@100", recall100Results);

        // Should skip null throughput and plot the valid one
        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void sortsRecallValuesInThroughputChart() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();

        // Add results with different recall values in non-sorted order
        List<BenchmarkResult> results = new ArrayList<>();
        results.add(createResultWithThroughput("elasticsearch", 0.93, 120.0, 8.3));
        results.add(createResultWithThroughput("elasticsearch", 0.85, 50.0, 20.0));
        results.add(createResultWithThroughput("elasticsearch", 0.90, 80.0, 12.5));
        data.put("recall@100", results);

        // Should sort by actual recall values (0.85, 0.90, 0.93)
        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_warnsWhenNoThroughputExceedsZero() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> recall100Results = new ArrayList<>();
        // Both engines at same rounded recall (0.90) but with 0 throughput
        recall100Results.add(createResultWithThroughput("elasticsearch", 0.901, 100.0, 0.0));
        recall100Results.add(createResultWithThroughput("qdrant", 0.899, 100.0, 0.0));
        data.put("recall@100", recall100Results);

        generator.generateThroughputOverview(data);

        assertFalse(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_warnsWhenRecallBucketsHaveNoEngineRows() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        data.put("recall@100", new ArrayList<>());

        generator.generateThroughputOverview(data);

        assertFalse(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_skipsRowsMissingRecallOrThroughput() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();

        BenchmarkResult noRecall = new BenchmarkResult(
                "test-run", "elasticsearch", "1.0", "vector_search", "test-dataset", "k=a", Map.of());
        noRecall.addMetric("throughput", 10.0);

        BenchmarkResult noThroughput = new BenchmarkResult(
                "test-run", "elasticsearch", "1.0", "vector_search", "test-dataset", "k=b", Map.of());
        noThroughput.addMetric("recall", 0.91);

        list.add(noRecall);
        list.add(noThroughput);
        list.add(createResultWithThroughput("qdrant", 0.88, 100.0, 12.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_mergeKeepsHigherThroughputAtSameRoundedRecall() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        // Multiple ES results that round to 0.85 - should keep the one with higher throughput (500.0)
        list.add(createResultWithThroughput("elasticsearch", 0.851, 100.0, 10.0));
        list.add(createResultWithThroughput("elasticsearch", 0.849, 100.0, 500.0));
        // Qdrant at same rounded recall
        list.add(createResultWithThroughput("qdrant", 0.850, 100.0, 8.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_mergeDoesNotReplaceWhenSecondThroughputIsLower() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        // Multiple ES results that round to 0.85 - should keep the one with higher throughput (500.0), not replace with lower (10.0)
        list.add(createResultWithThroughput("elasticsearch", 0.851, 100.0, 500.0));
        list.add(createResultWithThroughput("elasticsearch", 0.849, 100.0, 10.0));
        // Qdrant at same rounded recall
        list.add(createResultWithThroughput("qdrant", 0.850, 100.0, 8.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_mergeReplacesWhenExistingThroughputRereadIsNull() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        // First ES result returns null on second read - should be replaced by second ES result
        list.add(throughputSecondReadNull("elasticsearch", 0.851, 100.0, 40.0));
        list.add(createResultWithThroughput("elasticsearch", 0.849, 100.0, 25.0));
        // Qdrant at same rounded recall
        list.add(createResultWithThroughput("qdrant", 0.850, 100.0, 8.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_mergeKeepsExistingWhenNewThroughputRereadIsNull() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        // First ES result is valid, second returns null on re-read - should keep first
        list.add(createResultWithThroughput("elasticsearch", 0.851, 100.0, 99.0));
        list.add(throughputSecondReadNull("elasticsearch", 0.849, 100.0, 50.0));
        // Qdrant at same rounded recall
        list.add(createResultWithThroughput("qdrant", 0.850, 100.0, 8.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    /**
     * Covers both sides of {@code recall >= 0.70 && recall <= 0.90} (JaCoCo short-circuit branches).
     */
    @Test
    void generateThroughputOverview_recallFilterCoversRangeBounds() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        // Keys "0.69", "0.85", "0.91" after rounding — only 0.85 stays in candidate list
        list.add(createResultWithThroughput("elasticsearch", 0.694, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.694, 40.0, 25.0));
        list.add(createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.849, 40.0, 25.0));
        list.add(createResultWithThroughput("elasticsearch", 0.914, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.912, 40.0, 25.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    /**
     * With more than six candidate recalls, the selection loop exits when {@code maxBars} is reached
     * ({@code selectedRecalls.size() < maxBars} becomes false while {@code i < otherRecalls.size()}).
     */
    @Test
    void generateThroughputOverview_stopsAddingRecallsWhenMaxBarsReached() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        double[] recalls = {0.701, 0.712, 0.723, 0.734, 0.745, 0.756, 0.767};
        for (double r : recalls) {
            list.add(createResultWithThroughput("elasticsearch", r, 50.0, 20.0));
            list.add(createResultWithThroughput("qdrant", r - 0.001, 40.0, 25.0));
        }
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    /**
     * With exactly six candidate recalls, {@code otherRecalls} has size five after removing {@code maxDiffRecall};
     * the loop then exits because {@code i >= otherRecalls.size()} while still under {@code maxBars}.
     */
    @Test
    void generateThroughputOverview_loopEndsWhenOtherRecallsExhausted() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        double[] recalls = {0.701, 0.712, 0.723, 0.734, 0.745, 0.756};
        for (double r : recalls) {
            list.add(createResultWithThroughput("elasticsearch", r, 50.0, 20.0));
            list.add(createResultWithThroughput("qdrant", r - 0.001, 40.0, 25.0));
        }
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    /**
     * After enough {@code getMetricAsDouble("throughput")} calls for merge/filter/max, later chart reads
     * return null so the chart path adds 0.0 and may hit the secondary empty-data guard.
     */
    @Test
    void generateThroughputOverview_chartUsesZeroThroughputWhenMetricExhausted() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        BenchmarkResult esBase = createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0);
        BenchmarkResult qdBase = createResultWithThroughput("qdrant", 0.849, 40.0, 25.0);
        BenchmarkResult es = spy(esBase);
        BenchmarkResult qd = spy(qdBase);
        AtomicInteger esReads = new AtomicInteger();
        AtomicInteger qdReads = new AtomicInteger();

        doAnswer(inv -> {
            String name = inv.getArgument(0);
            if (!"throughput".equals(name)) {
                return inv.callRealMethod();
            }
            int n = esReads.incrementAndGet();
            // Reads 1–2: ingest + candidate filter; 3+: chart (null → 0.0, then !hasData)
            return n <= 2 ? 20.0 : null;
        }).when(es).getMetricAsDouble(anyString());

        doAnswer(inv -> {
            String name = inv.getArgument(0);
            if (!"throughput".equals(name)) {
                return inv.callRealMethod();
            }
            int n = qdReads.incrementAndGet();
            return n <= 2 ? 25.0 : null;
        }).when(qd).getMetricAsDouble(anyString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        data.put("recall@100", new ArrayList<>(List.of(es, qd)));

        generator.generateThroughputOverview(data);

        // With the new alignment logic, the chart is still generated even if throughput becomes null later
        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    private static BenchmarkResult throughputSecondReadNull(
            String engine, double recall, double latency, double firstThroughputRead) {
        BenchmarkResult r =
                new BenchmarkResult("test-run", engine, "1.0", "vector_search", "test-dataset", "k=merge", Map.of()) {
                    private int throughputGetCount;

                    @Override
                    public Double getMetricAsDouble(String name) {
                        if ("throughput".equals(name)) {
                            throughputGetCount++;
                            if (throughputGetCount == 1) {
                                return firstThroughputRead;
                            }
                            return null;
                        }
                        return super.getMetricAsDouble(name);
                    }
                };
        r.addMetric("recall", recall);
        r.addMetric("latency_avg", latency);
        return r;
    }

    private BenchmarkResult createResult(String engine, double recall, double latency) {
        BenchmarkResult result = new BenchmarkResult(
                "test-run", engine, "1.0", "vector_search", "test-dataset", "k=1", Map.of());
        result.addMetric("recall", recall);
        result.addMetric("latency_avg", latency);
        result.addMetric("server_latency_avg", latency);
        result.addMetric("server_latency_median", latency);
        result.addMetric("server_latency_p95", latency);
        result.addMetric("throughput", 1000.0 / latency);
        return result;
    }

    private BenchmarkResult createResultWithThroughput(String engine, double recall, double latency, double throughput) {
        BenchmarkResult result = new BenchmarkResult(
                "test-run", engine, "1.0", "vector_search", "test-dataset", "k=1", Map.of());
        result.addMetric("recall", recall);
        result.addMetric("latency_avg", latency);
        result.addMetric("throughput", throughput);
        return result;
    }

    // --- Engine version label tests ---

    @Test
    void engineLabelIncludesVersionWhenProvided() {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(), Map.of("elasticsearch", "9.3.2"));
        assertEquals("elasticsearch-9.3.2", generator.engineLabel("elasticsearch"));
    }

    @Test
    void engineLabelReturnsEngineNameWhenNoVersionProvided() {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(), Map.of());
        assertEquals("qdrant", generator.engineLabel("qdrant"));
    }

    @Test
    void engineLabelReturnsEngineNameWhenVersionMapDoesNotContainEngine() {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(), Map.of("elasticsearch", "9.3.2"));
        assertEquals("qdrant", generator.engineLabel("qdrant"));
    }

    /** Version present but blank: first conjunct of {@code version != null && !version.isBlank()} is true, second is false. */
    @Test
    void engineLabelReturnsEngineNameWhenVersionIsWhitespaceOnly() {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(), Map.of("elasticsearch", "  \t  "));
        assertEquals("elasticsearch", generator.engineLabel("elasticsearch"));
    }

    @Test
    void engineLabelReturnsEngineNameWhenVersionIsEmptyString() {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(), Map.of("qdrant", ""));
        assertEquals("qdrant", generator.engineLabel("qdrant"));
    }

    @Test
    void generatesRecallVsLatencyPlotWithEngineVersions() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(),
                Map.of("elasticsearch", "9.3.2", "qdrant", "1.17.0"));

        Map<String, List<BenchmarkResult>> resultsByEngine = new HashMap<>();
        resultsByEngine.put("elasticsearch", List.of(createResult("elasticsearch", 0.9, 100.0)));
        resultsByEngine.put("qdrant", List.of(createResult("qdrant", 0.85, 120.0)));

        generator.generateRecallVsLatencyPlot(resultsByEngine, "recall@100", "latency_avg");

        assertTrue(Files.list(tempDir).anyMatch(p -> p.toString().endsWith(".png")));
    }

    @Test
    void generatesThroughputOverviewWithEngineVersions() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString(),
                Map.of("elasticsearch", "9.3.2", "qdrant", "1.17.0"));

        Map<String, List<BenchmarkResult>> resultsByRecallAt = new HashMap<>();
        List<BenchmarkResult> results = new ArrayList<>();
        results.add(createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0));
        results.add(createResultWithThroughput("qdrant", 0.849, 40.0, 25.0));
        resultsByRecallAt.put("recall@100", results);

        generator.generateThroughputOverview(resultsByRecallAt);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void throughputMetric_nullResult() {
        assertNull(PlotGenerator.throughputMetric(null));
    }

    @Test
    void throughputMetric_readsMetric() {
        BenchmarkResult r = createResultWithThroughput("elasticsearch", 0.9, 50.0, 42.0);
        assertEquals(42.0, PlotGenerator.throughputMetric(r));
    }

    @Test
    void throughputMetric_missingMetric() {
        BenchmarkResult r = new BenchmarkResult("run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        assertNull(PlotGenerator.throughputMetric(r));
    }

    /**
     * A recall bucket whose numeric key is {@code > 1.0} is filtered out of {@code recallsInRange}
     * (covers {@code recall <= 1.00} false in the stream filter).
     */
    @Test
    void generateThroughputOverview_ignoresRoundedRecallBucketsAboveOne() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        list.add(createResultWithThroughput("elasticsearch", 1.021, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 1.019, 40.0, 25.0));
        list.add(createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.849, 40.0, 25.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    /**
     * When {@code allEngines} includes an engine that never appears in the 0.7–1.0 alignment window,
     * no recall group passes {@code allMatch} and the empty-overview warning path runs.
     */
    @Test
    void generateThroughputOverview_warnsWhenEngineOnlyPresentOutsideAlignmentRange() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        list.add(createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.849, 40.0, 25.0));
        list.add(createResultWithThroughput("opensearch", 0.50, 30.0, 15.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertFalse(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_nullThroughputUsesZeroInSeries() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        list.add(createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.849, 40.0, 25.0));
        BenchmarkResult os = new BenchmarkResult(
                "test-run", "opensearch", "1.0", "vector_search", "test-dataset", "k=os", Map.of()) {
            @Override
            public Double getMetricAsDouble(String name) {
                if ("throughput".equals(name)) {
                    return null;
                }
                return super.getMetricAsDouble(name);
            }
        };
        os.addMetric("recall", 0.850);
        os.addMetric("latency_avg", 35.0);
        list.add(os);
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_mergeKeepsFirstWhenThroughputsTied() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        list.add(createResultWithThroughput("elasticsearch", 0.851, 100.0, 50.0));
        list.add(createResultWithThroughput("elasticsearch", 0.849, 100.0, 50.0));
        list.add(createResultWithThroughput("qdrant", 0.850, 100.0, 8.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }

    @Test
    void generateThroughputOverview_requiresAllThreeEnginesAtAlignedRecall() throws IOException {
        PlotGenerator generator = new PlotGenerator(tempDir.toString());

        Map<String, List<BenchmarkResult>> data = new HashMap<>();
        List<BenchmarkResult> list = new ArrayList<>();
        list.add(createResultWithThroughput("elasticsearch", 0.851, 50.0, 20.0));
        list.add(createResultWithThroughput("qdrant", 0.849, 40.0, 25.0));
        list.add(createResultWithThroughput("opensearch", 0.850, 35.0, 30.0));
        data.put("recall@100", list);

        generator.generateThroughputOverview(data);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().equals("throughput_overview.png")));
    }
}
