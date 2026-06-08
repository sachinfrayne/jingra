package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Root configuration for jingra benchmarking framework.
 */
public class JingraConfig {

    private String engine;
    private String profile;
    private List<String> datasetNames;

    @JsonProperty("elasticsearch")
    private Map<String, Object> elasticsearch;

    @JsonProperty("opensearch")
    private Map<String, Object> opensearch;

    @JsonProperty("qdrant")
    private Map<String, Object> qdrant;

    @JsonProperty("prometheus")
    private Map<String, Object> prometheus;

    @JsonProperty("datasets")
    private Map<String, DatasetConfig> datasets;

    @JsonProperty("evaluation")
    private EvaluationConfig evaluation;

    @JsonProperty("output")
    private OutputConfig output;

    /**
     * Optional tuning for parallel ingest ({@code load} command).
     */
    @JsonProperty("load")
    private LoadConfig load;

    /**
     * Optional logging configuration for runtime log level adjustment.
     */
    @JsonProperty("logging")
    private LoggingConfig logging;

    /**
     * Optional analysis configuration for the analyze command.
     */
    @JsonProperty("analysis")
    private AnalysisConfig analysis;

    // Getters and setters
    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    /**
     * Legacy single-name accessor — returns the first configured dataset name, or {@code null}
     * if none. Use {@link #getDatasetNames()} when you need all of them.
     */
    @JsonIgnore
    public String getDataset() {
        return (datasetNames == null || datasetNames.isEmpty()) ? null : datasetNames.get(0);
    }

    /**
     * Legacy single-name setter — replaces the configured names with a single-element list.
     * A {@code null} clears the list; a non-null value (including the empty string) is preserved
     * verbatim so that validators can flag it as invalid.
     */
    @JsonIgnore
    public void setDataset(String dataset) {
        if (dataset == null) {
            this.datasetNames = null;
        } else {
            List<String> list = new ArrayList<>(1);
            list.add(dataset);
            this.datasetNames = list;
        }
    }

    @JsonProperty("dataset")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> getDatasetNames() {
        return datasetNames;
    }

    @JsonProperty("dataset")
    public void setDatasetNames(List<String> datasetNames) {
        this.datasetNames = datasetNames;
    }

    public Map<String, Object> getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(Map<String, Object> elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    public Map<String, Object> getOpensearch() {
        return opensearch;
    }

    public void setOpensearch(Map<String, Object> opensearch) {
        this.opensearch = opensearch;
    }

    public Map<String, Object> getQdrant() {
        return qdrant;
    }

    public void setQdrant(Map<String, Object> qdrant) {
        this.qdrant = qdrant;
    }

    public Map<String, Object> getPrometheus() {
        return prometheus;
    }

    public void setPrometheus(Map<String, Object> prometheus) {
        this.prometheus = prometheus;
    }

    public Map<String, DatasetConfig> getDatasets() {
        return datasets;
    }

    public void setDatasets(Map<String, DatasetConfig> datasets) {
        this.datasets = datasets;
    }

    public EvaluationConfig getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(EvaluationConfig evaluation) {
        this.evaluation = evaluation;
    }

    public OutputConfig getOutput() {
        return output;
    }

    public void setOutput(OutputConfig output) {
        this.output = output;
    }

    public LoadConfig getLoad() {
        return load;
    }

    public void setLoad(LoadConfig load) {
        this.load = load;
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    public AnalysisConfig getAnalysis() {
        return analysis;
    }

    public void setAnalysis(AnalysisConfig analysis) {
        this.analysis = analysis;
    }

    /**
     * Get the engine configuration for the selected engine.
     */
    public Map<String, Object> getEngineConfig() {
        return switch (engine.toLowerCase()) {
            case "elasticsearch" -> elasticsearch;
            case "opensearch" -> opensearch;
            case "qdrant" -> qdrant;
            case "prometheus" -> prometheus;
            default -> throw new IllegalStateException("Unknown engine: " + engine);
        };
    }

    /**
     * Get the first configured dataset's configuration. Retained for code paths (load, index
     * lookup) that still operate against a single dataset; eval uses {@link #getActiveDatasets()}.
     */
    public DatasetConfig getActiveDataset() {
        if (datasetNames == null || datasetNames.isEmpty() || datasets == null) {
            throw new IllegalStateException("No dataset configured");
        }
        String name = datasetNames.get(0);
        DatasetConfig config = datasets.get(name);
        if (config == null) {
            throw new IllegalStateException("Dataset not found: " + name);
        }
        return config;
    }

    /**
     * Returns the {@link DatasetConfig} entries for every name listed under {@code dataset:},
     * preserving declared order. Throws if any name is missing from {@link #getDatasets()}.
     */
    public List<DatasetConfig> getActiveDatasets() {
        if (datasetNames == null || datasetNames.isEmpty() || datasets == null) {
            throw new IllegalStateException("No dataset configured");
        }
        List<DatasetConfig> active = new ArrayList<>(datasetNames.size());
        for (String name : datasetNames) {
            DatasetConfig config = datasets.get(name);
            if (config == null) {
                throw new IllegalStateException("Dataset not found: " + name);
            }
            active.add(config);
        }
        return active;
    }
}
