package org.elasticsearch.jingra.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigValidatorTest {

    @Test
    void validateBase_ok() {
        ConfigValidator.validateBase(validBaseConfig());
    }

    @Test
    void validateBase_skipsDatasetValidationWhenAnalysisSectionPresent() {
        JingraConfig c = new JingraConfig();
        c.setEngine("elasticsearch");
        c.setElasticsearch(Map.of("url_env", "ES_URL"));
        c.setDataset(null);
        c.setDatasets(null);
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("run-1");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        c.setAnalysis(ac);
        ConfigValidator.validateBase(c);
    }

    @Test
    void validateBase_analyzeCommand_noBenchmarkEngine() {
        JingraConfig c = new JingraConfig();
        c.setDataset(null);
        c.setDatasets(null);
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("run-1");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        c.setAnalysis(ac);
        ConfigValidator.validateBase(c, "analyze");
    }

    @Test
    void validateBase_analyzeCommand_blankEngine_skipsEngineConfigLookup() {
        JingraConfig c = new JingraConfig();
        c.setEngine("   ");
        c.setDataset(null);
        c.setDatasets(null);
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("run-1");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        c.setAnalysis(ac);
        ConfigValidator.validateBase(c, "analyze");
    }

    @Test
    void validateBase_analyzeCommand_enginePresent_resolvesEngineConfig() {
        JingraConfig c = new JingraConfig();
        c.setEngine("elasticsearch");
        c.setElasticsearch(Map.of("url_env", "ES_URL"));
        c.setDataset(null);
        c.setDatasets(null);
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("run-1");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        c.setAnalysis(ac);
        ConfigValidator.validateBase(c, "analyze");
    }

    @Test
    void validateBase_analyzeCommand_optionalEngineStillValidated() {
        JingraConfig c = new JingraConfig();
        c.setEngine("unknown_engine");
        c.setDataset(null);
        c.setDatasets(null);
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("run-1");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        c.setAnalysis(ac);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c, "analyze"));
        assertEquals("Engine configuration not found for: unknown_engine", ex.getMessage());
    }

    @Test
    void validateBase_missingEngine() {
        JingraConfig c = validBaseConfig();
        c.setEngine(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("Engine not specified in configuration", ex.getMessage());
    }

    @Test
    void validateBase_emptyEngine() {
        JingraConfig c = validBaseConfig();
        c.setEngine("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("Engine not specified in configuration", ex.getMessage());
    }

    @Test
    void validateBase_missingDataset() {
        JingraConfig c = validBaseConfig();
        c.setDataset(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("Dataset not specified in configuration", ex.getMessage());
    }

    @Test
    void validateBase_emptyDataset() {
        JingraConfig c = validBaseConfig();
        c.setDataset("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("Dataset not specified in configuration", ex.getMessage());
    }

    @Test
    void validateBase_noDatasetsNull() {
        JingraConfig c = validBaseConfig();
        c.setDatasets(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("No datasets configured", ex.getMessage());
    }

    @Test
    void validateBase_noDatasetsEmpty() {
        JingraConfig c = validBaseConfig();
        c.setDatasets(Map.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("No datasets configured", ex.getMessage());
    }

    @Test
    void validateBase_activeDatasetMissingFromMap() {
        JingraConfig c = validBaseConfig();
        c.setDataset("missing");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("Dataset 'missing' not found in datasets configuration", ex.getMessage());
    }

    @Test
    void validateBase_engineConfigNotFound_unknownEngine() {
        JingraConfig c = validBaseConfig();
        c.setEngine("unknown_engine");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateBase(c));
        assertEquals("Engine configuration not found for: unknown_engine", ex.getMessage());
        assertNotNull(ex.getCause());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("Unknown engine: unknown_engine", ex.getCause().getMessage());
    }

    @Test
    void validateForEvaluation_nullConfig() {
        assertThrows(NullPointerException.class, () -> ConfigValidator.validateForEvaluation(null));
    }

    @Test
    void validateForEvaluation_ok() {
        ConfigValidator.validateForEvaluation(evalCompleteConfig());
    }

    @Test
    void validateForEvaluation_ok_withQueryTextFieldOnly() {
        JingraConfig c = evalCompleteConfig();
        DatasetConfig.QueriesMappingConfig qm = c.getActiveDataset().getQueriesMapping();
        qm.setQueryVectorField(null);
        qm.setQueryTextField("query_text");
        ConfigValidator.validateForEvaluation(c);
    }

    /** {@code query_vector_field} blank (non-null) exercises {@code isBlank()} on the left side of the OR. */
    @Test
    void validateForEvaluation_ok_whenVectorFieldWhitespaceOnly_andTextFieldSet() {
        JingraConfig c = evalCompleteConfig();
        DatasetConfig.QueriesMappingConfig qm = c.getActiveDataset().getQueriesMapping();
        qm.setQueryVectorField("  \t  ");
        qm.setQueryTextField("query_text");
        ConfigValidator.validateForEvaluation(c);
    }

    /** Both fields set: left conjunct of the vector/text guard is false without short-circuit ambiguity. */
    @Test
    void validateForEvaluation_ok_whenBothVectorAndTextFieldsNonBlank() {
        JingraConfig c = evalCompleteConfig();
        DatasetConfig.QueriesMappingConfig qm = c.getActiveDataset().getQueriesMapping();
        qm.setQueryVectorField("vec_col");
        qm.setQueryTextField("text_col");
        ConfigValidator.validateForEvaluation(c);
    }

    @Test
    void validateForEvaluation_missingEvaluationBlock() {
        JingraConfig c = evalCompleteConfig();
        c.setEvaluation(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals(
                "evaluation section is required in jingra.yaml for 'eval' (warmup/measurement workers and rounds)",
                ex.getMessage());
    }

    @Test
    void validateForEvaluation_warmupWorkersNull() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setWarmupWorkers(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.warmup_workers must be >= 1", ex.getMessage());
    }

    @Test
    void validateForEvaluation_warmupWorkersZero() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setWarmupWorkers(0);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.warmup_workers must be >= 1", ex.getMessage());
    }

    @Test
    void validateForEvaluation_measurementWorkersNull() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setMeasurementWorkers(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.measurement_workers must be >= 1", ex.getMessage());
    }

    @Test
    void validateForEvaluation_measurementWorkersZero() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setMeasurementWorkers(0);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.measurement_workers must be >= 1", ex.getMessage());
    }

    @Test
    void validateForEvaluation_warmupRoundsNull() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setWarmupRounds(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.warmup_rounds must be >= 0", ex.getMessage());
    }

    @Test
    void validateForEvaluation_warmupRoundsNegative() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setWarmupRounds(-1);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.warmup_rounds must be >= 0", ex.getMessage());
    }

    @Test
    void validateForEvaluation_measurementRoundsNull() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setMeasurementRounds(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.measurement_rounds must be >= 1", ex.getMessage());
    }

    @Test
    void validateForEvaluation_measurementRoundsZero() {
        JingraConfig c = evalCompleteConfig();
        c.getEvaluation().setMeasurementRounds(0);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("evaluation.measurement_rounds must be >= 1", ex.getMessage());
    }

    @Test
    void validateForEvaluation_datasetPathNull() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().setPath(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.path is required for evaluation", ex.getMessage());
    }

    @Test
    void validateForEvaluation_queriesPathNull() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().getPath().setQueriesPath(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.path.queries_path is required for evaluation", ex.getMessage());
    }

    @Test
    void validateForEvaluation_queriesPathBlank() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().getPath().setQueriesPath("   ");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.path.queries_path is required for evaluation", ex.getMessage());
    }

    @Test
    void validateForEvaluation_queriesMappingNull() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().setQueriesMapping(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.queries_mapping is required for evaluation", ex.getMessage());
    }

    @Test
    void validateForEvaluation_queryVectorFieldMissing() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().getQueriesMapping().setQueryVectorField(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.queries_mapping.query_vector_field or query_text_field is required", ex.getMessage());
    }

    @Test
    void validateForEvaluation_queryVectorFieldBlank() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().getQueriesMapping().setQueryVectorField("");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.queries_mapping.query_vector_field or query_text_field is required", ex.getMessage());
    }

    @Test
    void validateForEvaluation_groundTruthFieldMissing() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().getQueriesMapping().setGroundTruthField(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.queries_mapping.ground_truth_field is required", ex.getMessage());
    }

    @Test
    void validateForEvaluation_groundTruthFieldBlank() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().getQueriesMapping().setGroundTruthField("  ");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.queries_mapping.ground_truth_field is required", ex.getMessage());
    }

    @Test
    void validateForEvaluation_paramGroupsNull() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().setParamGroups(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.param_groups is required for evaluation", ex.getMessage());
    }

    @Test
    void validateForEvaluation_paramGroupsEmpty() {
        JingraConfig c = evalCompleteConfig();
        c.getActiveDataset().setParamGroups(Map.of());
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForEvaluation(c));
        assertEquals("dataset.param_groups is required for evaluation", ex.getMessage());
    }

    @Test
    void validateForLoad_nullConfig() {
        assertThrows(NullPointerException.class, () -> ConfigValidator.validateForLoad(null));
    }

    @Test
    void validateForLoad_ok() {
        ConfigValidator.validateForLoad(loadCompleteConfig());
    }

    @Test
    void validateForLoad_pathNull() {
        JingraConfig c = loadCompleteConfig();
        c.getActiveDataset().setPath(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForLoad(c));
        assertEquals("dataset.path is required for load", ex.getMessage());
    }

    @Test
    void validateForLoad_dataPathNull() {
        JingraConfig c = loadCompleteConfig();
        c.getActiveDataset().getPath().setDataPath(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForLoad(c));
        assertEquals("dataset.path.data_path is required for load", ex.getMessage());
    }

    @Test
    void validateForLoad_dataPathBlank() {
        JingraConfig c = loadCompleteConfig();
        c.getActiveDataset().getPath().setDataPath("  ");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForLoad(c));
        assertEquals("dataset.path.data_path is required for load", ex.getMessage());
    }

    @Test
    void validateForLoad_dataMappingNull() {
        JingraConfig c = loadCompleteConfig();
        c.getActiveDataset().setDataMapping(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForLoad(c));
        assertEquals("dataset.data_mapping.id_field is required for load", ex.getMessage());
    }

    @Test
    void validateForLoad_idFieldNull() {
        JingraConfig c = loadCompleteConfig();
        c.getActiveDataset().getDataMapping().setIdField(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForLoad(c));
        assertEquals("dataset.data_mapping.id_field is required for load", ex.getMessage());
    }

    @Test
    void validateForLoad_idFieldBlank() {
        JingraConfig c = loadCompleteConfig();
        c.getActiveDataset().getDataMapping().setIdField("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForLoad(c));
        assertEquals("dataset.data_mapping.id_field is required for load", ex.getMessage());
    }

    /** Passes {@link ConfigValidator#validateBase} with engine {@code elasticsearch}. */
    private static JingraConfig validBaseConfig() {
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");
        config.setElasticsearch(Map.of("url_env", "ES_URL"));
        config.setDatasets(Map.of("test-dataset", new DatasetConfig()));
        return config;
    }

    /** Passes {@link ConfigValidator#validateForEvaluation}. */
    private static JingraConfig evalCompleteConfig() {
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");
        config.setElasticsearch(Map.of("url_env", "ES_URL"));
        DatasetConfig ds = new DatasetConfig();
        DatasetConfig.PathConfig path = new DatasetConfig.PathConfig();
        path.setQueriesPath("queries.parquet");
        ds.setPath(path);
        DatasetConfig.QueriesMappingConfig qm = new DatasetConfig.QueriesMappingConfig();
        qm.setQueryVectorField("v");
        qm.setGroundTruthField("g");
        ds.setQueriesMapping(qm);
        ds.setParamGroups(Map.of("r", List.of(Map.of("k", 10))));
        config.setDatasets(Map.of("test-dataset", ds));
        EvaluationConfig ev = new EvaluationConfig();
        ev.setWarmupRounds(0);
        ev.setMeasurementRounds(1);
        ev.setWarmupWorkers(1);
        ev.setMeasurementWorkers(1);
        config.setEvaluation(ev);
        return config;
    }

    /** Passes {@link ConfigValidator#validateForLoad}. */
    private static JingraConfig loadCompleteConfig() {
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");
        config.setElasticsearch(Map.of("url_env", "ES_URL"));
        DatasetConfig ds = new DatasetConfig();
        DatasetConfig.PathConfig path = new DatasetConfig.PathConfig();
        path.setDataPath("data.parquet");
        ds.setPath(path);
        DatasetConfig.DataMappingConfig dm = new DatasetConfig.DataMappingConfig();
        dm.setIdField("id");
        ds.setDataMapping(dm);
        config.setDatasets(Map.of("test-dataset", ds));
        return config;
    }

    @Test
    void validateForAnalysis_nullConfig() {
        assertThrows(NullPointerException.class, () -> ConfigValidator.validateForAnalysis(null));
    }

    @Test
    void validateForAnalysis_ok() {
        ConfigValidator.validateForAnalysis(analysisCompleteConfig());
    }

    @Test
    void validateForAnalysis_missingAnalysisBlock() {
        JingraConfig c = analysisCompleteConfig();
        c.setAnalysis(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis section is required in jingra.yaml for 'analyze' command", ex.getMessage());
    }

    @Test
    void validateForAnalysis_missingRunId() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setRunId(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis.run_id is required", ex.getMessage());
    }

    @Test
    void validateForAnalysis_emptyRunId() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setRunId("  ");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis.run_id is required", ex.getMessage());
    }

    @Test
    void validateForAnalysis_missingEngines() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setEngines(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis.engines must have at least 1 engine", ex.getMessage());
    }

    @Test
    void validateForAnalysis_enginesTooFewZero() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setEngines(List.of());
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis.engines must have at least 1 engine", ex.getMessage());
    }

    @Test
    void validateForAnalysis_singleEngineIsValid() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setEngines(List.of("elasticsearch"));
        assertDoesNotThrow(() -> ConfigValidator.validateForAnalysis(c));
    }

    @Test
    void validateForAnalysis_missingResultsCluster() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setResultsCluster(null);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis.results_cluster configuration is required", ex.getMessage());
    }

    @Test
    void validateForAnalysis_resultsClusterEmpty() {
        JingraConfig c = analysisCompleteConfig();
        c.getAnalysis().setResultsCluster(Map.of());
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> ConfigValidator.validateForAnalysis(c));
        assertEquals("analysis.results_cluster configuration is required", ex.getMessage());
    }

    /** Passes {@link ConfigValidator#validateForAnalysis}. */
    private static JingraConfig analysisCompleteConfig() {
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");
        config.setElasticsearch(Map.of("url_env", "ES_URL"));
        config.setDatasets(Map.of("test-dataset", new DatasetConfig()));
        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("test-run-123");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        config.setAnalysis(ac);
        return config;
    }
}
