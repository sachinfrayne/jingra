package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ResultsSinkDefaultMethodsTest {

    @Test
    void defaultWriteQueryMetricsBatchAndFlushAndClose_noOp() {
        ResultsSink sink = new ResultsSink() {
            @Override
            public void writeResult(BenchmarkResult result) {
            }
        };
        assertDoesNotThrow(() -> {
            sink.writeQueryMetricsBatch(List.of(Map.of("k", 1)));
            sink.flush();
            sink.close();
        });
    }
}
