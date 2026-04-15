package org.elasticsearch.jingra.analysis;

import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkComparatorTest {

    @Test
    void extractLatency_matchesGetLatencyBehavior() {
        BenchmarkResult r = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        assertEquals(5.0, comparator.extractLatency(r), 0.0001);
    }

    @Test
    void extractThroughput_matchesGetThroughputBehavior() {
        BenchmarkResult r = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        assertEquals(200.0, comparator.extractThroughput(r), 0.0001);
    }

    @Test
    void compare_matchesByParamKey() {
        BenchmarkResult baseline1 = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult baseline2 = createResult("es", "k=200", 0.97, 12.0, 90.0, 6.0, 180.0);
        BenchmarkResult target1 = createResult("qd", "k=100", 0.93, 5.0, 200.0, null, null);
        BenchmarkResult target2 = createResult("qd", "k=200", 0.96, 6.0, 180.0, null, null);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline1, baseline2),
                List.of(target1, target2),
                "recall@100"
        );

        assertEquals(2, results.size());

        // Find result for k=100
        ComparisonResult r1 = results.stream()
                .filter(r -> r.getParamKey().equals("k=100"))
                .findFirst()
                .orElseThrow();

        assertEquals("es", r1.getBaselineEngine());
        assertEquals("qd", r1.getTargetEngine());
        assertEquals(0.95, r1.getBaselineRecall(), 0.0001);
        assertEquals(0.93, r1.getTargetRecall(), 0.0001);
    }

    @Test
    void compare_handlesPartialOverlap() {
        BenchmarkResult baseline1 = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult baseline2 = createResult("es", "k=200", 0.97, 12.0, 90.0, 6.0, 180.0);
        BenchmarkResult target1 = createResult("qd", "k=100", 0.93, 5.0, 200.0, null, null);
        // target2 missing - only k=100 overlaps

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline1, baseline2),
                List.of(target1),
                "recall@100"
        );

        assertEquals(1, results.size());
        assertEquals("k=100", results.get(0).getParamKey());
    }

    @Test
    void compare_returnsEmptyWhenNoOverlap() {
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResult("qd", "k=200", 0.93, 5.0, 200.0, null, null);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void compare_usesServerLatencyWhenBothHaveIt() {
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, 8.0, 150.0, 4.0, 250.0);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);

        // Should use server_latency_median (5.0 and 4.0)
        assertEquals(5.0, r.getBaselineLatency(), 0.0001);
        assertEquals(4.0, r.getTargetLatency(), 0.0001);
        // Speedup = baseline / target = 5.0 / 4.0 = 1.25
        assertEquals(1.25, r.getLatencySpeedup(), 0.0001);
    }

    @Test
    void compare_fallsBackToClientLatencyWhenServerMissing() {
        // Baseline has server_latency, target only has client latency
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, 8.0, 150.0, null, null);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);

        // Baseline uses server_latency_median (5.0), target falls back to latency_median (8.0)
        assertEquals(5.0, r.getBaselineLatency(), 0.0001);
        assertEquals(8.0, r.getTargetLatency(), 0.0001);
        // Speedup = 5.0 / 8.0 = 0.625 (baseline is faster)
        assertEquals(0.625, r.getLatencySpeedup(), 0.0001);
    }

    @Test
    void compare_usesClientLatencyWhenConfigured() {
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, 8.0, 150.0, 4.0, 250.0);

        BenchmarkComparator comparator = new BenchmarkComparator("latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);

        // Should use latency_median (10.0 and 8.0), ignoring server_latency
        assertEquals(10.0, r.getBaselineLatency(), 0.0001);
        assertEquals(8.0, r.getTargetLatency(), 0.0001);
    }

    @Test
    void compare_doesNotFallBackToServerWhenClientMetricMissing() {
        // Configured metric is client-side (latency_median); when absent there is no server_* fallback
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, null, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, null, 150.0, 4.0, 250.0);

        BenchmarkComparator comparator = new BenchmarkComparator("latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);
        assertNull(r.getBaselineLatency());
        assertNull(r.getTargetLatency());
        assertTrue(Double.isNaN(r.getLatencySpeedup()));
    }

    @Test
    void compare_handlesNullLatencyGracefully() {
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, null, 100.0, null, null);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, null, 150.0, null, null);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);

        assertNull(r.getBaselineLatency());
        assertNull(r.getTargetLatency());
        assertTrue(Double.isNaN(r.getLatencySpeedup()));
    }

    @Test
    void compare_usesThroughputMetricWhenServerThroughputMissing() {
        // server_* latency path prefers server_throughput; when absent, fall back to throughput
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, null);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, 8.0, 150.0, 4.0, null);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);
        assertEquals(100.0, r.getBaselineThroughput(), 0.0001);
        assertEquals(150.0, r.getTargetThroughput(), 0.0001);
        assertEquals(1.5, r.getThroughputSpeedup(), 0.0001);
    }

    @Test
    void compare_usesZeroRecallWhenRecallMetricMissing() {
        BenchmarkResult baseline = createResultWithoutRecall("es", "k=100", 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResultWithoutRecall("qd", "k=100", 8.0, 150.0, 4.0, 250.0);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        assertEquals(1, results.size());
        ComparisonResult r = results.get(0);
        assertEquals(0.0, r.getBaselineRecall(), 0.0001);
        assertEquals(0.0, r.getTargetRecall(), 0.0001);
        assertEquals(0.0, r.getRecallDiff(), 0.0001);
    }

    @Test
    void compare_extractsThroughput() {
        BenchmarkResult baseline = createResult("es", "k=100", 0.95, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult target = createResult("qd", "k=100", 0.93, 8.0, 150.0, 4.0, 250.0);

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        List<ComparisonResult> results = comparator.compare(
                List.of(baseline),
                List.of(target),
                "recall@100"
        );

        ComparisonResult r = results.get(0);

        assertEquals(200.0, r.getBaselineThroughput(), 0.0001);
        assertEquals(250.0, r.getTargetThroughput(), 0.0001);
        // Throughput speedup = target / baseline = 250 / 200 = 1.25
        assertEquals(1.25, r.getThroughputSpeedup(), 0.0001);
    }

    @Test
    void findMaxRecallByEngine_returnsHighestRecallPerEngine() {
        BenchmarkResult es1 = createResult("elasticsearch", "k=100", 0.90, 10.0, 100.0, 5.0, 200.0);
        BenchmarkResult es2 = createResult("elasticsearch", "k=200", 0.95, 12.0, 90.0, 6.0, 180.0);
        BenchmarkResult qd1 = createResult("qdrant", "k=100", 0.88, 8.0, 150.0, 4.0, 250.0);
        BenchmarkResult qd2 = createResult("qdrant", "k=200", 0.92, 9.0, 140.0, 5.0, 220.0);

        Map<String, List<BenchmarkResult>> resultsByEngine = Map.of(
                "elasticsearch", List.of(es1, es2),
                "qdrant", List.of(qd1, qd2)
        );

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        Map<String, BenchmarkResult> maxPoints = comparator.findMaxRecallByEngine(resultsByEngine);

        assertEquals(2, maxPoints.size());

        // ES max recall is 0.95 (es2)
        assertEquals(0.95, maxPoints.get("elasticsearch").getMetricAsDouble("recall"), 0.0001);
        assertEquals("k=200", maxPoints.get("elasticsearch").getParamKey());

        // Qdrant max recall is 0.92 (qd2)
        assertEquals(0.92, maxPoints.get("qdrant").getMetricAsDouble("recall"), 0.0001);
        assertEquals("k=200", maxPoints.get("qdrant").getParamKey());
    }

    @Test
    void findMaxRecallByEngine_returnsEmptyWhenNoResults() {
        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        Map<String, BenchmarkResult> maxPoints = comparator.findMaxRecallByEngine(Map.of());

        assertTrue(maxPoints.isEmpty());
    }

    @Test
    void findMaxRecallByEngine_handlesEngineWithNoResults() {
        BenchmarkResult es1 = createResult("elasticsearch", "k=100", 0.90, 10.0, 100.0, 5.0, 200.0);

        Map<String, List<BenchmarkResult>> resultsByEngine = Map.of(
                "elasticsearch", List.of(es1),
                "qdrant", List.of()  // Empty list
        );

        BenchmarkComparator comparator = new BenchmarkComparator("server_latency_median");
        Map<String, BenchmarkResult> maxPoints = comparator.findMaxRecallByEngine(resultsByEngine);

        assertEquals(1, maxPoints.size());
        assertTrue(maxPoints.containsKey("elasticsearch"));
        assertFalse(maxPoints.containsKey("qdrant"));
    }

    // Helper method to create test results
    private BenchmarkResult createResult(
            String engine,
            String paramKey,
            double recall,
            Double latencyMedian,
            Double throughput,
            Double serverLatencyMedian,
            Double serverThroughput
    ) {
        BenchmarkResult result = new BenchmarkResult(
                "test-run",
                engine,
                "1.0",
                "vector_search",
                "test-dataset",
                paramKey,
                Map.of()
        );

        result.addMetric("recall", recall);

        if (latencyMedian != null) {
            result.addMetric("latency_median", latencyMedian);
        }
        if (throughput != null) {
            result.addMetric("throughput", throughput);
        }
        if (serverLatencyMedian != null) {
            result.addMetric("server_latency_median", serverLatencyMedian);
        }
        if (serverThroughput != null) {
            result.addMetric("server_throughput", serverThroughput);
        }

        return result;
    }

    private BenchmarkResult createResultWithoutRecall(
            String engine,
            String paramKey,
            Double latencyMedian,
            Double throughput,
            Double serverLatencyMedian,
            Double serverThroughput
    ) {
        BenchmarkResult result = new BenchmarkResult(
                "test-run",
                engine,
                "1.0",
                "vector_search",
                "test-dataset",
                paramKey,
                Map.of()
        );

        if (latencyMedian != null) {
            result.addMetric("latency_median", latencyMedian);
        }
        if (throughput != null) {
            result.addMetric("throughput", throughput);
        }
        if (serverLatencyMedian != null) {
            result.addMetric("server_latency_median", serverLatencyMedian);
        }
        if (serverThroughput != null) {
            result.addMetric("server_throughput", serverThroughput);
        }

        return result;
    }
}
