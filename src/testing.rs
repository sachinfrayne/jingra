//! Test doubles shared across module test suites (evaluator, CLI wiring). Compiled only under
//! `#[cfg(test)]`: `MockBenchmarkEngine` and `MockResultsSink`.

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
    /// Given the 0-based `ingest` call index, returns an error to fail that call with.
    ingest_error_fn: Box<dyn Fn(usize) -> Option<EngineError> + Send + Sync>,
    /// `index_exists` returns `true` for this many calls, then `false` forever after --
    /// simulates a pre-existing index that a delete removes.
    index_exists_true_for_calls: AtomicUsize,
    pub query_calls: AtomicUsize,
    pub ingest_calls: AtomicUsize,
    pub delete_index_calls: AtomicUsize,
    pub create_index_calls: AtomicUsize,
    pub document_count_value: AtomicU64,
    pub version_value: String,
    /// Run on every `query` call, on whatever thread the caller is using.
    query_hook: Option<Box<dyn Fn() + Send + Sync>>,
    version_fails: bool,
    document_count_fails: bool,
}

impl MockBenchmarkEngine {
    /// Every query call returns the same document IDs, `Ok`.
    pub fn with_fixed_ids(ids: Vec<String>) -> Self {
        Self::with_response_fn(move |_| Ok(QueryResponse::new(ids.clone(), Some(1.0), Some(1.0))))
    }

    /// `should_fail(call_index)` (0-based, across the whole engine's lifetime) decides whether
    /// that call returns an error instead of `ids`.
    pub fn failing_on(ids: Vec<String>, should_fail: impl Fn(usize) -> bool + Send + Sync + 'static) -> Self {
        Self::with_response_fn(move |call_index| {
            if should_fail(call_index) {
                Err(EngineError::Message("injected failure".to_string()))
            } else {
                Ok(QueryResponse::new(ids.clone(), Some(1.0), Some(1.0)))
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

    /// `ingest` fails on every call with `error()`.
    pub fn failing_ingest(error: impl Fn() -> EngineError + Send + Sync + 'static) -> Self {
        let mut engine = Self::with_fixed_ids(vec![]);
        engine.ingest_error_fn = Box::new(move |_| Some(error()));
        engine
    }

    /// The first `n` `ingest` calls fail with a transient error, then calls succeed.
    pub fn ingest_transiently_failing_first(n: usize) -> Self {
        let mut engine = Self::with_fixed_ids(vec![]);
        engine.ingest_error_fn = Box::new(move |call| {
            (call < n).then(|| EngineError::Transient("injected transient failure".to_string()))
        });
        engine
    }

    fn with_response_fn(f: impl Fn(usize) -> Result<QueryResponse, EngineError> + Send + Sync + 'static) -> Self {
        Self {
            response_fn: Box::new(f),
            ingest_report_fn: Box::new(|len| len),
            ingest_error_fn: Box::new(|_| None),
            index_exists_true_for_calls: AtomicUsize::new(0),
            query_calls: AtomicUsize::new(0),
            ingest_calls: AtomicUsize::new(0),
            delete_index_calls: AtomicUsize::new(0),
            create_index_calls: AtomicUsize::new(0),
            document_count_value: AtomicU64::new(0),
            version_value: "mock-1.0".to_string(),
            query_hook: None,
            version_fails: false,
            document_count_fails: false,
        }
    }

    /// Runs `hook` synchronously inside every `query` call (used to observe which thread
    /// queries execute on).
    pub fn with_query_hook(mut self, hook: impl Fn() + Send + Sync + 'static) -> Self {
        self.query_hook = Some(Box::new(hook));
        self
    }

    pub fn with_failing_version(mut self) -> Self {
        self.version_fails = true;
        self
    }

    pub fn with_failing_document_count(mut self) -> Self {
        self.document_count_fails = true;
        self
    }
}

#[async_trait]
impl BenchmarkEngine for MockBenchmarkEngine {
    async fn connect(&self) -> Result<(), EngineError> {
        Ok(())
    }
    async fn create_index(&self, _index_name: &str, _schema_name: &str) -> Result<(), EngineError> {
        self.create_index_calls.fetch_add(1, Ordering::SeqCst);
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
    async fn ingest(&self, documents: &[Document], _index_name: &str, _id_field: Option<&str>) -> Result<usize, EngineError> {
        let call = self.ingest_calls.fetch_add(1, Ordering::SeqCst);
        if let Some(error) = (self.ingest_error_fn)(call) {
            return Err(error);
        }
        Ok((self.ingest_report_fn)(documents.len()))
    }
    async fn query(&self, _index_name: &str, _query_name: &str, _params: &QueryParams) -> Result<QueryResponse, EngineError> {
        let call_index = self.query_calls.fetch_add(1, Ordering::SeqCst);
        if let Some(hook) = &self.query_hook {
            hook();
        }
        (self.response_fn)(call_index)
    }
    async fn document_count(&self, _index_name: &str) -> Result<u64, EngineError> {
        if self.document_count_fails {
            return Err(EngineError::Message("injected document_count failure".to_string()));
        }
        Ok(self.document_count_value.load(Ordering::SeqCst))
    }
    fn engine_name(&self) -> &'static str {
        "mock"
    }
    fn short_name(&self) -> &'static str {
        "mk"
    }
    async fn version(&self) -> Result<String, EngineError> {
        if self.version_fails {
            return Err(EngineError::Message("injected version failure".to_string()));
        }
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
    /// When true the sink reports that it does not want per-query records.
    pub declines_query_metrics: bool,
}

#[async_trait]
impl ResultsSink for MockResultsSink {
    fn wants_query_metrics(&self) -> bool {
        !self.declines_query_metrics
    }
    async fn write_result(&self, result: &BenchmarkResult) -> Result<(), OutputError> {
        self.results.lock().unwrap().push(result.clone());
        Ok(())
    }
    async fn write_query_metrics_batch(&self, metrics: &[QueryMetricRecord]) -> Result<(), OutputError> {
        self.query_metric_batches.lock().unwrap().push(metrics.to_vec());
        Ok(())
    }
}
