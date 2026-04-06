# Jingra - Generic Benchmarking Framework (Java)

A generic benchmarking framework for testing search engines, observability platforms, time-series databases, and more.

## Overview

Jingra provides a unified interface for benchmarking different types of engines:
- **Vector Search Engines**: Elasticsearch, OpenSearch, Qdrant
- **Observability Platforms**: (Coming soon)
- **Time-Series Databases**: (Coming soon)

## Agent instructions (Cursor & Claude)

Project rules for assistants live in **`AGENTS.md`**. **`CLAUDE.md`** and **`.cursor/rules/jingra-project.mdc`** should be symlinks to that file (same content everywhere—edit **`AGENTS.md`** only). If links are missing after clone, run:

```bash
ln -sf AGENTS.md CLAUDE.md
ln -sf ../../AGENTS.md .cursor/rules/jingra-project.mdc
```

## Architecture

### Core Components

1. **BenchmarkEngine Interface**: Generic abstraction for all engines
   - `connect()` - Connect to the engine
   - `createIndex()` - Create index/collection with schema
   - `ingest()` - Ingest documents (agnostic to data source)
   - `query()` - Execute queries

2. **Data Loading**: Pluggable data readers
   - `ParquetReader` - Reads Parquet files and converts to `Document` objects
   - Engines receive generic `Document` objects, not file paths

3. **Results Output**: Pluggable sinks
   - `ConsoleResultsSink` - Outputs to console (always enabled)
   - `ElasticsearchResultsSink` - Outputs to Elasticsearch cluster
   - Extensible for custom sinks

## Building Docker Image

The primary way to use jingra is via Docker:

```bash
# Build and push multi-arch image (replace <DOCKER_URL> with your image reference, e.g. registry.example.com/jingra:1)
make image=registry.example.com/jingra:1

# Build without cache (if needed)
make build-no-cache image=registry.example.com/jingra:1

# Pull the built image
make pull image=registry.example.com/jingra:1
```

## Running

Study automation—Terraform, Kubernetes Job manifests, and engine-specific Makefiles for benchmarking **Elasticsearch**, **OpenSearch**, and **Qdrant** together on GCP—is maintained in the companion **competitive-benchmarking-studies** repository (`https://github.com/<your-github-org-or-username>/competitive-benchmarking-studies`), under **`IN-PROGRESS-es-vs-os-vs-qd/`** (for example `elasticsearch-gcp/k8s/`, `opensearch-gcp/k8s/`, `qdrant-gcp/k8s/`). The helper script **`compare.sh`** in that folder diffs sibling `*-gcp/` trees.

### Local Development (Without Docker)

For development and testing without Docker:

```bash
# Build JAR
mvn clean package

# Run data loading
java -jar target/jingra-0.2.0-jar-with-dependencies.jar load config.yaml

# Run evaluation
java -jar target/jingra-0.2.0-jar-with-dependencies.jar eval config.yaml
```

## Configuration

Configuration is YAML-based. Example:

```yaml
engine: "elasticsearch"

elasticsearch:
  url_env: "ELASTICSEARCH_URL"
  user_env: "ELASTICSEARCH_USER"
  password_env: "ELASTICSEARCH_PASSWORD"

dataset: "ecommerce-search-128"

datasets:
  ecommerce-search-128:
    type: "parquet"
    index_name: "ecommerce_search_catalog_128"
    vector_size: 128
    distance: "cosine"
    schema_name: "ecommerce_filtered_knn"
    query_name: "ecommerce_filtered_knn"
    path:
      data_path: "datasets/ecommerce-search-catalog-embedding-128-filters/data.parquet"
      queries_path: "datasets/ecommerce-search-catalog-embedding-128-filters/queries.parquet"
    data_mapping:
      id_field: "catalog_id"
      vector_field: "search_catalog_embedding"
    queries_mapping:
      query_vector_field: "search_catalog_embedding"
      ground_truth_field: "closest_ids"
      conditions_field: "conditions"
    param_groups:
      recall@100:
        - {size: 100, k: 10000, num_candidates: 10000, rescore: 1}

evaluation:
  warmup_workers: 16
  measurement_workers: 16
  warmup_rounds: 3
  measurement_rounds: 1

output:
  sinks:
    - type: "elasticsearch"
      config:
        url: "http://localhost:9200"
        user: "elastic"
        password: "changeme"
        index: "jingra-results"
```

## Engine Implementations

### Elasticsearch
- Uses Elasticsearch Java client 8.x
- Supports BBQ HNSW with rescore
- Bulk ingestion

### OpenSearch
- Uses OpenSearch Java client 2.x
- SSL/TLS support with self-signed certificates
- Compatible with FAISS HNSW

### Qdrant
- Uses Qdrant gRPC client
- Supports binary quantization
- Payload indexing for efficient filtering

## Metrics

### Vector Search
- **Precision**, **Recall**, **F1 Score**, **MRR**
- **Latency**: Client and server-side (avg, median, p90, p95, p99)
- **Throughput**: Queries per second

Metrics are flexible and extensible for different benchmark types (observability, time-series, etc.)

## Extending

### Adding a New Engine

1. Implement `BenchmarkEngine`:
```java
public class MyEngine extends AbstractBenchmarkEngine {
    // Implement required methods
}
```

2. Add to `Main.createEngine()`
3. Create schema/query templates in `jingra-config/schemas/` and `jingra-config/queries/`

### Adding a Results Sink

1. Implement `ResultsSink`:
```java
public class MyResultsSink implements ResultsSink {
    public void writeResult(BenchmarkResult result) { ... }
}
```

2. Register in `Main.createResultsSinks()`

## Testing

### Running Tests

The project uses JUnit 5 for testing. Always source `.envrc` before running tests to ensure Java 21 is used:

```bash
# Run all tests
source .envrc && mvn test

# Run specific test
source .envrc && mvn test -Dtest=MetricsCalculatorTest

# Generate coverage report
source .envrc && mvn test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

### Test Coverage

**Target coverage: 50% instruction coverage, 40% branch coverage**

Current implementation:
- **Unit tests**: ~100 tests (27% coverage baseline)
- **Integration tests**: 48 tests across 3 engines (Elasticsearch, OpenSearch, Qdrant)
- **Main orchestration tests**: 10 tests
- **Total**: ~158 tests achieving 50%+ coverage

Coverage is enforced via JaCoCo:

```bash
# Verify coverage meets minimum (requires Docker for integration tests)
source .envrc && mvn verify
```

### Test Categories

1. **Unit Tests** (no external dependencies):
   - `AbstractBenchmarkEngineTest` - Template rendering, config loading
   - `MetricsCalculatorTest` - Precision, recall, F1, MRR, latency calculations
   - `ParquetReaderTest` - Parquet reading, Avro conversion
   - `QueryResponseTest` - Response model
   - `BenchmarkResultTest` - Result model
   - `ConfigLoaderTest` - YAML config parsing
   - `DocumentTest` - Document model
   - `QueryParamsTest` - Query parameter handling
   - `ConsoleResultsSinkTest` - Console output
   - `FileDownloaderTest` - File download logic (with mocks)
   - `ElasticsearchResultsSinkTest` - Config validation

2. **Integration Tests** (require Docker/Testcontainers - **ENABLED**):
   - `ElasticsearchEngineTest` - 16 tests with Testcontainers
   - `OpenSearchEngineTest` - 16 tests with Testcontainers
   - `QdrantEngineTest` - 16 tests with Testcontainers
   - `BenchmarkEvaluatorTest` - Requires running engine (8 tests disabled, documented)

   **Prerequisites**: Docker must be running
   ```bash
   # Verify Docker is running
   docker ps

   # Run all tests (including integration tests)
   source .envrc && mvn test

   # Run specific engine test
   source .envrc && mvn test -Dtest=ElasticsearchEngineTest
   ```

   Integration tests use Testcontainers to automatically start Docker containers for each engine.

### Test Architecture

- **JUnit 5**: Test framework
- **Mockito**: Mocking framework (used selectively due to Java 21 compatibility)
- **Testcontainers**: Docker-based integration tests (planned)
- **JaCoCo**: Code coverage enforcement

### Coverage Breakdown

**50%+ coverage achieved with:**
- ✅ Unit tests (27% baseline)
- ✅ Engine integration tests (ElasticsearchEngineTest, OpenSearchEngineTest, QdrantEngineTest)
- ✅ Main orchestration tests
- ✅ All core business logic

**Remaining uncovered code (~50%):**
- Complex error handling paths (network failures, retry logic)
- Edge cases in engine-specific implementations
- Main.main() with System.exit() calls
- Some configuration validation paths

### Adding Tests

When adding new tests, follow these patterns:

1. **Unit tests**: Place in `src/test/java` matching the source package structure
2. **Use Java 21**: Always run via `source .envrc && mvn test`
3. **Test naming**: `{ClassUnderTest}Test.java`, methods as `test{Method}_{scenario}()`
4. **Arrange-Act-Assert**: Follow AAA pattern for clarity

Example:
```java
@Test
void testCalculatePrecision_withEmptyRetrieved() {
    // Arrange
    List<QueryResult> results = List.of(
        new QueryResult(List.of("1", "2"), List.of(), 100.0, null)
    );
    MetricsCalculator calc = new MetricsCalculator(results);

    // Act
    double precision = calc.calculatePrecision();

    // Assert
    assertEquals(0.0, precision, 0.001);
}
```
