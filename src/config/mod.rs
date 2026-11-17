//! YAML-backed configuration for jingra: which engine/dataset to use, evaluation tuning,
//! where benchmark results get written, and what `report` should compare.

mod validate;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::path::Path;

pub use validate::{validate_base, validate_for_bench, validate_for_load, validate_for_report};

/// Which CLI command is loading the config; relaxes validation for `report`, which does not
/// need a benchmark `engine`/dataset — only a `report` block pointing at stored results.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    Load,
    Bench,
    Report,
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("config file not found: {0}")]
    FileNotFound(String),
    #[error("config path is not a regular file: {0}")]
    NotAFile(String),
    #[error("failed to read config file: {0}")]
    Io(#[from] std::io::Error),
    #[error("failed to parse YAML: {0}")]
    Parse(#[from] serde_yaml::Error),
    #[error("{0}")]
    Invalid(String),
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct JingraConfig {
    pub engine: Option<String>,
    pub dataset: Option<String>,
    #[serde(default)]
    pub qdrant: Option<HashMap<String, Value>>,
    #[serde(default)]
    pub datasets: Option<HashMap<String, DatasetConfig>>,
    pub evaluation: Option<EvaluationConfig>,
    pub output: Option<OutputConfig>,
    pub load: Option<LoadConfig>,
    pub logging: Option<LoggingConfig>,
    pub report: Option<ReportConfig>,
}

impl JingraConfig {
    /// The config block for the selected engine. Only `qdrant` is a known engine today; the
    /// match is written to make adding the next engine a one-arm addition.
    pub fn engine_config(&self) -> Result<&HashMap<String, Value>, ConfigError> {
        match self.engine.as_deref() {
            Some("qdrant") => self
                .qdrant
                .as_ref()
                .ok_or_else(|| ConfigError::Invalid("qdrant configuration block missing".into())),
            Some(other) => Err(ConfigError::Invalid(format!("Unknown engine: {other}"))),
            None => Err(ConfigError::Invalid(
                "Engine not specified in configuration".into(),
            )),
        }
    }

    pub fn active_dataset(&self) -> Result<&DatasetConfig, ConfigError> {
        let key = self
            .dataset
            .as_deref()
            .ok_or_else(|| ConfigError::Invalid("No dataset configured".into()))?;
        self.datasets
            .as_ref()
            .and_then(|d| d.get(key))
            .ok_or_else(|| ConfigError::Invalid(format!("Dataset not found: {key}")))
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DatasetConfig {
    #[serde(rename = "type")]
    pub dataset_type: Option<String>,
    pub index_name: Option<String>,
    pub vector_size: Option<i64>,
    pub distance: Option<String>,
    pub schema_name: Option<String>,
    pub query_name: Option<String>,
    pub path: Option<PathConfig>,
    pub data_mapping: Option<DataMappingConfig>,
    pub queries_mapping: Option<QueriesMappingConfig>,
    pub param_groups: Option<HashMap<String, Vec<HashMap<String, Value>>>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PathConfig {
    pub data_path: Option<String>,
    pub queries_path: Option<String>,
    pub data_url_env: Option<String>,
    pub queries_url_env: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DataMappingConfig {
    pub id_field: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct QueriesMappingConfig {
    pub query_vector_field: Option<String>,
    pub query_text_field: Option<String>,
    pub ground_truth_field: Option<String>,
    pub conditions_field: Option<String>,
}

fn default_warmup_workers() -> i64 {
    16
}
fn default_measurement_workers() -> i64 {
    16
}
fn default_warmup_rounds() -> i64 {
    3
}
fn default_measurement_rounds() -> i64 {
    1
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationConfig {
    #[serde(default = "default_warmup_workers")]
    pub warmup_workers: i64,
    #[serde(default = "default_measurement_workers")]
    pub measurement_workers: i64,
    #[serde(default = "default_warmup_rounds")]
    pub warmup_rounds: i64,
    #[serde(default = "default_measurement_rounds")]
    pub measurement_rounds: i64,
    pub run_id: Option<String>,
}

impl Default for EvaluationConfig {
    fn default() -> Self {
        Self {
            warmup_workers: default_warmup_workers(),
            measurement_workers: default_measurement_workers(),
            warmup_rounds: default_warmup_rounds(),
            measurement_rounds: default_measurement_rounds(),
            run_id: None,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LoadConfig {
    pub batch_size: Option<i64>,
    pub threads: Option<i64>,
    pub queue_capacity: Option<i64>,
    #[serde(default)]
    pub await_index_ready: bool,
    /// Whether `load` may delete and recreate an index that already exists (default `true`:
    /// every load starts from an empty index, which is what a benchmark needs). Set to `false`
    /// when pointing at a shared cluster, so `load` errors instead of dropping data.
    pub recreate_existing_index: Option<bool>,
    /// Retries per ingest batch for transient failures (timeouts, connection drops). Permanent
    /// failures such as a wrong vector dimension or a bad API key are never retried.
    pub max_retries: Option<i64>,
    /// Longest to wait for the engine to finish background indexing when `await_index_ready`
    /// is set, in seconds.
    pub index_ready_timeout_seconds: Option<i64>,
}

impl LoadConfig {
    /// Default 1,000 documents per batch. Every queued batch is held in memory as JSON values
    /// (about 32 bytes per vector component), so large batches times a deep queue scale to
    /// gigabytes on high-dimensional data, and a single gRPC request must stay under the
    /// server's message size limit.
    pub fn batch_size_or_default(&self) -> i64 {
        self.batch_size.filter(|v| *v > 0).unwrap_or(1_000)
    }
    pub fn threads_or_default(&self) -> i64 {
        self.threads.filter(|v| *v > 0).unwrap_or(10)
    }
    pub fn queue_capacity_or_default(&self) -> i64 {
        self.queue_capacity.filter(|v| *v > 0).unwrap_or(8)
    }
    pub fn recreate_existing_index_or_default(&self) -> bool {
        self.recreate_existing_index.unwrap_or(true)
    }
    pub fn max_retries_or_default(&self) -> u32 {
        self.max_retries.filter(|v| *v >= 0).unwrap_or(10) as u32
    }
    pub fn index_ready_timeout_or_default(&self) -> std::time::Duration {
        std::time::Duration::from_secs(self.index_ready_timeout_seconds.filter(|v| *v > 0).unwrap_or(4 * 60 * 60) as u64)
    }
}

fn default_per_query_metrics() -> bool {
    true
}

/// Where and how benchmark results get written. Results are always printed to console;
/// `results_uri` additionally persists JSONL through the `object_store` abstraction (local
/// filesystem, S3, GCS, or Azure Blob — see the config value's scheme), so `bench` runs
/// executed as a Kubernetes Job leave durable, network-accessible results behind even after
/// the pod completes and is garbage-collected.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutputConfig {
    pub results_uri: Option<String>,
    #[serde(default = "default_per_query_metrics")]
    pub per_query_metrics: bool,
}

impl Default for OutputConfig {
    fn default() -> Self {
        Self {
            results_uri: None,
            per_query_metrics: default_per_query_metrics(),
        }
    }
}

impl OutputConfig {
    pub const DEFAULT_RESULTS_URI: &'static str = "file://./results";

    pub fn results_uri_or_default(&self) -> &str {
        self.results_uri
            .as_deref()
            .filter(|s| !s.is_empty())
            .unwrap_or(Self::DEFAULT_RESULTS_URI)
    }
}

fn default_log_level() -> String {
    "INFO".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingConfig {
    #[serde(default = "default_log_level")]
    pub level: String,
    pub loggers: Option<HashMap<String, String>>,
}

impl Default for LoggingConfig {
    fn default() -> Self {
        Self {
            level: default_log_level(),
            loggers: None,
        }
    }
}

fn default_output_directory() -> String {
    "./analysis-output".to_string()
}
fn default_generate_plots() -> bool {
    true
}

/// Configuration for the `report` command: which stored run to read back (via
/// `results_uri`, the same `object_store`-addressed location `bench` wrote to) and how to
/// compare engines within it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReportConfig {
    pub run_id: Option<String>,
    #[serde(default)]
    pub engines: Vec<String>,
    pub results_uri: Option<String>,
    #[serde(default = "default_output_directory")]
    pub output_directory: String,
    pub latency_metrics: Option<Vec<String>>,
    #[serde(default = "default_generate_plots")]
    pub generate_plots: bool,
    pub engine_versions: Option<HashMap<String, String>>,
}

impl Default for ReportConfig {
    fn default() -> Self {
        Self {
            run_id: None,
            engines: Vec::new(),
            results_uri: None,
            output_directory: default_output_directory(),
            latency_metrics: None,
            generate_plots: default_generate_plots(),
            engine_versions: None,
        }
    }
}

impl ReportConfig {
    pub fn latency_metrics_or_default(&self) -> Vec<String> {
        match &self.latency_metrics {
            Some(m) if !m.is_empty() => m.clone(),
            _ => vec!["server_latency_median".to_string()],
        }
    }

    pub fn engine_versions_or_empty(&self) -> HashMap<String, String> {
        self.engine_versions.clone().unwrap_or_default()
    }
}

/// Loads and base-validates a [`JingraConfig`] from a YAML file on disk.
pub fn load_from_file(config_path: &str, command: Option<Command>) -> Result<JingraConfig, ConfigError> {
    let path = Path::new(config_path);
    if !path.exists() {
        return Err(ConfigError::FileNotFound(config_path.to_string()));
    }
    if !path.is_file() {
        return Err(ConfigError::NotAFile(config_path.to_string()));
    }
    let contents = std::fs::read_to_string(path)?;
    let config: JingraConfig = serde_yaml::from_str(&contents)?;
    validate_base(&config, command)?;
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_temp_yaml(contents: &str) -> tempfile::NamedTempFile {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        f.write_all(contents.as_bytes()).unwrap();
        f
    }

    const MINIMAL_VALID_YAML: &str = r#"
engine: qdrant
dataset: demo
qdrant:
  url_env: QDRANT_URL
datasets:
  demo:
    type: vector
    index_name: demo
"#;

    #[test]
    fn load_from_file_missing_file_errors() {
        let err = load_from_file("/no/such/path.yaml", None).unwrap_err();
        assert!(matches!(err, ConfigError::FileNotFound(_)));
    }

    #[test]
    fn load_from_file_directory_path_errors() {
        let dir = tempfile::tempdir().unwrap();
        let err = load_from_file(dir.path().to_str().unwrap(), None).unwrap_err();
        assert!(matches!(err, ConfigError::NotAFile(_)));
    }

    #[test]
    fn load_from_file_parses_minimal_config() {
        let f = write_temp_yaml(MINIMAL_VALID_YAML);
        let config = load_from_file(f.path().to_str().unwrap(), None).unwrap();
        assert_eq!(config.engine.as_deref(), Some("qdrant"));
        assert_eq!(config.dataset.as_deref(), Some("demo"));
        let ds = config.active_dataset().unwrap();
        assert_eq!(ds.index_name.as_deref(), Some("demo"));
    }

    #[test]
    fn load_from_file_invalid_yaml_errors() {
        let f = write_temp_yaml("engine: [this is not, valid: yaml structure for our schema");
        let err = load_from_file(f.path().to_str().unwrap(), None).unwrap_err();
        assert!(matches!(err, ConfigError::Parse(_)));
    }

    #[test]
    fn engine_config_unknown_engine_errors() {
        let mut config = JingraConfig {
            engine: Some("elasticsearch".to_string()),
            ..Default::default()
        };
        config.dataset = Some("demo".to_string());
        let err = config.engine_config().unwrap_err();
        assert!(matches!(err, ConfigError::Invalid(_)));
    }

    #[test]
    fn engine_config_missing_engine_errors() {
        let config = JingraConfig::default();
        assert!(config.engine_config().is_err());
    }

    #[test]
    fn engine_config_qdrant_resolves_block() {
        let f = write_temp_yaml(MINIMAL_VALID_YAML);
        let config = load_from_file(f.path().to_str().unwrap(), None).unwrap();
        let block = config.engine_config().unwrap();
        assert_eq!(
            block.get("url_env").and_then(|v| v.as_str()),
            Some("QDRANT_URL")
        );
    }

    #[test]
    fn active_dataset_missing_key_errors() {
        let config = JingraConfig {
            dataset: Some("missing".to_string()),
            datasets: Some(HashMap::new()),
            ..Default::default()
        };
        assert!(config.active_dataset().is_err());
    }

    #[test]
    fn load_config_defaults_apply_when_unset_or_non_positive() {
        let lc = LoadConfig::default();
        assert_eq!(lc.batch_size_or_default(), 1_000);
        assert_eq!(lc.threads_or_default(), 10);
        assert_eq!(lc.queue_capacity_or_default(), 8);
        assert!(lc.recreate_existing_index_or_default());
        assert_eq!(lc.max_retries_or_default(), 10);
        assert_eq!(lc.index_ready_timeout_or_default(), std::time::Duration::from_secs(4 * 3600));

        let lc2 = LoadConfig {
            batch_size: Some(0),
            threads: Some(-1),
            queue_capacity: Some(5),
            await_index_ready: true,
            recreate_existing_index: Some(false),
            max_retries: Some(0),
            index_ready_timeout_seconds: Some(90),
        };
        assert_eq!(lc2.batch_size_or_default(), 1_000);
        assert_eq!(lc2.threads_or_default(), 10);
        assert_eq!(lc2.queue_capacity_or_default(), 5);
        assert!(!lc2.recreate_existing_index_or_default());
        assert_eq!(lc2.max_retries_or_default(), 0);
        assert_eq!(lc2.index_ready_timeout_or_default(), std::time::Duration::from_secs(90));
    }

    #[test]
    fn output_config_results_uri_defaults_to_local_file() {
        let oc = OutputConfig::default();
        assert_eq!(oc.results_uri_or_default(), OutputConfig::DEFAULT_RESULTS_URI);
        let oc2 = OutputConfig {
            results_uri: Some("s3://bucket/prefix".to_string()),
            per_query_metrics: false,
        };
        assert_eq!(oc2.results_uri_or_default(), "s3://bucket/prefix");
    }

    #[test]
    fn report_config_latency_metrics_defaults() {
        let rc = ReportConfig::default();
        assert_eq!(rc.latency_metrics_or_default(), vec!["server_latency_median"]);
        let rc2 = ReportConfig {
            latency_metrics: Some(vec!["latency_avg".to_string()]),
            ..Default::default()
        };
        assert_eq!(rc2.latency_metrics_or_default(), vec!["latency_avg"]);
    }

    #[test]
    fn evaluation_config_defaults_when_yaml_omits_fields() {
        let yaml = r#"
engine: qdrant
dataset: demo
qdrant: {}
datasets:
  demo:
    type: vector
evaluation:
  run_id: my-run
"#;
        let f = write_temp_yaml(yaml);
        let config = load_from_file(f.path().to_str().unwrap(), None).unwrap();
        let ev = config.evaluation.unwrap();
        assert_eq!(ev.warmup_workers, 16);
        assert_eq!(ev.measurement_workers, 16);
        assert_eq!(ev.warmup_rounds, 3);
        assert_eq!(ev.measurement_rounds, 1);
        assert_eq!(ev.run_id.as_deref(), Some("my-run"));
    }
}
