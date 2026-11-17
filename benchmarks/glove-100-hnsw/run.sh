#!/usr/bin/env bash
# Runs the GloVe-100 HNSW benchmark entirely in Docker: nothing but Docker is needed on the host.
#
#   benchmarks/glove-100-hnsw/run.sh            full sweep (4 HNSW builds x 19 hnsw_ef values)
#   benchmarks/glove-100-hnsw/run.sh quick      one build, 8 hnsw_ef values, 1 measured round
#
# Results land in benchmarks/glove-100-hnsw/work/report (SVG plots and comparison.csv).
# Overrides (environment): BUILDS="8:64,16:100"  EF="10,32,128"  WORKERS=4
#                          WARMUP_ROUNDS=1  MEASUREMENT_ROUNDS=3
# Set COMPOSE="docker-compose" if you only have the standalone Compose v1/v2 binary.
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE="${COMPOSE:-docker compose}"

if ! command -v "${COMPOSE%% *}" >/dev/null 2>&1 || ! $COMPOSE version >/dev/null 2>&1; then
  echo "error: '$COMPOSE' not found. Install Docker with Compose (https://docs.docker.com/compose/install/)." >&2
  exit 1
fi

if [ "${1:-}" = "quick" ]; then
  export BUILDS="${BUILDS:-16:100}"
  export EF="${EF:-10,16,32,64,128,256,512,1024}"
  export MEASUREMENT_ROUNDS="${MEASUREMENT_ROUNDS:-1}"
elif [ -n "${1:-}" ]; then
  echo "usage: run.sh [quick]" >&2
  exit 2
fi

export QDRANT_VERSION="$(tr -d '[:space:]' < ../../engine-versions/.qdrant)"
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
mkdir -p work

cleanup() { $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

$COMPOSE run --rm --build bench
echo
echo "Results: $PWD/work/report"
