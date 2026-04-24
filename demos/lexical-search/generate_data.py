#!/usr/bin/env python3
"""
Download BeIR/nfcorpus from HuggingFace and convert to NDJSON for the lexical-search demo.

Corpus:  3,633 documents (biomedical/nutritional abstracts)
Queries: 323 test queries with qrels (relevance judgments)

Output:
  data/docs.ndjson    — { "id", "title", "description" }
  data/queries.ndjson — { "query_text", "ground_truth": [doc_id, ...] }

Usage:
  pip install datasets
  python3 generate_data.py
"""

import json
import os
from datasets import load_dataset


DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DOCS_PATH = os.path.join(DATA_DIR, "docs.ndjson")
QUERIES_PATH = os.path.join(DATA_DIR, "queries.ndjson")

# Minimum relevance score to include a doc in ground truth.
# NFCorpus uses: 2 = directly relevant, 1 = partially relevant
MIN_RELEVANCE = 2


def main():
    os.makedirs(DATA_DIR, exist_ok=True)

    print("Downloading BeIR/nfcorpus corpus...")
    corpus = load_dataset("BeIR/nfcorpus", "corpus", split="corpus")

    print(f"Writing {len(corpus):,} docs to {DOCS_PATH}...")
    with open(DOCS_PATH, "w") as f:
        for row in corpus:
            doc = {
                "id": row["_id"],
                "title": row["title"],
                "description": row["text"],
            }
            f.write(json.dumps(doc) + "\n")

    print("Downloading BeIR/nfcorpus queries (test split)...")
    queries = load_dataset("BeIR/nfcorpus", "queries", split="queries")
    query_map = {row["_id"]: row["text"] for row in queries}

    print("Downloading BeIR/nfcorpus-qrels (test split)...")
    qrels = load_dataset("BeIR/nfcorpus-qrels", split="test")

    # Build query_id -> [relevant doc_ids] mapping
    ground_truth: dict[str, list[str]] = {}
    for row in qrels:
        if row["score"] >= MIN_RELEVANCE:
            ground_truth.setdefault(row["query-id"], []).append(row["corpus-id"])

    # Only keep queries that have at least one relevant doc
    test_queries = [
        (qid, query_map[qid])
        for qid in ground_truth
        if qid in query_map
    ]
    test_queries.sort(key=lambda x: x[0])

    print(f"Writing {len(test_queries):,} queries to {QUERIES_PATH}...")
    with open(QUERIES_PATH, "w") as f:
        for qid, text in test_queries:
            record = {
                "query_text": text,
                "ground_truth": ground_truth[qid],
            }
            f.write(json.dumps(record) + "\n")

    print(f"\nDone.")
    print(f"  docs:    {len(corpus):,}")
    print(f"  queries: {len(test_queries):,}")
    avg_gt = sum(len(v) for v in ground_truth.values()) / len(ground_truth)
    print(f"  avg relevant docs per query: {avg_gt:.1f}")


if __name__ == "__main__":
    main()
