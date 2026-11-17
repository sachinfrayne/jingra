//! Core data types shared across the config/data/engine/evaluation layers.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;

/// A single record read from a dataset (data or queries file). Field values are kept as
/// [`serde_json::Value`] so that Parquet and NDJSON readers can converge on one representation
/// regardless of source format.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Document(HashMap<String, Value>);

impl Document {
    pub fn new(fields: HashMap<String, Value>) -> Self {
        Self(fields)
    }

    pub fn fields(&self) -> &HashMap<String, Value> {
        &self.0
    }

    pub fn into_fields(self) -> HashMap<String, Value> {
        self.0
    }

    pub fn contains_field(&self, key: &str) -> bool {
        self.0.contains_key(key)
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        self.0.get(key)
    }

    /// Returns the field as a UTF-8 string, if present and textual.
    pub fn get_str(&self, key: &str) -> Option<&str> {
        self.0.get(key)?.as_str()
    }

    /// Returns the field as a numeric vector of `f32`, if present and a JSON array of numbers.
    /// Used for vector fields regardless of whether the source stored them as float32 or
    /// float64 — JSON does not distinguish the two, so one accessor covers both.
    pub fn get_float_list(&self, key: &str) -> Option<Vec<f32>> {
        let arr = self.0.get(key)?.as_array()?;
        arr.iter().map(|v| v.as_f64().map(|f| f as f32)).collect()
    }

    /// Returns the field as a vector of strings, if present and a JSON array of strings.
    pub fn get_string_list(&self, key: &str) -> Option<Vec<String>> {
        let arr = self.0.get(key)?.as_array()?;
        arr.iter()
            .map(|v| v.as_str().map(|s| s.to_string()))
            .collect()
    }

    /// Renders whichever value is stored at `key` as a canonical id string: numbers keep their
    /// natural formatting, strings pass through unchanged. Returns `None` if absent or not a
    /// scalar.
    pub fn get_id_string(&self, key: &str) -> Option<String> {
        match self.0.get(key)? {
            Value::String(s) => Some(s.clone()),
            Value::Number(n) => Some(n.to_string()),
            _ => None,
        }
    }
}

/// Parameters for a single query execution: benchmark-configured params (e.g. `hnsw_ef`) merged
/// with the per-query vector/text/conditions extracted from the queries dataset.
///
/// The benchmark-wide parameters are shared (`Arc`) across every query of a run, and query
/// vectors are stored as typed `f32` slices, so building one `QueryParams` per query costs a few
/// small allocations rather than a deep copy of the parameter map plus a JSON encoding of the
/// vector.
#[derive(Debug, Clone, Default)]
pub struct QueryParams {
    base: Arc<HashMap<String, Value>>,
    overrides: HashMap<String, Value>,
    vectors: HashMap<String, Arc<[f32]>>,
}

impl QueryParams {
    pub fn new(params: HashMap<String, Value>) -> Self {
        Self::from_shared(Arc::new(params))
    }

    /// Starts from parameters shared with other queries; later `put`s only affect this value.
    pub fn from_shared(params: Arc<HashMap<String, Value>>) -> Self {
        Self {
            base: params,
            overrides: HashMap::new(),
            vectors: HashMap::new(),
        }
    }

    pub fn put(&mut self, key: impl Into<String>, value: impl Into<Value>) {
        self.overrides.insert(key.into(), value.into());
    }

    /// Stores a vector without converting it to JSON. Takes precedence over a JSON value
    /// stored under the same key.
    pub fn put_vector(&mut self, key: impl Into<String>, vector: Arc<[f32]>) {
        self.vectors.insert(key.into(), vector);
    }

    fn get(&self, key: &str) -> Option<&Value> {
        self.overrides.get(key).or_else(|| self.base.get(key))
    }

    pub fn get_integer(&self, key: &str) -> Option<i64> {
        self.get(key)?.as_i64()
    }

    pub fn get_f64(&self, key: &str) -> Option<f64> {
        self.get(key)?.as_f64()
    }

    pub fn get_float_list(&self, key: &str) -> Option<Vec<f32>> {
        if let Some(v) = self.vectors.get(key) {
            return Some(v.to_vec());
        }
        let arr = self.get(key)?.as_array()?;
        arr.iter().map(|v| v.as_f64().map(|f| f as f32)).collect()
    }
}

/// Result of executing one query against an engine.
#[derive(Debug, Clone, Default)]
pub struct QueryResponse {
    pub document_ids: Vec<String>,
    pub client_latency_ms: Option<f64>,
    pub server_latency_ms: Option<f64>,
}

impl QueryResponse {
    pub fn new(
        document_ids: Vec<String>,
        client_latency_ms: Option<f64>,
        server_latency_ms: Option<f64>,
    ) -> Self {
        Self {
            document_ids,
            client_latency_ms,
            server_latency_ms,
        }
    }

    pub fn empty() -> Self {
        Self::default()
    }
}

/// One row of benchmark output: the aggregate metrics for a single (dataset, parameter-set)
/// evaluation. Serialized as one JSON line per result by the results sink.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BenchmarkResult {
    pub run_id: String,
    pub engine: String,
    pub engine_version: String,
    pub dataset_type: String,
    pub dataset_name: String,
    pub param_key: String,
    pub params: HashMap<String, Value>,
    #[serde(default)]
    pub metrics: HashMap<String, f64>,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub schema: Option<HashMap<String, Value>>,
}

impl BenchmarkResult {
    pub fn new(
        run_id: impl Into<String>,
        engine: impl Into<String>,
        engine_version: impl Into<String>,
        dataset_type: impl Into<String>,
        dataset_name: impl Into<String>,
        param_key: impl Into<String>,
        params: HashMap<String, Value>,
    ) -> Self {
        Self {
            run_id: run_id.into(),
            engine: engine.into(),
            engine_version: engine_version.into(),
            dataset_type: dataset_type.into(),
            dataset_name: dataset_name.into(),
            param_key: param_key.into(),
            params,
            metrics: HashMap::new(),
            metadata: HashMap::new(),
            schema: None,
        }
    }

    pub fn add_metric(&mut self, key: impl Into<String>, value: f64) {
        self.metrics.insert(key.into(), value);
    }

    pub fn add_metadata(&mut self, key: impl Into<String>, value: impl Into<String>) {
        self.metadata.insert(key.into(), value.into());
    }

    pub fn set_schema(&mut self, schema: HashMap<String, Value>) {
        self.schema = Some(schema);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn doc(fields: &[(&str, Value)]) -> Document {
        Document::new(
            fields
                .iter()
                .map(|(k, v)| (k.to_string(), v.clone()))
                .collect(),
        )
    }

    #[test]
    fn get_float_list_reads_numeric_array() {
        let d = doc(&[("embedding", json!([1.0, 2.5, 3.0]))]);
        assert_eq!(d.get_float_list("embedding"), Some(vec![1.0, 2.5, 3.0]));
    }

    #[test]
    fn get_float_list_missing_field_is_none() {
        let d = doc(&[]);
        assert_eq!(d.get_float_list("embedding"), None);
    }

    #[test]
    fn get_float_list_non_numeric_element_is_none() {
        let d = doc(&[("embedding", json!([1.0, "oops", 3.0]))]);
        assert_eq!(d.get_float_list("embedding"), None);
    }

    #[test]
    fn get_id_string_handles_string_and_number() {
        let d = doc(&[("id", json!("abc")), ("num_id", json!(42))]);
        assert_eq!(d.get_id_string("id"), Some("abc".to_string()));
        assert_eq!(d.get_id_string("num_id"), Some("42".to_string()));
        assert_eq!(d.get_id_string("missing"), None);
    }

    #[test]
    fn get_string_list_reads_string_array() {
        let d = doc(&[("ground_truth", json!(["a", "b", "c"]))]);
        assert_eq!(
            d.get_string_list("ground_truth"),
            Some(vec!["a".to_string(), "b".to_string(), "c".to_string()])
        );
    }

    #[test]
    fn query_params_round_trip() {
        let mut p = QueryParams::default();
        p.put("size", 10);
        p.put("query_vector", json!([1.0, 2.0]));
        assert_eq!(p.get_integer("size"), Some(10));
        assert_eq!(p.get_float_list("query_vector"), Some(vec![1.0, 2.0]));
        assert_eq!(p.get_integer("missing"), None);
    }

    #[test]
    fn query_params_typed_vectors_round_trip_without_json() {
        let mut p = QueryParams::default();
        p.put_vector("query_vector", std::sync::Arc::from(vec![1.0_f32, 2.5]));
        assert_eq!(p.get_float_list("query_vector"), Some(vec![1.0, 2.5]));
    }

    #[test]
    fn query_params_typed_vector_takes_precedence_over_a_json_value() {
        let mut p = QueryParams::default();
        p.put("query_vector", json!([9.0]));
        p.put_vector("query_vector", std::sync::Arc::from(vec![1.0_f32]));
        assert_eq!(p.get_float_list("query_vector"), Some(vec![1.0]));
    }

    #[test]
    fn benchmark_result_accumulates_metrics_and_metadata() {
        let mut r = BenchmarkResult::new(
            "run-1",
            "qdrant",
            "1.18.0",
            "vector",
            "demo",
            "hnsw_ef=32",
            HashMap::new(),
        );
        r.add_metric("recall", 0.95);
        r.add_metadata("recall_label", "recall@10");
        assert_eq!(r.metrics.get("recall"), Some(&0.95));
        assert_eq!(
            r.metadata.get("recall_label"),
            Some(&"recall@10".to_string())
        );
        assert!(r.schema.is_none());
    }

    #[test]
    fn benchmark_result_serializes_round_trip_through_json() {
        let mut r = BenchmarkResult::new(
            "run-1", "qdrant", "1.18.0", "vector", "demo", "k=1", HashMap::new(),
        );
        r.add_metric("recall", 1.0);
        let json_str = serde_json::to_string(&r).unwrap();
        let back: BenchmarkResult = serde_json::from_str(&json_str).unwrap();
        assert_eq!(back.run_id, "run-1");
        assert_eq!(back.metrics.get("recall"), Some(&1.0));
    }
}
