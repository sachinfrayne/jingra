package org.elasticsearch.jingra.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic query response containing results and performance metrics.
 */
public class QueryResponse {
    private final List<String> documentIds;
    private final Double clientLatencyMs;
    private final Long serverLatencyMs;

    public QueryResponse(List<String> documentIds, Double clientLatencyMs, Long serverLatencyMs) {
        this.documentIds = documentIds != null ? new ArrayList<>(documentIds) : new ArrayList<>();
        this.clientLatencyMs = clientLatencyMs;
        this.serverLatencyMs = serverLatencyMs;
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
