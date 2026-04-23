#!/usr/bin/env python3
"""Generate query dataset with ground truth for lexical search demo."""

import pandas as pd
import random
import re

random.seed(42)

def extract_keywords(text, num_words=2):
    """Extract meaningful keywords from text."""
    # Remove special chars and convert to lowercase
    words = re.findall(r'\b[a-z]{4,}\b', text.lower())
    # Remove common stop words
    stop_words = {'with', 'this', 'that', 'from', 'have', 'will', 'your', 'their', 'about', 'which', 'when', 'where'}
    keywords = [w for w in words if w not in stop_words]
    # Return random sample
    return random.sample(keywords, min(num_words, len(keywords))) if keywords else []

def generate_queries(docs_df, num_queries=100, target_size=10):
    """Generate queries with exact ground truth size for precision=1.0.

    Strategy: Use batch_id to query exactly N documents (each batch has exactly N docs).
    This ensures perfect recall=1.0 and precision=1.0.
    """
    queries = []

    # Get unique batch IDs
    batch_ids = docs_df['batch_id'].unique()

    # Sample random batches for queries
    selected_batches = random.sample(list(batch_ids), min(num_queries, len(batch_ids)))

    for batch_id in selected_batches:
        # Get all documents in this batch
        batch_docs = docs_df[docs_df['batch_id'] == batch_id]

        # Verify batch has exactly target_size documents
        if len(batch_docs) != target_size:
            continue

        # Use batch_id as the query (will match exactly these docs)
        query_text = batch_id

        # Ground truth: all document IDs in this batch
        ground_truth = batch_docs['id'].tolist()

        queries.append({
            "query_text": query_text,
            "ground_truth": ground_truth
        })

    return pd.DataFrame(queries)

def main():
    # Load generated documents
    docs_path = "datasets/demo-data.parquet"
    print(f"Loading documents from {docs_path}...")
    docs_df = pd.read_parquet(docs_path)
    print(f"Loaded {len(docs_df)} documents")

    # Generate queries for recall@10 (target_size=10 for precision=1.0)
    print(f"\nGenerating queries for recall@10 (ground truth size=10)...")
    queries_df = generate_queries(docs_df, num_queries=50, target_size=10)

    print(f"✓ Generated {len(queries_df)} queries with 10 ground truth docs each")

    # Save to parquet
    output_path = "datasets/demo-queries.parquet"
    queries_df.to_parquet(output_path, index=False)

    print(f"✓ Created {output_path} with {len(queries_df)} queries")
    print(f"\nSample queries:")
    for i in range(min(10, len(queries_df))):
        q = queries_df.iloc[i]
        print(f"{i+1:2d}. '{q['query_text']}' -> {len(q['ground_truth'])} docs: {q['ground_truth'][:3]}...")

    print(f"\nQuery stats:")
    print(f"  Total queries: {len(queries_df)}")
    print(f"  Avg ground truth size: {queries_df['ground_truth'].apply(len).mean():.1f}")
    print(f"  Min ground truth size: {queries_df['ground_truth'].apply(len).min()}")
    print(f"  Max ground truth size: {queries_df['ground_truth'].apply(len).max()}")

if __name__ == "__main__":
    main()
