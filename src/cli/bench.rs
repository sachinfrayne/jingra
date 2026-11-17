//! `bench`: run the warmup/measurement sweep and write results to the configured sinks.

use super::CliError;
use crate::config::{self, JingraConfig};
use crate::engine::{self, BenchmarkEngine};
use crate::evaluation::{resolve_run_id, BenchmarkEvaluator};
use crate::output::{ConsoleResultsSink, ObjectStoreResultsSink, ResultsSink};
use std::sync::Arc;

pub async fn run_bench(config: &JingraConfig) -> Result<(), CliError> {
    config::validate_for_bench(config)?;

    let engine_name = config.engine.as_deref().unwrap_or_default();
    let engine_config = config.engine_config()?.clone();
    let engine: Arc<dyn BenchmarkEngine> = Arc::from(engine::create_engine(engine_name, engine_config)?);
    run_bench_with_engine(config, engine).await
}

/// Same as [`run_bench`] but takes an already-constructed engine, so tests can inject a mock.
pub async fn run_bench_with_engine(config: &JingraConfig, engine: Arc<dyn BenchmarkEngine>) -> Result<(), CliError> {
    config::validate_for_bench(config)?;

    tracing::info!("Connecting to {}...", engine.engine_name());
    engine.connect().await?;

    let run_id = resolve_run_id(config);
    let output = config.output.clone().unwrap_or_default();

    let mut sinks: Vec<Arc<dyn ResultsSink>> = vec![Arc::new(ConsoleResultsSink)];
    let object_store_sink = ObjectStoreResultsSink::new(output.results_uri_or_default(), run_id, output.per_query_metrics)?;
    sinks.push(Arc::new(object_store_sink));

    let evaluator = BenchmarkEvaluator::new(config, engine, sinks);
    evaluator.run_evaluation(config).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{DatasetConfig, EvaluationConfig, PathConfig, QueriesMappingConfig};
    use crate::testing::MockBenchmarkEngine;
    use std::collections::HashMap;
    use std::io::Write;

    fn queries_fixture() -> tempfile::NamedTempFile {
        let mut f = tempfile::Builder::new().suffix(".ndjson").tempfile().unwrap();
        writeln!(f, r#"{{"embedding": [1.0, 0.0], "ground_truth": ["1"]}}"#).unwrap();
        f
    }

    fn test_config(queries_path: &str, results_uri: &str) -> JingraConfig {
        let mut param_groups = HashMap::new();
        param_groups.insert("recall@10".to_string(), vec![HashMap::from([("size".to_string(), serde_json::json!(1))])]);

        let dataset = DatasetConfig {
            dataset_type: Some("vector".to_string()),
            index_name: Some("demo-index".to_string()),
            query_name: Some("demo-query".to_string()),
            path: Some(PathConfig {
                queries_path: Some(queries_path.to_string()),
                ..Default::default()
            }),
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
            qdrant: Some(HashMap::new()),
            datasets: Some(HashMap::from([("demo".to_string(), dataset)])),
            evaluation: Some(EvaluationConfig {
                warmup_workers: 1,
                measurement_workers: 1,
                warmup_rounds: 1,
                measurement_rounds: 1,
                run_id: Some("bench-test-run".to_string()),
            }),
            output: Some(crate::config::OutputConfig {
                results_uri: Some(results_uri.to_string()),
                per_query_metrics: true,
            }),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn run_bench_with_engine_writes_results_to_object_store() {
        let queries_file = queries_fixture();
        let results_dir = tempfile::tempdir().unwrap();
        // `file://` (unlike `memory://`) is backed by real disk, so a second `parse_url` call
        // in this test sees exactly what the sink wrote -- `memory://` gives every parse_url
        // call its own independent, unshared in-process store.
        let results_uri = format!("file://{}", results_dir.path().display());
        let config = test_config(queries_file.path().to_str().unwrap(), &results_uri);
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec!["1".to_string()]));

        run_bench_with_engine(&config, engine).await.unwrap();

        let (store, prefix) = object_store::parse_url(&url::Url::parse(&results_uri).unwrap()).unwrap();
        use futures::StreamExt;
        let results_prefix = prefix.join("bench-test-run").join("results");
        let objects: Vec<_> = store.list(Some(&results_prefix)).collect().await;
        assert_eq!(objects.len(), 1, "expected exactly one result object written");
    }
}
