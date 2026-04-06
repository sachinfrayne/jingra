package org.elasticsearch.jingra.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic query parameters for benchmarking.
 */
public class QueryParams {
    private final Map<String, Object> params;

    public QueryParams() {
        this.params = new HashMap<>();
    }

    public QueryParams(Map<String, Object> params) {
        this.params = new HashMap<>(params);
    }

    public QueryParams put(String key, Object value) {
        params.put(key, value);
        return this;
    }

    public Object get(String key) {
        return params.get(key);
    }

    public String getString(String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    public Integer getInteger(String key) {
        Object value = params.get(key);
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }

    public Long getLong(String key) {
        Object value = params.get(key);
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<Float> getFloatList(String key) {
        Object value = params.get(key);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Float> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number) {
                    result.add(((Number) item).floatValue());
                } else {
                    // If not a number, list cannot be converted to Float list
                    return null;
                }
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object value = params.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    public Map<String, Object> getAll() {
        return new HashMap<>(params);
    }

    @Override
    public String toString() {
        return "QueryParams{" + params + '}';
    }
}
