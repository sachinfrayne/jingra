#!/usr/bin/env python3
"""Converts an ann-benchmarks HDF5 dataset (default: glove-100-angular) into the Parquet files
jingra reads: docs.parquet (id, embedding) and queries.parquet (embedding, ground_truth).

ground_truth holds the exact top-K neighbor ids (as strings, matching how jingra compares
retrieved point ids) taken from the dataset's precomputed `neighbors` table.

Requires: numpy, h5py, pyarrow.
"""
import argparse
import os

import h5py
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

ROW_GROUP = 100_000


def list_array(matrix: np.ndarray) -> pa.ListArray:
    rows, dims = matrix.shape
    offsets = pa.array(np.arange(0, (rows + 1) * dims, dims, dtype=np.int32))
    return pa.ListArray.from_arrays(offsets, pa.array(matrix.reshape(-1)))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("hdf5")
    p.add_argument("outdir")
    p.add_argument("--k", type=int, default=10, help="ground-truth neighbors per query")
    args = p.parse_args()
    os.makedirs(args.outdir, exist_ok=True)

    with h5py.File(args.hdf5, "r") as f:
        distance = f.attrs.get("distance", "unknown")
        train, test, neighbors = f["train"], f["test"], f["neighbors"]
        print(f"distance={distance} train={train.shape} test={test.shape} neighbors={neighbors.shape}")

        schema = pa.schema([("id", pa.int64()), ("embedding", pa.list_(pa.float32()))])
        with pq.ParquetWriter(os.path.join(args.outdir, "docs.parquet"), schema) as writer:
            for start in range(0, train.shape[0], ROW_GROUP):
                chunk = np.asarray(train[start : start + ROW_GROUP], dtype=np.float32)
                ids = pa.array(np.arange(start, start + chunk.shape[0], dtype=np.int64))
                writer.write_table(pa.Table.from_arrays([ids, list_array(chunk)], schema=schema))
        print(f"wrote docs.parquet ({train.shape[0]} rows)")

        queries = np.asarray(test, dtype=np.float32)
        truth = np.asarray(neighbors[:, : args.k])
        truth_col = pa.array([[str(i) for i in row] for row in truth], type=pa.list_(pa.string()))
        qschema = pa.schema([("embedding", pa.list_(pa.float32())), ("ground_truth", pa.list_(pa.string()))])
        pq.write_table(
            pa.Table.from_arrays([list_array(queries), truth_col], schema=qschema),
            os.path.join(args.outdir, "queries.parquet"),
        )
        print(f"wrote queries.parquet ({queries.shape[0]} queries, top-{args.k} ground truth)")


if __name__ == "__main__":
    main()
