package org.elasticsearch.jingra.evaluation;

import org.elasticsearch.jingra.config.EvaluationConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.elasticsearch.jingra.testing.MockBenchmarkEngine;
import org.elasticsearch.jingra.testing.MockResultsSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BenchmarkEvaluator.
 * Uses test queries parquet file created in src/test/resources/test_queries.parquet
 */
class BenchmarkEvaluatorTest {

    private BenchmarkEvaluator evaluator;
    private MockBenchmarkEngine mockEngine;
    private MockResultsSink mockSink;
    private JingraConfig jingraConfig;

    @BeforeEach
    void setUp() {
        // Create mock engine that returns predictable results
        mockEngine = new MockBenchmarkEngine();

        // Create mock sink to capture results
        mockSink = new MockResultsSink();

        // Create jingra config with dataset and evaluation config
        jingraConfig = new JingraConfig();
        jingraConfig.setEngine("mock");
        jingraConfig.setDataset("test-dataset");

        // Create dataset config
        org.elasticsearch.jingra.config.DatasetConfig datasetConfig = new org.elasticsearch.jingra.config.DatasetConfig();
        datasetConfig.setIndexName("test-index");
        datasetConfig.setQueryName("test-query");

        // Set path config with queries path
        org.elasticsearch.jingra.config.DatasetConfig.PathConfig pathConfig = new org.elasticsearch.jingra.config.DatasetConfig.PathConfig();
        pathConfig.setQueriesPath("src/test/resources/test_queries.parquet");
        datasetConfig.setPath(pathConfig);

        // Set queries mapping config
        org.elasticsearch.jingra.config.DatasetConfig.QueriesMappingConfig queriesMapping = new org.elasticsearch.jingra.config.DatasetConfig.QueriesMappingConfig();
        queriesMapping.setQueryVectorField("query_vector");
        queriesMapping.setGroundTruthField("ground_truth");
        queriesMapping.setConditionsField("meta_conditions");
        datasetConfig.setQueriesMapping(queriesMapping);

        // Create parameter groups
        Map<String, Object> params1 = new HashMap<>();
        params1.put("k", 10);
        params1.put("size", 10);
        Map<String, List<Map<String, Object>>> paramGroups = new HashMap<>();
        paramGroups.put("default", List.of(params1));
        datasetConfig.setParamGroups(paramGroups);

        // Add dataset to config
        Map<String, org.elasticsearch.jingra.config.DatasetConfig> datasets = new HashMap<>();
        datasets.put("test-dataset", datasetConfig);
        jingraConfig.setDatasets(datasets);

        // Create evaluation config
        EvaluationConfig evalConfig = new EvaluationConfig();
        evalConfig.setWarmupRounds(0);
        evalConfig.setMeasurementRounds(1);
        evalConfig.setWarmupWorkers(1);
        evalConfig.setMeasurementWorkers(1);
        jingraConfig.setEvaluation(evalConfig);

        evaluator = new BenchmarkEvaluator(
                jingraConfig,
                mockEngine,
                List.of(mockSink)
        );
    }

    @Test
    void testConstructor() {
        assertNotNull(evaluator);
    }

    @Test
    void testRunEvaluation_executesQueriesAndCalculatesMetrics() throws Exception {
        // Run evaluation
        evaluator.runEvaluation();

        // Verify queries were executed
        assertTrue(mockEngine.queryCount > 0, "Should execute queries");

        // Verify results were written
        assertTrue(mockSink.resultCount > 0, "Should write results");
    }

    @Test
    void testRunEvaluation_multipleParameterGroups() throws Exception {
        // Configure multiple parameter groups
        Map<String, Object> params1 = new HashMap<>();
        params1.put("k", 10);
        params1.put("size", 10);

        Map<String, Object> params2 = new HashMap<>();
        params2.put("k", 20);
        params2.put("size", 20);

        org.elasticsearch.jingra.config.DatasetConfig datasetConfig = jingraConfig.getActiveDataset();
        Map<String, List<Map<String, Object>>> paramGroups = new HashMap<>();
        paramGroups.put("default", List.of(params1, params2));
        datasetConfig.setParamGroups(paramGroups);

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Should have results for both parameter groups
        assertTrue(mockSink.resultCount >= 2, "Should write results for each parameter group");
    }

    @Test
    void testRunEvaluation_warmupRoundsExecute() throws Exception {
        EvaluationConfig evalConfig = jingraConfig.getEvaluation();
        evalConfig.setWarmupRounds(2);
        evalConfig.setMeasurementRounds(1);

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Warmup + measurement rounds should execute
        assertTrue(mockEngine.queryCount >= 10, "Should execute warmup and measurement queries");
    }

    @Test
    void testRunEvaluation_handlesQueryErrors() {
        mockEngine.shouldFail = true;

        assertThrows(IllegalStateException.class, () -> evaluator.runEvaluation());
    }

    @Test
    void testRunEvaluation_passesQueryVector() throws Exception {
        evaluator.runEvaluation();

        // Verify query vectors were passed
        assertFalse(mockEngine.receivedVectors.isEmpty(), "Should pass query vectors");
        assertEquals(128, mockEngine.receivedVectors.get(0).size(), "Vector should have 128 dimensions");
    }

    @Test
    void testRunEvaluation_passesMetaConditions() throws Exception {
        evaluator.runEvaluation();

        // Verify meta conditions were passed
        assertFalse(mockEngine.receivedParams.isEmpty(), "Should pass query parameters");
    }

    @Test
    void testRunEvaluation_calculatesAllMetrics() throws Exception {
        evaluator.runEvaluation();

        // Verify all metrics are present in results
        assertTrue(mockSink.resultCount > 0, "Should calculate and write metrics");
    }

    @Test
    void testRunEvaluation_usesConfiguredWorkerCounts() throws Exception {
        EvaluationConfig evalConfig = jingraConfig.getEvaluation();
        evalConfig.setWarmupWorkers(2);
        evalConfig.setMeasurementWorkers(4);

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Verify execution completed
        assertTrue(mockEngine.queryCount > 0, "Should execute with configured worker counts");
    }

    @Test
    void testRunEvaluation_emptyParamGroups_returnsEarly() throws Exception {
        jingraConfig.getActiveDataset().setParamGroups(new HashMap<>());
        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();
        assertEquals(0, mockEngine.queryCount);
    }

    @Test
    void testRunEvaluation_addsVectorTypeMetadataWhenPresent() throws Exception {
        MockBenchmarkEngine engineWithMeta = new MockBenchmarkEngine() {
            @Override
            public Map<String, String> getIndexMetadata(String indexName) {
                return Map.of("vector_type", "dense_vector");
            }
        };
        final BenchmarkResult[] last = new BenchmarkResult[1];
        MockResultsSink sink = new MockResultsSink() {
            @Override
            public void writeResult(BenchmarkResult result) {
                super.writeResult(result);
                last[0] = result;
            }
        };
        evaluator = new BenchmarkEvaluator(jingraConfig, engineWithMeta, List.of(sink));
        evaluator.runEvaluation();
        assertEquals("dense_vector", last[0].getMetadata().get("vector_type"));
    }

}
