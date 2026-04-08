package org.elasticsearch.jingra.testing;

import org.elasticsearch.jingra.model.BenchmarkResult;
import org.elasticsearch.jingra.output.ResultsSink;

import java.util.List;
import java.util.Map;

/**
 * Captures {@link BenchmarkResult} writes for assertions in tests.
 */
public class MockResultsSink implements ResultsSink {
    public int resultCount = 0;
    public BenchmarkResult lastResult = null;

    @Override
    public void writeResult(BenchmarkResult result) {
        resultCount++;
        lastResult = result;
    }

    @Override
    public void writeQueryMetricsBatch(List<Map<String, Object>> queryMetrics) {
        // default ResultsSink has empty impl; keep no-op for tests that only assert writeResult
    }
}
