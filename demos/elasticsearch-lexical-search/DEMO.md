# Elasticsearch Lexical Search Demo

This demo showcases Jingra's ability to benchmark **lexical (text-based) search** using Elasticsearch's match queries. Unlike vector search which uses embeddings, lexical search relies on traditional full-text search with BM25 scoring.

**🐳 Fully Dockerized**: Everything runs in containers - no local Python or Java installation needed!

## Overview

- **Engine**: Elasticsearch 9.3.2 (running in Docker)
- **Dataset**: 1,000 synthetic product documents (electronics, books, clothing, sports, home)
- **Queries**: 100 text queries derived from product titles/descriptions
- **Search Type**: Multi-match query on `title` (boosted 2x) and `description` fields
- **Metrics**: Precision, recall, latency, throughput

## Prerequisites

- Docker and Docker Compose
- Jingra JAR built from the main project: `cd ../.. && mvn clean package`

**Note**: Everything runs in Docker! No local Python or Java installation required.

## Quick Start

### Option 1: Using Makefile (Recommended)

```bash
make generate start load eval
make clean
```

### Option 2: Using docker-compose directly

```bash
# 1. Generate synthetic dataset and queries
docker-compose run --rm datagen

# 2. Start Elasticsearch
docker-compose up -d elasticsearch

# 3. Load data into Elasticsearch
docker-compose run --rm jingra load config.yaml

# 4. Run benchmark evaluation
docker-compose run --rm jingra eval config.yaml

# 5. Cleanup
docker-compose down -v
```

**That's it!** No Python or Java installation required - everything runs in Docker.

## Step-by-Step Walkthrough

### Step 1: Generate Synthetic Dataset (in Docker)

```bash
docker-compose run --rm datagen
```

This command:
1. Builds a Python container with pandas and pyarrow
2. Runs `generate-docs.py` to create 1,000 product documents
3. Runs `generate-queries.py` to create 100 queries with ground truth
4. Saves files to `datasets/` directory (mounted from host)

**Output**: `datasets/demo-data.parquet` with 1,000 product documents across 5 categories:

- **Electronics**: Headphones, laptops, cameras, etc.
- **Books**: Novels, guides, textbooks, etc.
- **Clothing**: Shirts, pants, jackets, etc.
- **Sports**: Equipment, training gear, etc.
- **Home**: Furniture, decor, etc.

Each document has:
- `id`: Unique product ID (P00001, P00002, ...)
- `title`: Product name (e.g., "Wireless Bluetooth Headphones")
- `description`: Detailed description with attributes
- `category`: Product category (electronics, books, etc.)
- `tags`: Array of keywords

**Example document**:
```json
{
  "id": "P00042",
  "title": "Professional Headphones",
  "description": "Professional Headphones - noise-cancelling, HD, waterproof. High-quality audio equipment.",
  "category": "electronics",
  "tags": ["noise-cancelling", "HD", "waterproof", "electronics", "headphones"]
}
```

**Output**: `datasets/demo-queries.parquet` with 100 queries.

**Why Docker?** No need to install Python locally or manage pip dependencies. The container has everything pre-configured.

**Query generation methodology**:
1. Randomly select 1-3 documents from the dataset
2. Extract 2-4 meaningful keywords from their titles and descriptions
3. Combine keywords to form a natural query
4. Ground truth = IDs of the source documents

**Example queries**:
```
"bluetooth headphones wireless"     -> ["P00001", "P00023"]
"laptop gaming professional"        -> ["P00156"]
"comfortable cotton shirt stylish"  -> ["P00234", "P00289", "P00301"]
```

This approach ensures:
- Queries are realistic (derived from actual document content)
- Ground truth is well-defined (documents query was extracted from)
- Recall can be measured (are the source docs returned?)

### Step 2: Start Elasticsearch

```bash
docker-compose up -d elasticsearch
```

This starts:
- Elasticsearch 9.3.2 on port 9200
- Single-node cluster (no security, for demo simplicity)
- 512MB heap (adjust if needed)
- Healthcheck that waits for cluster to be ready

The `jingra` service depends on Elasticsearch being healthy, so it will wait automatically.

### Step 3: Load Data (in Docker)

```bash
docker-compose run --rm jingra load config.yaml
```

This command:
1. Runs Jingra in a Docker container with Java 21
2. Connects to Elasticsearch at `http://elasticsearch:9200` (Docker network)
3. Creates index `demo-products` with the schema from `jingra-config/schemas/demo-lexical-schema.json`
4. Ingests all 1,000 documents in parallel batches
5. Verifies final document count

**Expected output**:
```
INFO  - Loading data from: datasets/demo-data.parquet
INFO  - Parquet file contains 1000 documents
INFO  - Starting parallel ingestion with 4 threads...
INFO  - Progress: 1000/1000 docs (100.0%)
INFO  - Data loading complete!
INFO  -   Total ingested: 1000 documents
INFO  -   Average rate: 2500 docs/sec
INFO  -   Final count: 1000 documents
```

### Step 4: Run Benchmark Evaluation (in Docker)

```bash
docker-compose run --rm jingra eval config.yaml
```

This command:
1. Runs Jingra in a Docker container
2. Loads 100 queries from `datasets/demo-queries.parquet`
3. Runs warmup round (1 round with 2 workers)
4. Runs measurement round (1 round with 4 workers)
5. Executes all queries using the `demo-lexical-query` template
6. Calculates metrics: precision, recall, F1, MRR, latency, throughput

**Expected output**:
```
INFO  - Starting benchmark evaluation
INFO  -   Engine: elasticsearch
INFO  -   Dataset: demo-lexical
INFO  - Loaded 100 queries
INFO  - Evaluating parameter group: recall@10
INFO  - Running 1 warmup rounds with 2 workers...
INFO  - Running 1 measurement rounds with 4 workers...
INFO  - Completed 100 queries in 2.5s (40 qps)
INFO  - Calculating metrics from 100 query results...

Benchmark Results
================================================================================
Engine:      elasticsearch 9.3.2
Dataset:     demo-lexical
Parameters:  size=10

Quality Metrics:
  Precision:   0.85
  Recall:      0.92
  F1 Score:    0.88
  MRR:         0.91

Latency Metrics (ms):
  Average:     12.5
  Median:      11.2
  P90:         18.3
  P95:         22.1
  P99:         31.4

Throughput:
  QPS:         40.0

Index:
  Documents:   1000
================================================================================
```

### Step 5: Analyze Results (Optional)

If you have Elasticsearch configured as a results sink, you can run:

```bash
docker-compose run --rm jingra analyze config.yaml
```

This queries the results index and generates comparison charts.

### Step 6: Cleanup

```bash
docker-compose down -v
```

This stops and removes all containers (Elasticsearch, datagen, and jingra) and volumes.

## Understanding the Configuration

### Jingra Config (`config.yaml`)

```yaml
datasets:
  demo-lexical:
    queries_mapping:
      query_text_field: query_text  # NEW: For lexical queries
      ground_truth_field: ground_truth
```

The key difference from vector search config:
- **Vector search**: `query_vector_field` specifies the field containing embedding vectors
- **Lexical search**: `query_text_field` specifies the field containing query text

### Elasticsearch Schema (`demo-lexical-schema.json`)

```json
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "standard"
      },
      "description": {
        "type": "text",
        "analyzer": "standard"
      }
    }
  }
}
```

- Uses `text` type for full-text search
- `standard` analyzer (tokenization, lowercasing, stop words)
- No vector fields (unlike vector search schemas)

### Query Template (`demo-lexical-query.json`)

```json
{
  "query": {
    "multi_match": {
      "query": "{{query_text}}",
      "fields": ["title^2", "description"],
      "type": "best_fields"
    }
  },
  "size": {{size}}
}
```

- `multi_match` searches across multiple fields
- `title^2` boosts title matches by 2x
- `{{query_text}}` is replaced with the actual query at runtime
- `{{size}}` controls how many results to return (10 for recall@10)

## Key Differences: Lexical vs. Vector Search

| Aspect | Lexical Search (this demo) | Vector Search |
|--------|---------------------------|---------------|
| **Query input** | Text string | Embedding vector |
| **Matching** | BM25 (term frequency) | Cosine/dot product similarity |
| **Schema** | `text` fields | `dense_vector` fields |
| **Query template** | `match`, `multi_match` | `knn`, `script_score` |
| **Jingra config** | `query_text_field` | `query_vector_field` |

## Ground Truth Methodology

This demo uses a novel approach for lexical search ground truth:

1. **Generate documents** (1,000 products)
2. **Generate queries** from documents (extract keywords from 1-3 random docs)
3. **Ground truth** = IDs of docs the query was extracted from
4. **Validation**: If doc is missing from index, recall < 1.0 (proves methodology works)

**Why this works**:
- If we query for keywords from a document, that document *should* be returned
- Measures whether Elasticsearch correctly retrieves documents containing the query terms
- Realistic: mimics how users search (using terms from product descriptions)

**Limitations**:
- Doesn't test relevance ranking (we only check if docs are returned, not their rank)
- Ground truth is artificial (real user queries might have different expectations)
- Primarily useful for timing/throughput comparisons, not relevance tuning

## Extending This Demo

### 1. Add More Query Types

Create additional query templates:

**Boolean query** (`jingra-config/queries/boolean-query.json`):
```json
{
  "query": {
    "bool": {
      "should": [
        {"match": {"title": "{{query_text}}"}},
        {"match": {"description": "{{query_text}}"}}
      ]
    }
  },
  "size": {{size}}
}
```

**Filtered query**:
```json
{
  "query": {
    "bool": {
      "must": {"multi_match": {"query": "{{query_text}}", "fields": ["title", "description"]}},
      "filter": {"term": {"category": "electronics"}}
    }
  },
  "size": {{size}}
}
```

Then add to `config.yaml`:
```yaml
param_groups:
  boolean_recall@10:
    - {size: 10, query_name: boolean-query}
```

### 2. Compare Multiple Parameter Sets

```yaml
param_groups:
  recall@10:
    - {size: 10}
  recall@20:
    - {size: 20}
  recall@50:
    - {size: 50}
```

Jingra will benchmark each and show recall/latency tradeoffs.

### 3. Add Filters to Queries

Modify `generate-queries.py` to include filter conditions:

```python
queries.append({
    "query_text": query_text,
    "ground_truth": ground_truth,
    "meta_conditions": {"category": "electronics"}
})
```

Update `queries_mapping` in `config.yaml`:
```yaml
queries_mapping:
  query_text_field: query_text
  ground_truth_field: ground_truth
  conditions_field: meta_conditions
```

Update query template to use filters:
```json
{
  "query": {
    "bool": {
      "must": {"multi_match": {"query": "{{query_text}}", "fields": ["title^2", "description"]}},
      "filter": {{meta_conditions}}
    }
  },
  "size": {{size}}
}
```

### 4. Increase Dataset Size

Change `num_docs` in `generate-docs.py`:
```python
num_docs = 10_000  # or 100_000
```

Then regenerate the data:
```bash
docker-compose run --rm datagen
```

This tests how performance scales with index size.

### 5. Compare Analyzers

Create multiple schemas with different analyzers:

- Standard analyzer (current)
- English analyzer (stemming, stop words)
- Custom analyzer (ngrams, synonyms)

Then run separate load/eval cycles for each.

## Troubleshooting

### Elasticsearch won't start
```bash
# Check logs
docker-compose logs elasticsearch

# Common issue: port 9200 already in use
lsof -i :9200
kill -9 <PID>

# Or change the port in docker-compose.yml:
# ports:
#   - "9201:9200"
```

### Jingra can't connect to Elasticsearch
```bash
# Check Elasticsearch is running and healthy
docker-compose ps

# View Jingra logs
docker-compose logs jingra

# Verify network connectivity
docker-compose run --rm jingra sh -c "apk add curl && curl http://elasticsearch:9200"
```

### Jingra JAR not found during build
```bash
# Build Jingra first
cd ../..
mvn clean package
cd demos/elasticsearch-lexical-search

# Then rebuild Docker images
docker-compose build jingra
```

### Low recall (< 0.5)
This is expected! The ground truth methodology means:
- If query = "bluetooth headphones wireless"
- And doc P00001 has title = "Wireless Bluetooth Headphones"
- Then P00001 *should* be in top 10 results

Low recall means Elasticsearch didn't return the source documents, which could indicate:
- Poor query (too generic, too many terms)
- BM25 scoring issues
- Need different analyzer or query type

### Evaluation hangs
```bash
# Check if index exists and has documents
curl http://localhost:9200/demo-products/_count

# Check query template is valid
curl -X POST http://localhost:9200/demo-products/_search \
  -H 'Content-Type: application/json' \
  -d '{"query": {"multi_match": {"query": "bluetooth headphones", "fields": ["title", "description"]}}, "size": 10}'

# Run Jingra with verbose logging
docker-compose run --rm jingra sh -c "java -jar jingra.jar eval config.yaml"
```

## Next Steps

1. **Run the demo** end-to-end to verify Jingra's lexical search support
2. **Experiment** with different query templates and parameters
3. **Compare** vector vs. lexical search on the same dataset (add embeddings to docs)
4. **Scale up** to larger datasets (10K, 100K, 1M docs)
5. **Integrate** with real product catalogs or text corpora

## Credits

This demo uses:
- **Elasticsearch** 9.3.2 (Apache 2.0 license)
- **Pandas** and **PyArrow** for data manipulation
- **Jingra** for benchmarking

---

**Questions or issues?** Check the main Jingra documentation or open an issue on GitHub.
