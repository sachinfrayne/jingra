package org.elasticsearch.jingra.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkResultTest {

    @Test
    void testConstructorAndGetters() {
        Map<String, Object> params = new HashMap<>();
        params.put("size", 100);
        params.put("k", 1000);

        BenchmarkResult result = new BenchmarkResult(
                "20260325-120000",
                "elasticsearch",
                "8.17.0",
                "vector_search",
                "ecommerce-128",
                "size=100_k=1000",
                params
        );

        assertEquals("20260325-120000", result.getRunId());
        assertEquals("elasticsearch", result.getEngine());
        assertEquals("8.17.0", result.getEngineVersion());
        assertEquals("vector_search", result.getBenchmarkType());
        assertEquals("ecommerce-128", result.getDataset());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void testAddMetric() {
        BenchmarkResult result = createTestResult();

        result.addMetric("precision", 0.98);
        result.addMetric("recall", 0.95);

        assertEquals(0.98, result.getMetricAsDouble("precision"));
        assertEquals(0.95, result.getMetricAsDouble("recall"));
    }

    @Test
    void testAddMetrics() {
        BenchmarkResult result = createTestResult();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("precision", 0.98);
        metrics.put("recall", 0.95);
        metrics.put("f1", 0.965);

        result.addMetrics(metrics);

        assertEquals(3, result.getMetrics().size());
        assertEquals(0.98, result.getMetricAsDouble("precision"));
    }

    @Test
    void testAddMetadata() {
        BenchmarkResult result = createTestResult();

        result.addMetadata("vector_type", "hnsw");
        result.addMetadata("recall_label", "recall@100");

        Map<String, String> metadata = result.getMetadata();
        assertEquals(2, metadata.size());
        assertEquals("hnsw", metadata.get("vector_type"));
    }

    @Test
    void testToMap() {
        BenchmarkResult result = createTestResult();
        result.addMetric("precision", 0.98);
        result.addMetric("recall", 0.95);
        result.addMetadata("vector_type", "hnsw");

        Map<String, Object> map = result.toMap();

        assertTrue(map.containsKey("@timestamp"));
        assertTrue(map.containsKey("run_id"));
        assertTrue(map.containsKey("engine"));
        assertTrue(map.containsKey("precision"));
        assertTrue(map.containsKey("recall"));
        assertTrue(map.containsKey("metadata"));
    }

    @Test
    void testFluentAPI() {
        BenchmarkResult result = createTestResult()
                .addMetric("precision", 0.98)
                .addMetric("recall", 0.95)
                .addMetadata("vector_type", "hnsw");

        assertEquals(0.98, result.getMetricAsDouble("precision"));
        assertEquals("hnsw", result.getMetadata().get("vector_type"));
    }

    @Test
    void testGetMetricAsInteger() {
        BenchmarkResult result = createTestResult();
        result.addMetric("num_samples", 10000);

        assertEquals(10000, result.getMetricAsInteger("num_samples"));
    }

    @Test
    void getMetricAsDouble_nonNumberReturnsNull() {
        BenchmarkResult result = createTestResult();
        result.addMetric("bad", "x");
        assertNull(result.getMetricAsDouble("bad"));
    }

    @Test
    void getMetricAsInteger_nonNumberReturnsNull() {
        BenchmarkResult result = createTestResult();
        result.addMetric("bad", "x");
        assertNull(result.getMetricAsInteger("bad"));
    }

    @Test
    void toMap_withoutMetadataOmitsMetadataKey() {
        BenchmarkResult result = createTestResult();
        result.addMetric("precision", 1.0);
        Map<String, Object> map = result.toMap();
        assertFalse(map.containsKey("metadata"));
    }

    @Test
    void getMetric_missingKeyReturnsNull() {
        assertNull(createTestResult().getMetric("no_such_metric"));
    }

    private BenchmarkResult createTestResult() {
        Map<String, Object> params = new HashMap<>();
        params.put("size", 100);

        return new BenchmarkResult(
                "test-run",
                "test-engine",
                "1.0",
                "vector_search",
                "test-dataset",
                "test-params",
                params
        );
    }
}
