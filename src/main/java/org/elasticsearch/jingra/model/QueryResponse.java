package org.elasticsearch.jingra.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic query response containing results and performance metrics.
 */
public class QueryResponse {
    private final List<String> documentIds;
    private final Double clientLatencyMs;
    private final Long serverLatencyMs;
    private final Map<String, Object> aggregateValues;

    public QueryResponse(List<String> documentIds, Double clientLatencyMs, Long serverLatencyMs) {
        this(documentIds, clientLatencyMs, serverLatencyMs, null);
    }

    public QueryResponse(List<String> documentIds, Double clientLatencyMs, Long serverLatencyMs,
                         Map<String, Object> aggregateValues) {
        this.documentIds = documentIds != null ? new ArrayList<>(documentIds) : new ArrayList<>();
        this.clientLatencyMs = clientLatencyMs;
        this.serverLatencyMs = serverLatencyMs;
        this.aggregateValues = aggregateValues;
    }

    public List<String> getDocumentIds() {
        return new ArrayList<>(documentIds);
    }

    public Double getClientLatencyMs() {
        return clientLatencyMs;
    }

    public Long getServerLatencyMs() {
        return serverLatencyMs;
    }

    public Map<String, Object> getAggregateValues() {
        return aggregateValues;
    }

    public int getResultCount() {
        return documentIds.size();
    }

    @Override
    public String toString() {
        return "QueryResponse{" +
                "resultCount=" + documentIds.size() +
                ", clientLatencyMs=" + clientLatencyMs +
                ", serverLatencyMs=" + serverLatencyMs +
                '}';
    }
}
