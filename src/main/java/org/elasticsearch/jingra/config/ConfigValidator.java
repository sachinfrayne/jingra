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
     */
    public static void validateBase(JingraConfig config) {
        String engine = config.getEngine();
        if (engine == null || engine.isEmpty()) {
            throw new IllegalStateException("Engine not specified in configuration");
        }

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

        try {
            config.getEngineConfig();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Engine configuration not found for: " + config.getEngine(), e);
        }

        logger.info("Configuration validated successfully");
        logger.info("  Engine: {}", config.getEngine());
        logger.info("  Dataset: {}", config.getDataset());
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
        requireNonBlank(
                ds.getQueriesMapping().getQueryVectorField(),
                "dataset.queries_mapping.query_vector_field is required");
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
