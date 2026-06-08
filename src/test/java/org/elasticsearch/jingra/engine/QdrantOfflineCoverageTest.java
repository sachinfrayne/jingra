package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QdrantOfflineCoverageTest {

    private static final String BOGUS_URL_ENV = "__JINGRA_OFFLINE_TEST_URL_NOT_SET__";

    private QdrantEngine engineWithoutUrl() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        return new QdrantEngine(cfg);
    }

    @Test
    void connectFailsWhenUrlMissing() {
        QdrantEngine e = engineWithoutUrl();
        try {
            assertFalse(e.connect());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void connectFailsWhenEndpointUnreachable() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "127.0.0.1:1");
        QdrantEngine e = new QdrantEngine(cfg);
        try {
            assertFalse(e.connect());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void grpcTimeoutSecondsFromConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url_env", BOGUS_URL_ENV);
        cfg.put("grpc_timeout_seconds", 7);
        QdrantEngine e = new QdrantEngine(cfg);
        try {
            assertFalse(e.connect());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void operationsNoOpWhenNeverConnected() {
        QdrantEngine e = engineWithoutUrl();
        assertFalse(e.connect());
        assertEquals("qdrant", e.getEngineName());
        assertEquals("qd", e.getShortName());
        assertEquals("unknown", e.getVersion());
        assertFalse(e.createDataStore("c", "s"));
        assertFalse(e.dataStoreExists("c"));
        assertFalse(e.resetDataStore("c"));
        assertEquals(0, e.ingest(List.of(new Document(Map.of("x", 1))), "c", null));
        assertTrue(e.query("c", "q", new QueryParams()).getDocumentIds().isEmpty());
        assertEquals(0L, e.getDocumentCount("c"));
        assertTrue(e.getIndexMetadata("c").isEmpty());
        assertDoesNotThrow(() -> e.close());
    }

    @Test
    void compiledExprHelpers_coverDoubleAndVectorExprBranches() throws Exception {
        // compileIntExpr(expression)
        var compileInt = QdrantEngine.class.getDeclaredMethod("compileIntExpr", String.class);
        compileInt.setAccessible(true);

        Object iNull = compileInt.invoke(null, (Object) null);
        Object iEmptyParam = compileInt.invoke(null, "{{   }}");
        Object iLiteral = compileInt.invoke(null, "  7 ");
        Object iBad = compileInt.invoke(null, "nope");

        // compileDoubleExpr(expression)
        var compileDouble = QdrantEngine.class.getDeclaredMethod("compileDoubleExpr", String.class);
        compileDouble.setAccessible(true);

        Object dNull = compileDouble.invoke(null, (Object) null);
        Object dEmptyParam = compileDouble.invoke(null, "{{   }}");
        Object dLiteral = compileDouble.invoke(null, "  1.25 ");
        Object dBad = compileDouble.invoke(null, "nope");

        // resolve(QueryParams)
        var resolveInt = iLiteral.getClass().getDeclaredMethod("resolve", QueryParams.class);
        resolveInt.setAccessible(true);
        assertEquals(7, (Integer) resolveInt.invoke(iLiteral, new QueryParams()));
        assertNull(resolveInt.invoke(iNull, new QueryParams()));
        assertNull(resolveInt.invoke(iBad, new QueryParams()));
        assertNull(resolveInt.invoke(iEmptyParam, new QueryParams()));

        Object iParam = compileInt.invoke(null, "{{k}}");
        assertEquals(3, resolveInt.invoke(iParam, new QueryParams(Map.of("k", 3))));
        // Short-circuit branches in placeholder detection
        assertNotNull(compileInt.invoke(null, "{{x"));   // startsWith true, endsWith false
        assertNotNull(compileInt.invoke(null, "x}}"));   // startsWith false, endsWith true

        var resolveDouble = dLiteral.getClass().getDeclaredMethod("resolve", QueryParams.class);
        resolveDouble.setAccessible(true);

        assertEquals(1.25, (Double) resolveDouble.invoke(dLiteral, new QueryParams()));
        assertNull(resolveDouble.invoke(dNull, new QueryParams()));
        assertNull(resolveDouble.invoke(dBad, new QueryParams()));
        assertNull(resolveDouble.invoke(dEmptyParam, new QueryParams()));

        Object dParam = compileDouble.invoke(null, "{{x}}");
        Map<String, Object> p = new HashMap<>();
        p.put("x", 9);
        assertEquals(9.0, (Double) resolveDouble.invoke(dParam, new QueryParams(p)));

        p.put("x", "not-a-number");
        assertNull(resolveDouble.invoke(dParam, new QueryParams(p)));
        // Short-circuit branches in placeholder detection
        assertNotNull(compileDouble.invoke(null, "{{x"));
        assertNotNull(compileDouble.invoke(null, "x}}"));

        // compileVectorExpr(JsonNode)
        var compileVector = QdrantEngine.class.getDeclaredMethod("compileVectorExpr", JsonNode.class);
        compileVector.setAccessible(true);

        JsonNode missing = null;
        Object vNull = compileVector.invoke(null, missing);
        Object vMissingNode = compileVector.invoke(null, MissingNode.getInstance());
        Object vNullNode = compileVector.invoke(null, NullNode.getInstance());
        Object vParam = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("\"{{vec}}\""));
        Object vEmptyParam = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("\"{{   }}\""));
        Object vNotPlaceholder = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("\"hello\""));
        Object vStartsNoEnds = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("\"{{x\""));
        Object vEndsNoStarts = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("\"x}}\""));
        Object vLiteral = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("[1.0, 2.0, 3.0]"));
        Object vBad = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("[1.0, \"x\"]"));
        Object vObject = compileVector.invoke(null, AbstractBenchmarkEngine.objectMapper.readTree("{\"a\":1}"));

        var resolveVector = vLiteral.getClass().getDeclaredMethod("resolve", QueryParams.class);
        resolveVector.setAccessible(true);

        assertEquals(List.of(1.0f, 2.0f, 3.0f), resolveVector.invoke(vLiteral, new QueryParams()));
        assertNull(resolveVector.invoke(vNull, new QueryParams()));
        assertNull(resolveVector.invoke(vMissingNode, new QueryParams()));
        assertNull(resolveVector.invoke(vNullNode, new QueryParams()));
        assertNull(resolveVector.invoke(vBad, new QueryParams()));
        assertNull(resolveVector.invoke(vEmptyParam, new QueryParams()));
        assertNull(resolveVector.invoke(vNotPlaceholder, new QueryParams()));
        assertNull(resolveVector.invoke(vStartsNoEnds, new QueryParams()));
        assertNull(resolveVector.invoke(vEndsNoStarts, new QueryParams()));
        assertNull(resolveVector.invoke(vObject, new QueryParams()));

        Map<String, Object> pv = new HashMap<>();
        pv.put("vec", List.of(0.1f, 0.2f));
        assertEquals(List.of(0.1f, 0.2f), resolveVector.invoke(vParam, new QueryParams(pv)));
    }

    @Test
    void queryCoversVectorMissingAndLimitDefaultAndDumpSerializationFailure() throws Exception {
        class TestEngine extends QdrantEngine {
            TestEngine() {
                super(Map.of("url_env", BOGUS_URL_ENV));
            }

            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            protected boolean shouldWriteFirstQueryDump() {
                return true;
            }

            @Override
            protected String printSearchPointsForDump(io.qdrant.client.grpc.Points.SearchPoints searchRequest)
                    throws com.google.protobuf.InvalidProtocolBufferException {
                throw new com.google.protobuf.InvalidProtocolBufferException("boom");
            }

            @Override
            protected JsonNode loadQueryTemplateCached(String queryName) {
                try {
                    return objectMapper.readTree("{\"vector\":\"{{vec}}\",\"with_payload\":true,\"with_vector\":true}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        TestEngine e = new TestEngine();
        try {
            // Legacy expression helpers: cover placeholder empty and parse failure branches
            var legacyInt = QdrantEngine.class.getDeclaredMethod("resolveIntParam", String.class, QueryParams.class);
            legacyInt.setAccessible(true);
            assertNull(legacyInt.invoke(e, "{{ }}", new QueryParams()));
            assertNull(legacyInt.invoke(e, "nope", new QueryParams()));

            var legacyDouble = QdrantEngine.class.getDeclaredMethod("resolveDoubleParam", String.class, QueryParams.class);
            legacyDouble.setAccessible(true);
            assertNull(legacyDouble.invoke(e, "{{ }}", new QueryParams()));
            assertNull(legacyDouble.invoke(e, "nope", new QueryParams()));

            // Wire a deep-stubbed client that throws on search so we never contact a server.
            io.qdrant.client.QdrantClient client =
                    Mockito.mock(io.qdrant.client.QdrantClient.class, Mockito.RETURNS_DEEP_STUBS);
            Mockito.when(client.grpcClient().points().search(ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("no server"));

            var f = QdrantEngine.class.getDeclaredField("client");
            f.setAccessible(true);
            f.set(e, client);

            // 1) Missing vector: returns before hitting client usage.
            assertTrue(e.query("c", "q", new QueryParams()).getDocumentIds().isEmpty());

            // 2) Vector present, limit missing -> default path; dump serialization fails but is swallowed; then search throws and is caught.
            QueryParams params = new QueryParams(Map.of("vec", List.of(0.1f, 0.2f, 0.3f)));
            assertTrue(e.query("c", "q", params).getDocumentIds().isEmpty());
        } finally {
            assertDoesNotThrow(e::close);
        }
    }

    @Test
    void compileTemplate_coversQuantizationRescoreOverride() throws Exception {
        QdrantEngine e = new QdrantEngine(Map.of("url_env", BOGUS_URL_ENV));
        try {
            JsonNode tpl = AbstractBenchmarkEngine.objectMapper.readTree(
                    "{\"template\":{\"vector\":\"{{vec}}\",\"params\":{\"quantization\":{\"rescore\":false,\"oversampling\":\"{{o}}\"}}}}");
            var m = QdrantEngine.class.getDeclaredMethod("compileTemplate", JsonNode.class);
            m.setAccessible(true);
            Object compiled = m.invoke(e, tpl);
            assertNotNull(compiled);

            // Cover vector/limit presence and explicit-null cases in compileTemplate
            assertNotNull(m.invoke(e, AbstractBenchmarkEngine.objectMapper.readTree("{\"template\":{}}")));
            assertNotNull(m.invoke(e, AbstractBenchmarkEngine.objectMapper.readTree("{\"template\":{\"vector\":null,\"limit\":null}}")));
        } finally {
            assertDoesNotThrow(e::close);
        }
    }
}
