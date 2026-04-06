package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Output configuration for benchmark results.
 * Results are always printed to console. Additional sinks can be configured.
 */
public class OutputConfig {

    @JsonProperty("sinks")
    private List<ResultsSinkConfig> sinks = new ArrayList<>();

    public List<ResultsSinkConfig> getSinks() {
        return sinks;
    }

    public void setSinks(List<ResultsSinkConfig> sinks) {
        this.sinks = sinks;
    }

    /**
     * Configuration for a results sink (e.g., Elasticsearch, database, file, etc.)
     */
    public static class ResultsSinkConfig {
        /**
         * Type of sink: "elasticsearch", "opensearch", "file", etc.
         */
        private String type;

        /**
         * Sink-specific configuration (e.g., connection details, index name, etc.)
         */
        private Map<String, Object> config;

        /**
         * Whether to write per-query metrics to this sink (default: true)
         */
        @JsonProperty("write_query_metrics")
        private Boolean writeQueryMetrics;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }

        public Boolean getWriteQueryMetrics() {
            return writeQueryMetrics != null ? writeQueryMetrics : true;
        }

        public void setWriteQueryMetrics(Boolean writeQueryMetrics) {
            this.writeQueryMetrics = writeQueryMetrics;
        }
    }
}
