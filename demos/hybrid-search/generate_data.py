#!/usr/bin/env python3
"""
Download BeIR/scidocs, embed with all-MiniLM-L6-v2 (384D),
and generate NDJSON for the hybrid-search demo.

Corpus:  25,171 Semantic Scholar paper abstracts (title + abstract)
Queries: ~1,000 paper-title queries with citation-based ground truth

Output:
  data/docs.ndjson    — { "id": int (0-based index), "title", "description", "embedding": [384 floats] }
  data/queries.ndjson — { "query_text", "embedding": [384 floats], "ground_truth": [str(doc_id), ...] }

Note on doc IDs:
  The SciDocs corpus uses string IDs, which are not valid as Qdrant point IDs
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
import re

import numpy as np
from datasets import load_dataset
from sentence_transformers import SentenceTransformer

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
DOCS_PATH = os.path.join(DATA_DIR, "docs.ndjson")
QUERIES_PATH = os.path.join(DATA_DIR, "queries.ndjson")

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
MAX_DESC = 500


def sanitize_text(text: str) -> str:
    """Remove characters that break raw JSON string template substitution."""
    text = text.replace("\\", " ").replace('"', "'")
    return re.sub(r"[\x00-\x1f\x7f]", " ", text).strip()


def normalise(vecs: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    return vecs / np.maximum(norms, 1e-9)


def main():
    os.makedirs(DATA_DIR, exist_ok=True)

    print(f"Loading model {MODEL_NAME}...")
    model = SentenceTransformer(MODEL_NAME)

    # ── Corpus ────────────────────────────────────────────────────────────────
    print("Downloading BeIR/scidocs corpus...")
    corpus = load_dataset("BeIR/scidocs", "corpus", split="corpus")

    doc_ids, doc_titles, doc_descs, doc_embed_texts = [], [], [], []
    for row in corpus:
        title = sanitize_text(row["title"] or "")
        desc = sanitize_text((row["text"] or "")[:MAX_DESC])
        doc_ids.append(row["_id"])
        doc_titles.append(title)
        doc_descs.append(desc)
        doc_embed_texts.append(f"{title} {desc}".strip())

    print(f"Embedding {len(doc_ids):,} documents...")
    doc_embeddings = model.encode(
        doc_embed_texts, batch_size=64, show_progress_bar=True, convert_to_numpy=True
    )
    doc_embeddings = normalise(doc_embeddings)

    id_map = {orig_id: i for i, orig_id in enumerate(doc_ids)}

    print(f"Writing {len(doc_ids):,} docs to {DOCS_PATH}...")
    with open(DOCS_PATH, "w") as f:
        for doc_id, title, desc, emb in zip(doc_ids, doc_titles, doc_descs, doc_embeddings):
            record = {
                "id": id_map[doc_id],
                "title": title,
                "description": desc,
                "embedding": [round(float(x), 4) for x in emb],
            }
            f.write(json.dumps(record) + "\n")

    # ── Queries + qrels ───────────────────────────────────────────────────────
    print("Downloading BeIR/scidocs queries...")
    queries_ds = load_dataset("BeIR/scidocs", "queries", split="queries")
    query_map = {row["_id"]: row["text"] for row in queries_ds}

    print("Downloading BeIR/scidocs-qrels...")
    qrels_ds = load_dataset("BeIR/scidocs-qrels", split="test")

    ground_truth: dict[str, list[str]] = {}
    for row in qrels_ds:
        if row["score"] > 0:
            int_id = id_map.get(str(row["corpus-id"]))
            if int_id is not None:
                ground_truth.setdefault(str(row["query-id"]), []).append(str(int_id))

    test_queries = [
        (qid, sanitize_text(query_map[qid]))
        for qid in sorted(ground_truth)
        if qid in query_map
    ]

    query_texts = [text for _, text in test_queries]
    print(f"Embedding {len(query_texts):,} queries...")
    query_embeddings = model.encode(
        query_texts, batch_size=64, show_progress_bar=True, convert_to_numpy=True
    )
    query_embeddings = normalise(query_embeddings)

    print(f"Writing {len(test_queries):,} queries to {QUERIES_PATH}...")
    with open(QUERIES_PATH, "w") as f:
        for i, (qid, text) in enumerate(test_queries):
            record = {
                "query_text": text,
                "embedding": [round(float(x), 4) for x in query_embeddings[i]],
                "ground_truth": ground_truth[qid],
            }
            f.write(json.dumps(record) + "\n")

    print("\nDone.")
    print(f"  docs:    {len(doc_ids):,}")
    print(f"  queries: {len(test_queries):,}")
    print(f"  dims:    {doc_embeddings.shape[1]}")
    gt_sizes = [len(ground_truth[qid]) for qid, _ in test_queries]
    print(f"  avg relevant docs/query: {sum(gt_sizes) / len(gt_sizes):.1f}")


if __name__ == "__main__":
    main()
