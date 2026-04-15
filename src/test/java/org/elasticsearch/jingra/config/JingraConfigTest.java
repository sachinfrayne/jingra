package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JingraConfigTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void getEngineConfig_returnsElasticsearchMap_whenEngineIsLowercase() {
        JingraConfig c = new JingraConfig();
        c.setEngine("elasticsearch");
        Map<String, Object> es = Map.of("url", "http://localhost:9200");
        c.setElasticsearch(es);
        assertSame(es, c.getEngineConfig());
    }

    @Test
    void getEngineConfig_returnsElasticsearchMap_whenEngineIsMixedCase() {
        JingraConfig c = new JingraConfig();
        c.setEngine("Elasticsearch");
        Map<String, Object> es = Map.of("url", "http://localhost:9200");
        c.setElasticsearch(es);
        assertSame(es, c.getEngineConfig());
    }

    @Test
    void getEngineConfig_returnsOpensearchMap() {
        JingraConfig c = new JingraConfig();
        c.setEngine("opensearch");
        Map<String, Object> os = Map.of("url", "http://localhost:9200");
        c.setOpensearch(os);
        assertSame(os, c.getEngineConfig());
    }

    @Test
    void getEngineConfig_returnsQdrantMap() {
        JingraConfig c = new JingraConfig();
        c.setEngine("qdrant");
        Map<String, Object> q = Map.of("url", "http://localhost:6333");
        c.setQdrant(q);
        assertSame(q, c.getEngineConfig());
    }

    @Test
    void getEngineConfig_throwsIllegalStateWithMessage_whenEngineUnknown() {
        JingraConfig c = new JingraConfig();
        c.setEngine("nope");
        IllegalStateException ex = assertThrows(IllegalStateException.class, c::getEngineConfig);
        assertEquals("Unknown engine: nope", ex.getMessage());
    }

    /**
     * Production uses {@code engine.toLowerCase()} with no null check; null engine yields NPE (not
     * {@link IllegalStateException}).
     */
    @Test
    void getEngineConfig_throwsNullPointer_whenEngineIsNull() {
        JingraConfig c = new JingraConfig();
        c.setEngine(null);
        assertThrows(NullPointerException.class, c::getEngineConfig);
    }

    @Test
    void getActiveDataset_returnsConfiguredEntry_whenKeyExists() {
        JingraConfig c = new JingraConfig();
        c.setDataset("main");
        DatasetConfig ds = new DatasetConfig();
        ds.setType("dense_vector");
        c.setDatasets(Map.of("main", ds));
        assertSame(ds, c.getActiveDataset());
    }

    @Test
    void getActiveDataset_throws_whenDatasetKeyIsNull() {
        JingraConfig c = new JingraConfig();
        c.setDataset(null);
        c.setDatasets(Map.of("x", new DatasetConfig()));
        IllegalStateException ex = assertThrows(IllegalStateException.class, c::getActiveDataset);
        assertEquals("No dataset configured", ex.getMessage());
    }

    @Test
    void getActiveDataset_throws_whenDatasetsMapIsNull() {
        JingraConfig c = new JingraConfig();
        c.setDataset("main");
        c.setDatasets(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, c::getActiveDataset);
        assertEquals("No dataset configured", ex.getMessage());
    }

    @Test
    void getActiveDataset_throwsWithMessage_whenDatasetNameMissingFromMap() {
        JingraConfig c = new JingraConfig();
        c.setDataset("missing");
        c.setDatasets(new HashMap<>());
        IllegalStateException ex = assertThrows(IllegalStateException.class, c::getActiveDataset);
        assertEquals("Dataset not found: missing", ex.getMessage());
    }

    @Test
    void gettersSetters_roundTrip_allRootFields() {
        Map<String, Object> es = Map.of("url", "http://es:9200");
        Map<String, Object> os = Map.of("url", "http://os:9200");
        Map<String, Object> qd = Map.of("url", "http://q:6333");

        DatasetConfig datasetConfig = new DatasetConfig();
        datasetConfig.setType("t");
        Map<String, DatasetConfig> datasets = Map.of("d1", datasetConfig);

        EvaluationConfig evaluation = new EvaluationConfig();
        evaluation.setWarmupWorkers(1);
        evaluation.setMeasurementWorkers(2);
        evaluation.setWarmupRounds(3);
        evaluation.setMeasurementRounds(4);

        OutputConfig output = new OutputConfig();
        OutputConfig.ResultsSinkConfig sink = new OutputConfig.ResultsSinkConfig();
        sink.setType("file");
        sink.setConfig(Map.of("path", "/tmp/out"));
        sink.setWriteQueryMetrics(false);
        output.setSinks(List.of(sink));

        LoadConfig load = new LoadConfig();
        load.setBatchSize(5000);
        load.setThreads(8);
        load.setQueueCapacity(40);

        LoggingConfig logging = new LoggingConfig();
        logging.setLevel("WARN");
        logging.setLoggers(Map.of("org.example", "DEBUG"));

        AnalysisConfig analysis = new AnalysisConfig();
        analysis.setRunId("test-run-123");
        analysis.setEngines(List.of("elasticsearch", "qdrant"));
        analysis.setResultsCluster(Map.of("url", "http://localhost:9200"));
        analysis.setOutputDirectory("./test-output");

        JingraConfig c = new JingraConfig();
        c.setEngine("elasticsearch");
        c.setDataset("d1");
        c.setElasticsearch(es);
        c.setOpensearch(os);
        c.setQdrant(qd);
        c.setDatasets(datasets);
        c.setEvaluation(evaluation);
        c.setOutput(output);
        c.setLoad(load);
        c.setLogging(logging);
        c.setAnalysis(analysis);

        assertEquals("elasticsearch", c.getEngine());
        assertEquals("d1", c.getDataset());
        assertSame(es, c.getElasticsearch());
        assertSame(os, c.getOpensearch());
        assertSame(qd, c.getQdrant());
        assertSame(datasets, c.getDatasets());
        assertSame(evaluation, c.getEvaluation());
        assertSame(output, c.getOutput());
        assertSame(load, c.getLoad());
        assertSame(logging, c.getLogging());
        assertSame(analysis, c.getAnalysis());

        assertEquals(1, c.getEvaluation().getWarmupWorkers());
        assertEquals(2, c.getEvaluation().getMeasurementWorkers());
        assertEquals(3, c.getEvaluation().getWarmupRounds());
        assertEquals(4, c.getEvaluation().getMeasurementRounds());
        assertEquals("file", c.getOutput().getSinks().getFirst().getType());
        assertEquals(5000, c.getLoad().getBatchSize());
        assertEquals("WARN", c.getLogging().getLevel());
        assertEquals("DEBUG", c.getLogging().getLoggers().get("org.example"));
        assertEquals("test-run-123", c.getAnalysis().getRunId());
        assertEquals("./test-output", c.getAnalysis().getOutputDirectory());
    }

    @Test
    void deserializesYaml_rootAndNestedPropertyMappings() throws Exception {
        String yaml =
                """
                engine: elasticsearch
                dataset: main
                elasticsearch:
                  url: http://localhost:9200
                opensearch:
                  url: http://os:9200
                qdrant:
                  url: http://q:6333
                datasets:
                  main:
                    type: dense_vector
                evaluation:
                  warmup_workers: 2
                  measurement_workers: 4
                  warmup_rounds: 1
                  measurement_rounds: 2
                output:
                  sinks:
                    - type: file
                      config:
                        path: /out
                      write_query_metrics: false
                load:
                  batch_size: 1000
                  threads: 3
                  queue_capacity: 30
                logging:
                  level: DEBUG
                  loggers:
                    org.foo: TRACE
                analysis:
                  run_id: "test-yaml-run"
                  engines:
                    - elasticsearch
                    - qdrant
                  results_cluster:
                    url: "http://results:9200"
                  output_directory: "./yaml-output"
                """;

        JingraConfig c = YAML_MAPPER.readValue(yaml, JingraConfig.class);

        assertEquals("elasticsearch", c.getEngine());
        assertEquals("main", c.getDataset());
        assertEquals("http://localhost:9200", c.getElasticsearch().get("url"));
        assertEquals("http://os:9200", c.getOpensearch().get("url"));
        assertEquals("http://q:6333", c.getQdrant().get("url"));

        assertNotNull(c.getDatasets().get("main"));
        assertEquals("dense_vector", c.getDatasets().get("main").getType());

        assertEquals(2, c.getEvaluation().getWarmupWorkers());
        assertEquals(4, c.getEvaluation().getMeasurementWorkers());
        assertEquals(1, c.getEvaluation().getWarmupRounds());
        assertEquals(2, c.getEvaluation().getMeasurementRounds());

        assertEquals(1, c.getOutput().getSinks().size());
        assertEquals("file", c.getOutput().getSinks().getFirst().getType());
        assertEquals("/out", c.getOutput().getSinks().getFirst().getConfig().get("path"));
        assertEquals(false, c.getOutput().getSinks().getFirst().getWriteQueryMetrics());

        assertEquals(1000, c.getLoad().getBatchSize());
        assertEquals(3, c.getLoad().getThreads());
        assertEquals(30, c.getLoad().getQueueCapacity());

        assertEquals("DEBUG", c.getLogging().getLevel());
        assertEquals("TRACE", c.getLogging().getLoggers().get("org.foo"));

        assertNotNull(c.getAnalysis());
        assertEquals("test-yaml-run", c.getAnalysis().getRunId());
        assertEquals(List.of("elasticsearch", "qdrant"), c.getAnalysis().getEngines());
        assertEquals("http://results:9200", c.getAnalysis().getResultsCluster().get("url"));
        assertEquals("./yaml-output", c.getAnalysis().getOutputDirectory());
    }
}
