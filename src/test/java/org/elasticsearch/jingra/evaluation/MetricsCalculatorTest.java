package org.elasticsearch.jingra.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCalculatorTest {

    @Test
    void testPerfectPrecisionAndRecall() {
        // Ground truth: [1, 2, 3]
        // Retrieved: [1, 2, 3]
        // Precision = 3/3 = 1.0, Recall = 3/3 = 1.0
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        100.0,
                        50L
                )
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(1.0, calc.calculatePrecision(), 0.001);
        assertEquals(1.0, calc.calculateRecall(), 0.001);
        assertEquals(1.0, calc.calculateF1(), 0.001);
    }

    @Test
    void testPartialPrecisionAndRecall() {
        // Ground truth: [1, 2, 3, 4, 5]
        // Retrieved: [1, 2, 6, 7, 8]
        // Precision = 2/5 = 0.4, Recall = 2/5 = 0.4
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3", "4", "5"),
                        Arrays.asList("1", "2", "6", "7", "8"),
                        100.0,
                        50L
                )
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(0.4, calc.calculatePrecision(), 0.001);
        assertEquals(0.4, calc.calculateRecall(), 0.001);
        assertEquals(0.4, calc.calculateF1(), 0.001);
    }

    @Test
    void testMRR() {
        // Query 1: First relevant at position 1 (rank 1) -> RR = 1/1 = 1.0
        // Query 2: First relevant at position 3 (rank 3) -> RR = 1/3 = 0.333
        // MRR = (1.0 + 0.333) / 2 = 0.666
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2"),
                        Arrays.asList("1", "3", "4"),
                        100.0,
                        50L
                ),
                new MetricsCalculator.QueryResult(
                        Arrays.asList("5", "6"),
                        Arrays.asList("7", "8", "5"),
                        100.0,
                        50L
                )
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(0.666, calc.calculateMRR(), 0.01);
    }

    @Test
    void testLatencyMetrics() {
        // Latencies: 100, 200, 300, 400, 500
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, 50L),
                createResult(200.0, 100L),
                createResult(300.0, 150L),
                createResult(400.0, 200L),
                createResult(500.0, 250L)
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        // Average: (100 + 200 + 300 + 400 + 500) / 5 = 300
        assertEquals(300.0, calc.calculateLatencyAvg(), 0.001);

        // Median: 300
        assertEquals(300.0, calc.calculateLatencyMedian(), 0.001);

        // P90: 90th percentile of [100, 200, 300, 400, 500] = 460
        assertEquals(460.0, calc.calculateLatencyPercentile(90), 1.0);

        // P99: 99th percentile ≈ 496
        assertEquals(496.0, calc.calculateLatencyPercentile(99), 1.0);
    }

    @Test
    void testServerLatencyMetrics() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, 50L),
                createResult(200.0, 100L),
                createResult(300.0, 150L)
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertTrue(calc.hasServerLatencies());
        assertEquals(100.0, calc.calculateServerLatencyAvg(), 0.001);
        assertEquals(100.0, calc.calculateServerLatencyMedian(), 0.001);
    }

    @Test
    void testThroughput() {
        // 5 queries with total latency 1500ms
        // Throughput = 5000 / 1500 = 3.333 qps
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, null),
                createResult(200.0, null),
                createResult(300.0, null),
                createResult(400.0, null),
                createResult(500.0, null)
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(3.333, calc.calculateThroughput(), 0.01);
    }

    @Test
    void testEmptyResults() {
        List<MetricsCalculator.QueryResult> results = List.of();
        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(0.0, calc.calculatePrecision());
        assertEquals(0.0, calc.calculateRecall());
        assertEquals(0.0, calc.calculateF1());
        assertEquals(0.0, calc.calculateMRR());
        assertEquals(0.0, calc.calculateLatencyAvg());
        assertEquals(0.0, calc.calculateThroughput());
        assertEquals(0.0, calc.calculateThroughputAggregateLatencyModel());
    }

    @Test
    void testF1_whenPrecisionAndRecallBothZero() {
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("a"),
                        Arrays.asList("b"),
                        100.0,
                        50L
                )
        );
        MetricsCalculator calc = new MetricsCalculator(results);
        assertEquals(0.0, calc.calculatePrecision(), 0.001);
        assertEquals(0.0, calc.calculateRecall(), 0.001);
        assertEquals(0.0, calc.calculateF1(), 0.001);
    }

    // ===== Edge Case Tests =====

    @Test
    void testPrecision_withEmptyRetrieved() {
        // Ground truth: [1, 2, 3]
        // Retrieved: [] (empty)
        // Precision = 0/0 = undefined, should return 0.0
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        List.of(), // Empty retrieved
                        100.0,
                        50L
                )
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        // When retrieved is empty, precision should be 0.0 (not NaN)
        assertEquals(0.0, calc.calculatePrecision(), 0.001);
    }

    @Test
    void testRecall_withEmptyGroundTruth() {
        // Ground truth: [] (empty)
        // Retrieved: [1, 2, 3]
        // Recall = 0/0 = undefined, should return 0.0
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        List.of(), // Empty ground truth
                        Arrays.asList("1", "2", "3"),
                        100.0,
                        50L
                )
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        // When ground truth is empty, recall should be 0.0 (not NaN)
        assertEquals(0.0, calc.calculateRecall(), 0.001);
    }

    @Test
    void testMRR_withNoRelevantItems() {
        // Query 1: Ground truth [1, 2], Retrieved [3, 4, 5] - no overlap
        // Query 2: Ground truth [6, 7], Retrieved [8, 9, 10] - no overlap
        // MRR should be 0.0 since no relevant items found
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2"),
                        Arrays.asList("3", "4", "5"),
                        100.0,
                        50L
                ),
                new MetricsCalculator.QueryResult(
                        Arrays.asList("6", "7"),
                        Arrays.asList("8", "9", "10"),
                        100.0,
                        50L
                )
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(0.0, calc.calculateMRR(), 0.001);
    }

    @Test
    void testPercentile_withSingleValue() {
        // Single latency value: 100ms
        // Any percentile should return that single value
        List<MetricsCalculator.QueryResult> results = List.of(
                createResult(100.0, null)
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(100.0, calc.calculateLatencyPercentile(50), 0.001);
        assertEquals(100.0, calc.calculateLatencyPercentile(90), 0.001);
        assertEquals(100.0, calc.calculateLatencyPercentile(99), 0.001);
    }

    @Test
    void testPercentile_withTwoValues() {
        // Two latency values: 100ms, 200ms
        // P50 (median) should be 150ms (interpolated)
        // P90 should be interpolated between 100 and 200
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, null),
                createResult(200.0, null)
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        // Median of two values
        assertEquals(150.0, calc.calculateLatencyMedian(), 0.001);

        // P90 interpolation: 100 + 0.9 * (200 - 100) = 190
        assertEquals(190.0, calc.calculateLatencyPercentile(90), 1.0);
    }

    @Test
    void testThroughput_withZeroLatencies() {
        // Sum of client latencies is 0 -> totalTimeMs == 0 -> 0.0 (no division)
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(0.0, null),
                createResult(0.0, null),
                createResult(0.0, null)
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        assertEquals(0.0, calc.calculateThroughput(), 0.001);
    }

    @Test
    void testServerLatency_withMixedNulls() {
        // Some queries have server latency, some don't
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, 50L),   // Has server latency
                createResult(200.0, null),  // No server latency
                createResult(300.0, 150L),  // Has server latency
                createResult(400.0, null)   // No server latency
        );

        MetricsCalculator calc = new MetricsCalculator(results);

        // Should only calculate based on non-null server latencies: [50, 150]
        assertTrue(calc.hasServerLatencies());
        // Average of [50, 150] = 100
        assertEquals(100.0, calc.calculateServerLatencyAvg(), 0.001);
        // Median of [50, 150] = 100
        assertEquals(100.0, calc.calculateServerLatencyMedian(), 0.001);
    }

    @Test
    void testThroughput_usesWallClockWhenProvided() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, null),
                createResult(200.0, null)
        );
        MetricsCalculator calc = new MetricsCalculator(results, 3000L);
        assertEquals(2.0 / 3.0, calc.calculateThroughput(), 0.001);
        // Sum of client latencies 300ms -> 2 * 1000 / 300
        assertEquals(20.0 / 3.0, calc.calculateThroughputAggregateLatencyModel(), 0.001);
    }

    @Test
    void serverLatencyMetrics_whenAllServerLatenciesNull() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(10.0, null),
                createResult(20.0, null)
        );
        MetricsCalculator calc = new MetricsCalculator(results);
        assertFalse(calc.hasServerLatencies());
        assertEquals(0.0, calc.calculateServerLatencyPercentile(90), 0.001);
    }

    @Test
    void throughputAggregateModel_whenClientLatenciesSumToZero() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(null, null),
                createResult(null, null)
        );
        MetricsCalculator calc = new MetricsCalculator(results);
        assertEquals(0.0, calc.calculateThroughputAggregateLatencyModel(), 0.001);
    }

    @Test
    void throughput_whenWallClockNonPositiveFallsBackToLatencySum() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                createResult(100.0, null),
                createResult(200.0, null)
        );
        // Wall-clock not used when <= 0; throughput uses sum of client latencies (300ms) -> 2*1000/300
        double expected = 2.0 * 1000.0 / 300.0;
        MetricsCalculator calcZero = new MetricsCalculator(results, 0L);
        assertEquals(expected, calcZero.calculateThroughput(), 0.02);
        MetricsCalculator calcNeg = new MetricsCalculator(results, -1L);
        assertEquals(expected, calcNeg.calculateThroughput(), 0.02);
    }

    @Test
    void mrr_whenNoRelevantDocInNonEmptyLists() {
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        List.of("1"),
                        List.of("2", "3"),
                        1.0,
                        null
                )
        );
        assertEquals(0.0, new MetricsCalculator(results).calculateMRR(), 0.001);
    }

    @Test
    void mrr_rrShortCircuit_whenRetrievedEmptyAndGroundTruthNonEmpty() {
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("a", "b"),
                        List.of(),
                        1.0,
                        null
                )
        );
        assertEquals(0.0, new MetricsCalculator(results).calculateMRR(), 0.001);
    }

    @Test
    void mrr_rrShortCircuit_whenRetrievedNonEmptyAndGroundTruthEmpty() {
        List<MetricsCalculator.QueryResult> results = List.of(
                new MetricsCalculator.QueryResult(
                        List.of(),
                        Arrays.asList("x", "y"),
                        1.0,
                        null
                )
        );
        assertEquals(0.0, new MetricsCalculator(results).calculateMRR(), 0.001);
    }

    @Test
    void latencyAvgMedianPercentile_ignoreNullClientLatencies() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        100.0,
                        null),
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        null,
                        null),
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        300.0,
                        null)
        );
        MetricsCalculator calc = new MetricsCalculator(results);
        assertEquals(200.0, calc.calculateLatencyAvg(), 0.001);
        assertEquals(200.0, calc.calculateLatencyMedian(), 0.001);
        assertEquals(280.0, calc.calculateLatencyPercentile(90), 0.001);
    }

    @Test
    void throughput_withMixedNullAndNonNullClientLatencies_sumsOnlyNonNull() {
        List<MetricsCalculator.QueryResult> results = Arrays.asList(
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        100.0,
                        null),
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        null,
                        null),
                new MetricsCalculator.QueryResult(
                        Arrays.asList("1", "2", "3"),
                        Arrays.asList("1", "2", "3"),
                        200.0,
                        null)
        );
        MetricsCalculator calc = new MetricsCalculator(results);
        assertEquals(10.0, calc.calculateThroughput(), 0.001);
        assertEquals(10.0, calc.calculateThroughputAggregateLatencyModel(), 0.001);
    }

    private MetricsCalculator.QueryResult createResult(Double clientLatency, Long serverLatency) {
        return new MetricsCalculator.QueryResult(
                Arrays.asList("1", "2", "3"),
                Arrays.asList("1", "2", "3"),
                clientLatency,
                serverLatency
        );
    }
}
