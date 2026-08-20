# Jingra Demos

End-to-end benchmarks that download a real public dataset, index it into Qdrant, run the
benchmark sweep, and generate recall/latency CSV + plots.

## Prerequisites

- Docker and Docker Compose
- Rust (`rust-toolchain.toml` at the repo root pins the version; `cargo build --release` compiles the binary the demo containers run)
- Internet access (datasets download from HuggingFace on first run)

## Structure

```
demos/
  vector-search/qdrant/       # ANN recall benchmark
  await-index-ready/qdrant/   # demos the awaitIndexReady optimizer-polling behavior
  common/                     # Shared infrastructure
    docker-compose.yml              # base: jingra service + network
    docker-compose.qdrant.yml       # Qdrant engine-under-test container
    Dockerfile                      # packages the pre-built jingra binary
    Dockerfile.qdrant                # Qdrant image + curl (for healthchecks)
    demo.mk                         # shared make targets
    generate.mk                     # shared data generation target
```

Elasticsearch/OpenSearch demos will return once those engines are added back to the Rust
rewrite (see the repo root `README.md`); the demo infrastructure here (`demo.mk`,
`docker-compose.yml`) is written to add an engine the same way it always was -- a new
`docker-compose.<engine>.yml` override plus an engine-specific `config.yaml`.

## Running a demo

From the repo root or `demos/` directory:

```bash
make vector-search/qdrant
make all                           # run every demo sequentially
```

From the engine directory — full lifecycle:

```bash
cd demos/vector-search/qdrant
make run
```

Individual steps (from the engine directory):

```bash
make build     # cargo build --release, then build the jingra image
make start     # start the Qdrant container
make load      # index the dataset
make bench     # run the benchmark sweep
make report    # generate recall/latency CSV + plots -> ./output/
make stop      # tear down the container
make clean     # stop + delete ./output/ and ./results/
```

## Infrastructure

| Service  | External port | Purpose                  |
|----------|----------------|---------------------------|
| `qdrant` | 6333 / 6334    | Engine under test          |

`QDRANT_URL` is set by `docker-compose.qdrant.yml`. Results write to a local `file://` mount
(`./results`, via `output.results_uri` / `report.results_uri` in `config.yaml`) rather than a
separate results-storage cluster -- there's no sink service to stand up or wait for.

The Qdrant version is pinned once, in `engine-versions/.qdrant`; the demo compose file and the
Rust integration test (`tests/qdrant_engine.rs`) both read it from that single file, so they
can't drift out of sync.

---

## Adding an engine to an existing demo type

Example: adding a new engine to `vector-search` once it exists in the Rust codebase.

**1. Create the directory:**

```
demos/vector-search/<engine>/
  Makefile
  config.yaml
  config/schemas/demo-vector-schema.json
  config/queries/demo-vector-query.json
```

**2. Makefile** — every engine Makefile follows the same pattern:

```makefile
DEMO_NAME := <Engine> Vector Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export <ENGINE>_VERSION := $(shell cat ../../../engine-versions/.<engine> | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.<engine>.yml
ENGINE_SERVICE := <engine>
include ../../common/demo.mk
```

**3. config.yaml** — point the engine block's `url_env` at the engine URL, and set
`output.results_uri` / `report.results_uri` to a local mount (`file:///app/results` works the
same way regardless of engine):

```yaml
engine: <engine>

<engine>:
  url_env: <ENGINE>_URL

# ... datasets, param_groups ...

output:
  results_uri: "file:///app/results"
  per_query_metrics: true

report:
  engines: [<engine>]
  results_uri: "file:///app/results"
```

**4. Register the engine in the demo-type Makefile** (`demos/vector-search/Makefile`).

---

## Adding a new demo type

Example: `hybrid-search`.

**1. Directory structure:**

```
demos/hybrid-search/
  Makefile
  generate_data.py
  qdrant/
    Makefile
    config.yaml
    config/schemas/
    config/queries/
```

**2. Top-level Makefile** (`demos/hybrid-search/Makefile`):

```makefile
include ../common/generate.mk

.PHONY: qdrant

qdrant: generate_data
    $(MAKE) -C $@ run

%:
    @:
```

**3. generate_data.py**

Runs inside Docker (`common/Dockerfile.generate`). Must write two NDJSON files to `./data/`:

- `docs.ndjson` — one document per line
- `queries.ndjson` — one query per line, with a `ground_truth` field

**Doc ID rule:** always convert source string IDs to sequential integers with an `id_map` --
Qdrant point IDs are numeric-or-UUID, and sequential integers keep things simple:

```python
corpus_list = list(corpus)
id_map = {row["_id"]: i for i, row in enumerate(corpus_list)}

# writing a doc
{"id": id_map[row["_id"]], "title": ..., "description": ...}

# ground_truth in queries must use the same integer IDs
ground_truth[qid].append(str(id_map[row["corpus-id"]]))
```

Data is generated once — if `./data/` is non-empty the step is skipped automatically.

**4. Schema and query templates**

Filenames must match `schema_name`/`query_name` in `config.yaml`. For Qdrant, schemas are a
direct passthrough to its REST collection-creation API:
`{"template": {"vectors": {"size": N, "distance": "Cosine"}}}`.
