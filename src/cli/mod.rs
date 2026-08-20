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

/// Loads the config, configures logging from it, and runs the given command. Returns the
/// process exit code, so `main.rs` stays a thin shim around this (the only genuinely untestable
/// line left there is the actual `std::process::exit` call).
pub async fn dispatch(command: crate::config::Command, config_path: &str) -> i32 {
    use crate::config::Command;

    let jingra_config = match crate::config::load_from_file(config_path, Some(command)) {
        Ok(c) => c,
        Err(e) => {
            tracing::error!("Failed to load config: {e}");
            return 1;
        }
    };
    crate::utils::configure_logging(jingra_config.logging.as_ref());

    let result = match command {
        Command::Load => run_load(&jingra_config).await,
        Command::Bench => run_bench(&jingra_config).await,
        Command::Report => run_report(&jingra_config).await,
    };

    match result {
        Ok(()) => 0,
        Err(e) => {
            tracing::error!("Command failed: {e}");
            1
        }
    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Command;
    use std::io::Write;

    #[tokio::test]
    async fn dispatch_returns_1_when_config_file_is_missing() {
        let code = dispatch(Command::Report, "/no/such/config.yaml").await;
        assert_eq!(code, 1);
    }

    #[tokio::test]
    async fn dispatch_returns_1_when_config_fails_validation() {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        writeln!(f, "engine: qdrant").unwrap(); // missing dataset/datasets -> validate_base fails
        let code = dispatch(Command::Load, f.path().to_str().unwrap()).await;
        assert_eq!(code, 1);
    }

    #[tokio::test]
    async fn dispatch_runs_report_end_to_end_and_returns_0_for_empty_run() {
        let results_dir = tempfile::tempdir().unwrap();
        let output_dir = tempfile::tempdir().unwrap();
        let mut f = tempfile::NamedTempFile::new().unwrap();
        writeln!(
            f,
            r#"
report:
  run_id: no-such-run
  engines: [qdrant]
  results_uri: "file://{}"
  output_directory: "{}"
  generate_plots: false
"#,
            results_dir.path().display(),
            output_dir.path().display()
        )
        .unwrap();

        let code = dispatch(Command::Report, f.path().to_str().unwrap()).await;
        assert_eq!(code, 0);
        assert!(output_dir.path().join("comparison.csv").exists());
    }
}
