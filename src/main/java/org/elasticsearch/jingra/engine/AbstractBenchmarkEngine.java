package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base class for benchmark engines providing common functionality.
 */
public abstract class AbstractBenchmarkEngine implements BenchmarkEngine {
    /**
     * Relative directory for on-disk schema/query overrides (local dev, K8s mounts).
     * Templates live under {@code jingra-config/schemas/} and {@code jingra-config/queries/} (same layout for every engine).
     * Classpath fallback uses {@code /schemas/} and {@code /queries/}.
     */
    public static final String JINGRA_CONFIG_DIR = "jingra-config";

    protected static final Logger logger = LoggerFactory.getLogger(AbstractBenchmarkEngine.class);
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * When set under the active engine block (e.g. {@code opensearch}, {@code qdrant}), the first
     * materialized search request body is written as pretty-printed JSON to
     * {@code <query_dump_directory>/<shortName>-first-query.json}. Empty or unset disables dumps.
     */
    public static final String CONFIG_QUERY_DUMP_DIRECTORY = "query_dump_directory";

    protected final Map<String, Object> config;
    protected final String schemasPath;
    protected final String queriesPath;

    private final Path queryDumpDirectory;
    private final AtomicBoolean firstQueryDumpWritten = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, JsonNode> queryTemplateCache = new ConcurrentHashMap<>();

    protected AbstractBenchmarkEngine(Map<String, Object> config) {
        this.config = config;
        this.schemasPath = "schemas";
        this.queriesPath = "queries";
        String dumpDir = getConfigString(CONFIG_QUERY_DUMP_DIRECTORY, null);
        if (dumpDir != null && !dumpDir.isBlank()) {
            this.queryDumpDirectory = Paths.get(dumpDir).toAbsolutePath().normalize();
        } else {
            this.queryDumpDirectory = null;
        }
    }

    /**
     * Writes {@code requestJson} to the configured dump directory once per engine instance, using
     * filename {@code <engineShortName>-first-query.json}. Intended for the exact payload sent to
     * the engine (OpenSearch/Elasticsearch: search DSL JSON; Qdrant: {@link com.google.protobuf.util.JsonFormat}
     * view of the gRPC {@code SearchPoints} message).
     */
    protected void writeFirstQueryDumpIfConfigured(String engineShortName, String requestJson) {
        if (queryDumpDirectory == null) {
            return;
        }
        if (!firstQueryDumpWritten.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(queryDumpDirectory);
            Path path = queryDumpDirectory.resolve(engineShortName + "-first-query.json");
            String pretty = prettifyJsonForDump(requestJson);
            Files.writeString(
                    path,
                    pretty,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            logger.info("Wrote first query payload to {}", path.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write query dump under {}: {}", queryDumpDirectory, e.toString());
        }
    }

    /**
     * Cheap guard for callers to avoid doing expensive dump serialization work when dumps are disabled or already written.
     */
    protected boolean shouldWriteFirstQueryDump() {
        return queryDumpDirectory != null && !firstQueryDumpWritten.get();
    }

    private static String prettifyJsonForDump(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * Load a schema template by name.
     *
     * @param schemaName the schema name
     * @return schema template as JSON node, or null if not found
     */
    protected JsonNode loadSchemaTemplate(String schemaName) {
        String filename = schemaName + ".json";

        // Try loading from filesystem first (for development)
        File file = new File(JINGRA_CONFIG_DIR + "/" + schemasPath + "/" + filename);
        if (file.exists()) {
            try {
                return objectMapper.readTree(file);
            } catch (IOException e) {
                logger.warn("Failed to load schema from file: {}", file.getAbsolutePath(), e);
            }
        }

        // Try loading from classpath (for packaged JAR)
        String resourcePath = "/" + schemasPath + "/" + filename;
        URL url = getClass().getResource(resourcePath);
        if (url != null) {
            try {
                return objectMapper.readTree(url);
            } catch (IOException e) {
                logger.warn("Failed to load schema from classpath: {}", resourcePath, e);
            }
        }

        logger.error("Schema template '{}' not found for engine '{}'", schemaName, getEngineName());
        return null;
    }

    /**
     * Get the schema template as a Map (for storing in benchmark results).
     * Returns the "template" object containing mappings and settings.
     *
     * @param schemaName the schema name
     * @return schema template as Map with mappings and settings, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSchemaTemplate(String schemaName) {
        JsonNode template = loadSchemaTemplate(schemaName);
        if (template == null) {
            return null;
        }

        // Support both legacy Jingra-wrapped schemas ({name, template:{...}}) and direct schemas ({...}).
        JsonNode templateNode = template.has("template") ? template.get("template") : template;

        try {
            return objectMapper.convertValue(templateNode, Map.class);
        } catch (Exception e) {
            logger.error("Failed to convert schema template to Map", e);
            return null;
        }
    }

    /**
     * Load a query template by name.
     *
     * @param queryName the query name
     * @return query template as JSON node, or null if not found
     */
    protected JsonNode loadQueryTemplate(String queryName) {
        String filename = queryName + ".json";

        // Try loading from filesystem first (for development)
        File file = new File(JINGRA_CONFIG_DIR + "/" + queriesPath + "/" + filename);
        if (file.exists()) {
            try {
                return objectMapper.readTree(file);
            } catch (IOException e) {
                logger.warn("Failed to load query from file: {}", file.getAbsolutePath(), e);
            }
        }

        // Try loading from classpath (for packaged JAR)
        String resourcePath = "/" + queriesPath + "/" + filename;
        URL url = getClass().getResource(resourcePath);
        if (url != null) {
            try {
                return objectMapper.readTree(url);
            } catch (IOException e) {
                logger.warn("Failed to load query from classpath: {}", resourcePath, e);
            }
        }

        logger.error("Query template '{}' not found for engine '{}'", queryName, getEngineName());
        return null;
    }

    /**
     * Cached variant of {@link #loadQueryTemplate(String)} to avoid per-query filesystem/classpath I/O and JSON parsing.
     * Cache is per engine instance (safe for typical harness lifecycle) and does not auto-reload changes on disk.
     */
    protected JsonNode loadQueryTemplateCached(String queryName) {
        if (queryName == null) {
            return null;
        }
        return queryTemplateCache.computeIfAbsent(queryName, this::loadQueryTemplate);
    }

    /**
     * Render a template by replacing {{param}} placeholders with values.
     *
     * @param template the template JSON node
     * @param params the parameters to substitute
     * @return rendered template as JSON string
     */
    protected String renderTemplate(JsonNode template, Map<String, Object> params) {
        try {
            JsonNode templateNode = template.get("template");
            if (templateNode == null) {
                throw new IllegalStateException("Template JSON missing 'template' field");
            }

            String templateStr = objectMapper.writeValueAsString(templateNode);

            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String placeholder = "\"{{" + entry.getKey() + "}}\"";
                String value;

                Object paramValue = entry.getValue();
                if (paramValue instanceof String) {
                    value = "\"" + paramValue + "\"";
                } else if (paramValue instanceof Number || paramValue instanceof Boolean) {
                    value = paramValue.toString();
                } else {
                    value = objectMapper.writeValueAsString(paramValue);
                }

                templateStr = templateStr.replace(placeholder, value);
            }

            return templateStr;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render query template", e);
        }
    }

    /**
     * Get a configuration value.
     *
     * @param key the config key
     * @param defaultValue the default value if not found
     * @return the config value or default
     */
    protected String getConfigString(String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Get a configuration value as int.
     *
     * @param key the config key
     * @param defaultValue the default value if not found
     * @return the config value or default
     */
    protected int getConfigInt(String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    protected boolean getConfigBoolean(String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * Get environment variable with optional fallback.
     *
     * @param envVarName the environment variable name
     * @param fallback fallback value if env var not set
     * @return the environment variable value or fallback
     */
    protected String getEnv(String envVarName, String fallback) {
        String value = System.getenv(envVarName);
        return value != null ? value : fallback;
    }

    @Override
    public void close() throws Exception {
        // Default implementation - subclasses override if needed
    }
}
