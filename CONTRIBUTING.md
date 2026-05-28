# Contributing to Jingra

Thank you for your interest in contributing. This document explains how to get set up, how we work, and what we expect from contributions.

## Setting Up

**Prerequisites:** Java 21, Maven, Docker (required for integration tests)

```bash
git clone https://github.com/elastic/jingra
cd jingra
./ensure-agent-links.sh   # sets up CLAUDE.md and Cursor rule symlinks
source .envrc             # configures JAVA_HOME to Java 21
mvn clean package
```

Run the full test suite:

```bash
source .envrc && mvn test
```

Tests require Docker. Integration tests spin up real engine containers via Testcontainers — do not mock the engines.

## Benchmarking Philosophy

Two principles shape every decision in this project.

**Benchmark out of the box.** Unless a configuration option is the specific subject of a benchmark, use the default. The goal is to measure what a user actually gets when they deploy an engine, not what an expert can coax out of it with non-default tuning. Engine-specific configs (index settings, quantization parameters, HNSW construction parameters) are acceptable when they are the subject of the benchmark. Exotic tuning that most users would never apply is not.

**No force-merging to a single segment.** For Elasticsearch and OpenSearch we do not force-merge an index to 1 segment before measuring. A production index does not look like that, and a benchmark that force-merges is measuring a best-case scenario that users will never reproduce. If you want to measure post-merge steady-state performance, use `await_index_ready: true` in the load config — this waits for background merges to settle without collapsing the index into an artificially optimal structure.

## Development Requirements

### Test coverage

Every contribution must maintain 100% instruction and branch coverage. The build enforces this via JaCoCo — it will fail if any new code path is untested. To check coverage locally:

```bash
source .envrc && make coverage
```

Do not weaken assertions, remove test cases, or loosen mocks to make tests pass. Integration tests must use real engine containers via Testcontainers — do not mock the engines.

### No fixed sleeps

Do not use `Thread.sleep` or fixed delays for coordination, waiting for readiness, retries, or backpressure. Use `ExecutorService`, `CompletableFuture`, `awaitTermination`, `CountDownLatch`, `Phaser`, bounded blocking queues, or Awaitility for async assertions.

### Demos for new functionality

New engines and new search paradigms must include at least one demo. See [Adding a Demo](#adding-a-demo) below.

## Adding an Engine

1. Implement the `BenchmarkEngine` interface in `src/main/java/org/elasticsearch/jingra/engine/`
2. Register the engine in `EngineFactory.createEngine()`
3. Add schema and query JSON templates under `src/main/resources/` (or in the config directory structure used by demos)
4. Write a behaviour test suite that covers load, eval, and `awaitIndexReady` using a Testcontainers fixture for the real engine
5. Add at least one demo under `demos/` following the existing structure

Engines should use the engine's default index settings unless a specific non-default setting is the subject of the benchmark. Document any non-default settings in the config comments.

## Adding a Results Sink

1. Implement the `ResultsSink` interface
2. Register the sink in `ResultsSinkFactory.createSink()`
3. Cover the new sink with unit tests

## Adding a Demo

Demos live under `demos/<scenario-name>/<engine>/`. Each engine directory contains:

```
config.yaml                          # Jingra config for this demo
Makefile                             # delegates to demos/common/demo.mk
config/schemas/<name>.json           # index/collection schema
config/queries/<name>.json           # query template
```

The `Makefile` should follow this pattern:

```makefile
DEMO_NAME := My Demo Name
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export ES_VERSION := $(shell cat ../../../engine-versions/.elasticsearch | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.elasticsearch.yml
ENGINE_SERVICE := elasticsearch
include ../../common/demo.mk
```

Add the new engine target to the parent `demos/<scenario-name>/Makefile` `.PHONY` list.

## Pull Requests

- Keep changes focused. A PR that adds an engine and also refactors unrelated code is harder to review.
- Make sure `make test` passes before opening a PR.
- New engines and search paradigms must include a demo.
- Describe _why_ in the PR description, not just what. The diff shows what changed; the description should explain the motivation.

## License

By contributing you agree that your contributions will be licensed under the [Apache 2.0 License](LICENSE).
