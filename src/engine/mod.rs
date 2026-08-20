//! The engine abstraction: [`BenchmarkEngine`] is implemented once per search backend.
//! Only Qdrant is implemented today; the trait boundary is what lets Elasticsearch/OpenSearch
//! (or others) be added later without touching the evaluation/CLI layers.

pub mod qdrant;

use crate::model::{Document, QueryParams, QueryResponse};
use async_trait::async_trait;
use serde_json::Value;
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    #[error("{0}")]
    Message(String),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
}

/// Core abstraction for benchmarkable engines.
#[async_trait]
pub trait BenchmarkEngine: Send + Sync {
    /// Connect to the engine. Implementations manage their own interior mutability so this can
    /// be called once on a shared `Arc<dyn BenchmarkEngine>` before concurrent use.
    async fn connect(&self) -> Result<(), EngineError>;

    /// Create an index/collection with the given schema template name.
    async fn create_index(&self, index_name: &str, schema_name: &str) -> Result<(), EngineError>;

    async fn index_exists(&self, index_name: &str) -> Result<bool, EngineError>;

    async fn delete_index(&self, index_name: &str) -> Result<(), EngineError>;

    /// Ingest documents, returning the number successfully ingested.
    async fn ingest(
        &self,
        documents: Vec<Document>,
        index_name: &str,
        id_field: Option<&str>,
    ) -> Result<usize, EngineError>;

    async fn query(
        &self,
        index_name: &str,
        query_name: &str,
        params: &QueryParams,
    ) -> Result<QueryResponse, EngineError>;

    async fn document_count(&self, index_name: &str) -> Result<u64, EngineError>;

    fn engine_name(&self) -> &'static str;

    fn short_name(&self) -> &'static str;

    async fn version(&self) -> Result<String, EngineError>;

    async fn index_metadata(&self, index_name: &str) -> Result<HashMap<String, String>, EngineError>;

    /// The schema template's `template` object (or the whole document if untagged), for
    /// recording alongside benchmark results.
    fn schema_template(&self, schema_name: &str) -> Result<Option<HashMap<String, Value>>, EngineError>;

    /// Wait until the index is ready for querying (background optimization/merges settled).
    /// Engines that don't need to wait keep the default no-op.
    async fn await_index_ready(&self, _index_name: &str) -> Result<(), EngineError> {
        Ok(())
    }
}

/// Relative directory for on-disk schema/query overrides, same layout for every engine:
/// `jingra-config/schemas/*.json` and `jingra-config/queries/*.json`.
pub const JINGRA_CONFIG_DIR: &str = "jingra-config";

/// Loads a schema template JSON file by name from `jingra-config/schemas/{name}.json`.
/// Returns `Ok(None)` if the file doesn't exist (distinct from a JSON parse error).
pub fn load_schema_template(schema_name: &str) -> Result<Option<Value>, EngineError> {
    load_template("schemas", schema_name)
}

/// Loads a query template JSON file by name from `jingra-config/queries/{name}.json`.
pub fn load_query_template(query_name: &str) -> Result<Option<Value>, EngineError> {
    load_template("queries", query_name)
}

fn load_template(subdir: &str, name: &str) -> Result<Option<Value>, EngineError> {
    let path = Path::new(JINGRA_CONFIG_DIR).join(subdir).join(format!("{name}.json"));
    if !path.exists() {
        return Ok(None);
    }
    let contents = std::fs::read_to_string(path)?;
    Ok(Some(serde_json::from_str(&contents)?))
}

/// Extracts the `template` object from a loaded schema/query JSON document, supporting both
/// the legacy Jingra-wrapped form (`{"template": {...}}`) and a direct/unwrapped document.
pub fn unwrap_template(doc: &Value) -> &Value {
    doc.get("template").unwrap_or(doc)
}

/// Creates the engine configured in `engine_name`. Only `"qdrant"` is recognized today.
pub fn create_engine(
    engine_name: &str,
    config: HashMap<String, Value>,
) -> Result<Box<dyn BenchmarkEngine>, EngineError> {
    match engine_name {
        "qdrant" => Ok(Box::new(self::qdrant::QdrantEngine::new(config))),
        other => Err(EngineError::Message(format!("Unknown engine: {other}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unwrap_template_prefers_wrapped_field() {
        let wrapped = serde_json::json!({"template": {"vectors": {"size": 4}}});
        assert_eq!(unwrap_template(&wrapped), &serde_json::json!({"vectors": {"size": 4}}));

        let direct = serde_json::json!({"vectors": {"size": 4}});
        assert_eq!(unwrap_template(&direct), &direct);
    }

    #[test]
    fn load_schema_template_missing_file_returns_none() {
        // Relies on there being no jingra-config/schemas/does-not-exist.json in the repo/CWD.
        assert!(load_schema_template("does-not-exist-xyz").unwrap().is_none());
    }

    #[test]
    fn create_engine_rejects_unknown_engine() {
        let result = create_engine("elasticsearch", HashMap::new());
        assert!(matches!(result, Err(EngineError::Message(_))));
    }

    #[test]
    fn create_engine_accepts_qdrant() {
        assert!(create_engine("qdrant", HashMap::new()).is_ok());
    }
}
