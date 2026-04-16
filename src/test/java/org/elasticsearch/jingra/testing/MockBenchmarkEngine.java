package org.elasticsearch.jingra.testing;

import org.elasticsearch.jingra.engine.BenchmarkEngine;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight {@link BenchmarkEngine} for unit tests (no external services).
 */
public class MockBenchmarkEngine implements BenchmarkEngine {
    public int queryCount = 0;
    public boolean shouldFail = false;
    public final List<List<Float>> receivedVectors = new ArrayList<>();
    public final List<QueryParams> receivedParams = new ArrayList<>();

    /**
     * Simulated index presence for load/delete flows. {@link #deleteIndex} clears it;
     * {@link #createIndex} sets it again so eval tests that assume an index exists keep working.
     */
    protected volatile boolean indexPresent = true;

    @Override
    public boolean connect() {
        return true;
    }

    @Override
    public boolean createIndex(String indexName, String schemaName) {
        indexPresent = true;
        return true;
    }

    @Override
    public boolean indexExists(String indexName) {
        return indexPresent;
    }

    @Override
    public boolean deleteIndex(String indexName) {
        indexPresent = false;
        return true;
    }

    @Override
    public int ingest(List<Document> documents, String indexName, String idField) {
        return documents.size();
    }

    @Override
    public QueryResponse query(String indexName, String queryName, QueryParams params) {
        queryCount++;
        receivedParams.add(params);

        List<Float> vec = params.getFloatList("query_vector");
        if (vec != null) {
            receivedVectors.add(vec);
        }

        if (shouldFail) {
            throw new RuntimeException("simulated query failure");
        }

        List<String> docIds = List.of("doc-0", "doc-1", "doc-2");
        return new QueryResponse(docIds, 10.0, 5L);
    }

    @Override
    public long getDocumentCount(String indexName) {
        return 1000;
    }

    @Override
    public String getEngineName() {
        return "mock";
    }

    @Override
    public String getShortName() {
        return "mk";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Map<String, String> getIndexMetadata(String indexName) {
        return new HashMap<>();
    }

    @Override
    public Map<String, Object> getSchemaTemplate(String schemaName) {
        return new HashMap<>();
    }

    @Override
    public void close() throws Exception {
        // no-op
    }
}
