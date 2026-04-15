package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Root configuration for jingra benchmarking framework.
 */
public class JingraConfig {

    private String engine;
    private String dataset;

    @JsonProperty("elasticsearch")
    private Map<String, Object> elasticsearch;

    @JsonProperty("opensearch")
    private Map<String, Object> opensearch;

    @JsonProperty("qdrant")
    private Map<String, Object> qdrant;

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

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
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
            default -> throw new IllegalStateException("Unknown engine: " + engine);
        };
    }

    /**
     * Get the active dataset configuration.
     */
    public DatasetConfig getActiveDataset() {
        if (dataset == null || datasets == null) {
            throw new IllegalStateException("No dataset configured");
        }
        DatasetConfig config = datasets.get(dataset);
        if (config == null) {
            throw new IllegalStateException("Dataset not found: " + dataset);
        }
        return config;
    }
}
