package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline and helper-focused tests for {@link QdrantEngine} (no mock gRPC client).
 */
class QdrantEngineBehaviorTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_QD_OFFLINE_URL_ENV__";

    @AfterEach
    void clearInsecureTlsProperty() {
        System.clearProperty("jingra.insecure.tls");
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

}
