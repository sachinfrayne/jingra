#!/usr/bin/env python3
"""
Download BeIR/nfcorpus from HuggingFace and convert to NDJSON for the lexical-search demo.

Corpus:  3,633 documents (biomedical/nutritional abstracts)
Queries: 323 test queries with qrels (relevance judgments)

Output:
  data/docs.ndjson    — { "id": int (0-based index), "title", "description" }
  data/queries.ndjson — { "query_text", "ground_truth": [str(doc_id), ...] }

Note on doc IDs:
  The NFCorpus uses string IDs like "MED-4391", which are not valid as Qdrant point IDs
  (Qdrant only accepts numeric uint64 or standard UUID format). To keep the benchmark
  correct across all engines, doc IDs are remapped to sequential integers (0, 1, 2, ...).
  Ground truth values are written as strings (e.g. "42") because the benchmark evaluator
  reads them as List<String>.

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

MIN_RELEVANCE = 1


def main():
    os.makedirs(DATA_DIR, exist_ok=True)

    print("Downloading BeIR/nfcorpus corpus...")
    corpus = load_dataset("BeIR/nfcorpus", "corpus", split="corpus")
    corpus_list = list(corpus)

    id_map = {row["_id"]: i for i, row in enumerate(corpus_list)}

    print(f"Writing {len(corpus_list):,} docs to {DOCS_PATH}...")
    with open(DOCS_PATH, "w") as f:
        for row in corpus_list:
            doc = {
                "id": id_map[row["_id"]],
                "title": row["title"],
                "description": row["text"],
            }
            f.write(json.dumps(doc) + "\n")

    print("Downloading BeIR/nfcorpus queries (test split)...")
    queries = load_dataset("BeIR/nfcorpus", "queries", split="queries")
    query_map = {row["_id"]: row["text"] for row in queries}

    print("Downloading BeIR/nfcorpus-qrels (test split)...")
    qrels = load_dataset("BeIR/nfcorpus-qrels", split="test")

    # Build query_id -> [relevant doc integer ids] mapping
    ground_truth: dict[str, list[str]] = {}
    for row in qrels:
        if row["score"] >= MIN_RELEVANCE:
            int_id = id_map.get(row["corpus-id"])
            if int_id is not None:
                ground_truth.setdefault(row["query-id"], []).append(str(int_id))

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
    print(f"  docs:    {len(corpus_list):,}")
    print(f"  queries: {len(test_queries):,}")
    avg_gt = sum(len(v) for v in ground_truth.values()) / len(ground_truth)
    print(f"  avg relevant docs per query: {avg_gt:.1f}")


if __name__ == "__main__":
    main()
