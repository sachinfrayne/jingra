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
    pub query_calls: AtomicUsize,
    pub ingest_calls: AtomicUsize,
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

    fn with_response_fn(f: impl Fn(usize) -> Result<QueryResponse, EngineError> + Send + Sync + 'static) -> Self {
        Self {
            response_fn: Box::new(f),
            query_calls: AtomicUsize::new(0),
            ingest_calls: AtomicUsize::new(0),
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
        Ok(false)
    }
    async fn delete_index(&self, _index_name: &str) -> Result<(), EngineError> {
        Ok(())
    }
    async fn ingest(&self, documents: Vec<Document>, _index_name: &str, _id_field: Option<&str>) -> Result<usize, EngineError> {
        self.ingest_calls.fetch_add(1, Ordering::SeqCst);
        Ok(documents.len())
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
