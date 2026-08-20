# Jingra - Vector Search Benchmarking Framework

A Rust benchmarking framework for vector search engines, currently focused on Qdrant. The
engine abstraction (`BenchmarkEngine`) is written to add more engines later without touching
the config/data/evaluation/CLI layers.

## Supported Engines

- **Qdrant** - Vector search with binary quantization
- More engines coming soon

## Quick Start

**Prerequisites:** Rust (see `rust-toolchain.toml` -- `rustup` installs the pinned version automatically), Docker (for integration tests)

```bash
# Build
cargo build --release

# Load data
target/release/jingra load config.yaml

# Run the benchmark sweep
target/release/jingra bench config.yaml

# Generate CSV + recall/latency plots from stored results
target/release/jingra report config.yaml
```

## Configuration

Configuration is YAML-based. Connection credentials are read from environment variables at runtime.

```yaml
engine: "qdrant"

qdrant:
  url_env: "QDRANT_URL"
  vector_field: "embedding"

dataset: "my-dataset"

datasets:
  my-dataset:
    type: "vector"
    index_name: "my_index"
    schema_name: "my_schema"
    query_name: "my_query"
    path:
      data_path: "datasets/data.parquet"
      queries_path: "datasets/queries.parquet"
    data_mapping:
      id_field: "id"
    queries_mapping:
      query_vector_field: "embedding"
      ground_truth_field: "ground_truth"
    param_groups:
      recall@100:
        - {size: 100, hnsw_ef: 128}

evaluation:
  warmup_workers: 16
  measurement_workers: 16
  warmup_rounds: 3
  measurement_rounds: 1

output:
  # Any object_store-supported URI: file://, s3://, gs://, az://. Local by default, so `bench`
  # run as a Kubernetes Job still leaves results somewhere durable once the pod is gone.
  results_uri: "file://./results"
  per_query_metrics: true

report:
  run_id: "my-run"
  engines: ["qdrant"]
  results_uri: "file://./results"
  output_directory: "./report-output"
  generate_plots: true
```

See [demos/](demos/) for complete worked examples (config, schema/query templates, and a
Docker Compose harness that spins up a real Qdrant container).

## Docker

```bash
# Build image
docker build -t jingra:local .

# Run
docker run --rm \
  -e QDRANT_URL \
  -v "$PWD/config.yaml:/config/jingra.yaml:ro" \
  -v "$PWD/datasets:/app/datasets:ro" \
  -v "$PWD/results:/app/results" \
  jingra:local bench /config/jingra.yaml
```

## Development

### Agent Instructions

Project rules for AI assistants live in `AGENTS.md`. After cloning, create symlinks:

```bash
ln -sf AGENTS.md CLAUDE.md
ln -sf ../../AGENTS.md .cursor/rules/jingra-project.mdc
```

### Testing

```bash
# Run all tests (requires Docker, or podman with DOCKER_HOST pointed at its API socket)
make test

# Run a specific test
cargo test metrics::

# Coverage report
make coverage
```

`make test`/`make coverage` enforce a coverage floor via `cargo llvm-cov` -- see `AGENTS.md` for
why it's a ratchet on the codebase's actual achieved coverage rather than a hardcoded 100%.

### Adding an Engine

1. Implement the `BenchmarkEngine` trait in `src/engine/`
2. Register it in `engine::create_engine()`
3. Add schema/query templates in `jingra-config/schemas/` and `jingra-config/queries/`

### Adding a Results Sink

1. Implement the `ResultsSink` trait in `src/output/`
2. Wire it up wherever sinks are constructed (currently `cli::bench`)

## Competitive Benchmarking Studies

Large-scale competitive benchmarks (Terraform, Kubernetes, multi-engine comparisons) live in a separate benchmarking studies repository, not here. This repo is the Jingra CLI/library you build locally or package as a container.

## License

[Apache 2.0](LICENSE)
