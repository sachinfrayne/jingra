package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads jingra configuration from YAML files.
 */
public final class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    /**
     * Load configuration from a file path.
     *
     * @param configPath path to the YAML config file
     * @return loaded configuration
     * @throws IOException if loading fails
     */
    public static JingraConfig loadFromFile(String configPath) throws IOException {
        return loadFromFile(configPath, null);
    }

    /**
     * @param command CLI command ({@code load}, {@code eval}, {@code analyze}); passed to base validation so
     *                {@code analyze} can omit benchmark {@code engine} when the config is analysis-only.
     */
    public static JingraConfig loadFromFile(String configPath, String command) throws IOException {
        Objects.requireNonNull(configPath, "configPath");
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Config file not found: " + configPath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Config path is not a regular file: " + configPath);
        }

        logger.info("Loading configuration from: {}", configPath);
        try (InputStream is = Files.newInputStream(path)) {
            JingraConfig config = yamlMapper.readValue(is, JingraConfig.class);
            ConfigValidator.validateBase(config, command);
            return config;
        }
    }

    /**
     * Load configuration from classpath resource.
     *
     * @param resourcePath classpath resource path (e.g., "/config/jingra.yaml")
     * @return loaded configuration
     * @throws IOException if loading fails
     */
    public static JingraConfig loadFromResource(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream is = ConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Config resource not found: " + resourcePath);
            }

            logger.info("Loading configuration from resource: {}", resourcePath);
            JingraConfig config = yamlMapper.readValue(is, JingraConfig.class);
            ConfigValidator.validateBase(config);
            return config;
        }
    }

    /**
     * Validates fields required for {@code eval} (benchmark evaluation). Call from {@link org.elasticsearch.jingra.cli.EvalCommand} only;
     * {@code load} does not require an {@code evaluation} block.
     */
    public static void validateForEvaluation(JingraConfig config) {
        ConfigValidator.validateForEvaluation(config);
    }

    /**
     * Validates fields required for {@code load} (data ingest). Call from {@link org.elasticsearch.jingra.cli.LoadCommand}.
     */
    public static void validateForLoad(JingraConfig config) {
        ConfigValidator.validateForLoad(config);
    }

    /**
     * Validates fields required for {@code analyze} (benchmark result analysis). Call from {@link org.elasticsearch.jingra.cli.AnalyzeCommand}.
     */
    public static void validateForAnalysis(JingraConfig config) {
        ConfigValidator.validateForAnalysis(config);
    }
}
