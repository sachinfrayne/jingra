package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConsoleResultsSink output formatting and behavior.
 */
class ConsoleResultsSinkTest {

    private final ByteArrayOutputStream outputStreamCaptor = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setup() {
        // Redirect stdout to capture console output
        System.setOut(new PrintStream(outputStreamCaptor));
    }

    @AfterEach
    void teardown() {
        // Restore original stdout
        System.setOut(originalOut);
    }

    private static String invokeFormatQueryEndpoint(ConsoleResultsSink sink, String engineName, String indexName)
            throws Exception {
        Method m = ConsoleResultsSink.class.getDeclaredMethod("formatQueryEndpoint", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(sink, engineName, indexName);
    }

    private static String invokeFormatMetricValue(ConsoleResultsSink sink, String metricName, Object value)
            throws Exception {
        Method m = ConsoleResultsSink.class.getDeclaredMethod("formatMetricValue", String.class, Object.class);
        m.setAccessible(true);
        return (String) m.invoke(sink, metricName, value);
    }

    private BenchmarkResult createTestResult(String runId, String engine, String engineVersion,
                                            Map<String, Object> metrics, Map<String, String> metadata) {
        BenchmarkResult result = new BenchmarkResult(
                runId,
                engine,
                engineVersion,
                "knn",
                "test-dataset",
                "size=100,k=1000",
                Map.of("size", 100, "k", 1000)
        );

        if (metrics != null) {
            result.addMetrics(metrics);
        }

        if (metadata != null) {
            metadata.forEach(result::addMetadata);
        }

        return result;
    }

    @Test
    void testWriteResult_logsToStdout() {
        // Arrange
        ConsoleResultsSink sink = new ConsoleResultsSink();

        Map<String, String> metadata = new HashMap<>();
        metadata.put("index", "test-index");
        metadata.put("vector_type", "float32");

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("precision", 0.9523);
        metrics.put("recall", 0.8765);
        metrics.put("f1_score", 0.9127);
        metrics.put("latency_avg", 125.45);
        metrics.put("throughput", 789.12);

        BenchmarkResult result = createTestResult(
                "test-run-001",
                "elasticsearch",
                "9.3.2",
                metrics,
                metadata
        );

        // Act
        sink.writeResult(result);

        // Assert
        String output = outputStreamCaptor.toString();

        // Verify header/separator is present
        assertThat(output).contains("================================================================================");
        assertThat(output).contains("BENCHMARK RESULT");

        // Verify JSON output contains key fields (using snake_case)
        assertThat(output).contains("\"run_id\" : \"test-run-001\"");
        assertThat(output).contains("\"engine\" : \"elasticsearch\"");
        assertThat(output).contains("\"engine_version\" : \"9.3.2\"");
        assertThat(output).contains("\"dataset\" : \"test-dataset\"");

        // Verify metrics are present
        assertThat(output).contains("\"precision\"");
        assertThat(output).contains("\"recall\"");
        assertThat(output).contains("\"f1_score\"");
        assertThat(output).contains("\"latency_avg\"");

        // Verify summary table is present
        assertThat(output).contains("SUMMARY");
        assertThat(output).contains("Engine");
        assertThat(output).contains("elasticsearch");
        assertThat(output).contains("METRICS:");
    }

    @Test
    void testWriteResult_formatsMetricsCorrectly() {
        // Arrange
        ConsoleResultsSink sink = new ConsoleResultsSink();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("precision", 0.123456789);  // Should format to 4 decimals
        metrics.put("latency_p50", 123.456789); // Should format to 2 decimals
        metrics.put("custom_metric", 999.999);  // Should format to 4 decimals (default)

        BenchmarkResult result = createTestResult(
                "format-test",
                "test-engine",
                "1.0",
                metrics,
                Map.of()
        );

        // Act
        sink.writeResult(result);

        // Assert
        String output = outputStreamCaptor.toString();

        // Precision should have 4 decimals
        assertThat(output).containsPattern("precision.*0\\.1235");

        // Latency should have 2 decimals
        assertThat(output).containsPattern("latency_p50.*123\\.46");
    }

    @Test
    void testFlush_isNoOp() {
        // Arrange
        ConsoleResultsSink sink = new ConsoleResultsSink();

        // Act & Assert - flush should do nothing and not throw
        assertDoesNotThrow(() -> sink.flush());

        // Verify no output was written
        String output = outputStreamCaptor.toString();
        assertThat(output).isEmpty();
    }

    @Test
    void testClose_doesNotThrow() throws Exception {
        // Arrange
        ConsoleResultsSink sink = new ConsoleResultsSink();

        // Act & Assert - close should not throw (calls flush which is no-op)
        assertDoesNotThrow(() -> sink.close());
    }

    @Test
    void testWriteResult_formatsFloatMetrics() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("precision", 0.5f);
        metrics.put("latency_avg", 10.5f);
        BenchmarkResult result = createTestResult("f1", "e", "1.0", metrics, Map.of());
        sink.writeResult(result);
        String output = outputStreamCaptor.toString();
        assertThat(output).contains("precision");
    }

    @Test
    void testWriteResult_formatsIntegerMetricAsString() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("count", 42);
        BenchmarkResult result = createTestResult("i1", "e", "1.0", metrics, Map.of());
        sink.writeResult(result);
        String output = outputStreamCaptor.toString();
        assertThat(output).contains("42");
    }

    @Test
    void testMultipleResults() {
        // Arrange
        ConsoleResultsSink sink = new ConsoleResultsSink();

        BenchmarkResult result1 = createTestResult(
                "test-1",
                "engine1",
                "1.0",
                Map.of("metric", 1.0),
                Map.of()
        );

        BenchmarkResult result2 = createTestResult(
                "test-2",
                "engine2",
                "2.0",
                Map.of("metric", 2.0),
                Map.of()
        );

        // Act
        sink.writeResult(result1);
        sink.writeResult(result2);

        // Assert - both results should be in output
        String output = outputStreamCaptor.toString();
        assertThat(output).contains("test-1");
        assertThat(output).contains("test-2");
        assertThat(output).contains("engine1");
        assertThat(output).contains("engine2");

        // Should have two separators (one for each result)
        int separatorCount = output.split("================================================================================").length - 1;
        assertTrue(separatorCount >= 2, "Should have at least 2 separators for 2 results");
    }

    @Test
    void writeResult_wrapsExceptionFromToMap() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        BenchmarkResult bad = new BenchmarkResult(
                "r", "e", "1.0", "vector_search", "d", "p", Map.of()
        ) {
            @Override
            public Map<String, Object> toMap() {
                throw new IllegalStateException("serialization failure");
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.writeResult(bad));
        assertTrue(ex.getMessage().contains("Failed to write result to console"));
    }

    @Test
    void writeResult_printsConsoleQueryBlock_whenQueryMetadataComplete() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("query_json", "{\"query\":{\"match_all\":{}}}");
        metadata.put("index_name", "bench-idx");
        metadata.put("engine_name", "elasticsearch");

        BenchmarkResult result = createTestResult("q1", "elasticsearch", "8.0", Map.of("mrr", 1.0), metadata);
        sink.writeResult(result);

        String output = outputStreamCaptor.toString();
        assertThat(output).contains("Console query");
        assertThat(output).contains("POST /bench-idx/_search");
        assertThat(output).contains("{\"query\":{\"match_all\":{}}}");
    }

    @Test
    void writeResult_omitsConsoleQueryBlock_whenQueryJsonNull() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("index_name", "i");
        metadata.put("engine_name", "elasticsearch");

        sink.writeResult(createTestResult("a", "e", "1.0", Map.of(), metadata));
        assertThat(outputStreamCaptor.toString()).doesNotContain("Console query");
    }

    @Test
    void writeResult_omitsConsoleQueryBlock_whenIndexNameNull() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("query_json", "{}");
        metadata.put("engine_name", "elasticsearch");

        sink.writeResult(createTestResult("a", "e", "1.0", Map.of(), metadata));
        assertThat(outputStreamCaptor.toString()).doesNotContain("Console query");
    }

    @Test
    void writeResult_omitsConsoleQueryBlock_whenEngineNameNull() {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("query_json", "{}");
        metadata.put("index_name", "i");

        sink.writeResult(createTestResult("a", "e", "1.0", Map.of(), metadata));
        assertThat(outputStreamCaptor.toString()).doesNotContain("Console query");
    }

    @Test
    void formatQueryEndpoint_elasticsearchOpenSearchQdrantDefaultAndCase() throws Exception {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        String idx = "my-index";

        assertEquals("POST /" + idx + "/_search", invokeFormatQueryEndpoint(sink, "elasticsearch", idx));
        assertEquals("POST /" + idx + "/_search", invokeFormatQueryEndpoint(sink, "OpenSearch", idx));
        assertEquals("POST /collections/" + idx + "/points/search", invokeFormatQueryEndpoint(sink, "qdrant", idx));
        assertEquals("POST /" + idx + "/search", invokeFormatQueryEndpoint(sink, "custom-engine", idx));
        assertEquals("POST /" + idx + "/_search", invokeFormatQueryEndpoint(sink, "ElasticSearch", idx));
    }

    @Test
    void formatMetricValue_mrrBranchUsesFourDecimals() throws Exception {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        assertEquals("0.1235", invokeFormatMetricValue(sink, "mrr", 0.123456));
        assertEquals("0.1235", invokeFormatMetricValue(sink, "score_mrr", 0.123456));
    }

    @Test
    void formatMetricValue_numericBranchesExact() throws Exception {
        ConsoleResultsSink sink = new ConsoleResultsSink();
        assertEquals("0.1235", invokeFormatMetricValue(sink, "precision", 0.123456));
        assertEquals("123.46", invokeFormatMetricValue(sink, "latency_avg", 123.456));
        assertEquals("999.9990", invokeFormatMetricValue(sink, "other_num", 999.999));
        assertEquals("0.5000", invokeFormatMetricValue(sink, "x", 0.5f));
        assertEquals("42", invokeFormatMetricValue(sink, "k", 42));
    }
}
