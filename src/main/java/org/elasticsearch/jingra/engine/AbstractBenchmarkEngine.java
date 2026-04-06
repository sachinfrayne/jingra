package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Abstract base class for benchmark engines providing common functionality.
 */
public abstract class AbstractBenchmarkEngine implements BenchmarkEngine {
    /**
     * Relative directory for on-disk schema/query overrides (local dev, K8s mounts). Classpath fallback uses {@code /schemas/} and {@code /queries/}.
     */
    public static final String JINGRA_CONFIG_DIR = "jingra-config";

    protected static final Logger logger = LoggerFactory.getLogger(AbstractBenchmarkEngine.class);
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected final Map<String, Object> config;
    protected final String schemasPath;
    protected final String queriesPath;

    protected AbstractBenchmarkEngine(Map<String, Object> config) {
        this.config = config;
        this.schemasPath = "schemas/" + getEngineName();
        this.queriesPath = "queries/" + getEngineName();
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
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return objectMapper.readTree(is);
            }
        } catch (IOException e) {
            logger.warn("Failed to load schema from classpath: {}", resourcePath, e);
        }

        logger.error("Schema template '{}' not found for engine '{}'", schemaName, getEngineName());
        return null;
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
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return objectMapper.readTree(is);
            }
        } catch (IOException e) {
            logger.warn("Failed to load query from classpath: {}", resourcePath, e);
        }

        logger.error("Query template '{}' not found for engine '{}'", queryName, getEngineName());
        return null;
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
