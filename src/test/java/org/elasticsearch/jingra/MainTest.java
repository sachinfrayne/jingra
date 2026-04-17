package org.elasticsearch.jingra;

import org.elasticsearch.jingra.cli.AnalyzeCommand;
import org.elasticsearch.jingra.cli.EvalCommand;
import org.elasticsearch.jingra.cli.LoadCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for
 * {@link Main#runMain(String[], Main.CommandHandler, Main.CommandHandler)} and
 * CLI wiring.
 */
class MainTest {

    /**
     * Initial {@link Main#nonZeroExitAction} reference from {@link Main}'s static
     * initializer (restoring
     * {@code code -> System.exit(code)} here would replace the default with a
     * different lambda object).
     */
    private static final IntConsumer MAIN_DEFAULT_NON_ZERO_EXIT = Main.nonZeroExitAction;

    @AfterEach
    void restoreDefaultHandlers() {
        Main.defaultLoadHandler = LoadCommand::run;
        Main.defaultEvalHandler = EvalCommand::run;
        Main.defaultAnalyzeHandler = AnalyzeCommand::run;
        Main.nonZeroExitAction = MAIN_DEFAULT_NON_ZERO_EXIT;
    }

    private static String jingraVersionFromEnvReflect() throws Exception {
        Method m = Main.class.getDeclaredMethod("jingraVersionFromEnv");
        m.setAccessible(true);
        return (String) m.invoke(null);
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
        assertEquals(1, Main.runMain(new String[] {}, (c) -> {
        }, (c) -> {
        }, (c) -> {
        }));
        assertEquals(1, Main.runMain(new String[] { "load" }, (c) -> {
        }, (c) -> {
        }, (c) -> {
        }));
    }

    @Test
    void runMain_unknownCommand(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(1, Main.runMain(new String[] { "nope", configFile.toString() }, (c) -> {
        }, (c) -> {
        }, (c) -> {
        }));
    }

    @Test
    void runMain_configMissing() {
        assertEquals(1, Main.runMain(new String[] { "load", "/nonexistent/jingra.yaml" }, (c) -> {
        }, (c) -> {
        }, (c) -> {
        }));
    }

    @Test
    void runMain_loadSuccessWithNoOpHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(0, Main.runMain(new String[] { "load", configFile.toString() }, c -> {
        }, c -> {
        }, c -> {
        }));
    }

    @Test
    void runMain_evalSuccessWithNoOpHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(0, Main.runMain(new String[] { "eval", configFile.toString() }, c -> {
        }, c -> {
        }, c -> {
        }));
    }

    @Test
    void runMain_analyzeSuccessWithNoOpHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        assertEquals(0, Main.runMain(new String[] { "analyze", configFile.toString() }, c -> {
        }, c -> {
        }, c -> {
        }));
    }

    @Test
    void runMain_analyzeSuccess_analysisOnlyYamlWithoutBenchmarkEngine(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("analyze-only.yaml");
        Files.writeString(
                configFile,
                """
                        analysis:
                          run_id: run-1
                          engines:
                            - elasticsearch
                            - qdrant
                          results_cluster:
                            url: http://localhost:9200
                          output_directory: /tmp/out
                        """);
        assertEquals(0, Main.runMain(new String[] { "analyze", configFile.toString() }, c -> {
        }, c -> {
        }, c -> {
        }));
    }

    @Test
    void commandHandlerIsFunctionalInterface() {
        Main.CommandHandler h = c -> {
        };
        assertNotNull(h);
    }

    @Test
    void runMain_singleArgUsesDefaultHandlers(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        Main.defaultLoadHandler = c -> {
        };
        Main.defaultEvalHandler = c -> {
        };
        Main.defaultAnalyzeHandler = c -> {
        };
        assertEquals(0, Main.runMain(new String[] { "load", configFile.toString() }));
    }

    @Test
    @ClearEnvironmentVariable(key = "JINGRA_VERSION")
    void jingraVersionFromEnv_absent_returnsUnknown() throws Exception {
        assertEquals("unknown", jingraVersionFromEnvReflect());
    }

    @Test
    @SetEnvironmentVariable(key = "JINGRA_VERSION", value = "   ")
    void jingraVersionFromEnv_blank_returnsUnknown() throws Exception {
        assertEquals("unknown", jingraVersionFromEnvReflect());
    }

    @Test
    @SetEnvironmentVariable(key = "JINGRA_VERSION", value = "  2.1.0  ")
    void jingraVersionFromEnv_trimmedValueReturned() throws Exception {
        assertEquals("2.1.0", jingraVersionFromEnvReflect());
    }

    @Test
    void main_success_doesNotExit(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, minimalValidYaml());
        Main.defaultLoadHandler = c -> {
        };
        Main.defaultEvalHandler = c -> {
        };
        Main.defaultAnalyzeHandler = c -> {
        };
        int[] lastNonZero = { -1 };
        Main.nonZeroExitAction = c -> lastNonZero[0] = c;
        Main.main(new String[] { "load", configFile.toString() });
        assertEquals(-1, lastNonZero[0]);
    }

    @Test
    void main_failure_exitsWithCode1() {
        int[] lastNonZero = { -1 };
        Main.nonZeroExitAction = c -> lastNonZero[0] = c;
        Main.main(new String[0]);
        assertEquals(1, lastNonZero[0]);
    }

    @Test
    void mainConstructor_reflectiveInstance() throws Exception {
        Main instance = Main.class.getDeclaredConstructor().newInstance();
        assertNotNull(instance);
    }
}
