package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisConfigTest {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void deserializesFromYaml() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                  - qdrant
                results_cluster:
                  url: "http://localhost:9200"
                  user: "admin"
                  password: "secret"
                  index: "jingra-results"
                output_directory: "./test-output"
                latency_metrics:
                  - server_latency_median
                generate_plots: true
                """;

        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);

        assertEquals("test-run-123", config.getRunId());
        assertEquals(List.of("elasticsearch", "qdrant"), config.getEngines());
        assertEquals("http://localhost:9200", config.getResultsCluster().get("url"));
        assertEquals("admin", config.getResultsCluster().get("user"));
        assertEquals("secret", config.getResultsCluster().get("password"));
        assertEquals("jingra-results", config.getResultsCluster().get("index"));
        assertEquals("./test-output", config.getOutputDirectory());
        assertEquals(List.of("server_latency_median"), config.getLatencyMetrics());
        assertTrue(config.isGeneratePlots());
    }

    @Test
    void deserializesLatencyMetricsArray() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                  - qdrant
                results_cluster:
                  url: "http://localhost:9200"
                latency_metrics:
                  - server_latency_median
                  - server_latency_p95
                  - latency_avg
                """;

        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);

        assertEquals("test-run-123", config.getRunId());
        assertEquals(List.of("server_latency_median", "server_latency_p95", "latency_avg"),
                config.getLatencyMetrics());
    }

    @Test
    void usesDefaults() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                  - qdrant
                results_cluster:
                  url: "http://localhost:9200"
                """;

        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);

        assertEquals("./analysis-output", config.getOutputDirectory());
        assertEquals(List.of("server_latency_median"), config.getLatencyMetrics());
        assertTrue(config.isGeneratePlots());
    }

    @Test
    void allowsOverridingDefaults() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                  - qdrant
                results_cluster:
                  url: "http://localhost:9200"
                output_directory: "/custom/output"
                latency_metrics:
                  - latency_p95
                  - latency_p99
                generate_plots: false
                """;

        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);

        assertEquals("/custom/output", config.getOutputDirectory());
        assertEquals(List.of("latency_p95", "latency_p99"), config.getLatencyMetrics());
        assertFalse(config.isGeneratePlots());
    }

    @Test
    void resultsClusterSupportsEnvVars() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                  - qdrant
                results_cluster:
                  url_env: "RESULTS_ES_URL"
                  user_env: "RESULTS_ES_USER"
                  password_env: "RESULTS_ES_PASSWORD"
                  index: "custom-index"
                """;

        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);

        assertEquals("RESULTS_ES_URL", config.getResultsCluster().get("url_env"));
        assertEquals("RESULTS_ES_USER", config.getResultsCluster().get("user_env"));
        assertEquals("RESULTS_ES_PASSWORD", config.getResultsCluster().get("password_env"));
        assertEquals("custom-index", config.getResultsCluster().get("index"));
    }

    @Test
    void ignoresUnknownProperties() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                results_cluster:
                  url: "http://localhost:9200"
                unknown_field: "should be ignored"
                """;

        // Should not throw exception
        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);
        assertEquals("test-run-123", config.getRunId());
    }

    @Test
    void getLatencyMetrics_defaultsWhenListExplicitlyEmpty() {
        AnalysisConfig config = new AnalysisConfig();
        config.setLatencyMetrics(Collections.emptyList());
        assertEquals(List.of("server_latency_median"), config.getLatencyMetrics());
    }

    @Test
    void settersWork() {
        AnalysisConfig config = new AnalysisConfig();
        config.setRunId("test-123");
        config.setEngines(List.of("es", "qd"));
        config.setResultsCluster(Map.of("url", "http://localhost:9200"));
        config.setOutputDirectory("/output");
        config.setLatencyMetrics(List.of("latency_avg", "latency_p95"));
        config.setGeneratePlots(false);

        assertEquals("test-123", config.getRunId());
        assertEquals(List.of("es", "qd"), config.getEngines());
        assertEquals("http://localhost:9200", config.getResultsCluster().get("url"));
        assertEquals("/output", config.getOutputDirectory());
        assertEquals(List.of("latency_avg", "latency_p95"), config.getLatencyMetrics());
        assertFalse(config.isGeneratePlots());
    }

    @Test
    void getEngineVersions_returnsEmptyMapWhenUnset() {
        AnalysisConfig config = new AnalysisConfig();
        assertTrue(config.getEngineVersions().isEmpty());
    }

    @Test
    void setEngineVersions_roundTripsThroughGetter() {
        AnalysisConfig config = new AnalysisConfig();
        Map<String, String> versions = Map.of("elasticsearch", "9.3.2", "qdrant", "1.17.0");
        config.setEngineVersions(versions);
        assertEquals(versions, config.getEngineVersions());
    }

    @Test
    void getEngineVersions_returnsEmptyAfterSetToNull() {
        AnalysisConfig config = new AnalysisConfig();
        config.setEngineVersions(Map.of("opensearch", "2.11.0"));
        assertFalse(config.getEngineVersions().isEmpty());
        config.setEngineVersions(null);
        assertTrue(config.getEngineVersions().isEmpty());
    }

    @Test
    void deserializesEngineVersionsFromYaml() throws Exception {
        String yaml = """
                run_id: "test-run-123"
                engines:
                  - elasticsearch
                  - qdrant
                results_cluster:
                  url: "http://localhost:9200"
                engine_versions:
                  elasticsearch: "9.3.2"
                  qdrant: "1.17.0"
                """;

        AnalysisConfig config = yamlMapper.readValue(yaml, AnalysisConfig.class);

        assertEquals(Map.of("elasticsearch", "9.3.2", "qdrant", "1.17.0"), config.getEngineVersions());
    }
}
