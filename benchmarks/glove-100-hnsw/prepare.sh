#!/usr/bin/env bash
# Runs inside the prepare container. Idempotent: reuses an existing download and Parquet files.
set -euo pipefail

WORK="${WORK:-/work}"
URL="${DATASET_URL:-http://ann-benchmarks.com/glove-100-angular.hdf5}"
HDF5="$WORK/glove-100-angular.hdf5"

mkdir -p "$WORK/data"
if [ ! -f "$WORK/data/docs.parquet" ]; then
  [ -f "$HDF5" ] || python /bench/download.py "$URL" "$HDF5"
  python /bench/prepare_data.py "$HDF5" "$WORK/data"
else
  echo "reusing existing $WORK/data/docs.parquet"
fi

args=("$WORK" --builds "${BUILDS:-8:64,16:100,32:200,48:400}")
[ -n "${EF:-}" ] && args+=(--ef "$EF")
[ -n "${WORKERS:-}" ] && args+=(--workers "$WORKERS")
[ -n "${WARMUP_ROUNDS:-}" ] && args+=(--warmup-rounds "$WARMUP_ROUNDS")
[ -n "${MEASUREMENT_ROUNDS:-}" ] && args+=(--measurement-rounds "$MEASUREMENT_ROUNDS")
python /bench/generate_configs.py "${args[@]}"
