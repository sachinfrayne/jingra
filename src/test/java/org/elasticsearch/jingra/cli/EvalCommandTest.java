package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.EvaluationConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.testing.MockBenchmarkEngine;
import org.elasticsearch.jingra.testing.MockResultsSink;
import org.elasticsearch.jingra.engine.EngineFactory;
import org.elasticsearch.jingra.output.ResultsSinkFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalCommandTest {

    private JingraConfig jingraConfig;

    @AfterEach
    void restoreFactories() {
        EvalCommand.engineFactory = EngineFactory::create;
        EvalCommand.sinkFactory = ResultsSinkFactory::create;
    }

    @BeforeEach
    void setUp() {
        jingraConfig = new JingraConfig();
        jingraConfig.setEngine("mock");
        jingraConfig.setDataset("test-dataset");

        DatasetConfig datasetConfig = new DatasetConfig();
        datasetConfig.setIndexName("test-index");
        datasetConfig.setQueryName("test-query");

        DatasetConfig.PathConfig pathConfig = new DatasetConfig.PathConfig();
        pathConfig.setQueriesPath("src/test/resources/test_queries.parquet");
        datasetConfig.setPath(pathConfig);

        DatasetConfig.QueriesMappingConfig queriesMapping = new DatasetConfig.QueriesMappingConfig();
        queriesMapping.setQueryVectorField("query_vector");
        queriesMapping.setGroundTruthField("ground_truth");
        queriesMapping.setConditionsField("meta_conditions");
        datasetConfig.setQueriesMapping(queriesMapping);

        Map<String, Object> params1 = new HashMap<>();
        params1.put("k", 10);
        params1.put("size", 10);
        Map<String, List<Map<String, Object>>> paramGroups = new HashMap<>();
        paramGroups.put("default", List.of(params1));
        datasetConfig.setParamGroups(paramGroups);

        Map<String, DatasetConfig> datasets = new HashMap<>();
        datasets.put("test-dataset", datasetConfig);
        jingraConfig.setDatasets(datasets);

        EvaluationConfig evalConfig = new EvaluationConfig();
        evalConfig.setWarmupRounds(0);
        evalConfig.setMeasurementRounds(1);
        evalConfig.setWarmupWorkers(1);
        evalConfig.setMeasurementWorkers(1);
        jingraConfig.setEvaluation(evalConfig);
    }

    @Test
    void runCompletesWithMockEngine() throws Exception {
        MockBenchmarkEngine engine = new MockBenchmarkEngine();
        MockResultsSink sink = new MockResultsSink();
        EvalCommand.run(jingraConfig, c -> engine, c -> List.of(sink));
        assertTrue(engine.queryCount > 0);
        assertTrue(sink.resultCount > 0);
    }

    @Test
    void publicRunUsesInjectedFactories() throws Exception {
        MockBenchmarkEngine engine = new MockBenchmarkEngine();
        MockResultsSink sink = new MockResultsSink();
        EvalCommand.engineFactory = c -> engine;
        EvalCommand.sinkFactory = c -> List.of(sink);
        EvalCommand.run(jingraConfig);
        assertTrue(engine.queryCount > 0);
        assertTrue(sink.resultCount > 0);
    }

    @Test
    void runThrowsWhenConnectFails() {
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean connect() {
                return false;
            }
        };
        assertThrows(RuntimeException.class,
                () -> EvalCommand.run(jingraConfig, c -> engine, c -> List.of(new MockResultsSink())));
    }

    @Test
    void runThrowsWhenIndexMissing() {
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean indexExists(String indexName) {
                return false;
            }
        };
        assertThrows(RuntimeException.class,
                () -> EvalCommand.run(jingraConfig, c -> engine, c -> List.of(new MockResultsSink())));
    }

    @Test
    void privateCtor() throws Exception {
        var cl = Class.forName("org.elasticsearch.jingra.cli.EvalCommand");
        var ctor = cl.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }
}
