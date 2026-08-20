//! CLI command orchestration: `load`, `bench`, `report`.

mod bench;
mod load;

pub use bench::run_bench;
pub use load::run_load;

use crate::config::ConfigError;
use crate::data::DataError;
use crate::engine::EngineError;
use crate::evaluation::EvaluationError;
use crate::output::OutputError;
use crate::report::ReportError;
use crate::utils::UtilsError;

/// `report`: read back stored results and generate CSV/plots. Thin wrapper so `main.rs` only
/// needs `cli::*` for all three commands.
pub async fn run_report(config: &crate::config::JingraConfig) -> Result<(), CliError> {
    crate::report::run_report(config).await.map_err(CliError::from)
}

#[derive(Debug, thiserror::Error)]
pub enum CliError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Config(#[from] ConfigError),
    #[error(transparent)]
    Engine(#[from] EngineError),
    #[error(transparent)]
    Data(#[from] DataError),
    #[error(transparent)]
    Utils(#[from] UtilsError),
    #[error(transparent)]
    Evaluation(#[from] EvaluationError),
    #[error(transparent)]
    Output(#[from] OutputError),
    #[error(transparent)]
    Report(#[from] ReportError),
    #[error("task join error: {0}")]
    Join(#[from] tokio::task::JoinError),
}
