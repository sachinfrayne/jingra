//! Persists benchmark results through the `object_store` crate, which abstracts local
//! filesystem, S3, GCS, and Azure Blob behind one URI scheme (`file://`, `s3://`, `gs://`,
//! `az://`). This is what makes `bench` safe to run as a Kubernetes Job: a Job's pod filesystem
//! disappears once the pod completes and is garbage-collected, so results written only to local
//! disk on the pod would be unreachable afterward. Pointing `results_uri` at durable object
//! storage means results survive the pod and are visible to `report` run from anywhere -- a
//! different pod, CI, or a laptop.
//!
//! Layout under `<results_uri>/<run_id>/`:
//! - `results/<param_key>-<uuid>.json` -- one object per [`BenchmarkResult`] (object stores are
//!   whole-object-write, not append-friendly, so each result/batch gets its own object rather
//!   than sharing one growing file).
//! - `queries/<uuid>.jsonl` -- one object per per-query-metrics batch, newline-delimited
//!   [`QueryMetricRecord`]s.
//!
//! `report`'s reader lists and reads back everything under a run's prefix using this same
//! layout.

use super::{OutputError, QueryMetricRecord, ResultsSink};
use crate::model::BenchmarkResult;
use async_trait::async_trait;
use object_store::path::Path as ObjectPath;
use object_store::{ObjectStore, ObjectStoreExt};
use url::Url;
use uuid::Uuid;

pub struct ObjectStoreResultsSink {
    store: Box<dyn ObjectStore>,
    prefix: ObjectPath,
    run_id: String,
    write_query_metrics: bool,
}

impl ObjectStoreResultsSink {
    pub fn new(results_uri: &str, run_id: impl Into<String>, write_query_metrics: bool) -> Result<Self, OutputError> {
        let url = Url::parse(results_uri)
            .map_err(|e| OutputError::Message(format!("invalid results_uri '{results_uri}': {e}")))?;
        let (store, prefix) = object_store::parse_url(&url)
            .map_err(|e| OutputError::Message(format!("failed to open results_uri '{results_uri}': {e}")))?;
        Ok(Self::from_store(store, prefix, run_id, write_query_metrics))
    }

    /// Builds directly from an already-constructed store, so tests can inject an `InMemory`
    /// store they also hold a handle to (each `memory://` URL parsed via [`Self::new`] gets its
    /// own independent, unshared store -- there's no global in-process store to inspect from
    /// outside).
    pub fn from_store(
        store: Box<dyn ObjectStore>,
        prefix: ObjectPath,
        run_id: impl Into<String>,
        write_query_metrics: bool,
    ) -> Self {
        Self {
            store,
            prefix,
            run_id: run_id.into(),
            write_query_metrics,
        }
    }

    fn results_path(&self, param_key: &str) -> ObjectPath {
        self.prefix
            .clone()
            .join(self.run_id.as_str())
            .join("results")
            .join(format!("{}-{}.json", sanitize_key(param_key), Uuid::new_v4()))
    }

    fn queries_path(&self) -> ObjectPath {
        self.prefix
            .clone()
            .join(self.run_id.as_str())
            .join("queries")
            .join(format!("{}.jsonl", Uuid::new_v4()))
    }
}

fn sanitize_key(key: &str) -> String {
    key.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '=' || c == '_' || c == '.' || c == '-' { c } else { '_' })
        .collect()
}

#[async_trait]
impl ResultsSink for ObjectStoreResultsSink {
    async fn write_result(&self, result: &BenchmarkResult) -> Result<(), OutputError> {
        let bytes = serde_json::to_vec(result)?;
        self.store.put(&self.results_path(&result.param_key), bytes.into()).await?;
        Ok(())
    }

    async fn write_query_metrics_batch(&self, metrics: &[QueryMetricRecord]) -> Result<(), OutputError> {
        if !self.write_query_metrics || metrics.is_empty() {
            return Ok(());
        }
        let mut buf = Vec::new();
        for m in metrics {
            serde_json::to_writer(&mut buf, m)?;
            buf.push(b'\n');
        }
        self.store.put(&self.queries_path(), buf.into()).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::StreamExt;
    use object_store::memory::InMemory;
    use std::collections::HashMap;
    use std::sync::Arc;

    fn sample_result(param_key: &str) -> BenchmarkResult {
        let mut r = BenchmarkResult::new("run-1", "qdrant", "1.18.0", "vector", "demo", param_key, HashMap::new());
        r.add_metric("recall", 0.9);
        r
    }

    #[tokio::test]
    async fn write_result_lands_under_run_id_results_prefix() {
        let store = Arc::new(InMemory::new());
        let sink = ObjectStoreResultsSink::from_store(Box::new(store.clone()), ObjectPath::from(""), "run-1", true);
        sink.write_result(&sample_result("hnsw_ef=32")).await.unwrap();

        let listing_prefix = ObjectPath::from("run-1").join("results");
        let objects: Vec<_> = store.list(Some(&listing_prefix)).collect().await;
        assert_eq!(objects.len(), 1);
        let meta = objects[0].as_ref().unwrap();
        let bytes = store.get(&meta.location).await.unwrap().bytes().await.unwrap();
        let round_tripped: BenchmarkResult = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(round_tripped.param_key, "hnsw_ef=32");
    }

    #[tokio::test]
    async fn write_query_metrics_batch_skips_when_disabled() {
        let store = Arc::new(InMemory::new());
        let sink = ObjectStoreResultsSink::from_store(Box::new(store.clone()), ObjectPath::from(""), "run-2", false);
        let metric = QueryMetricRecord {
            run_id: "run-2".to_string(),
            engine: "qdrant".to_string(),
            dataset: "demo".to_string(),
            params: HashMap::new(),
            meta_conditions: None,
            ground_truth: vec!["a".to_string()],
            retrieved: vec!["a".to_string()],
            num_ground_truth: 1,
            num_retrieved: 1,
            client_latency_ms: Some(1.5),
            server_latency_ms: None,
            precision: 1.0,
            recall: 1.0,
            relevant_retrieved: 1,
        };
        sink.write_query_metrics_batch(&[metric]).await.unwrap();

        let listing_prefix = ObjectPath::from("run-2").join("queries");
        let objects: Vec<_> = store.list(Some(&listing_prefix)).collect().await;
        assert!(objects.is_empty(), "disabled per_query_metrics must not write anything");
    }

    #[tokio::test]
    async fn write_query_metrics_batch_writes_jsonl_when_enabled() {
        let store = Arc::new(InMemory::new());
        let sink = ObjectStoreResultsSink::from_store(Box::new(store.clone()), ObjectPath::from(""), "run-3", true);
        let metric = QueryMetricRecord {
            run_id: "run-3".to_string(),
            engine: "qdrant".to_string(),
            dataset: "demo".to_string(),
            params: HashMap::new(),
            meta_conditions: None,
            ground_truth: vec!["a".to_string()],
            retrieved: vec!["a".to_string(), "b".to_string()],
            num_ground_truth: 1,
            num_retrieved: 2,
            client_latency_ms: Some(2.0),
            server_latency_ms: Some(1),
            precision: 0.5,
            recall: 1.0,
            relevant_retrieved: 1,
        };
        sink.write_query_metrics_batch(&[metric.clone(), metric]).await.unwrap();

        let listing_prefix = ObjectPath::from("run-3").join("queries");
        let objects: Vec<_> = store.list(Some(&listing_prefix)).collect().await;
        assert_eq!(objects.len(), 1);
        let bytes = store.get(&objects[0].as_ref().unwrap().location).await.unwrap().bytes().await.unwrap();
        let text = String::from_utf8(bytes.to_vec()).unwrap();
        assert_eq!(text.lines().count(), 2);
    }

    #[test]
    fn sanitize_key_replaces_unsafe_characters() {
        assert_eq!(sanitize_key("hnsw_ef=32"), "hnsw_ef=32");
        assert_eq!(sanitize_key("path/like=1"), "path_like=1");
    }

    #[test]
    fn new_rejects_invalid_uri() {
        assert!(ObjectStoreResultsSink::new("not a url", "run", true).is_err());
    }
}
