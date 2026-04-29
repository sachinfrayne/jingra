# Jingra Demos

End-to-end benchmarks that download real public datasets, index them into an engine,
run the evaluation, and write results to a separate sink cluster.

## Prerequisites

- Docker and Docker Compose
- Internet access (datasets download from HuggingFace on first run)

## Structure

```
demos/
  lexical-search/          # BM25 recall benchmarks
    elasticsearch/
    opensearch/
  vector-search/           # ANN recall benchmarks
    elasticsearch/
    opensearch/
    qdrant/
  hybrid-search/           # RRF (BM25 + knn) recall benchmarks
    elasticsearch/
  parquet-file-format/     # Parquet ingestion smoke test
    elasticsearch/
  common/                  # Shared infrastructure
    docker-compose.yml              # base: jingra service + network only
    docker-compose.sink.yml         # results sink cluster
    docker-compose.elasticsearch.yml
    docker-compose.opensearch.yml
    docker-compose.qdrant.yml
    demo.mk                         # shared make targets
    generate.mk                     # shared data generation target
```

## Running a demo

From the repo root or `demos/` directory:

```bash
make lexical-search/elasticsearch
make vector-search/qdrant
make all                           # run every demo sequentially
```

From the demo-type directory — generates data if absent, then runs:

```bash
cd demos/lexical-search
make elasticsearch
make opensearch
```

From the engine directory — full lifecycle:

```bash
cd demos/lexical-search/elasticsearch
make run
```

Individual steps (from the engine directory):

```bash
make build     # compile Jingra
make start     # start the sink + engine containers
make load      # index the dataset
make eval      # run the benchmark
make analyze   # generate recall curves and latency plots → ./output/
make stop      # tear down all containers
make clean     # stop + delete ./output/
```

## Infrastructure

Every demo runs two independent clusters:

| Service | External port | Purpose |
|---|---|---|
| `elasticsearch-sink` | 9200 | Results store (configured in `docker-compose.sink.yml`) |
| `elasticsearch` | 9201 | Engine under test (ES demos) |
| `opensearch` | 9201 | Engine under test (OS demos) |
| `qdrant` | 6333 / 6334 | Engine under test (Qdrant demos) |

`SINK_URL` always points to the results sink. Engine URLs (`ELASTICSEARCH_URL`,
`OPENSEARCH_URL`, `QDRANT_URL`) are set by the engine's own compose override.

Engine and sink versions are pinned in `engine-versions/`:

```
engine-versions/
  .elasticsearch
  .opensearch
  .qdrant
```

---

## Adding an engine to an existing demo type

Example: adding OpenSearch to `hybrid-search`.

**1. Create the directory:**

```
demos/hybrid-search/opensearch/
  Makefile
  config.yaml
  config/schemas/demo-hybrid-schema.json
  config/queries/demo-hybrid-query.json
```

**2. Makefile** — every engine Makefile follows the same pattern:

```makefile
DEMO_NAME := OpenSearch Hybrid Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export OS_VERSION := $(shell cat ../../../engine-versions/.opensearch | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.opensearch.yml
ENGINE_SERVICE := opensearch
include ../../common/demo.mk
```

The version variable name must match what the engine's compose file uses
(`ES_VERSION`, `OS_VERSION`, `QD_VERSION`).

**3. config.yaml** — point `url_env` at the engine URL, use `SINK_URL` for the sink:

```yaml
engine: opensearch

opensearch:
  url_env: OPENSEARCH_URL

# ... datasets, param_groups ...

output:
  sinks:
    - type: elasticsearch
      config:
        url_env: SINK_URL
        index: jingra-results

analysis:
  results_cluster:
    url_env: SINK_URL
    index: jingra-results
```

**4. Register the engine in the demo-type Makefile** (`demos/hybrid-search/Makefile`):

```makefile
.PHONY: elasticsearch opensearch

elasticsearch opensearch: generate_data
    $(MAKE) -C $@ run
```

---

## Adding a new demo type

Example: `sparse-search`.

**1. Directory structure:**

```
demos/sparse-search/
  Makefile
  generate_data.py
  elasticsearch/
    Makefile
    config.yaml
    config/schemas/
    config/queries/
```

**2. Top-level Makefile** (`demos/sparse-search/Makefile`):

```makefile
include ../common/generate.mk

.PHONY: elasticsearch

elasticsearch: generate_data
    $(MAKE) -C $@ run

%:
    @:
```

**3. generate_data.py**

Runs inside Docker (`common/Dockerfile.generate`). Must write two NDJSON files to `./data/`:

- `docs.ndjson` — one document per line
- `queries.ndjson` — one query per line, with a `ground_truth` field

**Doc ID rule:** always convert source string IDs to sequential integers with an `id_map`.
This is required for Qdrant and keeps all engines consistent:

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

Each engine has its own templates under `config/`. The filenames must match
`schema_name` and `query_name` in `config.yaml`.

For Elasticsearch/OpenSearch: standard index mapping and query DSL.
For Qdrant: `{"template": {"vectors": {"size": N, "distance": "Cosine"}}}`.

---

## Changing the results sink

The sink is driven by a single variable in `demos/common/demo.mk`:

```makefile
SINK_ENGINE ?= elasticsearch
```

This controls:
- **Version** — reads `engine-versions/.<SINK_ENGINE>` automatically
- **Type** — passes `SINK_TYPE=<SINK_ENGINE>` to the jingra container

To swap the sink, set `SINK_ENGINE` and replace `docker-compose.sink.yml`.
All `config.yaml` files reference the sink only via `SINK_URL` and `SINK_TYPE`,
so no other files need to change.
