# Elasticsearch Lexical Search Demo

Demonstrates Jingra's lexical (text-based) search benchmarking with Elasticsearch.

## Quick Start

```bash
make generate start load eval
```

**Requirements**: Docker and Docker Compose only (no Python or Java needed locally!)

## What This Demo Does

1. Generates 1,000 synthetic product documents
2. Generates 100 text queries with ground truth
3. Loads data into Elasticsearch
4. Benchmarks lexical search performance using multi-match queries
5. Reports precision, recall, latency, and throughput metrics

## Full Documentation

See [DEMO.md](DEMO.md) for complete step-by-step instructions, configuration details, and troubleshooting.

## Clean Up

```bash
make clean
```

This removes all containers and volumes.
