#!/usr/bin/env python3
"""Generates the jingra configs and schema/query templates for an HNSW parameter sweep.

Each build configuration (m, ef_construct) becomes its own Qdrant collection and its own
`dataset` key (named like `m16_efc100`), because HNSW build parameters are fixed at index
creation. The query-time parameter `hnsw_ef` is swept inside each build via `param_groups`.
`jingra report` then draws one Pareto frontier per dataset key on a single chart.

Writes into <workdir>: config-<build>.yaml per build, config-report.yaml, and
jingra-config/{schemas,queries}/*.json. Run jingra with <workdir> as the working directory.
"""
import argparse
import json
import os

DIMS = 100
DEFAULT_EF_SWEEP = [10, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128, 160, 192, 256, 384, 512, 768, 1024]
RUN_ID = "glove100-hnsw-sweep"


def build_name(m: int, efc: int) -> str:
    return f"m{m}_efc{efc}"


def write(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("workdir")
    p.add_argument("--builds", default="16:100", help="comma-separated m:ef_construct pairs")
    p.add_argument("--ef", default=",".join(map(str, DEFAULT_EF_SWEEP)), help="comma-separated hnsw_ef values")
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--warmup-rounds", type=int, default=1)
    p.add_argument("--measurement-rounds", type=int, default=3)
    args = p.parse_args()

    work = os.path.abspath(args.workdir)
    builds = [tuple(int(x) for x in b.split(":")) for b in args.builds.split(",")]
    ef_values = [int(x) for x in args.ef.split(",")]
    results_uri = f"file://{work}/results"

    write(
        f"{work}/jingra-config/queries/glove-query.json",
        json.dumps(
            {
                "template": {
                    "vector": "{{query_vector}}",
                    "limit": "{{size}}",
                    "with_payload": False,
                    "with_vector": False,
                    "params": {"hnsw_ef": "{{hnsw_ef}}"},
                }
            },
            indent=2,
        ),
    )

    param_group = "\n".join(f"        - {{ size: 10, hnsw_ef: {ef} }}" for ef in ef_values)
    for m, efc in builds:
        name = build_name(m, efc)
        write(
            f"{work}/jingra-config/schemas/{name}.json",
            json.dumps(
                {"template": {"vectors": {"size": DIMS, "distance": "Cosine"}, "hnsw_config": {"m": m, "ef_construct": efc}}},
                indent=2,
            ),
        )
        write(
            f"{work}/config-{name}.yaml",
            f"""engine: qdrant

qdrant:
  url_env: QDRANT_URL
  vector_field: embedding
  grpc_timeout_seconds: 120

dataset: {name}

datasets:
  {name}:
    type: vector
    index_name: glove100-{name.replace('_', '-')}
    schema_name: {name}
    query_name: glove-query
    path:
      data_path: {work}/data/docs.parquet
      queries_path: {work}/data/queries.parquet
    data_mapping:
      id_field: id
    queries_mapping:
      query_vector_field: embedding
      ground_truth_field: ground_truth
    param_groups:
      recall@10:
{param_group}

load:
  threads: 4
  batch_size: 1000
  queue_capacity: 16
  await_index_ready: true

evaluation:
  run_id: {RUN_ID}
  warmup_workers: {args.workers}
  measurement_workers: {args.workers}
  warmup_rounds: {args.warmup_rounds}
  measurement_rounds: {args.measurement_rounds}

output:
  results_uri: "{results_uri}"
  per_query_metrics: false
""",
        )

    write(
        f"{work}/config-report.yaml",
        f"""report:
  run_id: {RUN_ID}
  engines: [qdrant]
  results_uri: "{results_uri}"
  output_directory: {work}/report
  generate_plots: true
  latency_metrics: [latency_median, latency_p99]
""",
    )
    print(f"wrote configs for builds {[build_name(m, e) for m, e in builds]} and {len(ef_values)} hnsw_ef values into {work}")


if __name__ == "__main__":
    main()
