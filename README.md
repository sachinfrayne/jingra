# Jingra - Generic Benchmarking Framework

A benchmarking framework for search engines, observability platforms, and time-series databases.

## Supported Engines

- **Elasticsearch** - Vector search with BBQ HNSW
- **OpenSearch** - Vector search with FAISS HNSW
- **Qdrant** - Vector search with binary quantization
- More engines coming soon

## Quick Start

**Prerequisites:** Java 21, Maven, Docker (for integration tests)

```bash
# Build
mvn clean package

# Load data
java -jar target/jingra-*-jar-with-dependencies.jar load config.yaml

# Run benchmark
java -jar target/jingra-*-jar-with-dependencies.jar eval config.yaml

# Analyze results (optional)
java -jar target/jingra-*-jar-with-dependencies.jar analyze config.yaml
```

## Configuration

Configuration is YAML-based. Connection credentials are read from environment variables at runtime.

```yaml
engine: "elasticsearch"

elasticsearch:
  url_env: "ELASTICSEARCH_URL"
  user_env: "ELASTICSEARCH_USER"
  password_env: "ELASTICSEARCH_PASSWORD"

dataset: "my-dataset"

datasets:
  my-dataset:
    type: "parquet"
    index_name: "my_index"
    vector_size: 128
    distance: "cosine"
    schema_name: "my_schema"
    query_name: "my_query"
    path:
      data_path: "datasets/data.parquet"
      queries_path: "datasets/queries.parquet"
    param_groups:
      recall@100:
        - {size: 100, k: 1000}

evaluation:
  warmup_workers: 16
  measurement_workers: 16
  warmup_rounds: 3
  measurement_rounds: 1

output:
  sinks:
    - type: "console"
    - type: "elasticsearch"
      config:
        url: "http://localhost:9200"
        index: "jingra-results"
```

See [example configs](examples/) for more details.

## Docker

```bash
# Build image
docker build -t jingra:local .

# Run
docker run --rm \
  -e ELASTICSEARCH_URL -e ELASTICSEARCH_USER -e ELASTICSEARCH_PASSWORD \
  -v "$PWD/config.yaml:/config/jingra.yaml:ro" \
  -v "$PWD/datasets:/app/datasets:ro" \
  jingra:local eval /config/jingra.yaml
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
# Run all tests (requires Docker)
source .envrc && mvn test

# Run specific test
source .envrc && mvn test -Dtest=MetricsCalculatorTest

# Coverage report
source .envrc && mvn test jacoco:report
open target/site/jacoco/index.html
```

Target: 100% instruction and branch coverage, enforced by JaCoCo.

### Adding an Engine

1. Implement `BenchmarkEngine` interface
2. Register in `EngineFactory.createEngine()`
3. Add schema/query templates in `jingra-config/schemas/` and `jingra-config/queries/`

### Adding a Results Sink

1. Implement `ResultsSink` interface
2. Register in `ResultsSinkFactory.createSink()`

## Competitive Benchmarking Studies

Large-scale competitive benchmarks (Terraform, Kubernetes, multi-engine comparisons) live in a separate benchmarking studies repository, not here. This repo is the Jingra CLI/library you build locally or package as a container.

## License

[Apache 2.0](LICENSE)
