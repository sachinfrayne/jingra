package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.model.BenchmarkResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Outputs benchmark results to console (stdout).
 * Results are formatted as JSON for easy parsing.
 */
public class ConsoleResultsSink implements ResultsSink {
    private static final Logger logger = LoggerFactory.getLogger(ConsoleResultsSink.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void writeResult(BenchmarkResult result) {
        try {
            Map<String, Object> resultMap = result.toMap();
            String json = objectMapper.writeValueAsString(resultMap);

            // Print separator for readability
            System.out.println("=" .repeat(80));
            System.out.println("BENCHMARK RESULT");
            System.out.println("=" .repeat(80));
            System.out.println(json);
            System.out.println();

            // Also print a summary table for quick viewing
            printSummaryTable(result);

        } catch (Exception e) {
            logger.error("Failed to write result to console", e);
            throw new RuntimeException("Failed to write result to console", e);
        }
    }

    /**
     * Print a formatted summary table of key metrics.
     */
    private void printSummaryTable(BenchmarkResult result) {
        System.out.println("SUMMARY");
        System.out.println("-".repeat(80));
        System.out.printf("%-20s: %s%n", "Engine", result.getEngine() + " " + result.getEngineVersion());
        System.out.printf("%-20s: %s%n", "Benchmark Type", result.getBenchmarkType());
        System.out.printf("%-20s: %s%n", "Dataset", result.getDataset());
        System.out.printf("%-20s: %s%n", "Parameters", result.getParamKey());
        System.out.println();

        System.out.println("METRICS:");
        result.getMetrics().forEach((key, value) -> {
            String formattedValue = formatMetricValue(key, value);
            System.out.printf("  %-25s: %s%n", key, formattedValue);
        });
        System.out.println("-" .repeat(80));

        // Print query if available
        String queryJson = result.getMetadata().get("query_json");
        String indexName = result.getMetadata().get("index_name");
        String engineName = result.getMetadata().get("engine_name");
        if (queryJson != null && indexName != null && engineName != null) {
            System.out.println("Console query");
            System.out.println("-" .repeat(80));
            System.out.println();

            // Format endpoint based on engine type
            String endpoint = formatQueryEndpoint(engineName, indexName);
            System.out.println(endpoint);
            System.out.println(queryJson);
            System.out.println("=" .repeat(80));
            System.out.println();
        }
    }

    /**
     * Format the query endpoint based on engine type.
     */
    private String formatQueryEndpoint(String engineName, String indexName) {
        return switch (engineName.toLowerCase()) {
            case "elasticsearch", "opensearch" -> "POST /" + indexName + "/_search";
            case "qdrant" -> "POST /collections/" + indexName + "/points/search";
            default -> "POST /" + indexName + "/search";
        };
    }

    /**
     * Format metric values for display.
     */
    private String formatMetricValue(String metricName, Object value) {
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            // Format percentages and ratios to 4 decimal places
            if (metricName.contains("precision") || metricName.contains("recall") ||
                metricName.contains("f1") || metricName.contains("mrr")) {
                return String.format("%.4f", d);
            }
            // Format latencies to 2 decimal places
            if (metricName.contains("latency") || metricName.contains("throughput")) {
                return String.format("%.2f", d);
            }
            // Default: 4 decimal places
            return String.format("%.4f", d);
        }
        return value.toString();
    }
}
