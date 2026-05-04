package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Configuration for the analyze command.
 * Specifies which benchmark run to analyze and how to compare engines.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisConfig {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("engines")
    private List<String> engines;

    @JsonProperty("results_cluster")
    private Map<String, Object> resultsCluster;

    @JsonProperty("output_directory")
    private String outputDirectory = "./analysis-output";

    @JsonProperty("latency_metrics")
    private List<String> latencyMetrics;

    @JsonProperty("generate_plots")
    private boolean generatePlots = true;

    @JsonProperty("engine_versions")
    private Map<String, String> engineVersions;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public List<String> getEngines() {
        return engines;
    }

    public void setEngines(List<String> engines) {
        this.engines = engines;
    }

    public Map<String, Object> getResultsCluster() {
        return resultsCluster;
    }

    public void setResultsCluster(Map<String, Object> resultsCluster) {
        this.resultsCluster = resultsCluster;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Get latency metrics to use for comparison.
     * Each metric will be exported as a separate column in the CSV.
     * Defaults to ["server_latency_median"] if not specified.
     *
     * @return list of latency metrics
     */
    public List<String> getLatencyMetrics() {
        if (latencyMetrics != null && !latencyMetrics.isEmpty()) {
            return latencyMetrics;
        }
        return List.of("server_latency_median");
    }

    public void setLatencyMetrics(List<String> latencyMetrics) {
        this.latencyMetrics = latencyMetrics;
    }

    public boolean isGeneratePlots() {
        return generatePlots;
    }

    public void setGeneratePlots(boolean generatePlots) {
        this.generatePlots = generatePlots;
    }

    public Map<String, String> getEngineVersions() {
        return engineVersions != null ? engineVersions : Map.of();
    }

    public void setEngineVersions(Map<String, String> engineVersions) {
        this.engineVersions = engineVersions;
    }
}
