//! Reads back everything `ObjectStoreResultsSink` wrote for one run: lists and parses every
//! object under `<results_uri>/<run_id>/results/`.

use super::ReportError;
use crate::model::BenchmarkResult;
use crate::output::{open_store, LocalDir};
use chrono::{DateTime, Utc};
use futures::StreamExt;
use object_store::path::Path as ObjectPath;
use object_store::{ObjectStore, ObjectStoreExt};
use std::collections::HashMap;

pub async fn read_results(results_uri: &str, run_id: &str) -> Result<Vec<BenchmarkResult>, ReportError> {
    let (store, prefix) = open_store(results_uri, LocalDir::MustExist).map_err(|e| ReportError::Message(e.to_string()))?;
    read_results_from_store(store.as_ref(), &prefix, run_id).await
}

/// Reads every result stored for `run_id`, keeping only the most recently written one for each
/// (engine, dataset, parameter set). Re-running `bench` with the same `run_id` (for example a
/// Kubernetes Job retry) writes new objects next to the old ones; without this the report would
/// plot both and double count them.
pub async fn read_results_from_store(
    store: &dyn ObjectStore,
    prefix: &ObjectPath,
    run_id: &str,
) -> Result<Vec<BenchmarkResult>, ReportError> {
    let results_prefix = prefix.clone().join(run_id).join("results");

    let mut stamped = Vec::new();
    let mut listing = store.list(Some(&results_prefix));
    while let Some(meta) = listing.next().await {
        let meta = meta?;
        let bytes = store.get(&meta.location).await?.bytes().await?;
        let result: BenchmarkResult = serde_json::from_slice(&bytes)?;
        stamped.push((meta.last_modified, meta.location.to_string(), result));
    }

    let total = stamped.len();
    let results = keep_latest(stamped);
    if results.len() < total {
        tracing::warn!(
            "Run '{run_id}' has {total} stored results but only {} distinct parameter sets: it was run more than once. Using the most recent result for each.",
            results.len()
        );
    }
    Ok(results)
}

/// Collapses results sharing (engine, dataset, param_key) to the one with the latest timestamp,
/// returned sorted by that key so output order does not depend on listing order.
///
/// Each entry is (last modified, object path, result). Equal timestamps (coarse clocks, object
/// stores with one-second resolution) are broken by path, so the winner never depends on the
/// order the store happened to list objects in.
fn keep_latest(stamped: Vec<(DateTime<Utc>, String, BenchmarkResult)>) -> Vec<BenchmarkResult> {
    type Key = (String, String, String);
    let mut latest: HashMap<Key, ((DateTime<Utc>, String), BenchmarkResult)> = HashMap::new();
    for (stamp, path, result) in stamped {
        let key = (result.engine.clone(), result.dataset_name.clone(), result.param_key.clone());
        let order = (stamp, path);
        match latest.get(&key) {
            Some((existing, _)) if *existing >= order => {}
            _ => {
                latest.insert(key, (order, result));
            }
        }
    }
    let mut keyed: Vec<_> = latest.into_iter().collect();
    keyed.sort_by(|a, b| a.0.cmp(&b.0));
    keyed.into_iter().map(|(_, (_, r))| r).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::output::{ObjectStoreResultsSink, ResultsSink};
    use url::Url;
    use object_store::memory::InMemory;
    use object_store::path::Path as ObjectPath;
    use std::collections::HashMap;
    use std::sync::Arc;

    fn result(param_key: &str, recall: f64) -> BenchmarkResult {
        let mut r = BenchmarkResult::new("run", "qdrant", "1.18.0", "vector", "demo", param_key, HashMap::new());
        r.add_metric("recall", recall);
        r
    }

    #[test]
    fn keep_latest_prefers_the_newest_result_for_a_repeated_parameter_set() {
        let t0 = DateTime::<Utc>::from_timestamp(1_000, 0).unwrap();
        let t1 = DateTime::<Utc>::from_timestamp(2_000, 0).unwrap();
        // Newest is listed first on purpose: result must not depend on listing order.
        let kept = keep_latest(vec![
            (t1, "b".to_string(), result("hnsw_ef=32", 0.9)),
            (t0, "a".to_string(), result("hnsw_ef=32", 0.5)),
            (t0, "c".to_string(), result("hnsw_ef=64", 0.8)),
        ]);
        assert_eq!(kept.len(), 2);
        assert_eq!(kept[0].param_key, "hnsw_ef=32");
        assert_eq!(kept[0].metrics["recall"], 0.9);
        assert_eq!(kept[1].param_key, "hnsw_ef=64");
    }

    #[test]
    fn keep_latest_distinguishes_engines_and_datasets() {
        let t = DateTime::<Utc>::from_timestamp(1_000, 0).unwrap();
        let mut other_engine = result("k=1", 0.1);
        other_engine.engine = "elasticsearch".to_string();
        let mut other_dataset = result("k=1", 0.2);
        other_dataset.dataset_name = "other".to_string();
        let stamped = vec![
            (t, "a".to_string(), result("k=1", 0.3)),
            (t, "b".to_string(), other_engine),
            (t, "c".to_string(), other_dataset),
        ];
        assert_eq!(keep_latest(stamped).len(), 3);
    }

    #[test]
    fn keep_latest_breaks_timestamp_ties_by_path_regardless_of_listing_order() {
        // Coarse timestamps (e.g. one-second resolution) can tie; the winner must not depend on
        // which object the store happened to list first.
        let t = DateTime::<Utc>::from_timestamp(1_000, 0).unwrap();
        let low = (t, "results/aaa.json".to_string(), result("k=1", 0.1));
        let high = (t, "results/zzz.json".to_string(), result("k=1", 0.9));
        let forward = keep_latest(vec![low.clone(), high.clone()]);
        let reverse = keep_latest(vec![high, low]);
        assert_eq!(forward[0].metrics["recall"], 0.9);
        assert_eq!(reverse[0].metrics["recall"], 0.9);
    }

    #[tokio::test]
    async fn rerunning_a_run_id_does_not_duplicate_results_in_the_report() {
        let store = Arc::new(InMemory::new());
        let sink = ObjectStoreResultsSink::from_store(Box::new(store.clone()), ObjectPath::from(""), "run", false);
        sink.write_result(&result("hnsw_ef=32", 0.5)).await.unwrap();
        sink.write_result(&result("hnsw_ef=32", 0.9)).await.unwrap();

        let results = read_results_from_store(store.as_ref(), &ObjectPath::from(""), "run").await.unwrap();
        assert_eq!(results.len(), 1);
    }

    #[tokio::test]
    async fn read_results_errors_clearly_when_the_results_directory_does_not_exist() {
        let dir = tempfile::tempdir().unwrap();
        let uri = format!("file://{}", dir.path().join("typo").display());
        let err = read_results(&uri, "run").await.unwrap_err();
        assert!(err.to_string().contains("does not exist"), "{err}");
    }

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
