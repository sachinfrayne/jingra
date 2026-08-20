//! `report`: read back a run's stored results (via the same `object_store` layout `bench`
//! wrote to) and produce a CSV export and recall-vs-QPS/latency plots.
//!
//! Unlike the Java version's `BenchmarkComparator` (which diffed exactly two named engines,
//! baseline vs target), this reads *all* configured engines' results for a run and plots them
//! together on the same axes -- the same code path handles one engine today and N engines once
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
