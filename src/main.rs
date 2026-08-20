use clap::{Parser, Subcommand};
use jingra::cli;
use jingra::config::{self, Command as ConfigCommand};

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

fn version_banner() -> String {
    std::env::var("JINGRA_VERSION")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_string())
}

#[tokio::main]
async fn main() {
    jingra::utils::configure_logging(None);
    let line = "=".repeat(80);
    tracing::info!("{line}");
    tracing::info!("JINGRA v{}", version_banner());
    tracing::info!("{line}");

    let cli = Cli::parse();
    let (config_path, config_command) = match &cli.command {
        Commands::Load { config } => (config, ConfigCommand::Load),
        Commands::Bench { config } => (config, ConfigCommand::Bench),
        Commands::Report { config } => (config, ConfigCommand::Report),
    };

    let jingra_config = match config::load_from_file(config_path, Some(config_command)) {
        Ok(c) => c,
        Err(e) => {
            tracing::error!("Failed to load config: {e}");
            std::process::exit(1);
        }
    };

    jingra::utils::configure_logging(jingra_config.logging.as_ref());

    let result = match &cli.command {
        Commands::Load { .. } => cli::run_load(&jingra_config).await,
        Commands::Bench { .. } => cli::run_bench(&jingra_config).await,
        Commands::Report { .. } => cli::run_report(&jingra_config).await,
    };

    if let Err(e) = result {
        tracing::error!("Command failed: {e}");
        std::process::exit(1);
    }
}
