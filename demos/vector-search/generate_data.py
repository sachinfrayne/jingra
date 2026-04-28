#!/usr/bin/env python3
"""
Download Wix/WixQA from HuggingFace, embed with all-MiniLM-L6-v2 (384D),
and generate NDJSON for the vector-search demo.

Corpus:  6,221 Wix Help Center articles
Queries: 400 real queries (200 expert-written + 200 simulated from support logs)
Ground truth = exact brute-force cosine top-k (correct for ANN recall benchmarks).

Output:
  data/docs.ndjson    — { "id": int (0-based index), "embedding": [384 floats] }
  data/queries.ndjson — { "embedding": [384 floats], "ground_truth": [str(doc_id), ...] }

Note on doc IDs:
  The Wix corpus uses 64-char hex strings as IDs, which are not valid as Qdrant point IDs
  (Qdrant only accepts numeric uint64 or standard UUID format). To keep the benchmark
  correct across all engines, doc IDs are remapped to sequential integers (0, 1, 2, ...).
  Ground truth values are written as strings (e.g. "42") because the benchmark evaluator
  reads them as List<String>.

Usage:
  pip install sentence-transformers datasets
  python3 generate_data.py
"""

import json
import os

import numpy as np
from datasets import load_dataset
from sentence_transformers import SentenceTransformer

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DOCS_PATH = os.path.join(DATA_DIR, "docs.ndjson")
QUERIES_PATH = os.path.join(DATA_DIR, "queries.ndjson")

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
TOP_K = 10


def normalise(vecs: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    return vecs / np.maximum(norms, 1e-9)


def main():
    os.makedirs(DATA_DIR, exist_ok=True)

    print(f"Loading model {MODEL_NAME}...")
    model = SentenceTransformer(MODEL_NAME)

    print("Downloading Wix/WixQA corpus...")
    corpus = load_dataset("Wix/WixQA", "wix_kb_corpus", split="train")
    corpus_list = list(corpus)
    hex_to_int = {row["id"]: i for i, row in enumerate(corpus_list)}
    doc_ids = list(range(len(corpus_list)))
    doc_texts = [f"{row['title']} {row['contents']}".strip() for row in corpus_list]

    print(f"Embedding {len(doc_ids):,} documents...")
    doc_embeddings = model.encode(doc_texts, batch_size=64, show_progress_bar=True, convert_to_numpy=True)
    doc_embeddings = normalise(doc_embeddings)

    print(f"Writing {len(doc_ids):,} docs to {DOCS_PATH}...")
    with open(DOCS_PATH, "w") as f:
        for doc_id, emb in zip(doc_ids, doc_embeddings):
            record = {
                "id": doc_id,
                "embedding": [round(float(x), 4) for x in emb],
            }
            f.write(json.dumps(record) + "\n")

    print("Downloading Wix/WixQA queries (expertwritten + simulated)...")
    expert = load_dataset("Wix/WixQA", "wixqa_expertwritten", split="train")
    simulated = load_dataset("Wix/WixQA", "wixqa_simulated", split="train")

    query_texts = [row["question"] for row in expert] + [row["question"] for row in simulated]

    print(f"Embedding {len(query_texts):,} queries...")
    query_embeddings = model.encode(query_texts, batch_size=64, show_progress_bar=True, convert_to_numpy=True)
    query_embeddings = normalise(query_embeddings)

    # Brute-force cosine top-k (vectors are already normalised, so dot product = cosine)
    print("Computing brute-force ground truth (cosine top-k)...")
    sims = query_embeddings @ doc_embeddings.T  # (n_queries, n_docs)
    top_k_indices = np.argsort(-sims, axis=1)[:, :TOP_K]

    print(f"Writing {len(query_texts):,} queries to {QUERIES_PATH}...")
    with open(QUERIES_PATH, "w") as f:
        for i in range(len(query_texts)):
            record = {
                "embedding": [round(float(x), 4) for x in query_embeddings[i]],
                "ground_truth": [str(doc_ids[j]) for j in top_k_indices[i]],
            }
            f.write(json.dumps(record) + "\n")

    print("\nDone.")
    print(f"  docs:    {len(doc_ids):,}")
    print(f"  queries: {len(query_texts):,}")
    print(f"  dims:    {doc_embeddings.shape[1]}")
    print(f"  top_k:   {TOP_K}")


if __name__ == "__main__":
    main()
