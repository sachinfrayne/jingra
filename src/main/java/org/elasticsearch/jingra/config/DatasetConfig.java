package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Dataset configuration.
 */
public class DatasetConfig {

    private String type;

    @JsonProperty("index_name")
    private String indexName;

    @JsonProperty("vector_size")
    private Integer vectorSize;

    private String distance;

    @JsonProperty("schema_name")
    private String schemaName;

    @JsonProperty("query_name")
    private String queryName;

    private PathConfig path;

    @JsonProperty("data_mapping")
    private DataMappingConfig dataMapping;

    @JsonProperty("queries_mapping")
    private QueriesMappingConfig queriesMapping;

    @JsonProperty("param_groups")
    private Map<String, List<Map<String, Object>>> paramGroups;

    // Getters and setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public Integer getVectorSize() {
        return vectorSize;
    }

    public void setVectorSize(Integer vectorSize) {
        this.vectorSize = vectorSize;
    }

    public String getDistance() {
        return distance;
    }

    public void setDistance(String distance) {
        this.distance = distance;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }

    public PathConfig getPath() {
        return path;
    }

    public void setPath(PathConfig path) {
        this.path = path;
    }

    public DataMappingConfig getDataMapping() {
        return dataMapping;
    }

    public void setDataMapping(DataMappingConfig dataMapping) {
        this.dataMapping = dataMapping;
    }

    public QueriesMappingConfig getQueriesMapping() {
        return queriesMapping;
    }

    public void setQueriesMapping(QueriesMappingConfig queriesMapping) {
        this.queriesMapping = queriesMapping;
    }

    public Map<String, List<Map<String, Object>>> getParamGroups() {
        return paramGroups;
    }

    public void setParamGroups(Map<String, List<Map<String, Object>>> paramGroups) {
        this.paramGroups = paramGroups;
    }

    public static class PathConfig {
        @JsonProperty("data_path")
        private String dataPath;

        @JsonProperty("queries_path")
        private String queriesPath;

        @JsonProperty("data_url_env")
        private String dataUrlEnv;

        @JsonProperty("queries_url_env")
        private String queriesUrlEnv;

        public String getDataPath() {
            return dataPath;
        }

        public void setDataPath(String dataPath) {
            this.dataPath = dataPath;
        }

        public String getQueriesPath() {
            return queriesPath;
        }

        public void setQueriesPath(String queriesPath) {
            this.queriesPath = queriesPath;
        }

        public String getDataUrlEnv() {
            return dataUrlEnv;
        }

        public void setDataUrlEnv(String dataUrlEnv) {
            this.dataUrlEnv = dataUrlEnv;
        }

        public String getQueriesUrlEnv() {
            return queriesUrlEnv;
        }

        public void setQueriesUrlEnv(String queriesUrlEnv) {
            this.queriesUrlEnv = queriesUrlEnv;
        }
    }

    public static class DataMappingConfig {
        @JsonProperty("id_field")
        private String idField;

        @JsonProperty("vector_field")
        private String vectorField;

        public String getIdField() {
            return idField;
        }

        public void setIdField(String idField) {
            this.idField = idField;
        }

        public String getVectorField() {
            return vectorField;
        }

        public void setVectorField(String vectorField) {
            this.vectorField = vectorField;
        }
    }

    public static class QueriesMappingConfig {
        @JsonProperty("query_vector_field")
        private String queryVectorField;

        @JsonProperty("query_text_field")
        private String queryTextField;

        @JsonProperty("ground_truth_field")
        private String groundTruthField;

        @JsonProperty("conditions_field")
        private String conditionsField;

        public String getQueryVectorField() {
            return queryVectorField;
        }

        public void setQueryVectorField(String queryVectorField) {
            this.queryVectorField = queryVectorField;
        }

        public String getQueryTextField() {
            return queryTextField;
        }

        public void setQueryTextField(String queryTextField) {
            this.queryTextField = queryTextField;
        }

        public String getGroundTruthField() {
            return groundTruthField;
        }

        public void setGroundTruthField(String groundTruthField) {
            this.groundTruthField = groundTruthField;
        }

        public String getConditionsField() {
            return conditionsField;
        }

        public void setConditionsField(String conditionsField) {
            this.conditionsField = conditionsField;
        }
    }
}
