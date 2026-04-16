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
        assertEquals("size=100_k=1000", result.getParamKey());
        assertEquals(100, result.getParams().get("size"));
        assertEquals(1000, result.getParams().get("k"));
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
    void setSchema_nullClearsSchemaAndGetSchemaReturnsNull() {
        BenchmarkResult result = createTestResult()
                .setSchema(Map.of("mappings", Map.of()));
        assertNotNull(result.getSchema());
        result.setSchema(null);
        assertNull(result.getSchema());
    }

    @Test
    void toMap_withEmptySchemaOmitsSchemaKey() {
        BenchmarkResult result = createTestResult().setSchema(Map.of());
        Map<String, Object> map = result.toMap();
        assertFalse(map.containsKey("schema"));
    }

    @Test
    void toMap_withNonEmptySchemaIncludesSchemaKey() {
        BenchmarkResult result = createTestResult().setSchema(Map.of("mappings", Map.of("properties", Map.of())));
        Map<String, Object> map = result.toMap();
        assertTrue(map.containsKey("schema"));
    }

    @Test
    void testGetMetricAsInteger() {
        BenchmarkResult result = createTestResult();
        result.addMetric("num_samples", 10000);

        assertEquals(10000, result.getMetricAsInteger("num_samples"));
        assertEquals(10000.0, result.getMetricAsDouble("num_samples"));
        result.addMetric("ratio", 3.9);
        assertEquals(3, result.getMetricAsInteger("ratio"));
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

    @Test
    void constructor_defensivelyCopiesParamsMap() {
        Map<String, Object> params = new HashMap<>();
        params.put("size", 100);
        BenchmarkResult result = new BenchmarkResult(
                "r", "e", "1", "vector_search", "d", "pk", params);
        params.put("injected", true);
        assertFalse(result.getParams().containsKey("injected"));
    }

    @Test
    void getParams_getMetrics_getMetadata_returnDefensiveCopies() {
        BenchmarkResult result = createTestResult()
                .addMetric("m", 1)
                .addMetadata("mk", "mv");

        result.getParams().put("x", 1);
        result.getMetrics().put("y", 2);
        result.getMetadata().put("z", "w");

        assertEquals(1, result.getParams().size());
        assertNull(result.getMetric("y"));
        assertEquals(1, result.getMetadata().size());
        assertEquals("mv", result.getMetadata().get("mk"));
    }

    @Test
    void toMap_returnedMapMutationsDoNotAddMetrics() {
        BenchmarkResult result = createTestResult().addMetric("m", 1);
        Map<String, Object> map = result.toMap();
        map.put("extra_metric", 99);
        assertNull(result.getMetric("extra_metric"));
    }

    @Test
    void fromMap_createsResultFromMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run-123");
        map.put("engine", "elasticsearch");
        map.put("engine_version", "9.4.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "test-dataset");
        map.put("param_key", "k=100");
        map.put("params", Map.of("k", 100));

        BenchmarkResult result = BenchmarkResult.fromMap(map);

        // Timestamp is constructor-generated, not preserved from map
        assertNotNull(result.getTimestamp());
        assertEquals("test-run-123", result.getRunId());
        assertEquals("elasticsearch", result.getEngine());
        assertEquals("9.4.0", result.getEngineVersion());
        assertEquals("vector_search", result.getBenchmarkType());
        assertEquals("test-dataset", result.getDataset());
        assertEquals("k=100", result.getParamKey());
        assertEquals(100, result.getParams().get("k"));
    }

    @Test
    void fromMap_extractsMetricsFromTopLevel() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run");
        map.put("engine", "qdrant");
        map.put("engine_version", "1.17.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "ds");
        map.put("param_key", "pk");
        map.put("params", Map.of());
        // Metrics are flattened at top level
        map.put("precision", 0.98);
        map.put("recall", 0.95);
        map.put("latency_median", 10.5);

        BenchmarkResult result = BenchmarkResult.fromMap(map);

        assertEquals(0.98, result.getMetricAsDouble("precision"));
        assertEquals(0.95, result.getMetricAsDouble("recall"));
        assertEquals(10.5, result.getMetricAsDouble("latency_median"));
    }

    @Test
    void fromMap_extractsMetadata() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run");
        map.put("engine", "elasticsearch");
        map.put("engine_version", "9.4.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "ds");
        map.put("param_key", "pk");
        map.put("params", Map.of());
        Map<String, String> metadata = new HashMap<>();
        metadata.put("recall_label", "recall@100");
        metadata.put("vector_type", "hnsw");
        map.put("metadata", metadata);

        BenchmarkResult result = BenchmarkResult.fromMap(map);

        assertEquals("recall@100", result.getMetadata().get("recall_label"));
        assertEquals("hnsw", result.getMetadata().get("vector_type"));
    }

    @Test
    void fromMap_roundtripPreservesData() {
        BenchmarkResult original = createTestResult()
                .addMetric("precision", 0.98)
                .addMetric("recall", 0.95)
                .addMetadata("recall_label", "recall@100");

        Map<String, Object> map = original.toMap();
        BenchmarkResult restored = BenchmarkResult.fromMap(map);

        assertEquals(original.getRunId(), restored.getRunId());
        assertEquals(original.getEngine(), restored.getEngine());
        assertEquals(original.getEngineVersion(), restored.getEngineVersion());
        assertEquals(original.getBenchmarkType(), restored.getBenchmarkType());
        assertEquals(original.getDataset(), restored.getDataset());
        assertEquals(original.getParamKey(), restored.getParamKey());
        assertEquals(original.getMetricAsDouble("precision"), restored.getMetricAsDouble("precision"));
        assertEquals(original.getMetricAsDouble("recall"), restored.getMetricAsDouble("recall"));
        assertEquals(original.getMetadata().get("recall_label"), restored.getMetadata().get("recall_label"));
    }

    @Test
    void fromMap_handlesEmptyMetadata() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run");
        map.put("engine", "elasticsearch");
        map.put("engine_version", "9.4.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "ds");
        map.put("param_key", "pk");
        map.put("params", Map.of());
        // No metadata field

        BenchmarkResult result = BenchmarkResult.fromMap(map);

        assertTrue(result.getMetadata().isEmpty());
    }

    @Test
    void fromMap_schemaPresentButNotMap_isIgnored() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run");
        map.put("engine", "qdrant");
        map.put("engine_version", "1.17.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "ds");
        map.put("param_key", "pk");
        map.put("params", Map.of());
        map.put("schema", "not-a-map");

        BenchmarkResult result = BenchmarkResult.fromMap(map);
        assertNull(result.getSchema());
    }

    @Test
    void fromMap_extractsSchemaWhenPresent() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run");
        map.put("engine", "qdrant");
        map.put("engine_version", "1.17.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "ds");
        map.put("param_key", "pk");
        map.put("params", Map.of());
        map.put("schema", Map.of("mappings", Map.of("properties", Map.of("f", Map.of("type", "keyword")))));

        BenchmarkResult result = BenchmarkResult.fromMap(map);
        assertNotNull(result.getSchema());
        assertTrue(result.getSchema().containsKey("mappings"));
    }

    @Test
    void fromMap_throwsOnMissingRequiredFields() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        // Missing run_id and other required fields

        assertThrows(NullPointerException.class, () -> BenchmarkResult.fromMap(map));
    }

    @Test
    void fromMap_timestampNotAddedAsMetric() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", "test-run");
        map.put("engine", "elasticsearch");
        map.put("engine_version", "9.4.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "ds");
        map.put("param_key", "pk");
        map.put("params", Map.of());
        map.put("precision", 0.98);

        BenchmarkResult result = BenchmarkResult.fromMap(map);

        // @timestamp should not appear as a metric
        assertNull(result.getMetric("@timestamp"));
        // But precision should be a metric
        assertEquals(0.98, result.getMetricAsDouble("precision"));
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
