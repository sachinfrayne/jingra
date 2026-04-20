package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

        public void publicWriteFirstQueryDump(String engineShortName, String requestJson) {
            writeFirstQueryDumpIfConfigured(engineShortName, requestJson);
        }

        public boolean publicShouldWriteFirstQueryDump() {
            return shouldWriteFirstQueryDump();
        }

        public JsonNode publicLoadQueryTemplateCached(String queryName) {
            return loadQueryTemplateCached(queryName);
        }
    }

    @BeforeEach
    void setup() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        engine = new TestBenchmarkEngine(config);
    }

    @AfterEach
    void cleanupJingraConfigOnDisk() throws IOException {
        Path root = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR);
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private void writeJingraFile(String relativeUnderJingraConfig, String content) throws IOException {
        Path p = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR).resolve(relativeUnderJingraConfig);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
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
    void testLoadSchemaTemplate_missingReturnsNull() {
        assertNull(engine.publicLoadSchemaTemplate("definitely-missing-schema-xyz"));
    }

    @Test
    void testGetSchemaTemplate_missingReturnsNull() {
        assertNull(engine.getSchemaTemplate("definitely-missing-schema-xyz"));
    }

    @Test
    void testGetSchemaTemplate_missingTemplateFieldReturnsNull() throws Exception {
        writeJingraFile("schemas/no-template-key.json", "{\"not_template\":{}}");
        Map<String, Object> tpl = engine.getSchemaTemplate("no-template-key");
        assertNotNull(tpl);
        assertTrue(tpl.containsKey("not_template"));
    }

    @Test
    void testGetSchemaTemplate_convertValueThrowsReturnsNull() throws Exception {
        // template value is a scalar; cannot be converted to Map
        writeJingraFile("schemas/bad-convert.json", "{\"template\":\"oops\"}");
        assertNull(engine.getSchemaTemplate("bad-convert"));
    }

    @Test
    void testGetSchemaTemplate_convertsTemplateObjectToMap() throws Exception {
        writeJingraFile("schemas/good-template.json", "{\"template\":{\"mappings\":{\"properties\":{\"f\":{\"type\":\"keyword\"}}}}}");
        Map<String, Object> tpl = engine.getSchemaTemplate("good-template");
        assertNotNull(tpl);
        assertTrue(tpl.containsKey("mappings"));
    }

    @Test
    void testLoadSchemaTemplate_fromClasspath() {
        JsonNode schema = engine.publicLoadSchemaTemplate("cp-schema");
        assertNotNull(schema);
        assertEquals("classpath", schema.get("from").asText());
    }

    @Test
    void testLoadSchemaTemplate_fromFilesystemOverridesClasspath() throws Exception {
        writeJingraFile("schemas/winner.json", "{\"winner\":\"disk\"}");
        JsonNode schema = engine.publicLoadSchemaTemplate("winner");
        assertEquals("disk", schema.get("winner").asText());
    }

    @Test
    void testLoadSchemaTemplate_invalidOnDiskFallsBackToClasspath() throws Exception {
        writeJingraFile("schemas/recover.json", "{ not json");
        JsonNode schema = engine.publicLoadSchemaTemplate("recover");
        assertNotNull(schema);
        assertTrue(schema.get("recoveredFromClasspath").asBoolean());
    }

    @Test
    void testLoadSchemaTemplate_invalidClasspathResourceReturnsNull() {
        assertNull(engine.publicLoadSchemaTemplate("bad-json"));
    }

    @Test
    void testLoadSchemaTemplate_invalidOnDiskAndMissingClasspathReturnsNull() throws Exception {
        writeJingraFile("schemas/only-disk-bad.json", "{ not json");
        assertNull(engine.publicLoadSchemaTemplate("only-disk-bad"));
    }

    @Test
    void testLoadSchemaTemplate_fromFilesystemOnly() throws Exception {
        writeJingraFile("schemas/fs-only.json", "{\"fs\":true}");
        JsonNode schema = engine.publicLoadSchemaTemplate("fs-only");
        assertTrue(schema.get("fs").asBoolean());
    }

    @Test
    void testLoadQueryTemplate_missingReturnsNull() {
        assertNull(engine.publicLoadQueryTemplate("definitely-missing-query-xyz"));
    }

    @Test
    void testLoadQueryTemplate_fromClasspath() {
        JsonNode query = engine.publicLoadQueryTemplate("cp-query");
        assertNotNull(query);
        assertEquals("classpath-query", query.get("from").asText());
    }

    @Test
    void testLoadQueryTemplate_invalidClasspathResourceReturnsNull() {
        assertNull(engine.publicLoadQueryTemplate("bad-query"));
    }

    @Test
    void testLoadQueryTemplate_invalidOnDiskFallsBackToClasspath() throws Exception {
        writeJingraFile("queries/recover-query.json", "{ not json");
        JsonNode query = engine.publicLoadQueryTemplate("recover-query");
        assertTrue(query.get("recoveredQuery").asBoolean());
    }

    @Test
    void testLoadQueryTemplate_fromFilesystemOnly() throws Exception {
        writeJingraFile("queries/fs-only-query.json", "{\"q\":\"disk\"}");
        JsonNode query = engine.publicLoadQueryTemplate("fs-only-query");
        assertEquals("disk", query.get("q").asText());
    }

    @Test
    void testGetConfigBoolean_nullUsesDefault() {
        Map<String, Object> cfg = new HashMap<>();
        engine = new TestBenchmarkEngine(cfg);
        assertTrue(engine.getConfigBoolean("absent", true));
        assertFalse(engine.getConfigBoolean("absent", false));
    }

    @Test
    void testGetConfigBoolean_booleanValue() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("flag", Boolean.FALSE);
        engine = new TestBenchmarkEngine(cfg);
        assertFalse(engine.getConfigBoolean("flag", true));
    }

    @Test
    void testGetConfigBoolean_stringParses() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("flag", "true");
        engine = new TestBenchmarkEngine(cfg);
        assertTrue(engine.getConfigBoolean("flag", false));
    }

    @Test
    void testQueryDumpDirectoryBlankTreatedAsDisabled() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, "   \t ");
        engine = new TestBenchmarkEngine(cfg);
        assertDoesNotThrow(() -> engine.publicWriteFirstQueryDump("test", "{\"a\":1}"));
    }

    @Test
    void testWriteFirstQueryDump_writesPrettyJsonOnce(@TempDir Path dumpRoot) throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, dumpRoot.toString());
        engine = new TestBenchmarkEngine(cfg);
        engine.publicWriteFirstQueryDump("ab", "{\"b\":2}");
        engine.publicWriteFirstQueryDump("ab", "{\"c\":3}");
        Path out = dumpRoot.resolve("ab-first-query.json");
        assertTrue(Files.isRegularFile(out));
        String body = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(body).contains("\"b\"");
        assertThat(body).doesNotContain("\"c\"");
    }

    @Test
    void testWriteFirstQueryDump_nonJsonPayloadWrittenVerbatim(@TempDir Path dumpRoot) throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, dumpRoot.toString());
        engine = new TestBenchmarkEngine(cfg);
        String raw = "not-json-at-all";
        engine.publicWriteFirstQueryDump("raw", raw);
        assertEquals(raw, Files.readString(dumpRoot.resolve("raw-first-query.json"), StandardCharsets.UTF_8));
    }

    @Test
    void testWriteFirstQueryDump_swallowsIOExceptionWhenDumpPathIsFile(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.createFile(blocker);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, blocker.toAbsolutePath().toString());
        engine = new TestBenchmarkEngine(cfg);
        assertDoesNotThrow(() -> engine.publicWriteFirstQueryDump("x", "{\"a\":1}"));
    }

    @Test
    void testShouldWriteFirstQueryDump_nullDirectoryIsFalse() {
        Map<String, Object> cfg = new HashMap<>();
        engine = new TestBenchmarkEngine(cfg);
        assertFalse(engine.publicShouldWriteFirstQueryDump());
    }

    @Test
    void testShouldWriteFirstQueryDump_trueThenFalseAfterWrite(@TempDir Path dumpRoot) throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, dumpRoot.toString());
        engine = new TestBenchmarkEngine(cfg);
        assertTrue(engine.publicShouldWriteFirstQueryDump());
        engine.publicWriteFirstQueryDump("t", "{\"x\":1}");
        assertFalse(engine.publicShouldWriteFirstQueryDump(), "after first write, subsequent dumps should be disabled");
    }

    @Test
    void testLoadQueryTemplateCached_nullNameReturnsNullWithoutLoading() {
        engine = new TestBenchmarkEngine(new HashMap<>()) {
            @Override
            protected JsonNode loadQueryTemplate(String queryName) {
                fail("loadQueryTemplate should not be called for null queryName");
                return null;
            }
        };
        assertNull(engine.publicLoadQueryTemplateCached(null));
    }

    @Test
    void testRenderTemplate_wrapsSerializationFailure() throws Exception {
        String templateJson = "{\"template\": {\"field\": \"{{v}}\"}}";
        JsonNode template = mapper.readTree(templateJson);
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        Map<String, Object> params = Map.of("v", cyclic);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.publicRenderTemplate(template, params));
        assertEquals("Failed to render query template", ex.getMessage());
        assertNotNull(ex.getCause());
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
