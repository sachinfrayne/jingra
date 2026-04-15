package org.elasticsearch.jingra.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonResultTest {

    @Test
    void constructsWithValidValues() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("k=1500_num_candidates=1500")
                .baselineEngine("elasticsearch")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qdrant")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        assertEquals("recall@100", result.getRecallAtN());
        assertEquals("k=1500_num_candidates=1500", result.getParamKey());
        assertEquals("elasticsearch", result.getBaselineEngine());
        assertEquals(0.95, result.getBaselineRecall(), 0.0001);
        assertEquals(10.0, result.getBaselineLatency(), 0.0001);
        assertEquals(100.0, result.getBaselineThroughput(), 0.0001);
        assertEquals("qdrant", result.getTargetEngine());
        assertEquals(0.93, result.getTargetRecall(), 0.0001);
        assertEquals(5.0, result.getTargetLatency(), 0.0001);
        assertEquals(200.0, result.getTargetThroughput(), 0.0001);
    }

    @Test
    void calculatesRecallDiff() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        // recallDiff = target - baseline = 0.93 - 0.95 = -0.02
        assertEquals(-0.02, result.getRecallDiff(), 0.0001);
    }

    @Test
    void calculatesLatencySpeedup_targetFaster() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        // latencySpeedup = baseline / target = 10.0 / 5.0 = 2.0 (target is 2x faster)
        assertEquals(2.0, result.getLatencySpeedup(), 0.0001);
    }

    @Test
    void calculatesLatencySpeedup_baselineFaster() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(5.0)
                .baselineThroughput(200.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(10.0)
                .targetThroughput(100.0)
                .build();

        // latencySpeedup = baseline / target = 5.0 / 10.0 = 0.5 (baseline is 2x faster)
        assertEquals(0.5, result.getLatencySpeedup(), 0.0001);
    }

    @Test
    void calculatesThroughputSpeedup_targetFaster() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        // throughputSpeedup = target / baseline = 200.0 / 100.0 = 2.0 (target is 2x faster)
        assertEquals(2.0, result.getThroughputSpeedup(), 0.0001);
    }

    @Test
    void calculatesThroughputSpeedup_baselineFaster() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(5.0)
                .baselineThroughput(200.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(10.0)
                .targetThroughput(100.0)
                .build();

        // throughputSpeedup = target / baseline = 100.0 / 200.0 = 0.5 (baseline is 2x faster)
        assertEquals(0.5, result.getThroughputSpeedup(), 0.0001);
    }

    @Test
    void handlesZeroTargetLatency_returnsInfinity() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(0.0)
                .targetThroughput(200.0)
                .build();

        // latencySpeedup = baseline / target = 10.0 / 0.0 = Infinity
        assertEquals(Double.POSITIVE_INFINITY, result.getLatencySpeedup());
    }

    @Test
    void handlesZeroBaselineLatency_returnsZero() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(0.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        // latencySpeedup = baseline / target = 0.0 / 5.0 = 0.0
        assertEquals(0.0, result.getLatencySpeedup(), 0.0001);
    }

    @Test
    void handlesZeroBaselineThroughput_returnsZero() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(0.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        // throughputSpeedup = target / baseline = 200.0 / 0.0 -> special case: 0.0
        // (dividing by zero baseline throughput doesn't make sense, return 0)
        assertEquals(0.0, result.getThroughputSpeedup(), 0.0001);
    }

    @Test
    void handlesNullLatencyValues() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(null)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(null)
                .targetThroughput(200.0)
                .build();

        assertNull(result.getBaselineLatency());
        assertNull(result.getTargetLatency());
        // When either is null, speedup should be NaN
        assertTrue(Double.isNaN(result.getLatencySpeedup()));
    }

    @Test
    void handlesNullLatencySpeedup_onlyBaselineNull() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(null)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        assertTrue(Double.isNaN(result.getLatencySpeedup()));
    }

    @Test
    void handlesNullLatencySpeedup_onlyTargetNull() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(null)
                .targetThroughput(200.0)
                .build();

        assertTrue(Double.isNaN(result.getLatencySpeedup()));
    }

    @Test
    void handlesNullThroughputValues() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(null)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(null)
                .build();

        assertNull(result.getBaselineThroughput());
        assertNull(result.getTargetThroughput());
        // When either is null, speedup should be NaN
        assertTrue(Double.isNaN(result.getThroughputSpeedup()));
    }

    @Test
    void handlesNullThroughputSpeedup_onlyBaselineNull() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(null)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        assertTrue(Double.isNaN(result.getThroughputSpeedup()));
    }

    @Test
    void handlesNullThroughputSpeedup_onlyTargetNull() {
        ComparisonResult result = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("test")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(5.0)
                .targetThroughput(null)
                .build();

        assertTrue(Double.isNaN(result.getThroughputSpeedup()));
    }

    @Test
    void builderRequiresAllFields() {
        // Missing required fields should throw exception
        assertThrows(NullPointerException.class, () -> {
            ComparisonResult.builder()
                    .recallAtN("recall@100")
                    // missing other required fields
                    .build();
        });
    }
}
