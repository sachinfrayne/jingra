package org.elasticsearch.jingra;

import org.elasticsearch.jingra.cli.EvalCommand;
import org.elasticsearch.jingra.cli.LoadCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Main#runMain(String[], Main.CommandHandler, Main.CommandHandler)} and CLI wiring.
 */
class MainTest {

    @AfterEach
    void restoreDefaultHandlers() {
        Main.defaultLoadHandler = LoadCommand::run;
        Main.defaultEvalHandler = EvalCommand::run;
    }

    private static String minimalValidYaml() {
        return """
                engine: elasticsearch
                dataset: test-dataset

                elasticsearch:
                  url_env: ES_URL

                datasets:
                  test-dataset:
                    type: parquet
                    index_name: test_index
                    vector_size: 128
                    distance: cosine
                    schema_name: test_schema
                    query_name: test_query
                    path:
                      data_path: data.parquet
                      queries_path: queries.parquet
                    data_mapping:
                      id_field: id
                      vector_field: vector
                    queries_mapping:
                      query_vector_field: vector
                      ground_truth_field: ground_truth
                """;
    }

    @Test
    void runMain_insufficientArgs() {
        assertEquals(1, Main.runMain(new String[]{}, (c) -> {}, (c) -> {}));
        assertEquals(1, Main.runMain(new String[]{"load"}, (c) -> {}, (c) -> {}));
    }

    @Test
    void runMain_unknownCommand(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(1, Main.runMain(new String[]{"nope", configFile.toString()}, (c) -> {}, (c) -> {}));
    }

    @Test
    void runMain_configMissing() {
        assertEquals(1, Main.runMain(new String[]{"load", "/nonexistent/jingra.yaml"}, (c) -> {}, (c) -> {}));
    }

    @Test
    void runMain_loadSuccessWithNoOpHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(0, Main.runMain(new String[]{"load", configFile.toString()}, c -> {}, c -> {}));
    }

    @Test
    void runMain_evalSuccessWithNoOpHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(0, Main.runMain(new String[]{"eval", configFile.toString()}, c -> {}, c -> {}));
    }

    @Test
    void commandHandlerIsFunctionalInterface() {
        Main.CommandHandler h = c -> {};
        assertNotNull(h);
    }

    @Test
    void runMain_singleArgUsesDefaultHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        Main.defaultLoadHandler = c -> {};
        Main.defaultEvalHandler = c -> {};
        assertEquals(0, Main.runMain(new String[]{"load", configFile.toString()}));
    }
}
