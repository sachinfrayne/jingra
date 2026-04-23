#!/usr/bin/env python3
"""Generate test text queries Parquet file for lexical search testing."""

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

# Create test text queries
data = {
    "query_text": [
        "wireless bluetooth headphones",
        "laptop computer gaming",
        "running shoes comfortable",
        "coffee maker automatic",
        "desk chair ergonomic",
        "smartphone android latest",
        "winter jacket waterproof",
        "book fiction bestseller",
        "yoga mat thick",
        "camera digital professional",
    ],
    "ground_truth": [
        ["P001", "P002", "P003"],
        ["P004", "P005"],
        ["P006", "P007", "P008"],
        ["P009"],
        ["P010", "P011"],
        ["P012", "P013", "P014", "P015"],
        ["P016"],
        ["P017", "P018"],
        ["P019", "P020", "P021"],
        ["P022"],
    ],
}

# Create DataFrame
df = pd.DataFrame(data)

# Write to Parquet
output_path = "test_text_queries.parquet"
df.to_parquet(output_path, index=False)

print(f"Created {output_path} with {len(df)} text queries")
print("\nSample queries:")
for i in range(min(3, len(df))):
    print(f"  {i+1}. '{df.iloc[i]['query_text']}' -> {df.iloc[i]['ground_truth']}")
