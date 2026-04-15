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
     * Query all benchmark results for a given run_id.
     *
     * @param runId the run ID to filter by
     * @return list of benchmark results
     * @throws IOException if query fails
     */
    public List<BenchmarkResult> queryByRunId(String runId) throws IOException {
        // Build Elasticsearch query to filter by run_id.keyword
        String queryJson = buildRunIdQuery(runId);

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
        return results.stream()
                .collect(Collectors.groupingBy(BenchmarkResult::getEngine));
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
     * Build Elasticsearch query JSON to filter by run_id.keyword.
     */
    private String buildRunIdQuery(String runId) {
        // Build a term query for run_id.keyword
        // Using keyword field ensures exact match (not analyzed)
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
}
