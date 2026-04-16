package org.elasticsearch.jingra.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Generic benchmark result containing metrics.
 * Flexible structure supports different benchmark types (vector search, observability, time-series, etc.)
 */
public class BenchmarkResult {

    // Common metadata fields
    private final String timestamp;
    private final String runId;
    private final String engine;
    private final String engineVersion;
    private final String benchmarkType;  // "vector_search", "observability", "timeseries", etc.
    private final String dataset;
    private final String paramKey;
    private final Map<String, Object> params;

    // Generic metrics - different benchmark types have different metrics
    // Vector search: precision, recall, f1, mrr, latency_avg, latency_p99, throughput, etc.
    // Observability: query_correctness, cardinality_accuracy, aggregation_latency, etc.
    private final Map<String, Object> metrics;

    // Additional metadata
    private final Map<String, String> metadata;

    // Schema information (mappings and settings from the index schema template)
    private Map<String, Object> schema;

    public BenchmarkResult(
            String runId,
            String engine,
            String engineVersion,
            String benchmarkType,
            String dataset,
            String paramKey,
            Map<String, Object> params
    ) {
        this.timestamp = Instant.now().toString();
        this.runId = runId;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.benchmarkType = benchmarkType;
        this.dataset = dataset;
        this.paramKey = paramKey;
        this.params = new HashMap<>(params);
        this.metrics = new HashMap<>();
        this.metadata = new HashMap<>();
    }

    // Fluent API for adding metrics
    public BenchmarkResult addMetric(String name, Object value) {
        metrics.put(name, value);
        return this;
    }

    public BenchmarkResult addMetrics(Map<String, Object> metrics) {
        this.metrics.putAll(metrics);
        return this;
    }

    // Fluent API for adding metadata
    public BenchmarkResult addMetadata(String key, String value) {
        metadata.put(key, value);
        return this;
    }

    // Fluent API for adding schema
    public BenchmarkResult setSchema(Map<String, Object> schema) {
        this.schema = schema != null ? new HashMap<>(schema) : null;
        return this;
    }

    // Getters
    public String getTimestamp() { return timestamp; }
    public String getRunId() { return runId; }
    public String getEngine() { return engine; }
    public String getEngineVersion() { return engineVersion; }
    public String getBenchmarkType() { return benchmarkType; }
    public String getDataset() { return dataset; }
    public String getParamKey() { return paramKey; }
    public Map<String, Object> getParams() { return new HashMap<>(params); }
    public Map<String, Object> getMetrics() { return new HashMap<>(metrics); }
    public Map<String, String> getMetadata() { return new HashMap<>(metadata); }
    public Map<String, Object> getSchema() { return schema != null ? new HashMap<>(schema) : null; }

    // Convenience getters for metrics
    public Object getMetric(String name) {
        return metrics.get(name);
    }

    public Double getMetricAsDouble(String name) {
        Object value = metrics.get(name);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    public Integer getMetricAsInteger(String name) {
        Object value = metrics.get(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * Convert result to a flat map for output (e.g., to Elasticsearch).
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", timestamp);
        map.put("run_id", runId);
        map.put("engine", engine);
        map.put("engine_version", engineVersion);
        map.put("benchmark_type", benchmarkType);
        map.put("dataset", dataset);
        map.put("param_key", paramKey);
        map.put("params", params);

        // Flatten metrics into the top level for easier querying
        map.putAll(metrics);

        // Add metadata
        if (!metadata.isEmpty()) {
            map.put("metadata", metadata);
        }

        // Add schema
        if (schema != null && !schema.isEmpty()) {
            map.put("schema", schema);
        }

        return map;
    }

    /**
     * Create a BenchmarkResult from a map (typically from Elasticsearch query results).
     * Note: The reconstructed BenchmarkResult will have a new timestamp (current time) rather than
     * preserving the original @timestamp from the map.
     *
     * @param map source map containing benchmark result data
     * @return BenchmarkResult instance
     */
    @SuppressWarnings("unchecked")
    public static BenchmarkResult fromMap(Map<String, Object> map) {
        // Extract and validate required fields
        String runId = (String) Objects.requireNonNull(map.get("run_id"), "run_id is required");
        String engine = (String) Objects.requireNonNull(map.get("engine"), "engine is required");
        String engineVersion = (String) Objects.requireNonNull(map.get("engine_version"), "engine_version is required");
        String benchmarkType = (String) Objects.requireNonNull(map.get("benchmark_type"), "benchmark_type is required");
        String dataset = (String) Objects.requireNonNull(map.get("dataset"), "dataset is required");
        String paramKey = (String) Objects.requireNonNull(map.get("param_key"), "param_key is required");
        Map<String, Object> params = (Map<String, Object>) map.getOrDefault("params", new HashMap<>());

        // Create result (timestamp will be set to current time by constructor)
        BenchmarkResult result = new BenchmarkResult(runId, engine, engineVersion, benchmarkType, dataset, paramKey, params);

        // Extract metadata if present
        Object metadataObj = map.get("metadata");
        if (metadataObj instanceof Map) {
            Map<String, String> metadataMap = (Map<String, String>) metadataObj;
            for (Map.Entry<String, String> entry : metadataMap.entrySet()) {
                result.addMetadata(entry.getKey(), entry.getValue());
            }
        }

        // Extract schema if present
        Object schemaObj = map.get("schema");
        if (schemaObj instanceof Map) {
            result.setSchema((Map<String, Object>) schemaObj);
        }

        // All other fields are metrics (flattened at top level)
        // Standard fields to skip when extracting metrics
        java.util.Set<String> standardFields = java.util.Set.of(
                "@timestamp", "run_id", "engine", "engine_version", "benchmark_type",
                "dataset", "param_key", "params", "metadata", "schema"
        );

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!standardFields.contains(entry.getKey())) {
                result.addMetric(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }
}
