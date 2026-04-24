#!/usr/bin/env python3
"""
Download BeIR/nfcorpus from HuggingFace and write as Parquet for the parquet-file-format demo.

Same corpus and queries as the lexical-search demo — this demo exists solely to show that
the framework reads Parquet files identically to NDJSON.

Corpus:  3,633 documents (biomedical/nutritional abstracts)
Queries: 119 test queries with relevance judgments (MIN_RELEVANCE=2, directly relevant only)

Output:
  data/docs.parquet    — columns: id (string), title (string), description (string)
  data/queries.parquet — columns: query_text (string), ground_truth (list<string>)

Usage:
  pip install datasets pandas pyarrow
  python3 generate_data.py
"""

import os

import pandas as pd
from datasets import load_dataset

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DOCS_PATH = os.path.join(DATA_DIR, "docs.parquet")
QUERIES_PATH = os.path.join(DATA_DIR, "queries.parquet")

MIN_RELEVANCE = 1  # NFCorpus: 2 = directly relevant, 1 = partially relevant


def main():
    os.makedirs(DATA_DIR, exist_ok=True)

    print("Downloading BeIR/nfcorpus corpus...")
    corpus = load_dataset("BeIR/nfcorpus", "corpus", split="corpus")

    docs_df = pd.DataFrame({
        "id": [row["_id"] for row in corpus],
        "title": [row["title"] for row in corpus],
        "description": [row["text"] for row in corpus],
    })

    print(f"Writing {len(docs_df):,} docs to {DOCS_PATH}...")
    docs_df.to_parquet(DOCS_PATH, index=False)

    print("Downloading BeIR/nfcorpus queries and qrels (test split)...")
    queries_ds = load_dataset("BeIR/nfcorpus", "queries", split="queries")
    qrels = load_dataset("BeIR/nfcorpus-qrels", split="test")

    query_map = {row["_id"]: row["text"] for row in queries_ds}

    ground_truth: dict[str, list[str]] = {}
    for row in qrels:
        if row["score"] >= MIN_RELEVANCE:
            ground_truth.setdefault(row["query-id"], []).append(row["corpus-id"])

    test_queries = sorted(
        [(qid, query_map[qid]) for qid in ground_truth if qid in query_map],
        key=lambda x: x[0],
    )

    queries_df = pd.DataFrame({
        "query_text": [text for _, text in test_queries],
        "ground_truth": [ground_truth[qid] for qid, _ in test_queries],
    })

    print(f"Writing {len(queries_df):,} queries to {QUERIES_PATH}...")
    queries_df.to_parquet(QUERIES_PATH, index=False)

    print("\nDone.")
    print(f"  docs:    {len(docs_df):,}")
    print(f"  queries: {len(queries_df):,}")
    avg_gt = queries_df["ground_truth"].apply(len).mean()
    print(f"  avg relevant docs per query: {avg_gt:.1f}")


if __name__ == "__main__":
    main()
