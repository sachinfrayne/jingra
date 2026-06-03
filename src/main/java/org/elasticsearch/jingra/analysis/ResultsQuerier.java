package org.elasticsearch.jingra.analysis;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Queries benchmark results from Elasticsearch.
 */
public class ResultsQuerier {
    private final ElasticsearchEngine engine;
    private final String indexName;

    public ResultsQuerier(ElasticsearchEngine engine, String indexName) {
        this.engine = engine;
        this.indexName = indexName;
    }

    /**
     * Query all benchmark results for a given run_id, optionally filtered to specific engines.
     *
     * @param runId   the run ID to filter by
     * @param engines engines to include; if empty, all engines are returned
     * @return list of benchmark results
     * @throws IOException if query fails
     */
    public List<BenchmarkResult> queryByRunId(String runId, List<String> engines) throws IOException {
        return queryByRunId(runId, "engine.keyword", engines);
    }

    /**
     * Query all benchmark results for a given run_id, filtered by an arbitrary keyword field.
     *
     * @param runId       the run ID to filter by
     * @param filterField the keyword field to filter on (e.g. "engine.keyword" or "profile.keyword")
     * @param values      values to include; if empty, all results for the run_id are returned
     * @return list of benchmark results
     * @throws IOException if query fails
     */
    public List<BenchmarkResult> queryByRunId(String runId, String filterField, List<String> values) throws IOException {
        String queryJson = buildRunIdQuery(runId, filterField, values);

        try {
            SearchResponse<Map> response = engine.search(indexName, queryJson);

            // Extract hits and convert to BenchmarkResult objects
            List<BenchmarkResult> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source != null) {
                    results.add(BenchmarkResult.fromMap(source));
                }
            }

            return results;
        } catch (Exception e) {
            throw new IOException("Failed to query benchmark results for run_id: " + runId, e);
        }
    }

    /**
     * Group benchmark results by engine name.
     *
     * @param results list of benchmark results
     * @return map of engine name to list of results
     */
    public Map<String, List<BenchmarkResult>> groupByEngine(List<BenchmarkResult> results) {
        return groupBy(results, BenchmarkResult::getEngine);
    }

    /**
     * Group benchmark results by an arbitrary string key extracted from each result.
     * Results whose key function returns null are excluded.
     *
     * @param results      list of benchmark results
     * @param keyExtractor function to extract the grouping key from a result
     * @return map of key to list of results
     */
    public Map<String, List<BenchmarkResult>> groupBy(
            List<BenchmarkResult> results,
            Function<BenchmarkResult, String> keyExtractor
    ) {
        return results.stream()
                .filter(r -> keyExtractor.apply(r) != null)
                .collect(Collectors.groupingBy(keyExtractor));
    }

    /**
     * Group benchmark results by recall@N label from metadata.
     *
     * @param results list of benchmark results
     * @return map of recall label (e.g., "recall@100") to list of results
     */
    public Map<String, List<BenchmarkResult>> groupByRecallLabel(List<BenchmarkResult> results) {
        Map<String, List<BenchmarkResult>> grouped = new HashMap<>();

        for (BenchmarkResult result : results) {
            String recallLabel = result.getMetadata().get("recall_label");
            if (recallLabel != null) {
                grouped.computeIfAbsent(recallLabel, k -> new ArrayList<>()).add(result);
            }
        }

        return grouped;
    }

    /**
     * Build Elasticsearch query JSON to filter by run_id.keyword and optionally by a keyword field.
     */
    private String buildRunIdQuery(String runId, String filterField, List<String> values) {
        if (values == null || values.isEmpty()) {
            return String.format("""
                    {
                      "query": {
                        "term": {
                          "run_id.keyword": "%s"
                        }
                      },
                      "size": 10000
                    }
                    """, runId);
        }

        String valueList = values.stream()
                .map(v -> "\"" + v + "\"")
                .collect(Collectors.joining(", "));

        return String.format("""
                {
                  "query": {
                    "bool": {
                      "must": [
                        { "term": { "run_id.keyword": "%s" } },
                        { "terms": { "%s": [%s] } }
                      ]
                    }
                  },
                  "size": 10000
                }
                """, runId, filterField, valueList);
    }
}
