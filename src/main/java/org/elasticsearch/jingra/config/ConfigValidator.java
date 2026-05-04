package org.elasticsearch.jingra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Validates {@link JingraConfig} instances after YAML loading or when constructed programmatically.
 */
public final class ConfigValidator {

    private static final Logger logger = LoggerFactory.getLogger(ConfigValidator.class);

    private ConfigValidator() {
    }

    /**
     * Base validation applied after loading YAML from a file or resource.
     * For analyze command (when analysis section is present), datasets are optional.
     */
    public static void validateBase(JingraConfig config) {
        validateBase(config, null);
    }

    /**
     * Same as {@link #validateBase(JingraConfig)} but {@code cliCommand} allows relaxing rules for
     * {@code analyze}, which does not use the benchmark {@code engine} / engine block (only
     * {@code analysis.results_cluster}).
     *
     * @param cliCommand CLI command name (e.g. {@code "load"}, {@code "eval"}, {@code "analyze"}), or {@code null}
     *                   for strict benchmark validation (engine required).
     */
    public static void validateBase(JingraConfig config, String cliCommand) {
        boolean analyzeCommand = "analyze".equals(cliCommand);
        if (!analyzeCommand) {
            requireBenchmarkEngine(config);
        } else {
            String engine = config.getEngine();
            if (engine != null && !engine.isBlank()) {
                try {
                    config.getEngineConfig();
                } catch (IllegalStateException e) {
                    throw new IllegalStateException("Engine configuration not found for: " + config.getEngine(), e);
                }
            }
        }

        // Skip dataset validation if this is an analysis-only config
        boolean isAnalysisOnly = config.getAnalysis() != null;

        if (!isAnalysisOnly) {
            String datasetKey = config.getDataset();
            if (datasetKey == null || datasetKey.isEmpty()) {
                throw new IllegalStateException("Dataset not specified in configuration");
            }

            Map<String, DatasetConfig> datasets = config.getDatasets();
            if (datasets == null || datasets.isEmpty()) {
                throw new IllegalStateException("No datasets configured");
            }

            if (!datasets.containsKey(datasetKey)) {
                throw new IllegalStateException("Dataset '" + datasetKey + "' not found in datasets configuration");
            }
        }

        logger.info("Configuration validated successfully");
        if (config.getEngine() != null && !config.getEngine().isBlank()) {
            logger.info("  Engine: {}", config.getEngine());
        }
        if (!isAnalysisOnly) {
            logger.info("  Dataset: {}", config.getDataset());
        }
    }

    private static void requireBenchmarkEngine(JingraConfig config) {
        String engine = config.getEngine();
        if (engine == null || engine.isEmpty()) {
            throw new IllegalStateException("Engine not specified in configuration");
        }
        try {
            config.getEngineConfig();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Engine configuration not found for: " + config.getEngine(), e);
        }
    }

    /**
     * Validates fields required for {@code eval} (benchmark evaluation).
     */
    public static void validateForEvaluation(JingraConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getEvaluation() == null) {
            throw new IllegalStateException(
                    "evaluation section is required in jingra.yaml for 'eval' (warmup/measurement workers and rounds)");
        }
        EvaluationConfig ev = config.getEvaluation();
        requireMin(ev.getWarmupWorkers(), 1, "evaluation.warmup_workers must be >= 1");
        requireMin(ev.getMeasurementWorkers(), 1, "evaluation.measurement_workers must be >= 1");
        requireMin(ev.getWarmupRounds(), 0, "evaluation.warmup_rounds must be >= 0");
        requireMin(ev.getMeasurementRounds(), 1, "evaluation.measurement_rounds must be >= 1");

        DatasetConfig ds = config.getActiveDataset();
        requireNonNullState(ds.getPath(), "dataset.path is required for evaluation");
        requireNonBlank(ds.getPath().getQueriesPath(), "dataset.path.queries_path is required for evaluation");
        requireNonNullState(ds.getQueriesMapping(), "dataset.queries_mapping is required for evaluation");

        String vectorField = ds.getQueriesMapping().getQueryVectorField();
        String textField = ds.getQueriesMapping().getQueryTextField();
        boolean vectorFieldMissing = vectorField == null ? true : vectorField.isBlank();
        boolean textFieldMissing = textField == null ? true : textField.isBlank();
        if (vectorFieldMissing & textFieldMissing) { // `&`: both booleans already; no short-circuit on the pair
            throw new IllegalStateException(
                "dataset.queries_mapping.query_vector_field or query_text_field is required");
        }
        requireNonBlank(
                ds.getQueriesMapping().getGroundTruthField(),
                "dataset.queries_mapping.ground_truth_field is required");
        if (ds.getParamGroups() == null || ds.getParamGroups().isEmpty()) {
            throw new IllegalStateException("dataset.param_groups is required for evaluation");
        }
    }

    /**
     * Validates fields required for {@code load} (data ingest).
     */
    public static void validateForLoad(JingraConfig config) {
        Objects.requireNonNull(config, "config");
        DatasetConfig ds = config.getActiveDataset();
        requireNonNullState(ds.getPath(), "dataset.path is required for load");
        requireNonBlank(ds.getPath().getDataPath(), "dataset.path.data_path is required for load");
        DatasetConfig.DataMappingConfig dm = ds.getDataMapping();
        if (dm == null) {
            throw new IllegalStateException("dataset.data_mapping.id_field is required for load");
        }
        requireNonBlank(dm.getIdField(), "dataset.data_mapping.id_field is required for load");
    }

    /**
     * Validates fields required for {@code analyze} (benchmark result analysis).
     */
    public static void validateForAnalysis(JingraConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getAnalysis() == null) {
            throw new IllegalStateException(
                    "analysis section is required in jingra.yaml for 'analyze' command");
        }
        AnalysisConfig ac = config.getAnalysis();
        requireNonBlank(ac.getRunId(), "analysis.run_id is required");
        if (ac.getEngines() == null || ac.getEngines().size() < 1) {
            throw new IllegalStateException("analysis.engines must have at least 1 engine");
        }
        if (ac.getResultsCluster() == null || ac.getResultsCluster().isEmpty()) {
            throw new IllegalStateException("analysis.results_cluster configuration is required");
        }
    }

    private static <T> T requireNonNullState(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static void requireMin(Integer value, int min, String message) {
        if (value == null || value < min) {
            throw new IllegalStateException(message);
        }
    }
}
