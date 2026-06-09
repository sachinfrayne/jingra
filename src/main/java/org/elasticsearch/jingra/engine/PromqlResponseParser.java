package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class PromqlResponseParser {

    private PromqlResponseParser() {}

    @SuppressWarnings("unchecked")
    static List<String> parseResultIds(String responseBody, ObjectMapper om, Logger logger) {
        try {
            Map<String, Object> parsed = om.readValue(responseBody, new TypeReference<>() {});
            Map<String, Object> data = (Map<String, Object>) parsed.get("data");
            if (data == null) return List.of();
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null) return List.of();
            List<String> ids = new ArrayList<>(results.size());
            for (Map<String, Object> r : results) {
                Map<String, String> metric = (Map<String, String>) r.get("metric");
                ids.add(metric != null ? metric.toString() : "");
            }
            return ids;
        } catch (Exception e) {
            logger.warn("Failed to parse PromQL query response", e);
            return List.of();
        }
    }
}
