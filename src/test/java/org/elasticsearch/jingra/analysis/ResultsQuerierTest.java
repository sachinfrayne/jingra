package org.elasticsearch.jingra.analysis;

import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultsQuerierTest {

    @Test
    void queryByRunId_returnsEmptyList_whenNoResults() throws Exception {
        ElasticsearchEngine engine = new TestElasticsearchEngine(List.of());
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        List<BenchmarkResult> results = querier.queryByRunId("non-existent-run", List.of());

        assertTrue(results.isEmpty());
    }

    @Test
    void queryByRunId_wrapsExceptionWhenSearchFails() {
        ElasticsearchEngine engine = new TestElasticsearchEngine(List.of()) {
            @Override
            public SearchResponse<Map> search(String indexName, String queryJson) {
                throw new RuntimeException("transport down");
            }
        };
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        IOException ex = assertThrows(IOException.class, () -> querier.queryByRunId("run-x", List.of()));
        assertTrue(ex.getMessage().contains("run_id: run-x"));
        assertEquals("transport down", ex.getCause().getMessage());
    }

    @Test
    void queryByRunId_skipsHitsWithNullSource() throws Exception {
        Map<String, Object> doc1 = createBenchmarkResultMap("test-run-123", "elasticsearch", "recall@100");
        ElasticsearchEngine engine = new TestElasticsearchEngine(List.of(doc1), true);
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        List<BenchmarkResult> results = querier.queryByRunId("test-run-123", List.of());

        assertEquals(1, results.size());
        assertEquals("elasticsearch", results.get(0).getEngine());
    }

    @Test
    void queryByRunId_returnsResults_whenFound() throws Exception {
        Map<String, Object> doc1 = createBenchmarkResultMap("test-run-123", "elasticsearch", "recall@100");
        Map<String, Object> doc2 = createBenchmarkResultMap("test-run-123", "qdrant", "recall@100");

        ElasticsearchEngine engine = new TestElasticsearchEngine(List.of(doc1, doc2));
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        List<BenchmarkResult> results = querier.queryByRunId("test-run-123", List.of());

        assertEquals(2, results.size());
        assertEquals("elasticsearch", results.get(0).getEngine());
        assertEquals("qdrant", results.get(1).getEngine());
    }

    @Test
    void queryByRunId_buildsCorrectQuery() throws Exception {
        TestElasticsearchEngine engine = new TestElasticsearchEngine(List.of());
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        querier.queryByRunId("test-run-123", List.of());

        String queryJson = engine.getLastQueryJson();
        assertNotNull(queryJson);
        assertTrue(queryJson.contains("test-run-123"));
        assertTrue(queryJson.contains("run_id"));
    }

    /** Covers {@code engines == null} (short-circuit) in {@code buildRunIdQuery}, distinct from an empty list. */
    @Test
    void queryByRunId_buildsSimpleTermQuery_whenEnginesArgumentIsNull() throws Exception {
        TestElasticsearchEngine engine = new TestElasticsearchEngine(List.of());
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        querier.queryByRunId("run-null-engines", null);

        String queryJson = engine.getLastQueryJson();
        assertNotNull(queryJson);
        assertTrue(queryJson.contains("run-null-engines"));
        assertTrue(queryJson.contains("\"term\""));
        assertFalse(queryJson.contains("\"bool\""), "null engines should use run_id-only term query");
    }

    @Test
    void queryByRunId_buildsEngineFilterQuery_whenEnginesProvided() throws Exception {
        TestElasticsearchEngine engine = new TestElasticsearchEngine(List.of());
        ResultsQuerier querier = new ResultsQuerier(engine, "jingra-results");

        querier.queryByRunId("test-run-123", List.of("elasticsearch", "qdrant"));

        String queryJson = engine.getLastQueryJson();
        assertNotNull(queryJson);
        assertTrue(queryJson.contains("bool"));
        assertTrue(queryJson.contains("run_id.keyword"));
        assertTrue(queryJson.contains("engine.keyword"));
        assertTrue(queryJson.contains("elasticsearch"));
        assertTrue(queryJson.contains("qdrant"));
    }

    @Test
    void groupByEngine_groupsResultsByEngineName() {
        BenchmarkResult es1 = createBenchmarkResult("run-1", "elasticsearch", "recall@100");
        BenchmarkResult es2 = createBenchmarkResult("run-1", "elasticsearch", "recall@10");
        BenchmarkResult qd1 = createBenchmarkResult("run-1", "qdrant", "recall@100");

        ResultsQuerier querier = new ResultsQuerier(null, "test-index");
        Map<String, List<BenchmarkResult>> grouped = querier.groupByEngine(List.of(es1, es2, qd1));

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("elasticsearch").size());
        assertEquals(1, grouped.get("qdrant").size());
    }

    @Test
    void groupByEngine_returnsEmptyMap_whenNoResults() {
        ResultsQuerier querier = new ResultsQuerier(null, "test-index");
        Map<String, List<BenchmarkResult>> grouped = querier.groupByEngine(List.of());

        assertTrue(grouped.isEmpty());
    }

    @Test
    void groupByRecallLabel_groupsByRecallAtN() {
        BenchmarkResult r100_1 = createBenchmarkResult("run-1", "elasticsearch", "recall@100");
        BenchmarkResult r100_2 = createBenchmarkResult("run-1", "qdrant", "recall@100");
        BenchmarkResult r10_1 = createBenchmarkResult("run-1", "elasticsearch", "recall@10");

        ResultsQuerier querier = new ResultsQuerier(null, "test-index");
        Map<String, List<BenchmarkResult>> grouped = querier.groupByRecallLabel(List.of(r100_1, r100_2, r10_1));

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("recall@100").size());
        assertEquals(1, grouped.get("recall@10").size());
    }

    @Test
    void groupByRecallLabel_skipsResultsWithoutRecallLabel() {
        BenchmarkResult withLabel = createBenchmarkResult("run-1", "elasticsearch", "recall@100");
        BenchmarkResult withoutLabel = new BenchmarkResult(
                "run-1", "qdrant", "1.17", "vector_search", "ds", "pk", Map.of()
        );

        ResultsQuerier querier = new ResultsQuerier(null, "test-index");
        Map<String, List<BenchmarkResult>> grouped = querier.groupByRecallLabel(List.of(withLabel, withoutLabel));

        assertEquals(1, grouped.size());
        assertEquals(1, grouped.get("recall@100").size());
    }

    @Test
    void groupByRecallLabel_returnsEmptyMap_whenNoResults() {
        ResultsQuerier querier = new ResultsQuerier(null, "test-index");
        Map<String, List<BenchmarkResult>> grouped = querier.groupByRecallLabel(List.of());

        assertTrue(grouped.isEmpty());
    }

    // Helper methods

    private Map<String, Object> createBenchmarkResultMap(String runId, String engine, String recallLabel) {
        Map<String, Object> map = new HashMap<>();
        map.put("@timestamp", "2026-01-01T00:00:00Z");
        map.put("run_id", runId);
        map.put("engine", engine);
        map.put("engine_version", "1.0");
        map.put("benchmark_type", "vector_search");
        map.put("dataset", "test-dataset");
        map.put("param_key", "k=100");
        map.put("params", Map.of("k", 100));
        map.put("precision", 0.95);
        map.put("recall", 0.90);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("recall_label", recallLabel);
        map.put("metadata", metadata);
        return map;
    }

    private BenchmarkResult createBenchmarkResult(String runId, String engine, String recallLabel) {
        BenchmarkResult result = new BenchmarkResult(
                runId, engine, "1.0", "vector_search", "test-dataset", "k=100", Map.of("k", 100)
        );
        result.addMetadata("recall_label", recallLabel);
        return result;
    }

    // Test double for ElasticsearchEngine
    private static class TestElasticsearchEngine extends ElasticsearchEngine {
        private final List<Map<String, Object>> documents;
        private final boolean appendNullSourceHit;
        private String lastQueryJson;

        public TestElasticsearchEngine(List<Map<String, Object>> documents) {
            this(documents, false);
        }

        public TestElasticsearchEngine(List<Map<String, Object>> documents, boolean appendNullSourceHit) {
            super(Map.of("url", "http://localhost:9200"));
            this.documents = documents;
            this.appendNullSourceHit = appendNullSourceHit;
        }

        @Override
        public SearchResponse<Map> search(String indexName, String queryJson) {
            this.lastQueryJson = queryJson;

            if (documents.isEmpty() && !appendNullSourceHit) {
                return SearchResponse.of(s -> s
                        .timedOut(false)
                        .took(1L)
                        .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                        .hits(HitsMetadata.of(h -> h
                                .hits(List.of())
                                .total(TotalHits.of(t -> t.value(0).relation(TotalHitsRelation.Eq))))));
            }

            List<Hit<Map>> hits = new java.util.ArrayList<>(documents.stream()
                    .map(doc -> Hit.<Map>of(h -> h
                            .id("test-id")
                            .index(indexName)
                            .source(doc)))
                    .toList());
            if (appendNullSourceHit) {
                hits.add(Hit.<Map>of(h -> h.id("no-source").index(indexName)));
            }

            return SearchResponse.of(s -> s
                    .timedOut(false)
                    .took(1L)
                    .shards(ShardStatistics.of(sh -> sh.total(1).successful(1).failed(0)))
                    .hits(HitsMetadata.of(h -> h
                            .hits(hits)
                            .total(TotalHits.of(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq))))));
        }

        public String getLastQueryJson() {
            return lastQueryJson;
        }
    }
}
