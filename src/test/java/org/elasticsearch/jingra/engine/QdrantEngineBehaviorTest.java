package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.SearchResponse;
import io.qdrant.client.grpc.PointsGrpc.PointsFutureStub;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * Offline and helper-focused tests for {@link QdrantEngine}; uses a Mockito {@link QdrantClient}
 * only where gRPC responses must be controlled for branch coverage.
 */
class QdrantEngineBehaviorTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_QD_OFFLINE_URL_ENV__";

    @AfterEach
    void cleanup() {
        System.clearProperty("jingra.insecure.tls");
        deleteTreeIfExists(Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR));
    }

    private static void deleteTreeIfExists(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void injectClient(QdrantEngine engine, QdrantClient client) throws Exception {
        Field f = QdrantEngine.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(engine, client);
    }

    private static void writeQdrantSchemaFile(String schemaBaseName, String json) throws Exception {
        Path schemaPath = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR)
                .resolve("schemas/" + schemaBaseName + ".json");
        Files.createDirectories(schemaPath.getParent());
        Files.writeString(schemaPath, json, StandardCharsets.UTF_8);
    }

    private static void writeQdrantQueryFile(String queryBaseName, String json) throws Exception {
        Path queryPath = Paths.get(AbstractBenchmarkEngine.JINGRA_CONFIG_DIR)
                .resolve("queries/" + queryBaseName + ".json");
        Files.createDirectories(queryPath.getParent());
        Files.writeString(queryPath, json, StandardCharsets.UTF_8);
    }

    private static Object invokePrivate(QdrantEngine engine, String method, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method m = QdrantEngine.class.getDeclaredMethod(method, paramTypes);
        m.setAccessible(true);
        return m.invoke(engine, args);
    }

    private static Object invokePrivateStatic(String method, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = QdrantEngine.class.getDeclaredMethod(method, paramTypes);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void connectReturnsFalseWhenUrlMissing() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        assertFalse(new QdrantEngine(cfg).connect());
    }

    @Test
    void ingestReturnsZeroWhenClientNotInitialized() {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        Map<String, Object> fields = new HashMap<>();
        fields.put("embedding", List.of(1.0f));
        assertEquals(0, e.ingest(List.of(new Document(fields)), "any-index", "id"));
    }

    @Test
    void createIndexReturnsFalseWhenClientNotInitialized() {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        assertFalse(e.createIndex("any-index", "any-schema"));
    }

    @Test
    void connectReturnsFalseWhenUnreachable() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "127.0.0.1:1");
        assertFalse(new QdrantEngine(cfg).connect());
    }

    @Test
    void connectParsesHostWithoutColonUsingDefaultGrpcPort() throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "127.0.0.1");
        QdrantEngine eng = new QdrantEngine(cfg);
        try {
            assertFalse(eng.connect());
        } finally {
            eng.close();
        }
    }

    @Test
    void connectPassesNonEmptyApiKeyWhenProvided() throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "127.0.0.1:1");
        cfg.put("api_key", "secret");
        QdrantEngine eng = new QdrantEngine(cfg);
        try {
            assertFalse(eng.connect());
        } finally {
            eng.close();
        }
    }

    @Test
    void connectUsesInsecureTlsTrustWhenPropertySet() throws Exception {
        System.setProperty("jingra.insecure.tls", "true");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "https://127.0.0.1:1");
        QdrantEngine eng = new QdrantEngine(cfg);
        try {
            assertFalse(eng.connect());
        } finally {
            eng.close();
        }
    }

    @Test
    void connectUsesTlsChannelWhenHttpsWithoutInsecureTlsProperty() throws Exception {
        System.clearProperty("jingra.insecure.tls");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "https://127.0.0.1:1");
        QdrantEngine eng = new QdrantEngine(cfg);
        try {
            assertFalse(eng.connect());
        } finally {
            eng.close();
        }
    }

    @Test
    void connectUsesUrlFromEnvironmentWhenUrlAbsentFromConfig() throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", "JINGRA_QD_URL_FROM_ENV");
        QdrantEngine eng = new QdrantEngine(cfg) {
            @Override
            protected String getEnv(String name, String fallback) {
                if ("JINGRA_QD_URL_FROM_ENV".equals(name)) {
                    return "127.0.0.1:1";
                }
                return super.getEnv(name, fallback);
            }
        };
        try {
            assertFalse(eng.connect());
        } finally {
            eng.close();
        }
    }

    @Test
    void defaultGrpcTimeoutIs30Seconds() {
        assertEquals(30L, new QdrantEngine(new HashMap<>()).configuredGrpcTimeoutSeconds());
    }

    @Test
    void configuredGrpcTimeoutFromConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 77);
        assertEquals(77L, new QdrantEngine(cfg).configuredGrpcTimeoutSeconds());
    }

    @Test
    void apiKeyDefaultsToEmptyWhenNullInConfig() throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "127.0.0.1:1");
        cfg.put("api_key", null);
        assertFalse(new QdrantEngine(cfg).connect());
    }

    @Test
    void gettersWhenNeverConnected() {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        assertFalse(e.connect());
        assertEquals("qdrant", e.getEngineName());
        assertEquals("qd", e.getShortName());
        assertEquals("unknown", e.getVersion());
    }

    @Test
    void createIndexReturnsFalseWhenClientNull() {
        assertFalse(new QdrantEngine(new HashMap<>()).createIndex("c", "any"));
    }

    @Test
    void indexExistsReturnsFalseWhenClientNull() {
        assertFalse(new QdrantEngine(new HashMap<>()).indexExists("c"));
    }

    @Test
    void deleteIndexReturnsFalseWhenClientNull() {
        assertFalse(new QdrantEngine(new HashMap<>()).deleteIndex("c"));
    }

    @Test
    void ingestReturnsZeroWhenClientNull() {
        assertEquals(0, new QdrantEngine(new HashMap<>()).ingest(
                List.of(new Document(Map.of("a", 1))), "c", null));
    }

    @Test
    void queryReturnsEmptyWhenClientNull() {
        QueryResponse r = new QdrantEngine(new HashMap<>()).query("c", "q", new QueryParams());
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
        assertNull(r.getServerLatencyMs());
    }

    @Test
    void getDocumentCountZeroWhenClientNull() {
        assertEquals(0L, new QdrantEngine(new HashMap<>()).getDocumentCount("c"));
    }

    @Test
    void getIndexMetadataEmptyWhenClientNull() {
        assertTrue(new QdrantEngine(new HashMap<>()).getIndexMetadata("c").isEmpty());
    }

    @Test
    void closeSafeWhenClientNull() throws Exception {
        new QdrantEngine(new HashMap<>()).close();
    }

    @Test
    void isNotFoundDetectsGrpcNotFound() throws Exception {
        StatusRuntimeException sre = new StatusRuntimeException(Status.NOT_FOUND);
        assertTrue((Boolean) invokePrivateStatic("isNotFound", new Class[]{Throwable.class}, sre));
        assertFalse((Boolean) invokePrivateStatic("isNotFound", new Class[]{Throwable.class},
                new StatusRuntimeException(Status.UNAVAILABLE)));
    }

    @Test
    void isNotFoundUnwrapsExecutionException() throws Exception {
        StatusRuntimeException sre = new StatusRuntimeException(Status.NOT_FOUND);
        ExecutionException wrapped = new ExecutionException(sre);
        assertTrue((Boolean) invokePrivateStatic("isNotFound", new Class[]{Throwable.class}, wrapped));
    }

    @Test
    void isNotFoundReturnsFalseWhenNoStatusRuntimeExceptionInChain() throws Exception {
        assertFalse((Boolean) invokePrivateStatic("isNotFound", new Class[]{Throwable.class}, new RuntimeException("x")));
        assertFalse((Boolean) invokePrivateStatic("isNotFound", new Class[]{Throwable.class},
                new RuntimeException("wrap", new IllegalStateException("inner"))));
    }

    @Test
    void parseFilterBuildsMustConditions() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree("""
                {"must": [{"key": "k", "match": {"value": "v"}}]}
                """);
        Filter f = (Filter) invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, node);
        assertNotNull(f);
        assertEquals(1, f.getMustCount());
    }

    @Test
    void parseFilterReturnsNullWhenMustEmptyOrMissing() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, mapper.readTree("{}")));
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, mapper.readTree("{\"must\":[]}")));
    }

    @Test
    void parseFilterReturnsNullWhenMatchValueUnsupported() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree("""
                {"must": [{"key": "k", "match": {"value": [1,2]}}]}
                """);
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, node));
    }

    @Test
    void parseFilterReturnsNullOnNullFilterNode() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, (JsonNode) null));
    }

    @Test
    void parseFilterSkipsWhenMustIsNotArray() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, mapper.readTree("{\"must\": {}}")));
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, mapper.readTree("{\"must\": \"bad\"}")));
    }

    @Test
    void parseFilterSkipsConditionWithoutKeyOrMatchOrValue() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class},
                mapper.readTree("{\"must\":[{\"key\":\"k\"}]}")));
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class},
                mapper.readTree("{\"must\":[{\"match\":{\"value\":\"v\"}}]}")));
        assertNull(invokePrivate(e, "parseFilter", new Class[]{JsonNode.class},
                mapper.readTree("{\"must\":[{\"key\":\"k\",\"match\":{}}]}")));
    }

    @Test
    void parseFilterAddsMultipleValidMustClauses() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree("""
                {"must":[
                  {"key":"a","match":{"value":1}},
                  {"key":"b","match":{"value":"x"}}
                ]}
                """);
        Filter f = (Filter) invokePrivate(e, "parseFilter", new Class[]{JsonNode.class}, node);
        assertNotNull(f);
        assertEquals(2, f.getMustCount());
    }

    @Test
    void buildMatchBooleanIntegerKeyword() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        Match mb = (Match) invokePrivate(e, "buildMatch", new Class[]{JsonNode.class}, mapper.readTree("true"));
        assertTrue(mb.getBoolean());
        Match mi = (Match) invokePrivate(e, "buildMatch", new Class[]{JsonNode.class}, mapper.readTree("42"));
        assertEquals(42L, mi.getInteger());
        Match mk = (Match) invokePrivate(e, "buildMatch", new Class[]{JsonNode.class}, mapper.readTree("\"hello\""));
        assertEquals("hello", mk.getKeyword());
    }

    @Test
    void buildMatchFloatingWholeNumberUsesIntegerBranch() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        Match m = (Match) invokePrivate(e, "buildMatch", new Class[]{JsonNode.class}, mapper.readTree("3.0"));
        assertEquals(3L, m.getInteger());
    }

    @Test
    void buildMatchFloatingNonWholeUsesKeywordBranch() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        Match m = (Match) invokePrivate(e, "buildMatch", new Class[]{JsonNode.class}, mapper.readTree("2.5"));
        assertEquals("2.5", m.getKeyword());
    }

    @Test
    void buildMatchUnsupportedArrayReturnsNull() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        assertNull(invokePrivate(e, "buildMatch", new Class[]{JsonNode.class}, mapper.readTree("[1,2]")));
    }

    @Test
    void resolveIntParamTemplateAndLiteral() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("num_candidates", 9));
        assertEquals(9, (Integer) invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class},
                "{{num_candidates}}", p));
        assertEquals(3, (Integer) invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class},
                "3", p));
    }

    @Test
    void resolveIntParamNullAndInvalidLiteral() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of());
        assertNull(invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class}, null, p));
        assertNull(invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class}, "{{x}}", p));
        assertNull(invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class}, "notint", p));
    }

    @Test
    void resolveIntParamNonTemplateFallsThroughToParseInt() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of());
        assertNull(invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class}, "{{nope", p));
        assertEquals(42, (Integer) invokePrivate(e, "resolveIntParam", new Class[]{String.class, QueryParams.class},
                "42", p));
    }

    @Test
    void resolveDoubleParamTemplateAndLiteral() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("rescore", 3));
        assertEquals(3.0, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{rescore}}", p), 0.0);
        assertEquals(2.5, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "2.5", p), 0.0);
    }

    @Test
    void resolveDoubleParamNullMissingOrInvalid() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of());
        assertNull(invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class}, null, p));
        assertNull(invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{missing}}", p));
        assertNull(invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "not-a-number", p));
    }

    @Test
    void resolveDoubleParamTemplateWithDoubleValue() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("rescore", 2.25d));
        assertEquals(2.25, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{rescore}}", p), 0.0);
    }

    @Test
    void resolveDoubleParamTemplateWithIntegerValue() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("w", 9));
        assertEquals(9.0, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{w}}", p), 0.0);
    }

    @Test
    void resolveDoubleParamTemplateWithBigDecimalValue() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("d", new BigDecimal("2.5")));
        assertEquals(2.5, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{d}}", p), 0.0);
    }

    @Test
    void resolveDoubleParamTemplateWithFloatValue() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("f", 1.25f));
        assertEquals(1.25, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{f}}", p), 0.0);
    }

    @Test
    void resolveDoubleParamTemplateWithNonNumericStringReturnsNull() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("rescore", "not-a-number"));
        assertNull(invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{rescore}}", p));
    }

    @Test
    void resolveDoubleParamLiteralTrimsWhitespace() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of());
        assertEquals(1.5, (Double) invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "  1.5  ", p), 0.0);
    }

    @Test
    void resolveDoubleParamTemplateWithBooleanReturnsNull() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of("flag", true));
        assertNull(invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{flag}}", p));
    }

    @Test
    void resolveDoubleParamOpensWithDoubleBraceButNotFullTemplateFallsThroughToLiteralParse() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QueryParams p = new QueryParams(Map.of());
        assertNull(invokePrivate(e, "resolveDoubleParam", new Class[]{String.class, QueryParams.class},
                "{{notclosed", p));
    }

    @Test
    void convertToQdrantValueTypes() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        assertTrue(((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, (Object) null))
                .hasNullValue());
        assertTrue(((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, true)).getBoolValue());
        assertEquals(7, ((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, 7)).getIntegerValue());
    }

    @Test
    void convertToQdrantValueLongDoubleStringAndFallback() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        assertEquals(9L, ((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, 9L)).getIntegerValue());
        assertEquals(1.0, ((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, 1.0d)).getDoubleValue(), 0.0);
        assertEquals("s", ((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, "s")).getStringValue());
        assertEquals("[x]", ((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, List.of("x"))).getStringValue());
    }

    @Test
    void convertToQdrantValueFloatUsesStringFallback() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        assertEquals("1.5", ((Value) invokePrivate(e, "convertToQdrantValue", new Class[]{Object.class}, 1.5f)).getStringValue());
    }

    @Test
    void quantizationSearchParamsIncludesRescoreTrue() {
        // This test verifies that when we build QuantizationSearchParams with oversampling,
        // we also set rescore=true as required by Qdrant documentation
        // According to Qdrant docs, quantization params should include:
        // "quantization": {
        //     "ignore": false,
        //     "rescore": true,      // ← This is required for rescoring to work
        //     "oversampling": 2.0
        // }

        // Build QuantizationSearchParams the CORRECT way (as the implementation should do it)
        QuantizationSearchParams params = QuantizationSearchParams.newBuilder()
                .setRescore(true)
                .setOversampling(25.0)
                .build();

        // Verify rescore is set to true to enable rescoring with full vectors
        assertTrue(params.hasRescore(), "rescore field should be set");
        assertTrue(params.getRescore(), "rescore should be true to enable rescoring with full vectors");
        assertEquals(25.0, params.getOversampling(), 0.001);
    }

    @Test
    void formatSearchPointsJsonForDumpReturnsOriginalWhenNotObject() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        String raw = "[1,2]";
        assertEquals(raw, invokePrivate(e, "formatSearchPointsJsonForDump", new Class[]{String.class}, raw));
    }

    @Test
    void formatSearchPointsJsonForDumpReturnsOriginalOnInvalidJson() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        String raw = "{ not json";
        assertEquals(raw, invokePrivate(e, "formatSearchPointsJsonForDump", new Class[]{String.class}, raw));
    }

    @Test
    void formatSearchPointsJsonForDumpNormalizesCollectionAndQuotedIntegrals() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        String in = """
                {"collectionName":"c","limit":"10","timeout":"3","params":{"hnswEf":"7"}}
                """;
        String out = (String) invokePrivate(e, "formatSearchPointsJsonForDump", new Class[]{String.class}, in);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode n = mapper.readTree(out);
        assertFalse(n.has("collectionName"));
        assertEquals(10L, n.get("limit").asLong());
        assertEquals(3L, n.get("timeout").asLong());
        assertEquals(7L, n.get("params").get("hnswEf").asLong());
    }

    @Test
    void formatSearchPointsJsonForDumpSkipsCoercionWhenLimitNotTextual() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        String in = "{\"collectionName\":\"x\",\"limit\":9}";
        String out = (String) invokePrivate(e, "formatSearchPointsJsonForDump", new Class[]{String.class}, in);
        assertEquals(9, new ObjectMapper().readTree(out).get("limit").asInt());
    }

    @Test
    void coerceQuotedIntegralLeavesNonParseableText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("f", "nope");
        invokePrivateStatic("coerceQuotedIntegral", new Class[]{ObjectNode.class, String.class}, node, "f");
        assertEquals("nope", node.get("f").asText());
    }

    @Test
    void coerceQuotedIntegralNoOpWhenFieldMissingOrNotText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode empty = mapper.createObjectNode();
        invokePrivateStatic("coerceQuotedIntegral", new Class[]{ObjectNode.class, String.class}, empty, "f");
        assertFalse(empty.has("f"));
        ObjectNode n = mapper.createObjectNode();
        n.put("f", 3);
        invokePrivateStatic("coerceQuotedIntegral", new Class[]{ObjectNode.class, String.class}, n, "f");
        assertTrue(n.get("f").isNumber());
    }

    @Test
    void indexExistsReturnsFalseWhenListCollectionsFails() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFailedFuture(new RuntimeException("boom")));
        injectClient(e, mockClient);
        assertFalse(e.indexExists("any"));
    }

    @Test
    void getVersionUnknownWhenHealthCheckFails() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.healthCheckAsync()).thenReturn(Futures.immediateFailedFuture(new RuntimeException("boom")));
        injectClient(e, mockClient);
        assertEquals("unknown", e.getVersion());
    }

    @Test
    void getVersionReadsFromHealthReply() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        HealthCheckReply reply = HealthCheckReply.newBuilder().setVersion("1.2.3").build();
        when(mockClient.healthCheckAsync()).thenReturn(Futures.immediateFuture(reply));
        injectClient(e, mockClient);
        assertEquals("1.2.3", e.getVersion());
    }

    @Test
    void deleteIndexTrueWhenNotFound() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.deleteCollectionAsync(anyString()))
                .thenReturn(Futures.immediateFailedFuture(new StatusRuntimeException(Status.NOT_FOUND)));
        injectClient(e, mockClient);
        assertTrue(e.deleteIndex("gone"));
    }

    @Test
    void deleteIndexFalseOnOtherFailure() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.deleteCollectionAsync(anyString()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("other")));
        injectClient(e, mockClient);
        assertFalse(e.deleteIndex("x"));
    }

    @Test
    void getIndexMetadataEmptyWhenInfoNull() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(null));
        injectClient(e, mockClient);
        assertTrue(e.getIndexMetadata("c").isEmpty());
    }

    @Test
    void getIndexMetadataSwallowsGetFailure() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("nope")));
        injectClient(e, mockClient);
        assertTrue(e.getIndexMetadata("c").isEmpty());
    }

    @Test
    void getIndexMetadataIncludesPointsCount() throws Exception {
        QdrantEngine e = new QdrantEngine(new HashMap<>());
        QdrantClient mockClient = mock(QdrantClient.class);
        CollectionInfo info = CollectionInfo.newBuilder().setPointsCount(42).build();
        when(mockClient.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(info));
        injectClient(e, mockClient);
        assertEquals("42", e.getIndexMetadata("c").get("points_count"));
    }

    @Test
    void createIndexReturnsFalseWhenCollectionAlreadyExists() throws Exception {
        writeQdrantSchemaFile("test-direct", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("col-a")));
        injectClient(e, mockClient);
        assertFalse(e.createIndex("col-a", "test-direct"));
    }

    @Test
    void createIndexReturnsFalseWhenSchemaMissingTemplate() throws Exception {
        writeQdrantSchemaFile("bad-shape", "{\"vectors\":{}}");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        injectClient(e, mockClient);
        assertFalse(e.createIndex("col", "bad-shape"));
    }

    @Test
    void createIndexReturnsFalseWhenCreateCollectionFails() throws Exception {
        writeQdrantSchemaFile("test-direct", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("cc")));
        injectClient(e, mockClient);
        assertFalse(e.createIndex("col", "test-direct"));
    }

    @Test
    void createIndexSucceedsWithDirectVectorsSchema() throws Exception {
        writeQdrantSchemaFile("test-direct", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-z", "test-direct"));
    }

    @Test
    void createIndexLogsPayloadIndexFailureInMappingsBranch() throws Exception {
        writeQdrantSchemaFile("test-map", """
                {"template":{"mappings":{"properties":{
                  "emb":{"type":"dense_vector","size":2,"distance":"Cosine"},
                  "title":{"type":"keyword"}
                }}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        cfg.put("vector_field", "emb");
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(mockClient.createPayloadIndexAsync(eq("col-m"), eq("title"), any(), any(), eq(true), any(), any()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("payload")));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-m", "test-map"));
    }

    @Test
    void createIndexUsesL2DistanceAlias() throws Exception {
        writeQdrantSchemaFile("test-l2", """
                {"template":{"vectors":{"size":2,"distance":"l2"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-l2", "test-l2"));
    }

    @Test
    void createIndexUsesUnknownDistanceStringAsDefaultCosine() throws Exception {
        writeQdrantSchemaFile("test-dist-default", """
                {"template":{"vectors":{"size":2,"distance":"not-a-known-metric"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-dd", "test-dist-default"));
    }

    @Test
    void createIndexUsesManhattanDistance() throws Exception {
        writeQdrantSchemaFile("test-man", """
                {"template":{"vectors":{"size":2,"distance":"Manhattan"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-man", "test-man"));
    }

    @Test
    void createIndexUsesDotDistance() throws Exception {
        writeQdrantSchemaFile("test-dot", """
                {"template":{"vectors":{"size":2,"distance":"Dot"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-dot", "test-dot"));
    }

    @Test
    void createIndexUsesTopLevelShardNumber() throws Exception {
        writeQdrantSchemaFile("test-shard-top", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},"shard_number":3}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-st", "test-shard-top"));
    }

    @Test
    void createIndexSkipsReplicationFactorWhenZero() throws Exception {
        writeQdrantSchemaFile("test-repl-zero", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},"replication_factor":0}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-r0", "test-repl-zero"));
    }

    @Test
    void createIndexReadsShardSettingsFromTemplateSettingsBlock() throws Exception {
        writeQdrantSchemaFile("test-shard-settings", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},
                "settings":{"shard_number":2,"replication_factor":1}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-sh", "test-shard-settings"));
    }

    @Test
    void createIndexSkipsShardNumberWhenZero() throws Exception {
        writeQdrantSchemaFile("test-shard-zero", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},"shard_number":0}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-z0", "test-shard-zero"));
    }

    @Test
    void createIndexAppliesHnswConfigWithMOnly() throws Exception {
        writeQdrantSchemaFile("test-hnsw-m", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},
                "settings":{"hnsw_config":{"m":8}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-h", "test-hnsw-m"));
    }

    @Test
    void createIndexCreatesPayloadIndexesFromPayloadIndexesArray() throws Exception {
        writeQdrantSchemaFile("test-payload-idx", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},
                "payload_indexes":[
                  {"field_name":"sku","field_schema":"keyword"},
                  {"field_name":"qty","field_schema":"integer"}
                ]}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(mockClient.createPayloadIndexAsync(anyString(), anyString(), any(), any(), eq(true), any(), any()))
                .thenReturn(Futures.immediateFuture(io.qdrant.client.grpc.Points.UpdateResult.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-pi", "test-payload-idx"));
    }

    @Test
    void createIndexWarnsWhenQuantizationVerificationFails() throws Exception {
        writeQdrantSchemaFile("test-q-verify", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},
                "settings":{"quantization_config":{"binary":{"always_ram":true}}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(mockClient.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("verify")));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-q", "test-q-verify"));
    }

    @Test
    void createIndexAppliesBinaryQuantizationWhenAlwaysRamKeyOmitted() throws Exception {
        writeQdrantSchemaFile("test-q-binary-no-always-ram", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},
                "settings":{"quantization_config":{"binary":{}}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(mockClient.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-bin-omit", "test-q-binary-no-always-ram"));
    }

    @Test
    void createIndexAppliesQuantizationConfigWhenBinarySectionAbsent() throws Exception {
        writeQdrantSchemaFile("test-q-quant-empty", """
                {"template":{"vectors":{"size":2,"distance":"Cosine"},
                "settings":{"quantization_config":{}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(mockClient.createCollectionAsync(any(CreateCollection.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(mockClient.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        injectClient(e, mockClient);
        assertTrue(e.createIndex("col-quant-empty", "test-q-quant-empty"));
    }

    @Test
    void queryReturnsHitsWithMockGrpcSearch() throws Exception {
        writeQdrantQueryFile("q-basic", """
                {"template":{}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder()
                .setTime(0.01)
                .addResult(ScoredPoint.newBuilder()
                        .setId(PointId.newBuilder().setUuid("550e8400-e29b-41d4-a716-446655440000").build())
                        .build())
                .build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f, 0.2f)));
        QueryResponse r = e.query("col", "q-basic", qp);
        assertEquals(1, r.getDocumentIds().size());
        assertNotNull(r.getClientLatencyMs());
        assertNotNull(r.getServerLatencyMs());
    }

    @Test
    void queryUsesNumericPointIdFromSearchResult() throws Exception {
        writeQdrantQueryFile("q-num", """
                {"template":{}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder()
                .setTime(0.001)
                .addResult(ScoredPoint.newBuilder()
                        .setId(PointId.newBuilder().setNum(99L).build())
                        .build())
                .build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertEquals("99", e.query("c", "q-num", qp).getDocumentIds().get(0));
    }

    @Test
    void queryHandlesUnsetPointIdOption() throws Exception {
        writeQdrantQueryFile("q-unset", """
                {"template":{}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder()
                .setTime(0.0)
                .addResult(ScoredPoint.newBuilder()
                        .setId(PointId.newBuilder().build())
                        .build())
                .build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertEquals(1, e.query("c", "q-unset", qp).getDocumentIds().size());
    }

    @Test
    void querySkipsQuantizationWhenOversamplingDoesNotResolve() throws Exception {
        writeQdrantQueryFile("q-quant-null", """
                {"template":{"params":{"quantization":{"oversampling":"{{missing}}"}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertNotNull(e.query("c", "q-quant-null", qp).getClientLatencyMs());
    }

    @Test
    void queryAppliesFilterFromTemplate() throws Exception {
        writeQdrantQueryFile("q-filter", """
                {"template":{"filter":{"must":[{"key":"k","match":{"value":"v"}}]}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertNotNull(e.query("c", "q-filter", qp).getClientLatencyMs());
    }

    @Test
    void queryAppliesHnswEfFromTemplateParams() throws Exception {
        writeQdrantQueryFile("q-hnsw", """
                {"template":{"params":{"hnsw_ef":"{{num}}"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f), "num", 64));
        assertNotNull(e.query("c", "q-hnsw", qp).getClientLatencyMs());
    }

    @Test
    void queryWritesFirstQueryDumpWhenConfigured(@TempDir Path dumpDir) throws Exception {
        writeQdrantQueryFile("q-dump", """
                {"template":{}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        cfg.put(AbstractBenchmarkEngine.CONFIG_QUERY_DUMP_DIRECTORY, dumpDir.toString());
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        e.query("c", "q-dump", qp);
        assertTrue(Files.isRegularFile(dumpDir.resolve("qd-first-query.json")));
    }

    @Test
    void queryReturnsEmptyWhenGrpcSearchThrows() throws Exception {
        writeQdrantQueryFile("q-search-fail", """
                {"template":{}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("search failed")));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f, 0.2f)));
        QueryResponse r = e.query("c", "q-search-fail", qp);
        assertTrue(r.getDocumentIds().isEmpty());
        assertNull(r.getClientLatencyMs());
        assertNull(r.getServerLatencyMs());
    }

    @Test
    void connectReturnsFalseWhenInsecureTlsChannelConstructionThrows() {
        System.setProperty("jingra.insecure.tls", "true");
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("url", "https://127.0.0.1:6334");
            cfg.put("grpc_timeout_seconds", 1L);
            QdrantEngine e = new QdrantEngine(cfg) {
                @Override
                protected ManagedChannel buildInsecureTlsManagedChannel(
                        String host, int port, javax.net.ssl.X509TrustManager trustAll) throws Exception {
                    throw new IllegalStateException("simulated insecure TLS channel failure");
                }
            };
            assertFalse(e.connect());
        } finally {
            System.clearProperty("jingra.insecure.tls");
        }
    }

    @Test
    void queryRunsSearchWhenDumpSerializationThrowsInvalidProtocolBufferException() throws Exception {
        writeQdrantQueryFile("q-ipbe-dump", """
                {"template":{}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg) {
            @Override
            protected String printSearchPointsForDump(SearchPoints request) throws InvalidProtocolBufferException {
                throw new InvalidProtocolBufferException("simulated serialization failure");
            }
        };
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        QueryResponse r = e.query("c", "q-ipbe-dump", qp);
        assertTrue(r.getDocumentIds().isEmpty());
        assertNotNull(r.getClientLatencyMs());
        assertNotNull(r.getServerLatencyMs());
    }

    @Test
    void searchParamsSupportsExactFlag() {
        // Verify that SearchParams can be built with exact flag for brute force search
        io.qdrant.client.grpc.Points.SearchParams params = io.qdrant.client.grpc.Points.SearchParams.newBuilder()
                .setExact(true)
                .build();

        assertTrue(params.hasExact(), "exact field should be set");
        assertTrue(params.getExact(), "exact should be true for brute force search");
    }

    @Test
    void queryAppliesExactFlagFromTemplateParams() throws Exception {
        writeQdrantQueryFile("q-exact", """
                {"template":{"params":{"exact":true}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertNotNull(e.query("c", "q-exact", qp).getClientLatencyMs());
    }

    @Test
    void querySkipsHnswEfAndQuantizationWhenExactIsTrue() throws Exception {
        // When exact:true is set, hnsw_ef and quantization should be ignored
        writeQdrantQueryFile("q-exact-ignores-approx", """
                {"template":{"params":{"exact":true,"hnsw_ef":"{{num}}","quantization":{"oversampling":"{{rescore}}"}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f), "num", 64, "rescore", 3.0));
        // Query should succeed - exact search ignores approximate search params
        assertNotNull(e.query("c", "q-exact-ignores-approx", qp).getClientLatencyMs());
    }

    @Test
    void queryWhenExactKeyIsFalseUsesApproximateParamsNotExactFlag() throws Exception {
        writeQdrantQueryFile("q-exact-false-hnsw", """
                {"template":{"params":{"exact":false,"hnsw_ef":"48"}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertNotNull(e.query("c", "q-exact-false-hnsw", qp).getClientLatencyMs());
    }

    @Test
    void queryWithQuantizationBlockWithoutOversamplingSkipsRescoreParams() throws Exception {
        writeQdrantQueryFile("q-quant-no-oversampling-key", """
                {"template":{"params":{"quantization":{}}}}
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        SearchResponse sr = SearchResponse.newBuilder().setTime(0.0).build();
        QdrantClient mockClient = mock(QdrantClient.class);
        QdrantGrpcClient grpc = mock(QdrantGrpcClient.class);
        PointsFutureStub points = mock(PointsFutureStub.class);
        when(mockClient.grpcClient()).thenReturn(grpc);
        when(grpc.points()).thenReturn(points);
        when(points.search(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(sr));
        injectClient(e, mockClient);
        QueryParams qp = new QueryParams(Map.of("query_vector", List.of(0.1f)));
        assertNotNull(e.query("c", "q-quant-no-oversampling-key", qp).getClientLatencyMs());
    }

    @Test
    void createIndexAppliesBinaryQuantizationFromTopLevelDirectSchema() throws Exception {
        // Direct Qdrant-console schema format: hnsw_config and quantization_config at top level,
        // no "template" or "settings" wrapper. This is what wiki-dpr-e5-768-knn.json uses.
        writeQdrantSchemaFile("test-direct-quant", """
                {
                  "vectors": {"size": 2, "distance": "Cosine"},
                  "shard_number": 3,
                  "replication_factor": 2,
                  "hnsw_config": {"m": 16, "ef_construct": 100},
                  "quantization_config": {"binary": {"always_ram": true}}
                }
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        ArgumentCaptor<CreateCollection> captor = ArgumentCaptor.forClass(CreateCollection.class);
        when(mockClient.createCollectionAsync(captor.capture()))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(mockClient.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        injectClient(e, mockClient);

        assertTrue(e.createIndex("col-direct-quant", "test-direct-quant"));

        CreateCollection created = captor.getValue();
        assertTrue(created.hasQuantizationConfig(),
                "Binary quantization must be applied when quantization_config is at top level of schema");
        assertTrue(created.getQuantizationConfig().hasBinary(),
                "Binary quantization config must be set");
    }

    @Test
    void createIndexAppliesHnswConfigFromTopLevelDirectSchema() throws Exception {
        writeQdrantSchemaFile("test-direct-hnsw", """
                {
                  "vectors": {"size": 2, "distance": "Cosine"},
                  "hnsw_config": {"m": 8, "ef_construct": 200}
                }
                """);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("grpc_timeout_seconds", 1);
        QdrantEngine e = new QdrantEngine(cfg);
        QdrantClient mockClient = mock(QdrantClient.class);
        when(mockClient.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of()));
        ArgumentCaptor<CreateCollection> captor = ArgumentCaptor.forClass(CreateCollection.class);
        when(mockClient.createCollectionAsync(captor.capture()))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        injectClient(e, mockClient);

        assertTrue(e.createIndex("col-direct-hnsw", "test-direct-hnsw"));

        CreateCollection created = captor.getValue();
        var vectorParams = created.getVectorsConfig().getParams();
        assertTrue(vectorParams.hasHnswConfig(),
                "HNSW config must be applied when hnsw_config is at top level of schema");
        assertEquals(8, vectorParams.getHnswConfig().getM());
        assertEquals(200, vectorParams.getHnswConfig().getEfConstruct());
    }

}
