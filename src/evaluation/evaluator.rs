//! Runs warmup/measurement rounds for each configured parameter set, computes metrics, and
//! writes results to the configured sinks.

use super::metrics::{MetricsCalculator, QueryResult};
use crate::config::{DatasetConfig, EvaluationConfig, JingraConfig};
use crate::data::{self, DataError};
use crate::engine::{BenchmarkEngine, EngineError};
use crate::model::{BenchmarkResult, QueryParams, QueryResponse};
use crate::output::{OutputError, QueryMetricRecord, ResultsSink};
use chrono::Utc;
use futures::stream::{self, StreamExt};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

#[derive(Debug, thiserror::Error)]
pub enum EvaluationError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Data(#[from] DataError),
    #[error(transparent)]
    Engine(#[from] EngineError),
    #[error(transparent)]
    Output(#[from] OutputError),
    #[error("{count} warmup queries failed")]
    WarmupFailures { count: usize },
    #[error("measurement round {round} had {count} failed queries")]
    MeasurementFailures { round: usize, count: usize },
}

#[derive(Clone)]
struct QueryDocument {
    query_vector: Option<Vec<f32>>,
    query_text: Option<String>,
    ground_truth: Vec<String>,
    meta_conditions: Option<HashMap<String, Value>>,
}

pub struct BenchmarkEvaluator {
    engine: Arc<dyn BenchmarkEngine>,
    sinks: Vec<Arc<dyn ResultsSink>>,
    run_id: String,
}

/// Resolves the run id from `evaluation.run_id`, or generates a timestamp-based one. Exposed so
/// callers building sinks that need the run id up front (e.g. the object-store sink's storage
/// path) use the exact same value `BenchmarkEvaluator` will use, rather than each generating
/// their own timestamp and silently diverging.
pub fn resolve_run_id(config: &JingraConfig) -> String {
    config
        .evaluation
        .as_ref()
        .and_then(|e| e.run_id.clone())
        .unwrap_or_else(generate_run_id)
}

impl BenchmarkEvaluator {
    pub fn new(config: &JingraConfig, engine: Arc<dyn BenchmarkEngine>, sinks: Vec<Arc<dyn ResultsSink>>) -> Self {
        Self {
            engine,
            sinks,
            run_id: resolve_run_id(config),
        }
    }

    pub async fn run_evaluation(&self, config: &JingraConfig) -> Result<(), EvaluationError> {
        let dataset = config.active_dataset().map_err(|e| EvaluationError::Message(e.to_string()))?;
        let eval_config = config
            .evaluation
            .as_ref()
            .ok_or_else(|| EvaluationError::Message("evaluation section is required".into()))?;

        let queries_path = dataset
            .path
            .as_ref()
            .and_then(|p| p.queries_path.clone())
            .ok_or_else(|| EvaluationError::Message("dataset.path.queries_path is required".into()))?;
        let reader = data::create_reader(&queries_path);
        let documents = reader.read_all(None)?;
        let queries = parse_query_documents(&documents, dataset)?;
        tracing::info!("Loaded {} queries", queries.len());

        let param_groups = dataset
            .param_groups
            .as_ref()
            .ok_or_else(|| EvaluationError::Message("dataset.param_groups is required".into()))?;

        let dataset_name = config
            .dataset
            .clone()
            .ok_or_else(|| EvaluationError::Message("dataset not specified".into()))?;

        for (recall_label, params_list) in param_groups {
            for params in params_list {
                self.evaluate_parameter_set(
                    &queries,
                    dataset,
                    &dataset_name,
                    eval_config,
                    recall_label,
                    params,
                )
                .await?;
            }
        }
        Ok(())
    }

    async fn evaluate_parameter_set(
        &self,
        queries: &[QueryDocument],
        dataset: &DatasetConfig,
        dataset_name: &str,
        eval_config: &EvaluationConfig,
        recall_label: &str,
        params: &HashMap<String, Value>,
    ) -> Result<(), EvaluationError> {
        let param_key = build_param_key(params);
        tracing::info!("Evaluating parameter set: {param_key}");

        for round in 1..=eval_config.warmup_rounds {
            tracing::info!("Warmup round {round}/{}", eval_config.warmup_rounds);
            let outcome = self
                .execute_queries(queries, dataset, params, eval_config.warmup_workers.max(1) as usize, false)
                .await;
            if outcome.failures > 0 {
                return Err(EvaluationError::WarmupFailures { count: outcome.failures });
            }
        }

        let mut all_results = Vec::new();
        let mut total_wall_ms: u64 = 0;
        for round in 1..=eval_config.measurement_rounds {
            tracing::info!("Measurement round {round}/{}", eval_config.measurement_rounds);
            let outcome = self
                .execute_queries(queries, dataset, params, eval_config.measurement_workers.max(1) as usize, true)
                .await;
            if outcome.failures > 0 {
                return Err(EvaluationError::MeasurementFailures {
                    round: round as usize,
                    count: outcome.failures,
                });
            }
            if !outcome.query_metrics.is_empty() {
                for sink in &self.sinks {
                    sink.write_query_metrics_batch(&outcome.query_metrics).await?;
                }
            }
            all_results.extend(outcome.results);
            total_wall_ms += outcome.wall_time_ms;
        }

        let ctx = ResultContext {
            dataset_name,
            recall_label,
            param_key: &param_key,
            params,
        };
        let result = self.calculate_metrics(&all_results, total_wall_ms, dataset, &ctx).await?;
        for sink in &self.sinks {
            sink.write_result(&result).await?;
        }
        Ok(())
    }

    async fn execute_queries(
        &self,
        queries: &[QueryDocument],
        dataset: &DatasetConfig,
        params: &HashMap<String, Value>,
        num_workers: usize,
        collect_results: bool,
    ) -> ExecuteQueriesOutcome {
        let index_name = dataset.index_name.clone().unwrap_or_default();
        let query_name = dataset.query_name.clone().unwrap_or_default();
        let start = Instant::now();

        let outcomes: Vec<Result<(QueryResult, Option<QueryMetricRecord>), EngineError>> = stream::iter(queries.iter())
            .map(|query| {
                let engine = self.engine.clone();
                let index_name = index_name.clone();
                let query_name = query_name.clone();
                let params = params.clone();
                let query = query.clone();
                let run_id = self.run_id.clone();
                async move { execute_single_query(&engine, &index_name, &query_name, &params, &query, &run_id).await }
            })
            .buffer_unordered(num_workers.max(1))
            .collect()
            .await;

        let mut results = Vec::new();
        let mut query_metrics = Vec::new();
        let mut failures = 0usize;
        for outcome in outcomes {
            match outcome {
                Ok((qr, metric)) => {
                    if collect_results {
                        results.push(qr);
                        if let Some(m) = metric {
                            query_metrics.push(m);
                        }
                    }
                }
                Err(e) => {
                    failures += 1;
                    tracing::error!("Query execution failed: {e}");
                }
            }
        }

        ExecuteQueriesOutcome {
            results,
            query_metrics,
            wall_time_ms: start.elapsed().as_millis() as u64,
            failures,
        }
    }

    async fn calculate_metrics(
        &self,
        results: &[QueryResult],
        total_wall_ms: u64,
        dataset: &DatasetConfig,
        ctx: &ResultContext<'_>,
    ) -> Result<BenchmarkResult, EvaluationError> {
        let calculator = MetricsCalculator::new(results, if total_wall_ms > 0 { Some(total_wall_ms) } else { None });
        let version = self.engine.version().await.unwrap_or_else(|_| "unknown".to_string());

        let mut result = BenchmarkResult::new(
            self.run_id.clone(),
            self.engine.engine_name(),
            version,
            dataset.dataset_type.clone().unwrap_or_default(),
            ctx.dataset_name,
            ctx.param_key,
            ctx.params.clone(),
        );

        result.add_metric("precision", calculator.precision());
        result.add_metric("recall", calculator.recall());
        result.add_metric("f1", calculator.f1());
        result.add_metric("mrr", calculator.mrr());
        result.add_metric("latency_avg", calculator.latency_avg());
        result.add_metric("latency_median", calculator.latency_median());
        result.add_metric("latency_p90", calculator.latency_percentile(90));
        result.add_metric("latency_p95", calculator.latency_percentile(95));
        result.add_metric("latency_p99", calculator.latency_percentile(99));

        if calculator.has_server_latencies() {
            result.add_metric("server_latency_avg", calculator.server_latency_avg());
            result.add_metric("server_latency_median", calculator.server_latency_median());
            result.add_metric("server_latency_p90", calculator.server_latency_percentile(90));
            result.add_metric("server_latency_p95", calculator.server_latency_percentile(95));
            result.add_metric("server_latency_p99", calculator.server_latency_percentile(99));
        }

        result.add_metric("throughput", calculator.throughput());
        if total_wall_ms > 0 {
            result.add_metric("throughput_aggregate_latency_model", calculator.throughput_aggregate_latency_model());
        }
        result.add_metric("num_samples", results.len() as f64);

        if let Some(index_name) = &dataset.index_name {
            let doc_count = self.engine.document_count(index_name).await.unwrap_or(0);
            result.add_metric("documents_ingested", doc_count as f64);

            let metadata = self.engine.index_metadata(index_name).await.unwrap_or_default();
            if let Some(vt) = metadata.get("vector_type") {
                result.add_metadata("vector_type", vt.clone());
            }
        }
        result.add_metadata("recall_label", ctx.recall_label);

        if let Some(schema_name) = &dataset.schema_name
            && let Ok(Some(schema)) = self.engine.schema_template(schema_name)
        {
            result.set_schema(schema);
        }

        Ok(result)
    }
}

/// Bundles the descriptive (non-numeric) context for one parameter-set's result, purely to keep
/// `calculate_metrics`'s argument count down.
struct ResultContext<'a> {
    dataset_name: &'a str,
    recall_label: &'a str,
    param_key: &'a str,
    params: &'a HashMap<String, Value>,
}

struct ExecuteQueriesOutcome {
    results: Vec<QueryResult>,
    query_metrics: Vec<QueryMetricRecord>,
    wall_time_ms: u64,
    failures: usize,
}

async fn execute_single_query(
    engine: &Arc<dyn BenchmarkEngine>,
    index_name: &str,
    query_name: &str,
    benchmark_params: &HashMap<String, Value>,
    query: &QueryDocument,
    run_id: &str,
) -> Result<(QueryResult, Option<QueryMetricRecord>), EngineError> {
    let mut query_params = QueryParams::new(benchmark_params.clone());
    if let Some(v) = &query.query_vector {
        query_params.put("query_vector", serde_json::to_value(v).unwrap());
    }
    if let Some(t) = &query.query_text {
        query_params.put("query_text", t.clone());
    }
    if let Some(mc) = &query.meta_conditions {
        query_params.put("meta_conditions", serde_json::to_value(mc).unwrap());
    }

    let response: QueryResponse = engine.query(index_name, query_name, &query_params).await?;

    let relevant_retrieved = response
        .document_ids
        .iter()
        .filter(|id| query.ground_truth.contains(id))
        .count();
    let precision = if response.document_ids.is_empty() {
        0.0
    } else {
        relevant_retrieved as f64 / response.document_ids.len() as f64
    };
    let recall = if query.ground_truth.is_empty() {
        0.0
    } else {
        relevant_retrieved as f64 / query.ground_truth.len() as f64
    };

    let metric = QueryMetricRecord {
        run_id: run_id.to_string(),
        engine: engine.engine_name().to_string(),
        dataset: index_name.to_string(),
        params: benchmark_params.clone(),
        meta_conditions: query.meta_conditions.clone(),
        ground_truth: query.ground_truth.clone(),
        retrieved: response.document_ids.clone(),
        num_ground_truth: query.ground_truth.len(),
        num_retrieved: response.document_ids.len(),
        client_latency_ms: response.client_latency_ms,
        server_latency_ms: response.server_latency_ms,
        precision,
        recall,
        relevant_retrieved,
    };

    let qr = QueryResult::new(
        query.ground_truth.clone(),
        response.document_ids,
        response.client_latency_ms,
        response.server_latency_ms,
    );

    Ok((qr, Some(metric)))
}

fn parse_query_documents(
    documents: &[crate::model::Document],
    dataset: &DatasetConfig,
) -> Result<Vec<QueryDocument>, EvaluationError> {
    let mapping = dataset
        .queries_mapping
        .as_ref()
        .ok_or_else(|| EvaluationError::Message("dataset.queries_mapping is required".into()))?;
    let vector_field = mapping.query_vector_field.as_deref();
    let text_field = mapping.query_text_field.as_deref();
    let ground_truth_field = mapping
        .ground_truth_field
        .as_deref()
        .ok_or_else(|| EvaluationError::Message("dataset.queries_mapping.ground_truth_field is required".into()))?;
    let conditions_field = mapping.conditions_field.as_deref();

    let mut queries = Vec::with_capacity(documents.len());
    let mut skipped = 0usize;
    for doc in documents {
        if text_field.is_none() && vector_field.is_none() {
            skipped += 1;
            continue;
        }

        let query_text = match text_field {
            Some(f) => match doc.get_str(f) {
                Some(t) => Some(t.to_string()),
                None => {
                    skipped += 1;
                    continue;
                }
            },
            None => None,
        };

        let query_vector = match vector_field {
            Some(f) => match doc.get_float_list(f) {
                Some(v) => Some(v),
                None => {
                    skipped += 1;
                    continue;
                }
            },
            None => None,
        };

        let ground_truth = doc.get_string_list(ground_truth_field).unwrap_or_default();

        let meta_conditions = conditions_field.and_then(|f| doc.get(f)).and_then(|v| match v {
            Value::Object(map) => Some(map.clone().into_iter().collect()),
            Value::String(s) => serde_json::from_str::<HashMap<String, Value>>(s).ok(),
            _ => None,
        });

        queries.push(QueryDocument {
            query_vector,
            query_text,
            ground_truth,
            meta_conditions,
        });
    }

    if skipped > 0 {
        tracing::warn!("Skipped {skipped} queries due to invalid vectors or text");
    }
    Ok(queries)
}

fn build_param_key(params: &HashMap<String, Value>) -> String {
    let mut entries: Vec<(&String, &Value)> = params.iter().collect();
    entries.sort_by_key(|(k, _)| k.as_str());
    entries
        .into_iter()
        .map(|(k, v)| format!("{k}={}", value_to_display(v)))
        .collect::<Vec<_>>()
        .join("_")
}

fn value_to_display(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

fn generate_run_id() -> String {
    Utc::now().format("%Y%m%d-%H%M%S").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_param_key_sorts_and_joins() {
        let mut params = HashMap::new();
        params.insert("size".to_string(), Value::from(10));
        params.insert("hnsw_ef".to_string(), Value::from(32));
        assert_eq!(build_param_key(&params), "hnsw_ef=32_size=10");
    }

    #[test]
    fn build_param_key_string_values_unquoted() {
        let mut params = HashMap::new();
        params.insert("mode".to_string(), Value::from("fast"));
        assert_eq!(build_param_key(&params), "mode=fast");
    }

    #[test]
    fn generate_run_id_matches_expected_format() {
        let id = generate_run_id();
        assert_eq!(id.len(), "20260101-120000".len());
        assert!(id.contains('-'));
    }

    use crate::config::{DataMappingConfig, PathConfig, QueriesMappingConfig};
    use crate::testing::{MockBenchmarkEngine, MockResultsSink};
    use std::io::Write;

    fn write_queries_ndjson(lines: &[&str]) -> tempfile::NamedTempFile {
        let mut f = tempfile::Builder::new().suffix(".ndjson").tempfile().unwrap();
        for line in lines {
            writeln!(f, "{line}").unwrap();
        }
        f
    }

    fn test_config(queries_path: &str) -> JingraConfig {
        let mut param_groups = HashMap::new();
        param_groups.insert(
            "recall@10".to_string(),
            vec![HashMap::from([("size".to_string(), Value::from(2))])],
        );

        let dataset = DatasetConfig {
            dataset_type: Some("vector".to_string()),
            index_name: Some("demo-index".to_string()),
            query_name: Some("demo-query".to_string()),
            path: Some(PathConfig {
                queries_path: Some(queries_path.to_string()),
                ..Default::default()
            }),
            data_mapping: Some(DataMappingConfig { id_field: Some("id".to_string()) }),
            queries_mapping: Some(QueriesMappingConfig {
                query_vector_field: Some("embedding".to_string()),
                ground_truth_field: Some("ground_truth".to_string()),
                ..Default::default()
            }),
            param_groups: Some(param_groups),
            ..Default::default()
        };

        JingraConfig {
            engine: Some("qdrant".to_string()),
            dataset: Some("demo".to_string()),
            datasets: Some(HashMap::from([("demo".to_string(), dataset)])),
            evaluation: Some(EvaluationConfig {
                warmup_workers: 2,
                measurement_workers: 2,
                warmup_rounds: 1,
                measurement_rounds: 1,
                run_id: Some("test-run".to_string()),
            }),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn run_evaluation_writes_one_result_per_param_set_with_correct_metrics() {
        let queries_file = write_queries_ndjson(&[
            r#"{"embedding": [1.0, 0.0], "ground_truth": ["1", "2"]}"#,
            r#"{"embedding": [0.0, 1.0], "ground_truth": ["1", "2"]}"#,
        ]);
        let config = test_config(queries_file.path().to_str().unwrap());

        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string(), "2".to_string()]));
        let sink = Arc::new(MockResultsSink::default());
        let evaluator = BenchmarkEvaluator::new(&config, engine.clone(), vec![sink.clone()]);

        evaluator.run_evaluation(&config).await.unwrap();

        let results = sink.results.lock().unwrap();
        assert_eq!(results.len(), 1);
        let result = &results[0];
        assert_eq!(result.run_id, "test-run");
        assert_eq!(result.param_key, "size=2");
        assert_eq!(result.metrics.get("recall"), Some(&1.0));
        assert_eq!(result.metrics.get("precision"), Some(&1.0));
        assert_eq!(result.metrics.get("num_samples"), Some(&2.0));
        assert_eq!(result.metadata.get("recall_label"), Some(&"recall@10".to_string()));

        // warmup round (2 queries) + measurement round (2 queries) = 4 query() calls.
        assert_eq!(engine.query_calls.load(std::sync::atomic::Ordering::SeqCst), 4);

        let batches = sink.query_metric_batches.lock().unwrap();
        assert_eq!(batches.iter().map(Vec::len).sum::<usize>(), 2);
    }

    #[tokio::test]
    async fn run_evaluation_fails_fast_on_warmup_errors() {
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let config = test_config(queries_file.path().to_str().unwrap());

        let engine = Arc::new(MockBenchmarkEngine::failing_on(vec!["1".to_string()], |_| true));
        let sink = Arc::new(MockResultsSink::default());
        let evaluator = BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]);

        let err = evaluator.run_evaluation(&config).await.unwrap_err();
        assert!(matches!(err, EvaluationError::WarmupFailures { .. }));
        assert!(sink.results.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn run_evaluation_fails_fast_on_measurement_errors_after_warmup_succeeds() {
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let config = test_config(queries_file.path().to_str().unwrap());

        // Warmup round is 1 query (call index 0, succeeds); measurement round's query (call
        // index 1) fails.
        let engine = Arc::new(MockBenchmarkEngine::failing_on(vec!["1".to_string()], |call_index| call_index == 1));
        let sink = Arc::new(MockResultsSink::default());
        let evaluator = BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]);

        let err = evaluator.run_evaluation(&config).await.unwrap_err();
        assert!(matches!(err, EvaluationError::MeasurementFailures { round: 1, .. }));
    }
}
