# Changelog

All notable changes to jingra are listed here. The project follows
[semantic versioning](https://semver.org/). While the major version is 0, the command line and
configuration format may still change between minor versions.

## 0.1.0 - 2026-11-17

First release. jingra is a benchmarking tool for vector search engines, rewritten in Rust from
an earlier Java tool. This release supports Qdrant only; the engine interface is designed so
other engines can be added.

### Added

- Three commands driven by one YAML configuration file:
  - `jingra load` creates a collection and ingests a Parquet or NDJSON dataset in parallel.
  - `jingra bench` runs warmup and measured rounds across a grid of query parameters and records
    recall, precision, F1, MRR, latency percentiles (client and server side), and throughput.
  - `jingra report` reads results back and writes a CSV plus recall vs throughput and recall vs
    latency charts, with one Pareto frontier per index build.
- Results are stored through `object_store`, so `results_uri` can be a local folder (such as
  `file://./results`) or an S3, GCS, or Azure Blob location. This lets `bench` run as a
  Kubernetes Job and keep its results after the pod is gone.
- A reproducible example in `benchmarks/glove-100-hnsw`: a sweep of Qdrant HNSW build
  parameters and query settings on the GloVe-100 dataset, run with one command that needs only
  Docker.
- `load.recreate_existing_index: false` to make `load` refuse to delete an existing index.
- `jingra --version`.

### Behavior worth knowing

- `load` deletes and recreates the target collection by default.
- `load` retries only transient errors (timeouts, dropped connections), a bounded number of
  times, and stops after the first batch that fails for good.
- `bench` fails the whole run if any query fails, rather than recording a partial result.
- Running `bench` again with the same `run_id` adds results; `report` uses the latest for each
  parameter set.
- A private certificate authority can be trusted with the `SSL_CERT_FILE` environment variable.

### Known limitations

- Qdrant is the only supported engine.
- Skipping TLS certificate verification (for self-signed certificates) is not supported.
- The Docker image is not published. Build it yourself with `make docker-build`, or build the
  binary with `make build`.
- Test coverage is about 94% of lines. The benchmark scripts and cloud storage backends are not
  covered by automated tests.
