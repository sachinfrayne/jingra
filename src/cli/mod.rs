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

/// Version shown in the startup banner: `JINGRA_VERSION` if set and non-blank, else the crate
/// version. Pure so tests don't need to mutate the process-global environment (which races
/// under the default parallel test runner).
fn version_banner(env_override: Option<String>) -> String {
    env_override
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_string())
}

fn log_banner() {
    let line = "=".repeat(80);
    tracing::info!("{line}");
    tracing::info!("JINGRA v{}", version_banner(std::env::var("JINGRA_VERSION").ok()));
    tracing::info!("{line}");
}

/// Loads the config, configures logging from it, and runs the given command. Returns the
/// process exit code, so `main.rs` stays a thin shim around this (the only genuinely untestable
/// line left there is the actual `std::process::exit` call).
///
/// Logging is configured exactly once, here, after the config is read: the global subscriber
/// can only be installed once per process, so installing a default one earlier (as `main` used
/// to) would make the config's `logging:` block silently ineffective. If the config cannot be
/// read there is no `logging:` block to honor, so the default level is used and the error is
/// still reported.
pub async fn dispatch(command: crate::config::Command, config_path: &str) -> i32 {
    use crate::config::Command;

    let loaded = crate::config::load_from_file(config_path, Some(command));
    crate::utils::configure_logging(loaded.as_ref().ok().and_then(|c| c.logging.as_ref()));
    log_banner();

    let jingra_config = match loaded {
        Ok(c) => c,
        Err(e) => {
            tracing::error!("Failed to load config: {e}");
            return 1;
        }
    };

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

    #[test]
    fn version_banner_defaults_to_cargo_package_version_without_env_override() {
        assert_eq!(version_banner(None), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn version_banner_uses_env_override_when_set_and_non_blank() {
        assert_eq!(version_banner(Some("9.9.9".to_string())), "9.9.9");
    }

    #[test]
    fn version_banner_ignores_blank_env_override() {
        assert_eq!(version_banner(Some("   ".to_string())), env!("CARGO_PKG_VERSION"));
    }

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
