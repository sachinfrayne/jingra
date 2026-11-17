# Contributing to Jingra

Thank you for your interest in contributing. This document explains how to get set up, how we work, and what we expect from contributions.

## Setting Up

**Prerequisites:** Rust (see `rust-toolchain.toml` for the pinned version -- `rustup` installs it automatically the first time you run `cargo`/`rustc` in this directory), Docker (required for integration tests)

```bash
git clone https://github.com/sachinfrayne/jingra
cd jingra
./ensure-agent-links.sh   # sets up CLAUDE.md and Cursor rule symlinks
cargo build
```

Run the full test suite:

```bash
make test
```

Tests require Docker (or a Docker-API-compatible engine, e.g. podman with `DOCKER_HOST` pointed at its API socket). Integration tests spin up a real Qdrant container via the `testcontainers` crate — do not mock the engine.

## Benchmarking Philosophy

Two principles shape every decision in this project.

**Benchmark out of the box.** Unless a configuration option is the specific subject of a benchmark, use the default. The goal is to measure what a user actually gets when they deploy an engine, not what an expert can coax out of it with non-default tuning. Engine-specific configs (index settings, quantization parameters, HNSW construction parameters) are acceptable when they are the subject of the benchmark. Exotic tuning that most users would never apply is not.

**No force-merging to a single segment.** A production index does not look like a force-merged one, and a benchmark that force-merges is measuring a best-case scenario users will never reproduce. If you want to measure post-merge steady-state performance, use `await_index_ready: true` in the load config -- this waits for Qdrant's optimizer to settle without collapsing the collection into an artificially optimal structure.

## Development Requirements

### Test coverage

`make test` and `make coverage` enforce `cargo llvm-cov --fail-under-lines --fail-under-regions` at whatever threshold is currently set in the `Makefile` -- a ratchet on the codebase's actual achieved coverage, not a hardcoded 100%. When you raise coverage, raise the threshold in the same change so it can't silently regress. (Branch coverage in particular is only approximated via region coverage today, since `cargo-llvm-cov`'s `--branch` mode is nightly-only.)

Do not weaken assertions, remove test cases, or loosen mocks to make tests pass. Integration tests must use a real Qdrant container via `testcontainers` -- do not mock the engine.

### No fixed sleeps

Avoid arbitrary fixed sleeps for test synchronization, async assertions, and thread coordination. Prefer `tokio::sync` primitives (`Notify`, channels, `Semaphore`), `JoinHandle`/`JoinSet`, bounded channels, or polling with `tokio::time::timeout` for those cases.

In production code, a fixed sleep may be acceptable for retry backoff or readiness polling only when there is no better event-driven mechanism and the wait is bounded (for example, bounded or exponential backoff with a timeout) and handles cancellation correctly. Do not use unbounded fixed-delay loops or sleeps as a substitute for proper backpressure or coordination primitives.

### Demos for new functionality

New engines and new search paradigms must include at least one demo. See [Adding a Demo](#adding-a-demo) below.

## Adding an Engine

1. Implement the `BenchmarkEngine` trait in `src/engine/`
2. Register the engine in `engine::create_engine()`
3. Add schema and query JSON templates under the config directory structure used by demos (`jingra-config/{schemas,queries}/`)
4. Write a test suite that covers load, bench, and `await_index_ready` using a `testcontainers`-based fixture for the real engine, plus unit tests for anything that doesn't need a live server (template compilation, filter parsing, etc.)
5. Add at least one demo under `demos/` following the existing structure

Engines should use the engine's default index settings unless a specific non-default setting is the subject of the benchmark. Document any non-default settings in the config comments.

## Adding a Results Sink

1. Implement the `ResultsSink` trait in `src/output/`
2. Wire it up wherever sinks are constructed (currently `cli::bench`)
3. Cover the new sink with unit tests

## Adding a Demo

Demos live under `demos/<scenario-name>/<engine>/`. Each engine directory contains:

```
config.yaml                          # Jingra config for this demo
Makefile                             # delegates to demos/common/demo.mk
config/schemas/<name>.json           # collection schema
config/queries/<name>.json           # query template
```

The `Makefile` should follow this pattern:

```makefile
DEMO_NAME := My Demo Name
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export QD_VERSION := $(shell cat ../../../engine-versions/.qdrant | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.qdrant.yml
ENGINE_SERVICE := qdrant
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
