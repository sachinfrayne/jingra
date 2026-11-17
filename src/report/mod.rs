//! `report`: read back a run's stored results (via the same `object_store` layout `bench`
//! wrote to) and produce a CSV export and recall-vs-QPS/latency plots.
//!
//! Rather than diffing exactly two named engines (a baseline and a target), this reads *all*
//! configured engines' results for a run and plots them together on the same axes -- the same code path handles one engine today and N engines once
//! Elasticsearch/OpenSearch return, without a separate "comparison" data model.

mod csv_export;
mod plot;
mod reader;

pub use csv_export::export_csv;
pub use plot::generate_tradeoff_plot;
pub use reader::read_results;

use crate::config::{self, JingraConfig};
use crate::model::BenchmarkResult;

#[derive(Debug, thiserror::Error)]
pub enum ReportError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Config(#[from] config::ConfigError),
    #[error("object store error: {0}")]
    ObjectStore(#[from] object_store::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("CSV error: {0}")]
    Csv(#[from] csv::Error),
    #[error("plot error: {0}")]
    Plot(String),
}

pub async fn run_report(config: &JingraConfig) -> Result<(), ReportError> {
    config::validate_for_report(config)?;
    let report_config = config.report.as_ref().expect("validated by validate_for_report");
    let run_id = report_config.run_id.as_deref().expect("validated by validate_for_report");
    let results_uri = report_config.results_uri.as_deref().expect("validated by validate_for_report");

    let all_results = read_results(results_uri, run_id).await?;
    let results: Vec<BenchmarkResult> = all_results
        .into_iter()
        .filter(|r| report_config.engines.iter().any(|e| e == &r.engine))
        .collect();

    if results.is_empty() {
        tracing::warn!(
            "No stored results found for run_id '{run_id}' matching engines {:?} at {results_uri}",
            report_config.engines
        );
    }

    std::fs::create_dir_all(&report_config.output_directory)?;

    let csv_path = std::path::Path::new(&report_config.output_directory).join("comparison.csv");
    export_csv(&results, &csv_path)?;
    tracing::info!("Wrote CSV comparison to {}", csv_path.display());

    if report_config.generate_plots {
        let qps_path = std::path::Path::new(&report_config.output_directory).join("recall_vs_throughput.svg");
        generate_tradeoff_plot(&results, "throughput", "Throughput (qps)", &qps_path)?;
        tracing::info!("Wrote plot to {}", qps_path.display());

        for metric in report_config.latency_metrics_or_default() {
            let path = std::path::Path::new(&report_config.output_directory).join(format!("recall_vs_{metric}.svg"));
            generate_tradeoff_plot(&results, &metric, &format!("{metric} (ms)"), &path)?;
            tracing::info!("Wrote plot to {}", path.display());
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::ReportConfig;
    use crate::output::{ObjectStoreResultsSink, ResultsSink};
    use std::collections::HashMap;

    #[tokio::test]
    async fn run_report_errors_when_report_block_missing() {
        let config = JingraConfig::default();
        assert!(run_report(&config).await.is_err());
    }

    #[tokio::test]
    async fn run_report_writes_csv_and_plots_for_matching_engine_results() {
        let results_dir = tempfile::tempdir().unwrap();
        let output_dir = tempfile::tempdir().unwrap();
        let results_uri = format!("file://{}", results_dir.path().display());

        let (store, prefix) = object_store::parse_url(&url::Url::parse(&results_uri).unwrap()).unwrap();
        let sink = ObjectStoreResultsSink::from_store(store, prefix, "run-1", false);
        let mut r = BenchmarkResult::new("run-1", "qdrant", "1.18.0", "vector", "demo", "hnsw_ef=32", HashMap::new());
        r.add_metric("recall", 0.9);
        r.add_metric("throughput", 500.0);
        sink.write_result(&r).await.unwrap();
        // A result from an engine not listed in report.engines must be excluded.
        let mut other = BenchmarkResult::new("run-1", "elasticsearch", "9.0.0", "vector", "demo", "k=1", HashMap::new());
        other.add_metric("recall", 0.5);
        sink.write_result(&other).await.unwrap();

        let config = JingraConfig {
            report: Some(ReportConfig {
                run_id: Some("run-1".to_string()),
                engines: vec!["qdrant".to_string()],
                results_uri: Some(results_uri),
                output_directory: output_dir.path().to_str().unwrap().to_string(),
                generate_plots: true,
                ..Default::default()
            }),
            ..Default::default()
        };

        run_report(&config).await.unwrap();

        let csv = std::fs::read_to_string(output_dir.path().join("comparison.csv")).unwrap();
        assert_eq!(csv.lines().count(), 2, "only the qdrant result should appear");
        assert!(csv.contains("qdrant"));
        assert!(!csv.contains("elasticsearch"));
        assert!(output_dir.path().join("recall_vs_throughput.svg").exists());
        assert!(output_dir.path().join("recall_vs_server_latency_median.svg").exists());
    }

    #[tokio::test]
    async fn run_report_skips_plots_when_disabled() {
        let results_dir = tempfile::tempdir().unwrap();
        let output_dir = tempfile::tempdir().unwrap();
        let config = JingraConfig {
            report: Some(ReportConfig {
                run_id: Some("run-2".to_string()),
                engines: vec!["qdrant".to_string()],
                results_uri: Some(format!("file://{}", results_dir.path().display())),
                output_directory: output_dir.path().to_str().unwrap().to_string(),
                generate_plots: false,
                ..Default::default()
            }),
            ..Default::default()
        };

        run_report(&config).await.unwrap();
        assert!(output_dir.path().join("comparison.csv").exists());
        assert!(!output_dir.path().join("recall_vs_throughput.svg").exists());
    }
}
