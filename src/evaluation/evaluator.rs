//! Runs warmup/measurement rounds for each configured parameter set, computes metrics, and
//! writes results to the configured sinks.

use super::metrics::{MetricsCalculator, QueryResult};
use crate::config::{DatasetConfig, EvaluationConfig, JingraConfig};
use crate::data::{self, DataError};
use crate::engine::{BenchmarkEngine, EngineError};
use crate::model::{BenchmarkResult, QueryParams, QueryResponse};
use crate::output::{OutputError, QueryMetricRecord, ResultsSink};
use chrono::Utc;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Instant;
use tokio::task::JoinSet;

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
    #[error("{count} warmup queries failed (first error: {first_error})")]
    WarmupFailures { count: usize, first_error: String },
    #[error("measurement round {round} had {count} failed queries (first error: {first_error})")]
    MeasurementFailures { round: usize, count: usize, first_error: String },
}

struct QueryDocument {
    query_vector: Option<Arc<[f32]>>,
    query_text: Option<String>,
    ground_truth: Arc<[String]>,
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
        let queries = Arc::new(parse_query_documents(&documents, dataset)?);
        tracing::info!("Loaded {} queries", queries.len());

        let param_groups = dataset
            .param_groups
            .as_ref()
            .ok_or_else(|| EvaluationError::Message("dataset.param_groups is required".into()))?;

        let dataset_name = config
            .dataset
            .clone()
            .ok_or_else(|| EvaluationError::Message("dataset not specified".into()))?;

        // Sorted by label: the config is a map, and iterating a HashMap directly made the run
        // order differ from one run to the next.
        let mut groups: Vec<_> = param_groups.iter().collect();
        groups.sort_by(|a, b| a.0.cmp(b.0));
        for (recall_label, params_list) in groups {
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
        queries: &Arc<Vec<QueryDocument>>,
        dataset: &DatasetConfig,
        dataset_name: &str,
        eval_config: &EvaluationConfig,
        recall_label: &str,
        params: &HashMap<String, Value>,
    ) -> Result<(), EvaluationError> {
        let param_key = build_param_key(params);
        tracing::info!("Evaluating parameter set: {param_key}");
        let shared_params = Arc::new(params.clone());

        for round in 1..=eval_config.warmup_rounds {
            tracing::info!("Warmup round {round}/{}", eval_config.warmup_rounds);
            let outcome = self
                .execute_queries(queries, dataset, &shared_params, eval_config.warmup_workers.max(1) as usize, false)
                .await;
            if outcome.failures > 0 {
                return Err(EvaluationError::WarmupFailures {
                    count: outcome.failures,
                    first_error: outcome.first_error.unwrap_or_default(),
                });
            }
        }

        let mut all_results = Vec::new();
        let mut total_wall_ms: u64 = 0;
        for round in 1..=eval_config.measurement_rounds {
            tracing::info!("Measurement round {round}/{}", eval_config.measurement_rounds);
            let outcome = self
                .execute_queries(queries, dataset, &shared_params, eval_config.measurement_workers.max(1) as usize, true)
                .await;
            if outcome.failures > 0 {
                return Err(EvaluationError::MeasurementFailures {
                    round: round as usize,
                    count: outcome.failures,
                    first_error: outcome.first_error.unwrap_or_default(),
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

    /// Runs every query once using `num_workers` concurrent workers.
    ///
    /// Each worker is its own spawned task pulling the next query index from a shared counter,
    /// so with a multi-threaded runtime the client-side work (request encoding, response
    /// handling, metric bookkeeping) runs on several threads. Driving the queries with
    /// `buffer_unordered` instead keeps all of that on the one task awaiting it, which capped
    /// the throughput the harness could measure regardless of `workers`.
    async fn execute_queries(
        &self,
        queries: &Arc<Vec<QueryDocument>>,
        dataset: &DatasetConfig,
        params: &Arc<HashMap<String, Value>>,
        num_workers: usize,
        collect_results: bool,
    ) -> ExecuteQueriesOutcome {
        let collect_records = collect_results && self.sinks.iter().any(|s| s.wants_query_metrics());
        let shared = Arc::new(WorkerShared {
            engine: self.engine.clone(),
            index_name: dataset.index_name.clone().unwrap_or_default(),
            query_name: dataset.query_name.clone().unwrap_or_default(),
            params: params.clone(),
            run_id: self.run_id.clone(),
            queries: queries.clone(),
            next: AtomicUsize::new(0),
            collect_records,
        });
        let start = Instant::now();

        let mut workers = JoinSet::new();
        for _ in 0..num_workers.max(1).min(queries.len().max(1)) {
            workers.spawn(run_worker(shared.clone()));
        }

        let mut outcome = ExecuteQueriesOutcome::default();
        while let Some(joined) = workers.join_next().await {
            match joined {
                Ok(worker) => {
                    if collect_results {
                        outcome.results.extend(worker.results);
                    }
                    outcome.query_metrics.extend(worker.query_metrics);
                    outcome.failures += worker.failures;
                    if outcome.first_error.is_none() {
                        outcome.first_error = worker.first_error;
                    }
                }
                Err(join_error) => {
                    outcome.failures += 1;
                    outcome.first_error.get_or_insert_with(|| format!("query worker panicked: {join_error}"));
                }
            }
        }
        outcome.wall_time_ms = start.elapsed().as_millis() as u64;
        outcome
    }

    async fn calculate_metrics(
        &self,
        results: &[QueryResult],
        total_wall_ms: u64,
        dataset: &DatasetConfig,
        ctx: &ResultContext<'_>,
    ) -> Result<BenchmarkResult, EvaluationError> {
        let calculator = MetricsCalculator::new(results, if total_wall_ms > 0 { Some(total_wall_ms) } else { None });
        // A result labelled with an unknown engine version or a zero document count would be
        // silently misleading, so these lookups must succeed.
        let version = self.engine.version().await?;

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
            let doc_count = self.engine.document_count(index_name).await?;
            result.add_metric("documents_ingested", doc_count as f64);

            let metadata = self.engine.index_metadata(index_name).await.unwrap_or_else(|e| {
                tracing::warn!("Could not read index metadata for '{index_name}': {e}");
                HashMap::new()
            });
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

#[derive(Default)]
struct ExecuteQueriesOutcome {
    results: Vec<QueryResult>,
    query_metrics: Vec<QueryMetricRecord>,
    wall_time_ms: u64,
    failures: usize,
    first_error: Option<String>,
}

/// State shared (read-only, apart from the work counter) by every worker of one pass.
struct WorkerShared {
    engine: Arc<dyn BenchmarkEngine>,
    index_name: String,
    query_name: String,
    params: Arc<HashMap<String, Value>>,
    run_id: String,
    queries: Arc<Vec<QueryDocument>>,
    next: AtomicUsize,
    collect_records: bool,
}

#[derive(Default)]
struct WorkerOutput {
    results: Vec<QueryResult>,
    query_metrics: Vec<QueryMetricRecord>,
    failures: usize,
    first_error: Option<String>,
}

async fn run_worker(shared: Arc<WorkerShared>) -> WorkerOutput {
    let mut out = WorkerOutput::default();
    loop {
        let index = shared.next.fetch_add(1, Ordering::Relaxed);
        let Some(query) = shared.queries.get(index) else {
            return out;
        };
        match execute_single_query(&shared, query).await {
            Ok((result, record)) => {
                out.results.push(result);
                out.query_metrics.extend(record);
            }
            Err(e) => {
                out.failures += 1;
                tracing::error!("Query execution failed: {e}");
                out.first_error.get_or_insert_with(|| e.to_string());
            }
        }
    }
}

async fn execute_single_query(
    shared: &WorkerShared,
    query: &QueryDocument,
) -> Result<(QueryResult, Option<QueryMetricRecord>), EngineError> {
    let mut query_params = QueryParams::from_shared(shared.params.clone());
    if let Some(v) = &query.query_vector {
        query_params.put_vector("query_vector", v.clone());
    }
    if let Some(t) = &query.query_text {
        query_params.put("query_text", t.clone());
    }
    if let Some(mc) = &query.meta_conditions {
        query_params.put("meta_conditions", serde_json::to_value(mc).unwrap());
    }

    let response: QueryResponse = shared.engine.query(&shared.index_name, &shared.query_name, &query_params).await?;

    let metric = shared.collect_records.then(|| {
        let relevant_retrieved = response.document_ids.iter().filter(|id| query.ground_truth.contains(id)).count();
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
        QueryMetricRecord {
            run_id: shared.run_id.clone(),
            engine: shared.engine.engine_name().to_string(),
            dataset: shared.index_name.clone(),
            params: shared.params.as_ref().clone(),
            meta_conditions: query.meta_conditions.clone(),
            ground_truth: query.ground_truth.to_vec(),
            retrieved: response.document_ids.clone(),
            num_ground_truth: query.ground_truth.len(),
            num_retrieved: response.document_ids.len(),
            client_latency_ms: response.client_latency_ms,
            server_latency_ms: response.server_latency_ms,
            precision,
            recall,
            relevant_retrieved,
        }
    });

    let qr = QueryResult::new(
        query.ground_truth.clone(), // shared, not copied
        response.document_ids,
        response.client_latency_ms,
        response.server_latency_ms,
    );

    Ok((qr, metric))
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
                Some(v) => Some(Arc::<[f32]>::from(v)),
                None => {
                    skipped += 1;
                    continue;
                }
            },
            None => None,
        };

        let ground_truth: Arc<[String]> = doc.get_string_list(ground_truth_field).unwrap_or_default().into();

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

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn queries_execute_in_parallel_on_multiple_threads() {
        // Regression: queries used to be driven by `buffer_unordered` on a single task, so all
        // client-side CPU work ran on one thread however many workers were configured, capping
        // measurable throughput. Each query records its thread, then waits (bounded) until a
        // second thread has also started one: only true parallel workers can satisfy that.
        let threads = Arc::new(std::sync::Mutex::new(std::collections::HashSet::new()));
        let hook_threads = threads.clone();
        let engine = Arc::new(
            MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]).with_query_hook(move || {
                hook_threads.lock().unwrap().insert(std::thread::current().id());
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
                while hook_threads.lock().unwrap().len() < 2 && std::time::Instant::now() < deadline {
                    std::thread::yield_now();
                }
            }),
        );
        let lines: Vec<String> = (0..16).map(|_| r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#.to_string()).collect();
        let line_refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        let queries_file = write_queries_ndjson(&line_refs);
        let mut config = test_config(queries_file.path().to_str().unwrap());
        let eval = config.evaluation.as_mut().unwrap();
        eval.warmup_rounds = 0;
        eval.measurement_workers = 4;

        let sink = Arc::new(MockResultsSink::default());
        BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]).run_evaluation(&config).await.unwrap();

        assert!(threads.lock().unwrap().len() >= 2, "queries ran on a single thread");
        assert_eq!(sink.results.lock().unwrap()[0].metrics["num_samples"], 16.0);
    }

    #[tokio::test]
    async fn a_single_worker_still_runs_every_query_exactly_once() {
        let lines: Vec<String> = (0..7).map(|_| r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#.to_string()).collect();
        let line_refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        let queries_file = write_queries_ndjson(&line_refs);
        let mut config = test_config(queries_file.path().to_str().unwrap());
        let eval = config.evaluation.as_mut().unwrap();
        eval.warmup_rounds = 0;
        eval.measurement_workers = 1;
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]));
        let sink = Arc::new(MockResultsSink::default());

        BenchmarkEvaluator::new(&config, engine.clone(), vec![sink.clone()]).run_evaluation(&config).await.unwrap();

        assert_eq!(engine.query_calls.load(std::sync::atomic::Ordering::SeqCst), 7);
        assert_eq!(sink.results.lock().unwrap()[0].metrics["num_samples"], 7.0);
    }

    #[tokio::test]
    async fn parameter_groups_run_in_a_deterministic_order() {
        // `param_groups` is a HashMap; iterating it directly made the run order (and therefore
        // the order results were written) differ from run to run.
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let mut config = test_config(queries_file.path().to_str().unwrap());
        let labels = ["g", "c", "a", "h", "e", "b", "f", "d"];
        let groups: HashMap<String, Vec<HashMap<String, Value>>> = labels
            .iter()
            .map(|l| (l.to_string(), vec![HashMap::from([("size".to_string(), Value::from(2))])]))
            .collect();
        config.datasets.as_mut().unwrap().get_mut("demo").unwrap().param_groups = Some(groups);
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]));
        let sink = Arc::new(MockResultsSink::default());

        BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]).run_evaluation(&config).await.unwrap();

        let order: Vec<String> = sink.results.lock().unwrap().iter().map(|r| r.metadata["recall_label"].clone()).collect();
        assert_eq!(order, vec!["a", "b", "c", "d", "e", "f", "g", "h"]);
    }

    #[tokio::test]
    async fn query_failures_report_the_first_underlying_error() {
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let config = test_config(queries_file.path().to_str().unwrap());
        let engine = Arc::new(MockBenchmarkEngine::failing_on(vec!["1".to_string()], |_| true));
        let evaluator = BenchmarkEvaluator::new(&config, engine, vec![]);

        let err = evaluator.run_evaluation(&config).await.unwrap_err();

        assert!(err.to_string().contains("injected failure"), "the cause must be in the message: {err}");
    }

    #[tokio::test]
    async fn a_failing_version_lookup_fails_the_run_instead_of_recording_unknown() {
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let config = test_config(queries_file.path().to_str().unwrap());
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]).with_failing_version());
        let sink = Arc::new(MockResultsSink::default());

        let err = BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]).run_evaluation(&config).await.unwrap_err();

        assert!(err.to_string().contains("injected version failure"), "{err}");
        assert!(sink.results.lock().unwrap().is_empty(), "no misleading result may be written");
    }

    #[tokio::test]
    async fn a_failing_document_count_fails_the_run_instead_of_recording_zero() {
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let config = test_config(queries_file.path().to_str().unwrap());
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]).with_failing_document_count());
        let sink = Arc::new(MockResultsSink::default());

        let err = BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]).run_evaluation(&config).await.unwrap_err();

        assert!(err.to_string().contains("injected document_count failure"), "{err}");
        assert!(sink.results.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn per_query_records_are_not_built_when_no_sink_wants_them() {
        let queries_file = write_queries_ndjson(&[r#"{"embedding": [1.0, 0.0], "ground_truth": ["1"]}"#]);
        let config = test_config(queries_file.path().to_str().unwrap());
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]));
        let sink = Arc::new(MockResultsSink { declines_query_metrics: true, ..Default::default() });

        BenchmarkEvaluator::new(&config, engine, vec![sink.clone()]).run_evaluation(&config).await.unwrap();

        assert!(sink.query_metric_batches.lock().unwrap().is_empty());
        assert_eq!(sink.results.lock().unwrap().len(), 1, "the aggregate result is still written");
    }
}
