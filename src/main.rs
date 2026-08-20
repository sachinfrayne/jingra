use clap::{Parser, Subcommand};
use jingra::config::Command as ConfigCommand;

#[derive(Parser)]
#[command(name = "jingra", about = "Benchmarking framework for vector search engines")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Load a dataset into the configured engine (create index + parallel ingest).
    Load { config: String },
    /// Run the benchmark sweep and write results.
    Bench { config: String },
    /// Read back stored results and generate comparisons/CSV/plots.
    Report { config: String },
}

/// Pure so tests don't need to mutate the process-global `JINGRA_VERSION` env var (which races
/// under the default parallel test runner); `main` passes in `std::env::var("JINGRA_VERSION")`.
fn version_banner(env_override: Option<String>) -> String {
    env_override
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_string())
}

#[tokio::main]
async fn main() {
    jingra::utils::configure_logging(None);
    let line = "=".repeat(80);
    tracing::info!("{line}");
    tracing::info!("JINGRA v{}", version_banner(std::env::var("JINGRA_VERSION").ok()));
    tracing::info!("{line}");

    let cli = Cli::parse();
    let (config_path, command) = match &cli.command {
        Commands::Load { config } => (config, ConfigCommand::Load),
        Commands::Bench { config } => (config, ConfigCommand::Bench),
        Commands::Report { config } => (config, ConfigCommand::Report),
    };

    let code = jingra::cli::dispatch(command, config_path).await;
    if code != 0 {
        std::process::exit(code);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
