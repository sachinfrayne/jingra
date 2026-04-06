package org.elasticsearch.jingra.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic document representation for ingestion.
 * A document is a map of field names to values.
 */
public class Document {
    private final Map<String, Object> fields;

    public Document() {
        this.fields = new HashMap<>();
    }

    public Document(Map<String, Object> fields) {
        this.fields = new HashMap<>(fields);
    }

    public void put(String field, Object value) {
        fields.put(field, value);
    }

    public Object get(String field) {
        return fields.get(field);
    }

    public String getString(String field) {
        Object value = fields.get(field);
        return value != null ? value.toString() : null;
    }

    public Integer getInteger(String field) {
        Object value = fields.get(field);
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) return Integer.parseInt((String) value);
        return null;
    }

    public Long getLong(String field) {
        Object value = fields.get(field);
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        return null;
    }

    public Double getDouble(String field) {
        Object value = fields.get(field);
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) return Double.parseDouble((String) value);
        return null;
    }

    public Boolean getBoolean(String field) {
        Object value = fields.get(field);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }

    public List<Float> getFloatList(String field) {
        Object value = fields.get(field);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Float> result = new ArrayList<>(list.size());
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

    public List<Double> getDoubleList(String field) {
        Object value = fields.get(field);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Double> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Number) {
                    result.add(((Number) item).doubleValue());
                } else {
                    // If not a number, list cannot be converted to Double list
                    return null;
                }
            }
            return result;
        }
        return null;
    }

    public Map<String, Object> getFields() {
        return new HashMap<>(fields);
    }

    public boolean containsField(String field) {
        return fields.containsKey(field);
    }

    @Override
    public String toString() {
        return "Document{fields=" + fields + '}';
    }
}
