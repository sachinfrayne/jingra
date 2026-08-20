//! Qdrant engine implementation: gRPC via the official `qdrant-client` crate for everything
//! except collection creation, which goes straight to Qdrant's REST API as a JSON passthrough
//! (see [`QdrantEngine::create_index`]) so new Qdrant schema/quantization options work without
//! code changes here.

use super::{load_query_template, load_schema_template, unwrap_template, BenchmarkEngine, EngineError};
use crate::model::{Document, QueryParams, QueryResponse};
use async_trait::async_trait;
use qdrant_client::qdrant::{
    Condition, CreateFieldIndexCollectionBuilder, FieldType, Filter, PointId, PointStruct,
    QueryPointsBuilder, QuantizationSearchParamsBuilder, SearchParamsBuilder,
};
use qdrant_client::{Payload, Qdrant};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, OnceLock, RwLock};
use std::time::{Duration, Instant};
use tonic::Code;
use uuid::Uuid;

const DEFAULT_GRPC_TIMEOUT_SECS: u64 = 30;
const DEFAULT_VECTOR_FIELD: &str = "search_catalog_embedding";
const DEFAULT_POLL_INTERVAL_MS: u64 = 10_000;

pub struct QdrantEngine {
    config: HashMap<String, Value>,
    client: OnceLock<Qdrant>,
    rest_base_url: OnceLock<String>,
    rest_api_key: OnceLock<String>,
    http: OnceLock<reqwest::Client>,
    compiled_templates: RwLock<HashMap<String, Arc<CompiledQueryTemplate>>>,
    grpc_timeout: Duration,
    /// Hook for tests to shrink the awaitIndexReady poll interval; production default is 10s.
    poll_interval_ms: u64,
}

impl QdrantEngine {
    pub fn new(config: HashMap<String, Value>) -> Self {
        let grpc_timeout_secs = config
            .get("grpc_timeout_seconds")
            .and_then(Value::as_u64)
            .unwrap_or(DEFAULT_GRPC_TIMEOUT_SECS);
        Self {
            config,
            client: OnceLock::new(),
            rest_base_url: OnceLock::new(),
            rest_api_key: OnceLock::new(),
            http: OnceLock::new(),
            compiled_templates: RwLock::new(HashMap::new()),
            grpc_timeout: Duration::from_secs(grpc_timeout_secs),
            poll_interval_ms: DEFAULT_POLL_INTERVAL_MS,
        }
    }

    /// Overrides the `await_index_ready` poll interval (production default: 10s). Exposed for
    /// tests so they don't have to wait out the real interval.
    pub fn with_poll_interval_ms(mut self, ms: u64) -> Self {
        self.poll_interval_ms = ms;
        self
    }

    fn config_string(&self, key: &str, default: &str) -> String {
        self.config
            .get(key)
            .and_then(Value::as_str)
            .unwrap_or(default)
            .to_string()
    }

    fn config_string_opt(&self, key: &str) -> Option<String> {
        self.config.get(key).and_then(Value::as_str).map(str::to_string)
    }

    fn require_client(&self) -> Result<&Qdrant, EngineError> {
        self.client
            .get()
            .ok_or_else(|| EngineError::Message("Qdrant client not initialized".into()))
    }

    fn require_rest(&self) -> Result<(&reqwest::Client, &str, Option<&str>), EngineError> {
        let http = self
            .http
            .get()
            .ok_or_else(|| EngineError::Message("Qdrant client not initialized".into()))?;
        let base = self
            .rest_base_url
            .get()
            .ok_or_else(|| EngineError::Message("Qdrant client not initialized".into()))?;
        let key = self.rest_api_key.get().map(String::as_str).filter(|k| !k.is_empty());
        Ok((http, base, key))
    }

    fn get_or_compile_template(&self, query_name: &str) -> Result<Arc<CompiledQueryTemplate>, EngineError> {
        if let Some(existing) = self.compiled_templates.read().unwrap().get(query_name) {
            return Ok(existing.clone());
        }
        let doc = load_query_template(query_name)?
            .ok_or_else(|| EngineError::Message(format!("Query template '{query_name}' not found")))?;
        let compiled = Arc::new(CompiledQueryTemplate::compile(&doc));
        self.compiled_templates
            .write()
            .unwrap()
            .insert(query_name.to_string(), compiled.clone());
        Ok(compiled)
    }
}

#[async_trait]
impl BenchmarkEngine for QdrantEngine {
    async fn connect(&self) -> Result<(), EngineError> {
        let url = match self.config_string_opt("url") {
            Some(u) => u,
            None => {
                let url_env = self.config_string("url_env", "QDRANT_URL");
                std::env::var(&url_env)
                    .map_err(|_| EngineError::Message(format!("Qdrant URL not set in config or environment: {url_env}")))?
            }
        };
        let api_key = self.config_string_opt("api_key").or_else(|| {
            let api_key_env = self.config_string("api_key_env", "QDRANT_API_KEY");
            std::env::var(&api_key_env).ok()
        });

        let use_tls = url.starts_with("https://");
        let host_port = url.trim_start_matches("https://").trim_start_matches("http://");
        let (host, port) = match host_port.split_once(':') {
            Some((h, p)) => (
                h.to_string(),
                p.parse::<u16>()
                    .map_err(|_| EngineError::Message(format!("Invalid Qdrant port in URL: {url}")))?,
            ),
            None => (host_port.to_string(), 6334),
        };

        let mut builder = Qdrant::from_url(&url).timeout(self.grpc_timeout);
        if let Some(key) = &api_key {
            builder = builder.api_key(key.clone());
        }
        let client = builder
            .build()
            .map_err(|e| EngineError::Message(format!("Failed to connect to Qdrant: {e}")))?;
        client
            .list_collections()
            .await
            .map_err(|e| EngineError::Message(format!("Failed to connect to Qdrant: {e}")))?;

        let rest_base_url = match self.config_string_opt("rest_url") {
            Some(u) => u,
            None => format!("{}://{}:6333", if use_tls { "https" } else { "http" }, host),
        };

        let _ = self.client.set(client);
        let _ = self.rest_base_url.set(rest_base_url);
        let _ = self.rest_api_key.set(api_key.unwrap_or_default());
        let _ = self.http.set(reqwest::Client::new());
        let _ = port; // parsed for validation; gRPC port itself is embedded in `url` already
        Ok(())
    }

    async fn create_index(&self, index_name: &str, schema_name: &str) -> Result<(), EngineError> {
        let (http, base, api_key) = self.require_rest()?;

        if self.index_exists(index_name).await? {
            return Err(EngineError::Message(format!("Collection '{index_name}' already exists")));
        }

        let doc = load_schema_template(schema_name)?
            .ok_or_else(|| EngineError::Message(format!("Schema template '{schema_name}' not found")))?;
        let template = unwrap_template(&doc);

        let schema_for_rest = if template.get("vectors").is_some() {
            normalize_vector_distance(template)
        } else {
            return Err(EngineError::Message(format!(
                "Schema must have 'vectors' (direct Qdrant format). Schema: {template}"
            )));
        };

        let mut req = http.put(format!("{base}/collections/{index_name}")).json(&schema_for_rest);
        if let Some(key) = api_key {
            req = req.header("api-key", key);
        }
        let response = req
            .send()
            .await
            .map_err(|e| EngineError::Message(format!("Failed to create collection '{index_name}': {e}")))?;
        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            return Err(EngineError::Message(format!(
                "Qdrant REST PUT /collections/{index_name} returned HTTP {status}: {body}"
            )));
        }

        if let Some(indexes) = template.get("payload_indexes").and_then(Value::as_array) {
            for spec in indexes {
                let field_name = spec
                    .get("field_name")
                    .and_then(Value::as_str)
                    .ok_or_else(|| EngineError::Message("payload_indexes entry missing field_name".into()))?;
                let field_schema = spec
                    .get("field_schema")
                    .and_then(Value::as_str)
                    .ok_or_else(|| EngineError::Message("payload_indexes entry missing field_schema".into()))?;
                self.create_payload_index(index_name, field_name, field_schema).await?;
            }
        }

        Ok(())
    }

    async fn index_exists(&self, index_name: &str) -> Result<bool, EngineError> {
        let client = self.require_client()?;
        client
            .collection_exists(index_name)
            .await
            .map_err(|e| EngineError::Message(format!("Failed to check if collection exists: {e}")))
    }

    async fn delete_index(&self, index_name: &str) -> Result<(), EngineError> {
        let client = self.require_client()?;
        match client.delete_collection(index_name).await {
            Ok(_) => Ok(()),
            Err(qdrant_client::QdrantError::ResponseError { status }) if status.code() == Code::NotFound => Ok(()),
            Err(e) => Err(EngineError::Message(format!("Failed to delete collection '{index_name}': {e}"))),
        }
    }

    async fn ingest(
        &self,
        documents: Vec<Document>,
        index_name: &str,
        id_field: Option<&str>,
    ) -> Result<usize, EngineError> {
        let client = self.require_client()?;
        let vector_field = self.config_string("vector_field", DEFAULT_VECTOR_FIELD);

        let mut points = Vec::with_capacity(documents.len());
        for doc in documents {
            let vector = match doc.get_float_list(&vector_field) {
                Some(v) => v,
                None => continue, // matches Java: log+skip documents missing the vector field
            };
            let point_id = resolve_point_id(id_field.and_then(|f| doc.get_id_string(f)));
            let mut fields = doc.into_fields();
            fields.remove(&vector_field);
            let payload: Payload = fields.into();
            points.push(PointStruct::new(point_id, vector, payload));
        }

        let count = points.len();
        client
            .upsert_points(qdrant_client::qdrant::UpsertPointsBuilder::new(index_name, points))
            .await
            .map_err(|e| EngineError::Message(format!("Failed to ingest documents: {e}")))?;
        Ok(count)
    }

    async fn query(
        &self,
        index_name: &str,
        query_name: &str,
        params: &QueryParams,
    ) -> Result<QueryResponse, EngineError> {
        let client = self.require_client()?;
        let compiled = self.get_or_compile_template(query_name)?;

        let query_vector = compiled
            .vector
            .resolve(params)
            .or_else(|| params.get_float_list("query_vector"))
            .ok_or_else(|| EngineError::Message("query_vector is required for Qdrant search".into()))?;
        let limit = compiled
            .limit
            .resolve(params)
            .or_else(|| params.get_integer("size"))
            .unwrap_or(10)
            .max(0) as u64;

        let mut search_params = SearchParamsBuilder::default();
        if compiled.exact {
            search_params = search_params.exact(true);
        } else {
            if let Some(ef) = compiled.hnsw_ef.resolve(params) {
                search_params = search_params.hnsw_ef(ef.max(0) as u64);
            }
            if let Some(oversampling) = compiled.quant_oversampling.resolve(params) {
                search_params = search_params.quantization(
                    QuantizationSearchParamsBuilder::default()
                        .rescore(compiled.quant_rescore)
                        .oversampling(oversampling),
                );
            }
        }

        let mut builder = QueryPointsBuilder::new(index_name)
            .query(query_vector)
            .limit(limit)
            .params(search_params)
            .with_payload(compiled.with_payload)
            .with_vectors(compiled.with_vector);
        if let Some(filter) = compiled.static_filter.clone() {
            builder = builder.filter(filter);
        }

        let start = Instant::now();
        let response = client
            .query(builder)
            .await
            .map_err(|e| EngineError::Message(format!("Query execution failed: {e}")))?;
        let client_latency_ms = start.elapsed().as_secs_f64() * 1000.0;
        let server_latency_ms = (response.time * 1000.0).round() as i64;

        let document_ids = response
            .result
            .iter()
            .map(|p| point_id_to_string(p.id.as_ref()))
            .collect();

        Ok(QueryResponse::new(
            document_ids,
            Some(client_latency_ms),
            Some(server_latency_ms),
        ))
    }

    async fn document_count(&self, index_name: &str) -> Result<u64, EngineError> {
        let client = self.require_client()?;
        let info = client
            .collection_info(index_name)
            .await
            .map_err(|e| EngineError::Message(format!("Failed to get document count: {e}")))?;
        Ok(info.result.and_then(|r| r.points_count).unwrap_or(0))
    }

    fn engine_name(&self) -> &'static str {
        "qdrant"
    }

    fn short_name(&self) -> &'static str {
        "qd"
    }

    async fn version(&self) -> Result<String, EngineError> {
        let client = self.require_client()?;
        let health = client
            .health_check()
            .await
            .map_err(|e| EngineError::Message(format!("Failed to get version: {e}")))?;
        Ok(health.version)
    }

    async fn index_metadata(&self, index_name: &str) -> Result<HashMap<String, String>, EngineError> {
        let client = self.require_client()?;
        let mut metadata = HashMap::new();
        let info = client
            .collection_info(index_name)
            .await
            .map_err(|e| EngineError::Message(format!("Failed to get collection metadata: {e}")))?;
        if let Some(result) = info.result
            && let Some(count) = result.points_count
        {
            metadata.insert("points_count".to_string(), count.to_string());
        }
        Ok(metadata)
    }

    fn schema_template(&self, schema_name: &str) -> Result<Option<HashMap<String, Value>>, EngineError> {
        let Some(doc) = load_schema_template(schema_name)? else {
            return Ok(None);
        };
        let template = unwrap_template(&doc);
        match template {
            Value::Object(map) => Ok(Some(map.clone().into_iter().collect())),
            _ => Ok(None),
        }
    }

    async fn await_index_ready(&self, index_name: &str) -> Result<(), EngineError> {
        use qdrant_client::qdrant::CollectionStatus;

        let client = self.require_client()?;
        loop {
            let info = client
                .collection_info(index_name)
                .await
                .map_err(|e| EngineError::Message(format!("Error polling Qdrant optimizer status for collection '{index_name}': {e}")))?;
            let Some(result) = info.result else {
                return Err(EngineError::Message(format!(
                    "Qdrant returned no collection info for '{index_name}'"
                )));
            };
            let green = result.status == CollectionStatus::Green as i32;
            let ok = result.optimizer_status.map(|s| s.ok).unwrap_or(false);
            if green && ok {
                return Ok(());
            }
            tokio::time::sleep(Duration::from_millis(self.poll_interval_ms)).await;
        }
    }
}

impl QdrantEngine {
    async fn create_payload_index(&self, index_name: &str, field_name: &str, field_schema: &str) -> Result<(), EngineError> {
        let client = self.require_client()?;
        let field_type = match field_schema.to_lowercase().as_str() {
            "integer" => FieldType::Integer,
            "float" => FieldType::Float,
            "bool" | "boolean" => FieldType::Bool,
            "geo" => FieldType::Geo,
            "text" => FieldType::Text,
            _ => FieldType::Keyword,
        };
        client
            .create_field_index(CreateFieldIndexCollectionBuilder::new(index_name, field_name, field_type))
            .await
            .map_err(|e| EngineError::Message(format!("Failed to create payload index on '{field_name}': {e}")))?;
        Ok(())
    }
}

/// Resolves a document's natural id (if present and parseable) into a Qdrant point ID,
/// falling back to a deterministic name-based UUID (stable across re-ingests of the same
/// document) and finally a random UUID when there is no natural id at all.
///
/// Not byte-identical to the Java version's `UUID.nameUUIDFromBytes` fallback (which is an
/// MD5-based name-UUID with no namespace) -- that exact scheme isn't reproducible without an
/// extra MD5 dependency, and cross-language ID equality was never a requirement. `Uuid::new_v5`
/// with a fixed namespace gives the same practical property: the same input id string always
/// maps to the same point ID within one ingest run.
fn resolve_point_id(id_str: Option<String>) -> PointId {
    match id_str {
        Some(s) => {
            if let Ok(num) = s.parse::<u64>() {
                PointId::from(num)
            } else if let Ok(uuid) = Uuid::parse_str(&s) {
                PointId::from(uuid)
            } else {
                PointId::from(Uuid::new_v5(&Uuid::NAMESPACE_OID, s.as_bytes()))
            }
        }
        None => PointId::from(Uuid::new_v4()),
    }
}

fn point_id_to_string(id: Option<&PointId>) -> String {
    use qdrant_client::qdrant::point_id::PointIdOptions;
    match id.and_then(|p| p.point_id_options.as_ref()) {
        Some(PointIdOptions::Num(n)) => n.to_string(),
        Some(PointIdOptions::Uuid(u)) => u.clone(),
        None => String::new(),
    }
}

/// Normalizes `vectors.distance` to Qdrant's capitalized REST form (`"dot"` -> `"Dot"`,
/// `"l2"`/`"euclidean"` -> `"Euclid"`, unknown values default to `"Cosine"`) so schema files can
/// use lowercase aliases.
fn normalize_vector_distance(template: &Value) -> Value {
    let mut copy = template.clone();
    let Some(vectors) = copy.get_mut("vectors") else {
        return copy;
    };
    let Some(raw) = vectors.get("distance").and_then(Value::as_str) else {
        return copy;
    };
    let normalized = normalize_distance_name(raw);
    vectors["distance"] = Value::String(normalized);
    copy
}

fn normalize_distance_name(raw: &str) -> String {
    match raw.to_lowercase().as_str() {
        "cosine" => "Cosine",
        "euclid" | "euclidean" | "l2" => "Euclid",
        "dot" => "Dot",
        "manhattan" => "Manhattan",
        _ => "Cosine",
    }
    .to_string()
}

#[derive(Debug, Clone, PartialEq)]
struct IntExpr {
    literal: Option<i64>,
    param: Option<String>,
}

impl IntExpr {
    fn none() -> Self {
        Self { literal: None, param: None }
    }

    fn resolve(&self, params: &QueryParams) -> Option<i64> {
        self.literal.or_else(|| self.param.as_deref().and_then(|p| params.get_integer(p)))
    }

    fn compile(expr: Option<&str>) -> Self {
        let Some(expr) = expr else { return Self::none() };
        let trimmed = expr.trim();
        if let Some(inner) = trimmed.strip_prefix("{{").and_then(|s| s.strip_suffix("}}")) {
            let name = inner.trim();
            Self {
                literal: None,
                param: if name.is_empty() { None } else { Some(name.to_string()) },
            }
        } else if let Ok(v) = trimmed.parse::<i64>() {
            Self { literal: Some(v), param: None }
        } else {
            Self::none()
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
struct DoubleExpr {
    literal: Option<f64>,
    param: Option<String>,
}

impl DoubleExpr {
    fn none() -> Self {
        Self { literal: None, param: None }
    }

    fn resolve(&self, params: &QueryParams) -> Option<f64> {
        self.literal.or_else(|| self.param.as_deref().and_then(|p| params.get_f64(p)))
    }

    fn compile(expr: Option<&str>) -> Self {
        let Some(expr) = expr else { return Self::none() };
        let trimmed = expr.trim();
        if let Some(inner) = trimmed.strip_prefix("{{").and_then(|s| s.strip_suffix("}}")) {
            let name = inner.trim();
            Self {
                literal: None,
                param: if name.is_empty() { None } else { Some(name.to_string()) },
            }
        } else if let Ok(v) = trimmed.parse::<f64>() {
            Self { literal: Some(v), param: None }
        } else {
            Self::none()
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
struct VectorExpr {
    literal: Option<Vec<f32>>,
    param: Option<String>,
}

impl VectorExpr {
    fn none() -> Self {
        Self { literal: None, param: None }
    }

    fn resolve(&self, params: &QueryParams) -> Option<Vec<f32>> {
        if let Some(v) = &self.literal {
            return Some(v.clone());
        }
        self.param.as_deref().and_then(|p| params.get_float_list(p))
    }

    fn compile(node: Option<&Value>) -> Self {
        match node {
            None | Some(Value::Null) => Self::none(),
            Some(Value::String(s)) => {
                let trimmed = s.trim();
                match trimmed.strip_prefix("{{").and_then(|x| x.strip_suffix("}}")) {
                    Some(inner) => {
                        let name = inner.trim();
                        Self {
                            literal: None,
                            param: if name.is_empty() { None } else { Some(name.to_string()) },
                        }
                    }
                    None => Self::none(),
                }
            }
            Some(Value::Array(arr)) => {
                let mut v = Vec::with_capacity(arr.len());
                for e in arr {
                    match e.as_f64() {
                        Some(f) => v.push(f as f32),
                        None => return Self::none(),
                    }
                }
                Self { literal: Some(v), param: None }
            }
            _ => Self::none(),
        }
    }
}

/// Turns a JSON scalar into the `{{param}}`-or-literal expression text `IntExpr`/`DoubleExpr`
/// compile from — mirrors Jackson's permissive `JsonNode.asText()` for numbers.
fn json_scalar_to_text(node: Option<&Value>) -> Option<String> {
    match node {
        Some(Value::String(s)) => Some(s.clone()),
        Some(Value::Number(n)) => Some(n.to_string()),
        _ => None,
    }
}

#[derive(Debug, Clone)]
struct CompiledQueryTemplate {
    vector: VectorExpr,
    limit: IntExpr,
    hnsw_ef: IntExpr,
    exact: bool,
    quant_rescore: bool,
    quant_oversampling: DoubleExpr,
    static_filter: Option<Filter>,
    with_payload: bool,
    with_vector: bool,
}

impl CompiledQueryTemplate {
    fn compile(doc: &Value) -> Self {
        let template = unwrap_template(doc);

        let vector = VectorExpr::compile(template.get("vector"));
        let limit = IntExpr::compile(json_scalar_to_text(template.get("limit")).as_deref());

        let params = template.get("params");
        let exact = params.and_then(|p| p.get("exact")).and_then(Value::as_bool).unwrap_or(false);

        let mut hnsw_ef = IntExpr::none();
        let mut quant_rescore = true;
        let mut quant_oversampling = DoubleExpr::none();
        if !exact
            && let Some(p) = params
        {
            hnsw_ef = IntExpr::compile(json_scalar_to_text(p.get("hnsw_ef")).as_deref());
            if let Some(q) = p.get("quantization") {
                quant_rescore = q.get("rescore").and_then(Value::as_bool).unwrap_or(true);
                quant_oversampling = DoubleExpr::compile(json_scalar_to_text(q.get("oversampling")).as_deref());
            }
        }

        let static_filter = template.get("filter").and_then(parse_filter);
        let with_payload = template.get("with_payload").and_then(Value::as_bool).unwrap_or(false);
        let with_vector = template.get("with_vector").and_then(Value::as_bool).unwrap_or(false);

        Self {
            vector,
            limit,
            hnsw_ef,
            exact,
            quant_rescore,
            quant_oversampling,
            static_filter,
            with_payload,
            with_vector,
        }
    }
}

/// Parses `{must: [{key, match: {value}}]}` into a Qdrant `Filter`. Returns `None` if there are
/// no valid `must` conditions.
fn parse_filter(filter_node: &Value) -> Option<Filter> {
    let must = filter_node.get("must")?.as_array()?;
    let mut conditions = Vec::new();
    for condition in must {
        let key = condition.get("key")?.as_str()?;
        let value = condition.get("match")?.get("value")?;
        let match_value = build_match_value(value)?;
        conditions.push(Condition::matches(key, match_value));
    }
    if conditions.is_empty() {
        None
    } else {
        Some(Filter::must(conditions))
    }
}

fn build_match_value(value: &Value) -> Option<qdrant_client::qdrant::r#match::MatchValue> {
    use qdrant_client::qdrant::r#match::MatchValue;
    match value {
        Value::Bool(b) => Some(MatchValue::from(*b)),
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Some(MatchValue::from(i))
            } else {
                n.as_f64().map(|f| {
                    if f == f.floor() {
                        MatchValue::from(f as i64)
                    } else {
                        MatchValue::from(f.to_string())
                    }
                })
            }
        }
        Value::String(s) => Some(MatchValue::from(s.clone())),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn params_with(pairs: &[(&str, Value)]) -> QueryParams {
        let mut p = QueryParams::default();
        for (k, v) in pairs {
            p.put(k.to_string(), v.clone());
        }
        p
    }

    #[test]
    fn int_expr_literal() {
        let e = IntExpr::compile(Some("42"));
        assert_eq!(e.resolve(&QueryParams::default()), Some(42));
    }

    #[test]
    fn int_expr_param_placeholder() {
        let e = IntExpr::compile(Some("{{hnsw_ef}}"));
        let params = params_with(&[("hnsw_ef", json!(128))]);
        assert_eq!(e.resolve(&params), Some(128));
        assert_eq!(e.resolve(&QueryParams::default()), None);
    }

    #[test]
    fn int_expr_empty_placeholder_and_garbage_resolve_to_none() {
        assert_eq!(IntExpr::compile(Some("{{}}")).resolve(&QueryParams::default()), None);
        assert_eq!(IntExpr::compile(Some("not-a-number")).resolve(&QueryParams::default()), None);
        assert_eq!(IntExpr::compile(None).resolve(&QueryParams::default()), None);
    }

    #[test]
    fn double_expr_literal_and_param() {
        let literal = DoubleExpr::compile(Some("1.5"));
        assert_eq!(literal.resolve(&QueryParams::default()), Some(1.5));

        let param = DoubleExpr::compile(Some("{{oversampling}}"));
        let params = params_with(&[("oversampling", json!(2.4))]);
        assert_eq!(param.resolve(&params), Some(2.4));
    }

    #[test]
    fn vector_expr_literal_array() {
        let e = VectorExpr::compile(Some(&json!([1.0, 2.0, 3.0])));
        assert_eq!(e.resolve(&QueryParams::default()), Some(vec![1.0, 2.0, 3.0]));
    }

    #[test]
    fn vector_expr_non_numeric_array_is_none() {
        let e = VectorExpr::compile(Some(&json!([1.0, "oops"])));
        assert_eq!(e.resolve(&QueryParams::default()), None);
    }

    #[test]
    fn vector_expr_param_placeholder() {
        let e = VectorExpr::compile(Some(&json!("{{query_vector}}")));
        let params = params_with(&[("query_vector", json!([4.0, 5.0]))]);
        assert_eq!(e.resolve(&params), Some(vec![4.0, 5.0]));
    }

    #[test]
    fn compiled_query_template_from_demo_style_json() {
        let doc = json!({
            "vector": "{{query_vector}}",
            "limit": "{{size}}",
            "with_payload": false,
            "with_vector": false,
            "params": {
                "hnsw_ef": "{{hnsw_ef}}",
                "quantization": {"rescore": true, "oversampling": "{{oversampling}}"}
            }
        });
        let compiled = CompiledQueryTemplate::compile(&doc);
        let params = params_with(&[
            ("query_vector", json!([1.0, 2.0])),
            ("size", json!(10)),
            ("hnsw_ef", json!(64)),
            ("oversampling", json!(2.0)),
        ]);
        assert_eq!(compiled.vector.resolve(&params), Some(vec![1.0, 2.0]));
        assert_eq!(compiled.limit.resolve(&params), Some(10));
        assert!(!compiled.exact);
        assert_eq!(compiled.hnsw_ef.resolve(&params), Some(64));
        assert!(compiled.quant_rescore);
        assert_eq!(compiled.quant_oversampling.resolve(&params), Some(2.0));
        assert!(!compiled.with_payload);
        assert!(!compiled.with_vector);
    }

    #[test]
    fn compiled_query_template_exact_skips_hnsw_and_quantization() {
        let doc = json!({"vector": [1.0], "limit": 5, "params": {"exact": true, "hnsw_ef": "10"}});
        let compiled = CompiledQueryTemplate::compile(&doc);
        assert!(compiled.exact);
        assert_eq!(compiled.hnsw_ef.resolve(&QueryParams::default()), None);
    }

    #[test]
    fn distance_normalization_covers_all_known_aliases_and_defaults_unknown_to_cosine() {
        assert_eq!(normalize_distance_name("cosine"), "Cosine");
        assert_eq!(normalize_distance_name("Cosine"), "Cosine");
        assert_eq!(normalize_distance_name("dot"), "Dot");
        assert_eq!(normalize_distance_name("l2"), "Euclid");
        assert_eq!(normalize_distance_name("euclidean"), "Euclid");
        assert_eq!(normalize_distance_name("euclid"), "Euclid");
        assert_eq!(normalize_distance_name("manhattan"), "Manhattan");
        assert_eq!(normalize_distance_name("something-else"), "Cosine");
    }

    #[test]
    fn normalize_vector_distance_rewrites_only_the_distance_field() {
        let template = json!({"vectors": {"size": 384, "distance": "l2"}, "payload_indexes": []});
        let normalized = normalize_vector_distance(&template);
        assert_eq!(normalized["vectors"]["distance"], json!("Euclid"));
        assert_eq!(normalized["vectors"]["size"], json!(384));
        assert_eq!(normalized["payload_indexes"], json!([]));
    }

    #[test]
    fn normalize_vector_distance_no_op_without_vectors_field() {
        let template = json!({"mappings": {}});
        assert_eq!(normalize_vector_distance(&template), template);
    }

    #[test]
    fn parse_filter_builds_must_conditions() {
        let filter_node = json!({
            "must": [
                {"key": "category", "match": {"value": "shoes"}},
                {"key": "in_stock", "match": {"value": true}},
                {"key": "rating", "match": {"value": 5}}
            ]
        });
        let filter = parse_filter(&filter_node).unwrap();
        assert_eq!(filter.must.len(), 3);
    }

    #[test]
    fn parse_filter_returns_none_without_must() {
        assert!(parse_filter(&json!({})).is_none());
        assert!(parse_filter(&json!({"must": []})).is_none());
    }

    #[test]
    fn parse_filter_skips_malformed_conditions_entirely_when_key_missing() {
        // A condition missing `key` fails to parse; the whole filter parse bails via `?`,
        // matching the Java behavior of only ever emitting well-formed conditions.
        let filter_node = json!({"must": [{"match": {"value": "x"}}]});
        assert!(parse_filter(&filter_node).is_none());
    }

    #[test]
    fn resolve_point_id_prefers_numeric_then_uuid_then_deterministic_then_random() {
        use qdrant_client::qdrant::point_id::PointIdOptions;

        let numeric = resolve_point_id(Some("42".to_string()));
        assert_eq!(numeric.point_id_options, Some(PointIdOptions::Num(42)));

        let uuid_str = "550e8400-e29b-41d4-a716-446655440000";
        let uuid = resolve_point_id(Some(uuid_str.to_string()));
        assert_eq!(uuid.point_id_options, Some(PointIdOptions::Uuid(uuid_str.to_string())));

        let deterministic_a = resolve_point_id(Some("catalog-123".to_string()));
        let deterministic_b = resolve_point_id(Some("catalog-123".to_string()));
        assert_eq!(deterministic_a.point_id_options, deterministic_b.point_id_options);
        assert!(matches!(deterministic_a.point_id_options, Some(PointIdOptions::Uuid(_))));

        let random_a = resolve_point_id(None);
        let random_b = resolve_point_id(None);
        assert_ne!(random_a.point_id_options, random_b.point_id_options);
    }

    #[test]
    fn point_id_to_string_handles_both_variants_and_missing() {
        assert_eq!(point_id_to_string(Some(&PointId::from(7u64))), "7");
        assert_eq!(
            point_id_to_string(Some(&PointId::from(Uuid::nil()))),
            Uuid::nil().to_string()
        );
        assert_eq!(point_id_to_string(None), "");
    }

    #[test]
    fn engine_name_and_short_name() {
        let engine = QdrantEngine::new(HashMap::new());
        assert_eq!(engine.engine_name(), "qdrant");
        assert_eq!(engine.short_name(), "qd");
    }
}
