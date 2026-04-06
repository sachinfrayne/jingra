package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extracts Elasticsearch / OpenSearch mapping vector field types from JSON trees.
 */
public final class VectorTypeInference {

    private VectorTypeInference() {}

    /**
     * Depth-first search for the first {@code type} field equal to {@code dense_vector} or {@code knn_vector}.
     */
    public static String firstElasticsearchVectorType(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.has("type")) {
            String t = node.get("type").asText("");
            if ("dense_vector".equals(t) || "knn_vector".equals(t)) {
                return t;
            }
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                String r = firstElasticsearchVectorType(child);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
