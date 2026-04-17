package org.elasticsearch.jingra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadFromFile_nullPath() {
        assertThrows(NullPointerException.class, () -> ConfigLoader.loadFromFile(null));
        assertThrows(NullPointerException.class, () -> ConfigLoader.loadFromFile(null, "analyze"));
    }

    @Test
    void loadFromFile_missingFile() {
        IOException ex = assertThrows(IOException.class, () -> ConfigLoader.loadFromFile("/nonexistent/config.yaml"));
        assertEquals("Config file not found: /nonexistent/config.yaml", ex.getMessage());
    }

    @Test
    void loadFromFile_pathIsDirectory() {
        IOException ex = assertThrows(IOException.class, () -> ConfigLoader.loadFromFile(tempDir.toString()));
        assertEquals("Config path is not a regular file: " + tempDir, ex.getMessage());
    }

    @Test
    void loadFromFile_validElasticsearchYaml() throws IOException {
        Path file = copyResourceToTemp("/config/valid-elasticsearch.yaml");
        JingraConfig config = ConfigLoader.loadFromFile(file.toString());
        assertEquals("elasticsearch", config.getEngine());
        assertEquals("test-dataset", config.getDataset());
        assertNotNull(config.getElasticsearch());
        assertNotNull(config.getDatasets());
        assertEquals(1, config.getDatasets().size());
        DatasetConfig ds = config.getActiveDataset();
        assertEquals("parquet", ds.getType());
        assertEquals("test_index", ds.getIndexName());
        assertEquals(128, ds.getVectorSize());
    }

    @Test
    void loadFromFile_validQdrantYaml() throws IOException {
        Path file = copyResourceToTemp("/config/valid-qdrant.yaml");
        JingraConfig config = ConfigLoader.loadFromFile(file.toString());
        var engineConfig = config.getEngineConfig();
        assertNotNull(engineConfig);
        assertEquals("QDRANT_URL", engineConfig.get("url_env"));
        assertEquals("QDRANT_KEY", engineConfig.get("api_key_env"));
    }

    @Test
    void loadFromFile_malformedYaml() throws IOException {
        Path file = copyResourceToTemp("/config/malformed.yaml");
        assertThrows(IOException.class, () -> ConfigLoader.loadFromFile(file.toString()));
    }

    @Test
    void loadFromResource_valid() throws IOException {
        JingraConfig config = ConfigLoader.loadFromResource("/config/valid-elasticsearch.yaml");
        assertEquals("elasticsearch", config.getEngine());
        assertEquals("test-dataset", config.getDataset());
    }

    @Test
    void loadFromResource_missing() {
        IOException ex =
                assertThrows(IOException.class, () -> ConfigLoader.loadFromResource("/config/missing-not-there.yaml"));
        assertEquals("Config resource not found: /config/missing-not-there.yaml", ex.getMessage());
    }

    @Test
    void loadFromResource_nullPath() {
        assertThrows(NullPointerException.class, () -> ConfigLoader.loadFromResource(null));
    }

    @Test
    void loadFromFile_baseValidation_missingEngine() throws IOException {
        Path f = write(
                tempDir,
                "bad.yaml",
                """
                        dataset: "test-dataset"
                        elasticsearch:
                          url_env: "ES_URL"
                        datasets:
                          test-dataset:
                            type: "parquet"
                        """);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadFromFile(f.toString()));
        assertEquals("Engine not specified in configuration", ex.getMessage());
    }

    @Test
    void loadFromFile_analyzeCommand_analysisOnlyYaml() throws IOException {
        Path f = write(
                tempDir,
                "analyze.yaml",
                """
                        analysis:
                          run_id: "run-1"
                          engines:
                            - elasticsearch
                            - qdrant
                          results_cluster:
                            url: "http://localhost:9200"
                          output_directory: "/tmp/out"
                        """);
        JingraConfig config = ConfigLoader.loadFromFile(f.toString(), "analyze");
        assertEquals("run-1", config.getAnalysis().getRunId());
        assertEquals(2, config.getAnalysis().getEngines().size());
    }

    @Test
    void loadFromFile_baseValidation_missingDataset() throws IOException {
        Path f = write(
                tempDir,
                "bad.yaml",
                """
                        engine: "elasticsearch"
                        elasticsearch:
                          url_env: "ES_URL"
                        datasets:
                          test-dataset:
                            type: "parquet"
                        """);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadFromFile(f.toString()));
        assertEquals("Dataset not specified in configuration", ex.getMessage());
    }

    @Test
    void loadFromFile_baseValidation_datasetNotInMap() throws IOException {
        Path f = write(
                tempDir,
                "bad.yaml",
                """
                        engine: "elasticsearch"
                        dataset: "missing"
                        elasticsearch:
                          url_env: "ES_URL"
                        datasets:
                          other:
                            type: "parquet"
                        """);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadFromFile(f.toString()));
        assertEquals("Dataset 'missing' not found in datasets configuration", ex.getMessage());
    }

    @Test
    void loadFromFile_baseValidation_unknownEngine() throws IOException {
        Path f = write(
                tempDir,
                "bad.yaml",
                """
                        engine: "unknown_engine"
                        dataset: "d"
                        datasets:
                          d:
                            type: "parquet"
                        """);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadFromFile(f.toString()));
        assertEquals("Engine configuration not found for: unknown_engine", ex.getMessage());
        assertEquals("Unknown engine: unknown_engine", ex.getCause().getMessage());
    }

    @Test
    void validateForAnalysis_nullConfig() {
        assertThrows(NullPointerException.class, () -> ConfigLoader.validateForAnalysis(null));
    }

    @Test
    void validateForAnalysis_ok() {
        ConfigLoader.validateForAnalysis(analysisCompleteConfigForAnalyze());
    }

    /**
     * Same shape as {@link ConfigValidatorTest} analysis fixture — exercises {@link ConfigLoader#validateForAnalysis}.
     */
    private static JingraConfig analysisCompleteConfigForAnalyze() {
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");
        config.setElasticsearch(Map.of("url_env", "ES_URL"));
        config.setDatasets(Map.of("test-dataset", new DatasetConfig()));
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("test-run-123");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        config.setAnalysis(ac);
        return config;
    }

    @Test
    void loadFromFile_baseValidation_emptyDatasets() throws IOException {
        Path f = write(
                tempDir,
                "e.yaml",
                """
                        engine: elasticsearch
                        dataset: d
                        elasticsearch:
                          url_env: ES_URL
                        datasets: {}
                        """);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadFromFile(f.toString()));
        assertEquals("No datasets configured", ex.getMessage());
    }

    private static Path write(Path dir, String name, String yaml) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, yaml);
        return f;
    }

    private Path copyResourceToTemp(String resourcePath) throws IOException {
        try (InputStream in = ConfigLoaderTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing classpath resource: " + resourcePath);
            String simple = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            Path out = tempDir.resolve(simple);
            Files.copy(in, out);
            return out;
        }
    }
}
