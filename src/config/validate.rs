//! Validation rules applied on top of YAML deserialization. Kept separate from struct
//! definitions because these rules depend on *which command* is loading the config
//! (`report` relaxes the benchmark-engine/dataset requirements that `load`/`bench` enforce).

use super::{Command, ConfigError, JingraConfig};

/// Base validation applied right after YAML parsing, regardless of command.
pub fn validate_base(config: &JingraConfig, command: Option<Command>) -> Result<(), ConfigError> {
    let is_report = command == Some(Command::Report);
    if !is_report {
        require_benchmark_engine(config)?;
    } else if let Some(engine) = config.engine.as_deref().filter(|e| !e.is_empty()) {
        // If `report` configs still name an engine, make sure it resolves — but don't require one.
        let _ = engine;
        config.engine_config()?;
    }

    // A report-only config (has a `report` block) does not need a benchmark dataset.
    let is_report_only = config.report.is_some();
    if !is_report_only {
        let dataset_key = config
            .dataset
            .as_deref()
            .filter(|s| !s.is_empty())
            .ok_or_else(|| ConfigError::Invalid("Dataset not specified in configuration".into()))?;
        let datasets = config
            .datasets
            .as_ref()
            .filter(|d| !d.is_empty())
            .ok_or_else(|| ConfigError::Invalid("No datasets configured".into()))?;
        if !datasets.contains_key(dataset_key) {
            return Err(ConfigError::Invalid(format!(
                "Dataset '{dataset_key}' not found in datasets configuration"
            )));
        }
    }
    Ok(())
}

fn require_benchmark_engine(config: &JingraConfig) -> Result<(), ConfigError> {
    if config.engine.as_deref().filter(|e| !e.is_empty()).is_none() {
        return Err(ConfigError::Invalid(
            "Engine not specified in configuration".into(),
        ));
    }
    config.engine_config()?;
    Ok(())
}

/// Fields required for `load` (data ingest).
pub fn validate_for_load(config: &JingraConfig) -> Result<(), ConfigError> {
    let ds = config.active_dataset()?;
    let path = ds
        .path
        .as_ref()
        .ok_or_else(|| ConfigError::Invalid("dataset.path is required for load".into()))?;
    require_non_blank(path.data_path.as_deref(), "dataset.path.data_path is required for load")?;
    let dm = ds.data_mapping.as_ref().ok_or_else(|| {
        ConfigError::Invalid("dataset.data_mapping.id_field is required for load".into())
    })?;
    require_non_blank(
        dm.id_field.as_deref(),
        "dataset.data_mapping.id_field is required for load",
    )?;
    Ok(())
}

/// Fields required for `bench` (running the benchmark sweep).
pub fn validate_for_bench(config: &JingraConfig) -> Result<(), ConfigError> {
    let ev = config.evaluation.as_ref().ok_or_else(|| {
        ConfigError::Invalid(
            "evaluation section is required in jingra.yaml for 'bench' (warmup/measurement workers and rounds)"
                .into(),
        )
    })?;
    require_min(ev.warmup_workers, 1, "evaluation.warmup_workers must be >= 1")?;
    require_min(
        ev.measurement_workers,
        1,
        "evaluation.measurement_workers must be >= 1",
    )?;
    require_min(ev.warmup_rounds, 0, "evaluation.warmup_rounds must be >= 0")?;
    require_min(
        ev.measurement_rounds,
        1,
        "evaluation.measurement_rounds must be >= 1",
    )?;

    let ds = config.active_dataset()?;
    require_non_blank(ds.index_name.as_deref(), "dataset.index_name is required for bench")?;
    require_non_blank(ds.query_name.as_deref(), "dataset.query_name is required for bench")?;
    let path = ds
        .path
        .as_ref()
        .ok_or_else(|| ConfigError::Invalid("dataset.path is required for bench".into()))?;
    require_non_blank(
        path.queries_path.as_deref(),
        "dataset.path.queries_path is required for bench",
    )?;
    let qm = ds.queries_mapping.as_ref().ok_or_else(|| {
        ConfigError::Invalid("dataset.queries_mapping is required for bench".into())
    })?;

    let vector_field_missing = qm.query_vector_field.as_deref().map(str::is_empty).unwrap_or(true);
    let text_field_missing = qm.query_text_field.as_deref().map(str::is_empty).unwrap_or(true);
    if vector_field_missing && text_field_missing {
        return Err(ConfigError::Invalid(
            "dataset.queries_mapping.query_vector_field or query_text_field is required".into(),
        ));
    }
    require_non_blank(
        qm.ground_truth_field.as_deref(),
        "dataset.queries_mapping.ground_truth_field is required",
    )?;

    if ds.param_groups.as_ref().map(|m| m.is_empty()).unwrap_or(true) {
        return Err(ConfigError::Invalid(
            "dataset.param_groups is required for bench".into(),
        ));
    }
    Ok(())
}

/// Fields required for `report` (reading back stored results).
pub fn validate_for_report(config: &JingraConfig) -> Result<(), ConfigError> {
    let rc = config.report.as_ref().ok_or_else(|| {
        ConfigError::Invalid("report section is required in jingra.yaml for 'report' command".into())
    })?;
    require_non_blank(rc.run_id.as_deref(), "report.run_id is required")?;
    if rc.engines.is_empty() {
        return Err(ConfigError::Invalid(
            "report.engines must have at least 1 engine".into(),
        ));
    }
    require_non_blank(rc.results_uri.as_deref(), "report.results_uri is required")?;
    Ok(())
}

fn require_non_blank(value: Option<&str>, message: &str) -> Result<(), ConfigError> {
    match value {
        Some(v) if !v.trim().is_empty() => Ok(()),
        _ => Err(ConfigError::Invalid(message.to_string())),
    }
}

fn require_min(value: i64, min: i64, message: &str) -> Result<(), ConfigError> {
    if value < min {
        Err(ConfigError::Invalid(message.to_string()))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{
        DataMappingConfig, DatasetConfig, EvaluationConfig, PathConfig, QueriesMappingConfig,
        ReportConfig,
    };
    use std::collections::HashMap;

    fn base_config() -> JingraConfig {
        let mut datasets = HashMap::new();
        datasets.insert(
            "demo".to_string(),
            DatasetConfig {
                dataset_type: Some("vector".to_string()),
                ..Default::default()
            },
        );
        JingraConfig {
            engine: Some("qdrant".to_string()),
            dataset: Some("demo".to_string()),
            qdrant: Some(HashMap::new()),
            datasets: Some(datasets),
            ..Default::default()
        }
    }

    #[test]
    fn validate_base_requires_engine_for_load_and_bench() {
        let mut config = base_config();
        config.engine = None;
        assert!(validate_base(&config, Some(Command::Load)).is_err());
        assert!(validate_base(&config, Some(Command::Bench)).is_err());
    }

    #[test]
    fn validate_base_does_not_require_engine_for_report() {
        let mut config = base_config();
        config.engine = None;
        config.dataset = None;
        config.datasets = None;
        config.report = Some(ReportConfig::default());
        assert!(validate_base(&config, Some(Command::Report)).is_ok());
    }

    #[test]
    fn validate_base_requires_known_dataset() {
        let mut config = base_config();
        config.dataset = Some("does-not-exist".to_string());
        assert!(validate_base(&config, Some(Command::Load)).is_err());
    }

    #[test]
    fn validate_for_load_requires_data_path_and_id_field() {
        let mut config = base_config();
        // No path/data_mapping at all.
        assert!(validate_for_load(&config).is_err());

        {
            let ds = config.datasets.as_mut().unwrap().get_mut("demo").unwrap();
            ds.path = Some(PathConfig {
                data_path: Some("data.parquet".to_string()),
                ..Default::default()
            });
        }
        assert!(validate_for_load(&config).is_err()); // still missing id_field

        {
            let ds = config.datasets.as_mut().unwrap().get_mut("demo").unwrap();
            ds.data_mapping = Some(DataMappingConfig {
                id_field: Some("id".to_string()),
            });
        }
        assert!(validate_for_load(&config).is_ok());
    }

    #[test]
    fn validate_for_bench_requires_evaluation_block() {
        let config = base_config();
        assert!(validate_for_bench(&config).is_err());
    }

    #[test]
    fn validate_for_bench_requires_positive_worker_and_round_counts() {
        let mut config = base_config();
        config.evaluation = Some(EvaluationConfig {
            warmup_workers: 0,
            ..Default::default()
        });
        assert!(validate_for_bench(&config).is_err());
    }

    #[test]
    fn validate_for_bench_requires_vector_or_text_field() {
        let mut config = base_config();
        config.evaluation = Some(EvaluationConfig::default());
        {
            let ds = config.datasets.as_mut().unwrap().get_mut("demo").unwrap();
            ds.path = Some(PathConfig {
                queries_path: Some("queries.parquet".to_string()),
                ..Default::default()
            });
            ds.index_name = Some("idx".to_string());
            ds.query_name = Some("q".to_string());
            ds.queries_mapping = Some(QueriesMappingConfig {
                ground_truth_field: Some("ground_truth".to_string()),
                ..Default::default()
            });
            ds.param_groups = Some(HashMap::from([("recall@10".to_string(), vec![])]));
        }
        assert!(validate_for_bench(&config).is_err());

        {
            let ds = config.datasets.as_mut().unwrap().get_mut("demo").unwrap();
            ds.queries_mapping.as_mut().unwrap().query_vector_field = Some("embedding".to_string());
        }
        assert!(validate_for_bench(&config).is_ok());
    }

    #[test]
    fn validate_for_bench_requires_param_groups() {
        let mut config = base_config();
        config.evaluation = Some(EvaluationConfig::default());
        let ds = config.datasets.as_mut().unwrap().get_mut("demo").unwrap();
        ds.index_name = Some("idx".to_string());
        ds.query_name = Some("q".to_string());
        ds.path = Some(PathConfig {
            queries_path: Some("queries.parquet".to_string()),
            ..Default::default()
        });
        ds.queries_mapping = Some(QueriesMappingConfig {
            query_vector_field: Some("embedding".to_string()),
            ground_truth_field: Some("ground_truth".to_string()),
            ..Default::default()
        });
        assert!(validate_for_bench(&config).is_err());
    }

    #[test]
    fn validate_for_bench_requires_index_name_and_query_name() {
        // Previously a missing index_name or query_name was silently replaced by an empty
        // string and surfaced later as an obscure engine error.
        let mut config = base_config();
        config.evaluation = Some(EvaluationConfig::default());
        {
            let ds = config.datasets.as_mut().unwrap().get_mut("demo").unwrap();
            ds.path = Some(PathConfig { queries_path: Some("queries.parquet".to_string()), ..Default::default() });
            ds.queries_mapping = Some(QueriesMappingConfig {
                query_vector_field: Some("embedding".to_string()),
                ground_truth_field: Some("ground_truth".to_string()),
                ..Default::default()
            });
            ds.param_groups = Some(HashMap::from([("recall@10".to_string(), vec![HashMap::new()])]));
        }
        let msg = validate_for_bench(&config).unwrap_err().to_string();
        assert!(msg.contains("index_name"), "{msg}");

        config.datasets.as_mut().unwrap().get_mut("demo").unwrap().index_name = Some("idx".to_string());
        let msg = validate_for_bench(&config).unwrap_err().to_string();
        assert!(msg.contains("query_name"), "{msg}");

        config.datasets.as_mut().unwrap().get_mut("demo").unwrap().query_name = Some("q".to_string());
        assert!(validate_for_bench(&config).is_ok());
    }

    #[test]
    fn validate_for_report_requires_run_id_engines_and_results_uri() {
        let mut config = JingraConfig::default();
        assert!(validate_for_report(&config).is_err());

        config.report = Some(ReportConfig {
            run_id: Some("run-1".to_string()),
            ..Default::default()
        });
        assert!(validate_for_report(&config).is_err()); // no engines yet

        config.report.as_mut().unwrap().engines = vec!["qdrant".to_string()];
        assert!(validate_for_report(&config).is_err()); // no results_uri yet

        config.report.as_mut().unwrap().results_uri = Some("file://./results".to_string());
        assert!(validate_for_report(&config).is_ok());
    }
}
