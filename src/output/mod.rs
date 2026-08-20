//! Where benchmark results go. Results are always printed to console; `ObjectStoreResultsSink`
//! additionally persists them durably (see its module doc for why).

mod console;
mod object_store_sink;

pub use console::ConsoleResultsSink;
pub use object_store_sink::ObjectStoreResultsSink;

use crate::model::BenchmarkResult;
use async_trait::async_trait;
use serde::Serialize;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, thiserror::Error)]
pub enum OutputError {
    #[error("{0}")]
    Message(String),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("object store error: {0}")]
    ObjectStore(#[from] object_store::Error),
}

/// One query's outcome plus context, flattened for JSONL export. Mirrors the Java version's
/// `buildQueryMetric` map.
#[derive(Debug, Clone, Serialize, serde::Deserialize)]
pub struct QueryMetricRecord {
    pub run_id: String,
    pub engine: String,
    pub dataset: String,
    pub params: HashMap<String, Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub meta_conditions: Option<HashMap<String, Value>>,
    pub ground_truth: Vec<String>,
    pub retrieved: Vec<String>,
    pub num_ground_truth: usize,
    pub num_retrieved: usize,
    pub client_latency_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub server_latency_ms: Option<i64>,
    pub precision: f64,
    pub recall: f64,
    pub relevant_retrieved: usize,
}

#[async_trait]
pub trait ResultsSink: Send + Sync {
    async fn write_result(&self, result: &BenchmarkResult) -> Result<(), OutputError>;
    async fn write_query_metrics_batch(&self, metrics: &[QueryMetricRecord]) -> Result<(), OutputError>;
}
