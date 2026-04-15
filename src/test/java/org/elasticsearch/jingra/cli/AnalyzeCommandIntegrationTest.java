package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.AnalysisConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for AnalyzeCommand using real Elasticsearch via Testcontainers.
 */
@Testcontainers(disabledWithoutDocker = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyzeCommandIntegrationTest {

    private static final String RESULTS_INDEX = "jingra-results-test";
    private static final String TEST_RUN_ID = "integration-test-run";
    private static final String ES_VERSION = readVersionFile("engine-versions/.elasticsearch");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:" + ES_VERSION)
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch")
    )
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.security.http.ssl.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withTmpFs(Map.of("/tmp", "rw,size=512m"));

    private static ElasticsearchEngine engine;

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void setUp() throws Exception {
        String url = "http://" + elasticsearch.getHttpHostAddress();

        Map<String, Object> config = new HashMap<>();
        config.put("url", url);

        engine = new ElasticsearchEngine(config);
        assertTrue(engine.connect(), "Failed to connect to Elasticsearch");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (engine != null) {
            // Clean up test index
            if (engine.indexExists(RESULTS_INDEX)) {
                engine.deleteIndex(RESULTS_INDEX);
            }
            engine.close();
        }
    }

    private static String readVersionFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(path)).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read version from " + path, e);
        }
    }

    @Test
    @Order(1)
    void insertSampleBenchmarkResults() throws Exception {
        // Delete index if it exists from previous run
        if (engine.indexExists(RESULTS_INDEX)) {
            engine.deleteIndex(RESULTS_INDEX);
        }
        // Create sample results for recall@100
        List<Map<String, Object>> results = List.of(
                createResultDoc("elasticsearch", "k=100", 0.95, 10.0, 5.0, 100.0, 200.0, "recall@100"),
                createResultDoc("elasticsearch", "k=200", 0.97, 12.0, 6.0, 90.0, 180.0, "recall@100"),
                createResultDoc("qdrant", "k=100", 0.93, 8.0, null, 150.0, null, "recall@100"),
                createResultDoc("qdrant", "k=200", 0.96, 9.0, null, 140.0, null, "recall@100"),
                // Add recall@10 results
                createResultDoc("elasticsearch", "k=50", 0.85, 5.0, 3.0, 150.0, 250.0, "recall@10"),
                createResultDoc("qdrant", "k=50", 0.83, 4.0, null, 200.0, null, "recall@10")
        );

        var bulkResponse = engine.bulkIndexMaps(RESULTS_INDEX, results);
        assertFalse(bulkResponse.errors(), "Bulk indexing should not have errors");

        // Wait for documents to be searchable
        Thread.sleep(1000);

        // Verify count
        long count = engine.getDocumentCount(RESULTS_INDEX);
        assertEquals(results.size(), count, "Document count mismatch");
    }

    @Test
    @Order(2)
    void endToEndAnalysis() throws Exception {
        // Create config
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");

        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId(TEST_RUN_ID);
        ac.setEngines(List.of("elasticsearch", "qdrant"));

        Map<String, Object> resultsCluster = new HashMap<>();
        resultsCluster.put("url", "http://" + elasticsearch.getHttpHostAddress());
        resultsCluster.put("index", RESULTS_INDEX);
        ac.setResultsCluster(resultsCluster);

        ac.setOutputDirectory(outputDir.toString());
        ac.setLatencyMetrics(List.of("server_latency_median"));
        ac.setGeneratePlots(false);

        config.setAnalysis(ac);

        // Run analyze command
        AnalyzeCommand.run(config);

        // Verify CSV files were created
        assertTrue(Files.exists(outputDir.resolve("recall@100_full_results.csv")),
                "recall@100_full_results.csv should exist");
        assertTrue(Files.exists(outputDir.resolve("recall@10_full_results.csv")),
                "recall@10_full_results.csv should exist");
        assertTrue(Files.exists(outputDir.resolve("summary_comparison.csv")),
                "summary_comparison.csv should exist");
        assertTrue(Files.exists(outputDir.resolve("throughput_comparison.csv")),
                "throughput_comparison.csv should exist");
    }

    @Test
    @Order(3)
    void verifyDetailedComparisonContent() throws Exception {
        Path csvFile = outputDir.resolve("recall@100_full_results.csv");
        assertTrue(Files.exists(csvFile));

        String content = Files.readString(csvFile);

        // Verify headers
        assertTrue(content.contains("RecallAtN"));
        assertTrue(content.contains("ParamKey"));
        assertTrue(content.contains("Engine"));
        assertTrue(content.contains("Recall"));
        assertTrue(content.contains("Latency"));

        // Verify data rows exist for both engines
        assertTrue(content.contains("elasticsearch"));
        assertTrue(content.contains("qdrant"));

        // Verify param keys
        assertTrue(content.contains("k=100"));
        assertTrue(content.contains("k=200"));

        // Verify recall values (as strings)
        assertTrue(content.contains("0.95") || content.contains("0.9500"));
        assertTrue(content.contains("0.93") || content.contains("0.9300"));

        // Verify speedup calculations exist (non-empty speedup columns)
        List<String> lines = Files.readAllLines(csvFile);
        // Should have header + 2 param_keys * 2 engines = 5 lines
        assertEquals(5, lines.size());
    }

    @Test
    @Order(4)
    void verifySummaryComparisonContent() throws Exception {
        Path csvFile = outputDir.resolve("summary_comparison.csv");
        assertTrue(Files.exists(csvFile));

        String content = Files.readString(csvFile);

        // Verify headers
        assertTrue(content.contains("RecallAtN"));
        assertTrue(content.contains("BaselineEngine"));
        assertTrue(content.contains("TargetEngine"));
        assertTrue(content.contains("LatencySpeedup"));

        // Verify data
        assertTrue(content.contains("recall@100"));
        assertTrue(content.contains("recall@10"));
        assertTrue(content.contains("elasticsearch"));
        assertTrue(content.contains("qdrant"));

        List<String> lines = Files.readAllLines(csvFile);
        // Header + 2 recall@N values = 3 lines
        assertEquals(3, lines.size());
    }

    @Test
    @Order(5)
    void verifyLatencyFallback() throws Exception {
        // Qdrant doesn't have server_latency_median, should fall back to latency_median
        Path csvFile = outputDir.resolve("recall@100_full_results.csv");
        String content = Files.readString(csvFile);

        // Find the qdrant row with k=100
        List<String> lines = Files.readAllLines(csvFile);
        String qdrantLine = lines.stream()
                .filter(line -> line.contains("qdrant") && line.contains("k=100"))
                .findFirst()
                .orElseThrow();

        // Should have latency value (8.0 - the client latency)
        assertTrue(qdrantLine.contains("8.0") || qdrantLine.contains("8.00"));
    }

    @Test
    @Order(6)
    void handlesMissingResultsGracefully() throws Exception {
        // Create config with non-existent run_id
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");

        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("non-existent-run");
        ac.setEngines(List.of("elasticsearch", "qdrant"));

        Map<String, Object> resultsCluster = new HashMap<>();
        resultsCluster.put("url", "http://" + elasticsearch.getHttpHostAddress());
        resultsCluster.put("index", RESULTS_INDEX);
        ac.setResultsCluster(resultsCluster);

        Path emptyOutputDir = outputDir.resolve("empty-run");
        ac.setOutputDirectory(emptyOutputDir.toString());
        ac.setLatencyMetrics(List.of("server_latency_median"));
        ac.setGeneratePlots(false);

        config.setAnalysis(ac);

        // Should not throw, just return early with warning
        AnalyzeCommand.run(config);

        // No CSV files should be created
        if (Files.exists(emptyOutputDir)) {
            assertTrue(Files.list(emptyOutputDir).findAny().isEmpty(),
                    "No files should be created for empty results");
        }
    }

    // Helper method to create result document
    private Map<String, Object> createResultDoc(
            String engine,
            String paramKey,
            double recall,
            Double latencyMedian,
            Double serverLatencyMedian,
            Double throughput,
            Double serverThroughput,
            String recallLabel
    ) {
        BenchmarkResult result = new BenchmarkResult(
                TEST_RUN_ID,
                engine,
                "1.0",
                "vector_search",
                "test-dataset",
                paramKey,
                Map.of()
        );

        result.addMetric("recall", recall);
        if (latencyMedian != null) {
            result.addMetric("latency_median", latencyMedian);
        }
        if (serverLatencyMedian != null) {
            result.addMetric("server_latency_median", serverLatencyMedian);
        }
        if (throughput != null) {
            result.addMetric("throughput", throughput);
        }
        if (serverThroughput != null) {
            result.addMetric("server_throughput", serverThroughput);
        }
        result.addMetadata("recall_label", recallLabel);

        return result.toMap();
    }
}
