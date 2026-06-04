package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.model.BenchmarkResult;

import java.util.Map;

/**
 * Interface for outputting benchmark results to various destinations.
 */
public interface ResultsSink extends AutoCloseable {

    /**
     * Write a benchmark result to the sink.
     *
     * @param result the benchmark result
     */
    void writeResult(BenchmarkResult result);

    /**
     * Write a batch of query metrics to the sink.
     * Called at the end of each measurement round. Default is no-op.
     *
     * @param queryMetrics list of query metric maps
     */
    default void writeQueryMetricsBatch(java.util.List<Map<String, Object>> queryMetrics) {
        // Default: no-op (e.g. console sink does not emit per-query rows)
    }

    /**
     * Whether this sink will actually consume per-query metrics.
     * Used by BenchmarkEvaluator to decide whether to build query metric objects at all.
     * Default is false — sinks that do write query metrics must override this to return true.
     */
    default boolean consumesQueryMetrics() {
        return false;
    }

    /**
     * Flush any buffered results.
     */
    default void flush() {
        // Default: no-op
    }

    @Override
    default void close() throws Exception {
        flush();
    }
}
