package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VectorTypeInferenceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void nullNode_returnsNull() {
        assertNull(VectorTypeInference.firstElasticsearchVectorType(null));
    }

    @Test
    void jsonNull_returnsNull() throws Exception {
        JsonNode n = M.readTree("null");
        assertNull(VectorTypeInference.firstElasticsearchVectorType(n));
    }

    @Test
    void findsDenseVectorAtRoot() throws Exception {
        JsonNode n = M.readTree("{\"type\":\"dense_vector\"}");
        assertEquals("dense_vector", VectorTypeInference.firstElasticsearchVectorType(n));
    }

    @Test
    void findsKnnVectorNested() throws Exception {
        JsonNode n = M.readTree("{\"properties\":{\"v\":{\"mappings\":{\"type\":\"knn_vector\"}}}}");
        assertEquals("knn_vector", VectorTypeInference.firstElasticsearchVectorType(n));
    }

    @Test
    void ignoresOtherTypes() throws Exception {
        JsonNode n = M.readTree("{\"type\":\"keyword\",\"properties\":{\"x\":{\"type\":\"dense_vector\"}}}");
        assertEquals("dense_vector", VectorTypeInference.firstElasticsearchVectorType(n));
    }

    @Test
    void arrayTraversal() throws Exception {
        JsonNode n = M.readTree("[{\"type\":\"text\"},{\"nested\":[{\"type\":\"dense_vector\"}]}]");
        assertEquals("dense_vector", VectorTypeInference.firstElasticsearchVectorType(n));
    }

    @Test
    void noVectorType_returnsNull() throws Exception {
        JsonNode n = M.readTree("{\"type\":\"float\"}");
        assertNull(VectorTypeInference.firstElasticsearchVectorType(n));
    }
}
