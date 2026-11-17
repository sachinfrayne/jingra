use clap::{Parser, Subcommand};
use jingra::config::Command as ConfigCommand;

#[derive(Parser)]
#[command(name = "jingra", version, about = "Benchmarking framework for vector search engines")]
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

#[tokio::main]
async fn main() {
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
