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
        pathConfig.setQueriesPath("src/test/resources/parquet/test_vector_queries.parquet");
        datasetConfig.setPath(pathConfig);

        DatasetConfig.QueriesMappingConfig queriesMapping = new DatasetConfig.QueriesMappingConfig();
        queriesMapping.setQueryVectorField("embedding");
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
            public boolean dataStoreExists(String indexName) {
                return false;
            }
        };
        assertThrows(RuntimeException.class,
                () -> EvalCommand.run(jingraConfig, c -> engine, c -> List.of(new MockResultsSink())));
    }

    /** Covers {@code queriesUrlEnv != null} → {@link org.elasticsearch.jingra.utils.FileDownloader#ensureFileExists}. */
    @Test
    void run_whenQueriesUrlEnvSet_stillRunsWhenLocalQueriesFileExists() throws Exception {
        jingraConfig.getActiveDataset().getPath().setQueriesUrlEnv("UNUSED_IF_FILE_EXISTS");
        MockBenchmarkEngine engine = new MockBenchmarkEngine();
        MockResultsSink sink = new MockResultsSink();
        EvalCommand.run(jingraConfig, c -> engine, c -> List.of(sink));
        assertTrue(engine.queryCount > 0);
    }

    @Test
    void run_whenQueriesFileMissingAndNoQueriesUrlEnv_throws() {
        jingraConfig.getActiveDataset().getPath().setQueriesPath("/no/such/path/queries-does-not-exist.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> EvalCommand.run(jingraConfig, c -> engine, c -> List.of(new MockResultsSink())));
        assertTrue(ex.getMessage().contains("Queries file not found"));
    }

    @Test
    void run_whenQueriesUrlBaseEnvSet_filePresent_runsWithoutDownload() throws Exception {
        jingraConfig.getActiveDataset().getPath().setQueriesUrlBaseEnv("UNUSED_BASE_URL_ENV");
        MockBenchmarkEngine engine = new MockBenchmarkEngine();
        MockResultsSink sink = new MockResultsSink();
        EvalCommand.run(jingraConfig, c -> engine, c -> List.of(sink));
        assertTrue(engine.queryCount > 0);
    }

    @Test
    void run_whenQueriesUrlBaseEnvSet_fileMissing_downloadsFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmpDir)
            throws Exception {
        byte[] body = new byte[]{1, 2, 3};
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        String filename = "test_vector_queries.parquet";
        server.createContext("/base/" + filename, ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.setExecutor(null);
        server.start();
        try {
            int port = server.getAddress().getPort();
            org.elasticsearch.jingra.utils.FileDownloader.downloadBaseUrlOverrideForTests
                .set("http://127.0.0.1:" + port + "/base");
            java.nio.file.Path localQueries = tmpDir.resolve(filename);
            jingraConfig.getActiveDataset().getPath().setQueriesPath(localQueries.toString());
            jingraConfig.getActiveDataset().getPath().setQueriesUrlBaseEnv("DATASET_QUERIES_URL");
            MockBenchmarkEngine engine = new MockBenchmarkEngine();
            // The query file is 3 garbage bytes so EvalCommand fails reading it, but the download happened
            assertThrows(RuntimeException.class,
                () -> EvalCommand.run(jingraConfig, c -> engine, c -> List.of(new MockResultsSink())));
            assertTrue(localQueries.toFile().exists(), "file should have been downloaded");
        } finally {
            server.stop(0);
            org.elasticsearch.jingra.utils.FileDownloader.downloadBaseUrlOverrideForTests.remove();
        }
    }

    @Test
    void privateCtor() throws Exception {
        var cl = Class.forName("org.elasticsearch.jingra.cli.EvalCommand");
        var ctor = cl.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }

    @Test
    void runIteratesOverEachConfiguredDataset() throws Exception {
        DatasetConfig second = new DatasetConfig();
        second.setIndexName("test-index");
        second.setQueryName("second-query");
        DatasetConfig.PathConfig pathConfig = new DatasetConfig.PathConfig();
        pathConfig.setQueriesPath("src/test/resources/parquet/test_vector_queries.parquet");
        second.setPath(pathConfig);
        DatasetConfig.QueriesMappingConfig qm = new DatasetConfig.QueriesMappingConfig();
        qm.setQueryVectorField("embedding");
        qm.setGroundTruthField("ground_truth");
        qm.setConditionsField("meta_conditions");
        second.setQueriesMapping(qm);
        Map<String, Object> p = new HashMap<>();
        p.put("k", 5);
        p.put("size", 5);
        second.setParamGroups(Map.of("default", List.of(p)));

        Map<String, DatasetConfig> merged = new HashMap<>(jingraConfig.getDatasets());
        merged.put("second-dataset", second);
        jingraConfig.setDatasets(merged);
        jingraConfig.setDatasetNames(List.of("test-dataset", "second-dataset"));

        MockBenchmarkEngine engine = new MockBenchmarkEngine();
        MockResultsSink sink = new MockResultsSink();
        EvalCommand.run(jingraConfig, c -> engine, c -> List.of(sink));

        long uniqueDatasets = sink.results.stream()
                .map(r -> r.getDataset())
                .distinct()
                .count();
        assertEquals(2, uniqueDatasets, "expected separate BenchmarkResult per dataset");
    }
}
