//! Reads back everything `ObjectStoreResultsSink` wrote for one run: lists and parses every
//! object under `<results_uri>/<run_id>/results/`.

use super::ReportError;
use crate::model::BenchmarkResult;
use futures::StreamExt;
use object_store::ObjectStoreExt;
use url::Url;

pub async fn read_results(results_uri: &str, run_id: &str) -> Result<Vec<BenchmarkResult>, ReportError> {
    let url = Url::parse(results_uri).map_err(|e| ReportError::Message(format!("invalid results_uri '{results_uri}': {e}")))?;
    let (store, prefix) = object_store::parse_url(&url)
        .map_err(|e| ReportError::Message(format!("failed to open results_uri '{results_uri}': {e}")))?;
    let results_prefix = prefix.join(run_id).join("results");

    let mut results = Vec::new();
    let mut listing = store.list(Some(&results_prefix));
    while let Some(meta) = listing.next().await {
        let meta = meta?;
        let bytes = store.get(&meta.location).await?.bytes().await?;
        let result: BenchmarkResult = serde_json::from_slice(&bytes)?;
        results.push(result);
    }
    Ok(results)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::output::{ObjectStoreResultsSink, ResultsSink};
    use object_store::memory::InMemory;
    use object_store::path::Path as ObjectPath;
    use std::collections::HashMap;
    use std::sync::Arc;

    #[tokio::test]
    async fn read_results_returns_empty_for_run_with_no_results() {
        let dir = tempfile::tempdir().unwrap();
        let uri = format!("file://{}", dir.path().display());
        let results = read_results(&uri, "no-such-run").await.unwrap();
        assert!(results.is_empty());
    }

    #[tokio::test]
    async fn read_results_round_trips_what_the_sink_wrote() {
        let dir = tempfile::tempdir().unwrap();
        let uri = format!("file://{}", dir.path().display());
        let (store, prefix) = object_store::parse_url(&Url::parse(&uri).unwrap()).unwrap();
        let sink = ObjectStoreResultsSink::from_store(store, prefix, "run-x", false);

        let mut r1 = BenchmarkResult::new("run-x", "qdrant", "1.18.0", "vector", "demo", "hnsw_ef=32", HashMap::new());
        r1.add_metric("recall", 0.9);
        let mut r2 = BenchmarkResult::new("run-x", "qdrant", "1.18.0", "vector", "demo", "hnsw_ef=64", HashMap::new());
        r2.add_metric("recall", 0.95);
        sink.write_result(&r1).await.unwrap();
        sink.write_result(&r2).await.unwrap();

        let mut results = read_results(&uri, "run-x").await.unwrap();
        results.sort_by(|a, b| a.param_key.cmp(&b.param_key));
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].param_key, "hnsw_ef=32");
        assert_eq!(results[1].param_key, "hnsw_ef=64");
    }

    #[tokio::test]
    async fn read_results_ignores_other_run_ids_under_same_prefix() {
        let store = Arc::new(InMemory::new());
        let sink_a = ObjectStoreResultsSink::from_store(Box::new(store.clone()) as Box<dyn object_store::ObjectStore>, ObjectPath::from(""), "run-a", false);
        let sink_b = ObjectStoreResultsSink::from_store(Box::new(store) as Box<dyn object_store::ObjectStore>, ObjectPath::from(""), "run-b", false);
        sink_a
            .write_result(&BenchmarkResult::new("run-a", "qdrant", "1.18.0", "vector", "demo", "k=1", HashMap::new()))
            .await
            .unwrap();
        sink_b
            .write_result(&BenchmarkResult::new("run-b", "qdrant", "1.18.0", "vector", "demo", "k=1", HashMap::new()))
            .await
            .unwrap();

        // read_results always parses its own store from the URI, so this test only verifies the
        // prefix-scoping logic against a real local-file store (memory:// can't be inspected
        // from a second parse_url call -- see the sink's own test module doc for why).
        let dir = tempfile::tempdir().unwrap();
        let uri = format!("file://{}", dir.path().display());
        let (fs_store, fs_prefix) = object_store::parse_url(&Url::parse(&uri).unwrap()).unwrap();
        let fs_sink_a = ObjectStoreResultsSink::from_store(fs_store, fs_prefix, "run-a", false);
        fs_sink_a
            .write_result(&BenchmarkResult::new("run-a", "qdrant", "1.18.0", "vector", "demo", "k=1", HashMap::new()))
            .await
            .unwrap();

        let results_a = read_results(&uri, "run-a").await.unwrap();
        let results_b = read_results(&uri, "run-b").await.unwrap();
        assert_eq!(results_a.len(), 1);
        assert!(results_b.is_empty());
    }
}
