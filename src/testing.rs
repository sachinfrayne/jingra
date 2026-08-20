//! Test doubles shared across module test suites (evaluator, CLI wiring). Compiled only under
//! `#[cfg(test)]` -- mirrors the Java test suite's `testing` package (`MockBenchmarkEngine`,
//! `MockResultsSink`).

use crate::engine::{BenchmarkEngine, EngineError};
use crate::model::{BenchmarkResult, Document, QueryParams, QueryResponse};
use crate::output::{OutputError, QueryMetricRecord, ResultsSink};
use async_trait::async_trait;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;

type ResponseFn = dyn Fn(usize) -> Result<QueryResponse, EngineError> + Send + Sync;

pub struct MockBenchmarkEngine {
    response_fn: Box<ResponseFn>,
    /// Maps a batch's length to how many documents `ingest` reports as ingested; default
    /// identity (reports the whole batch). Used to simulate documents silently dropped (e.g.
    /// missing vector field), which should surface as a row-count mismatch in `load`.
    ingest_report_fn: Box<dyn Fn(usize) -> usize + Send + Sync>,
    /// `index_exists` returns `true` for this many calls, then `false` forever after --
    /// simulates a pre-existing index that a delete removes.
    index_exists_true_for_calls: AtomicUsize,
    pub query_calls: AtomicUsize,
    pub ingest_calls: AtomicUsize,
    pub delete_index_calls: AtomicUsize,
    pub document_count_value: AtomicU64,
    pub version_value: String,
}

impl MockBenchmarkEngine {
    /// Every query call returns the same document IDs, `Ok`.
    pub fn with_fixed_ids(ids: Vec<String>) -> Self {
        Self::with_response_fn(move |_| Ok(QueryResponse::new(ids.clone(), Some(1.0), Some(1))))
    }

    /// `should_fail(call_index)` (0-based, across the whole engine's lifetime) decides whether
    /// that call returns an error instead of `ids`.
    pub fn failing_on(ids: Vec<String>, should_fail: impl Fn(usize) -> bool + Send + Sync + 'static) -> Self {
        Self::with_response_fn(move |call_index| {
            if should_fail(call_index) {
                Err(EngineError::Message("injected failure".to_string()))
            } else {
                Ok(QueryResponse::new(ids.clone(), Some(1.0), Some(1)))
            }
        })
    }

    /// `index_exists` reports `true` once, then `false` afterward (simulates a pre-existing
    /// index that gets deleted).
    pub fn index_exists_once_then_absent() -> Self {
        let mut engine = Self::with_fixed_ids(vec![]);
        engine.index_exists_true_for_calls = AtomicUsize::new(1);
        engine
    }

    /// `ingest` always reports one fewer document than the batch actually contained, so the
    /// total ingested count never matches the dataset's row count.
    pub fn under_reporting_ingest() -> Self {
        let mut engine = Self::with_fixed_ids(vec![]);
        engine.ingest_report_fn = Box::new(|len| len.saturating_sub(1));
        engine
    }

    fn with_response_fn(f: impl Fn(usize) -> Result<QueryResponse, EngineError> + Send + Sync + 'static) -> Self {
        Self {
            response_fn: Box::new(f),
            ingest_report_fn: Box::new(|len| len),
            index_exists_true_for_calls: AtomicUsize::new(0),
            query_calls: AtomicUsize::new(0),
            ingest_calls: AtomicUsize::new(0),
            delete_index_calls: AtomicUsize::new(0),
            document_count_value: AtomicU64::new(0),
            version_value: "mock-1.0".to_string(),
        }
    }
}

#[async_trait]
impl BenchmarkEngine for MockBenchmarkEngine {
    async fn connect(&self) -> Result<(), EngineError> {
        Ok(())
    }
    async fn create_index(&self, _index_name: &str, _schema_name: &str) -> Result<(), EngineError> {
        Ok(())
    }
    async fn index_exists(&self, _index_name: &str) -> Result<bool, EngineError> {
        let mut remaining = self.index_exists_true_for_calls.load(Ordering::SeqCst);
        if remaining == 0 {
            return Ok(false);
        }
        loop {
            match self.index_exists_true_for_calls.compare_exchange(
                remaining,
                remaining - 1,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => return Ok(true),
                Err(0) => return Ok(false),
                Err(actual) => remaining = actual,
            }
        }
    }
    async fn delete_index(&self, _index_name: &str) -> Result<(), EngineError> {
        self.delete_index_calls.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
    async fn ingest(&self, documents: Vec<Document>, _index_name: &str, _id_field: Option<&str>) -> Result<usize, EngineError> {
        self.ingest_calls.fetch_add(1, Ordering::SeqCst);
        Ok((self.ingest_report_fn)(documents.len()))
    }
    async fn query(&self, _index_name: &str, _query_name: &str, _params: &QueryParams) -> Result<QueryResponse, EngineError> {
        let call_index = self.query_calls.fetch_add(1, Ordering::SeqCst);
        (self.response_fn)(call_index)
    }
    async fn document_count(&self, _index_name: &str) -> Result<u64, EngineError> {
        Ok(self.document_count_value.load(Ordering::SeqCst))
    }
    fn engine_name(&self) -> &'static str {
        "mock"
    }
    fn short_name(&self) -> &'static str {
        "mk"
    }
    async fn version(&self) -> Result<String, EngineError> {
        Ok(self.version_value.clone())
    }
    async fn index_metadata(&self, _index_name: &str) -> Result<HashMap<String, String>, EngineError> {
        Ok(HashMap::new())
    }
    fn schema_template(&self, _schema_name: &str) -> Result<Option<HashMap<String, Value>>, EngineError> {
        Ok(None)
    }
}

#[derive(Default)]
pub struct MockResultsSink {
    pub results: Mutex<Vec<BenchmarkResult>>,
    pub query_metric_batches: Mutex<Vec<Vec<QueryMetricRecord>>>,
}

#[async_trait]
impl ResultsSink for MockResultsSink {
    async fn write_result(&self, result: &BenchmarkResult) -> Result<(), OutputError> {
        self.results.lock().unwrap().push(result.clone());
        Ok(())
    }
    async fn write_query_metrics_batch(&self, metrics: &[QueryMetricRecord]) -> Result<(), OutputError> {
        self.query_metric_batches.lock().unwrap().push(metrics.to_vec());
        Ok(())
    }
}
