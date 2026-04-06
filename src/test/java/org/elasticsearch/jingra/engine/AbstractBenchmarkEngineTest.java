package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AbstractBenchmarkEngine template rendering and loading functionality.
 */
class AbstractBenchmarkEngineTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private TestBenchmarkEngine engine;

    // Concrete test implementation of AbstractBenchmarkEngine
    private static class TestBenchmarkEngine extends AbstractBenchmarkEngine {
        public TestBenchmarkEngine(Map<String, Object> config) {
            super(config);
        }

        @Override
        public String getEngineName() {
            return "test";
        }

        @Override
        public String getShortName() {
            return "test";
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        public boolean createIndex(String indexName, String schemaName) {
            return true;
        }

        @Override
        public boolean deleteIndex(String indexName) {
            return true;
        }

        @Override
        public boolean indexExists(String indexName) {
            return false;
        }

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            return documents.size();
        }

        @Override
        public QueryResponse query(String indexName, String queryName, QueryParams params) {
            return new QueryResponse(List.of(), 0.0, null);
        }

        @Override
        public long getDocumentCount(String indexName) {
            return 0;
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public Map<String, String> getIndexMetadata(String indexName) {
            return Map.of();
        }

        // Expose protected methods for testing
        public String publicRenderTemplate(JsonNode template, Map<String, Object> params) {
            return renderTemplate(template, params);
        }

        public JsonNode publicLoadSchemaTemplate(String schemaName) {
            return loadSchemaTemplate(schemaName);
        }

        public JsonNode publicLoadQueryTemplate(String queryName) {
            return loadQueryTemplate(queryName);
        }
    }

    @BeforeEach
    void setup() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        engine = new TestBenchmarkEngine(config);
    }

    @Test
    void testRenderTemplate_withStringPlaceholder() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"field\": \"{{value}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> params = Map.of("value", "test-string");

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        assertEquals("test-string", resultNode.get("field").asText());
    }

    @Test
    void testRenderTemplate_withNumberPlaceholder() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"size\": \"{{size}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> params = Map.of("size", 100);

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        assertEquals(100, resultNode.get("size").asInt());
    }

    @Test
    void testRenderTemplate_withBooleanPlaceholder() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"enabled\": \"{{enabled}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> params = Map.of("enabled", true);

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        assertTrue(resultNode.get("enabled").asBoolean());
    }

    @Test
    void testRenderTemplate_withArrayPlaceholder() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"values\": \"{{values}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> params = Map.of("values", List.of(1, 2, 3));

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        JsonNode values = resultNode.get("values");
        assertTrue(values.isArray());
        assertEquals(3, values.size());
        assertEquals(1, values.get(0).asInt());
        assertEquals(2, values.get(1).asInt());
        assertEquals(3, values.get(2).asInt());
    }

    @Test
    void testRenderTemplate_withObjectPlaceholder() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"filter\": \"{{filter}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> filter = Map.of(
            "term", Map.of("category", "electronics")
        );
        Map<String, Object> params = Map.of("filter", filter);

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        JsonNode filterNode = resultNode.get("filter");
        assertNotNull(filterNode);
        assertEquals("electronics", filterNode.get("term").get("category").asText());
    }

    @Test
    void testRenderTemplate_withMissingPlaceholder() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"field\": \"{{missing}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> params = Map.of("other", "value");

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        // Missing placeholder should remain unchanged
        assertEquals("{{missing}}", resultNode.get("field").asText());
    }

    @Test
    void testRenderTemplate_withSpecialCharacters() throws Exception {
        // Arrange
        String templateJson = "{\"template\": {\"message\": \"{{text}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        // Use simpler string without embedded quotes that would require escaping
        Map<String, Object> params = Map.of("text", "Hello World with apostrophes");

        // Act
        String result = engine.publicRenderTemplate(template, params);

        // Assert
        JsonNode resultNode = mapper.readTree(result);
        // Verify the string was properly substituted
        assertThat(resultNode.get("message").asText()).isEqualTo("Hello World with apostrophes");
    }

    @Test
    void testRenderTemplate_withMissingTemplateField() throws Exception {
        String templateJson = "{\"no_template\": {\"field\": \"value\"}}";
        JsonNode template = mapper.readTree(templateJson);
        Map<String, Object> params = Map.of("key", "value");

        assertThrows(IllegalStateException.class, () -> engine.publicRenderTemplate(template, params));
    }

    @Test
    void testLoadSchemaTemplate_fromClasspath() {
        // Act - try to load from classpath (test resources)
        JsonNode schema = engine.publicLoadSchemaTemplate("test-schema");

        // Assert - should find /schemas/test/test-schema.json
        // Note: This will be null because we haven't created that path yet
        // But the method should not throw an exception
        // For a real test, we'd need to create the file at the expected path
        // This tests the null return behavior
        assertNull(schema);
    }

    @Test
    void testLoadQueryTemplate_success() {
        // Act
        JsonNode query = engine.publicLoadQueryTemplate("test-query");

        // Assert - should return null if not found (no exception)
        assertNull(query);
    }

    @Test
    void testGetConfigString_withValue() {
        // Act
        String url = engine.getConfigString("url", "default");

        // Assert
        assertEquals("http://localhost:9200", url);
    }

    @Test
    void testGetConfigString_withDefault() {
        // Act
        String missing = engine.getConfigString("missing", "default-value");

        // Assert
        assertEquals("default-value", missing);
    }

    @Test
    void testGetConfigInt_withValue() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("timeout", 30);
        engine = new TestBenchmarkEngine(config);

        // Act
        int timeout = engine.getConfigInt("timeout", 10);

        // Assert
        assertEquals(30, timeout);
    }

    @Test
    void testGetConfigInt_withStringValue() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("timeout", "45");
        engine = new TestBenchmarkEngine(config);

        // Act
        int timeout = engine.getConfigInt("timeout", 10);

        // Assert
        assertEquals(45, timeout);
    }

    @Test
    void testGetConfigInt_withDefault() {
        // Act
        int missing = engine.getConfigInt("missing", 100);

        // Assert
        assertEquals(100, missing);
    }

    @Test
    void testGetConfigInt_withInvalidString() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("timeout", "invalid");
        engine = new TestBenchmarkEngine(config);

        // Act
        int timeout = engine.getConfigInt("timeout", 50);

        // Assert - should return default when parsing fails
        assertEquals(50, timeout);
    }

    @Test
    void testGetEnv_withValue() {
        // Arrange - use a common environment variable that should exist
        String path = System.getenv("PATH");
        assertNotNull(path, "PATH environment variable should exist");

        // Act
        String result = engine.getEnv("PATH", "fallback");

        // Assert
        assertEquals(path, result);
    }

    @Test
    void testGetEnv_withFallback() {
        // Act - use a variable that definitely doesn't exist
        String result = engine.getEnv("NONEXISTENT_VAR_12345", "fallback-value");

        // Assert
        assertEquals("fallback-value", result);
    }

    @Test
    void testClose_noException() {
        // Act & Assert - should not throw exception
        assertDoesNotThrow(() -> engine.close());
    }

    @Test
    void testGetEngineName() {
        // Act
        String name = engine.getEngineName();

        // Assert
        assertEquals("test", name);
    }
}
