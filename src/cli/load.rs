//! `load`: create the index/collection and parallel-ingest a dataset into it.

use super::CliError;
use crate::config::{self, JingraConfig};
use crate::data::{self, DatasetReader};
use crate::engine::{self, BenchmarkEngine};
use crate::model::Document;
use crate::utils::retry_with_backoff;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::task::JoinSet;

/// Poll interval while waiting for the engine to finish removing an index after delete.
const INDEX_ABSENT_POLL: Duration = Duration::from_millis(500);
/// Max time to wait for `index_exists == false` after a successful delete call.
const INDEX_ABSENT_DEADLINE: Duration = Duration::from_secs(180);

/// Documents ingested per progress milestone log line.
const INGEST_MILESTONE_DOC_STEP: usize = 100_000;

pub async fn run_load(config: &JingraConfig) -> Result<(), CliError> {
    config::validate_for_load(config)?;
    let engine_name = config.engine.as_deref().unwrap_or_default();
    let engine_config = config.engine_config()?.clone();
    let engine: Arc<dyn BenchmarkEngine> = Arc::from(engine::create_engine(engine_name, engine_config)?);
    run_load_with_engine(config, engine).await
}

/// Same as [`run_load`] but takes an already-constructed engine, so tests can inject a mock
/// instead of exercising a real connection.
pub async fn run_load_with_engine(config: &JingraConfig, engine: Arc<dyn BenchmarkEngine>) -> Result<(), CliError> {
    config::validate_for_load(config)?;
    let dataset = config.active_dataset()?;

    tracing::info!("Connecting to {}...", engine.engine_name());
    engine.connect().await?;

    let index_name = dataset
        .index_name
        .clone()
        .ok_or_else(|| CliError::Message("dataset.index_name is required for load".into()))?;

    if engine.index_exists(&index_name).await? {
        tracing::info!("Index '{index_name}' already exists - deleting...");
        engine.delete_index(&index_name).await?;
        tracing::info!("Index delete request accepted; waiting until index is gone before create...");
        wait_until_index_absent(&engine, &index_name).await?;
        tracing::info!("Index deleted successfully (no longer reported by cluster)");
    }

    let schema_name = dataset
        .schema_name
        .clone()
        .ok_or_else(|| CliError::Message("dataset.schema_name is required for load".into()))?;
    tracing::info!("Creating index '{index_name}'...");
    engine.create_index(&index_name, &schema_name).await?;
    tracing::info!("Index created successfully");

    let path_config = dataset.path.as_ref().expect("validated by validate_for_load");
    let data_path = path_config.data_path.clone().expect("validated by validate_for_load");
    match &path_config.data_url_env {
        Some(url_env) => crate::utils::ensure_file_exists(&data_path, url_env).await?,
        None if !Path::new(&data_path).exists() => {
            return Err(CliError::Message(format!("Data file not found: {data_path}")));
        }
        None => {}
    }
    tracing::info!("Loading data from: {data_path}");

    let reader = data::create_reader(&data_path);
    let row_count = reader.row_count()?;
    tracing::info!("Data file contains {row_count} documents");

    let id_field = dataset.data_mapping.as_ref().and_then(|d| d.id_field.clone());
    let load_config = config.load.clone().unwrap_or_default();
    let batch_size = load_config.batch_size_or_default() as usize;
    let num_threads = load_config.threads_or_default() as usize;
    let queue_capacity = load_config.queue_capacity_or_default().max(1) as usize;

    tracing::info!("Starting parallel ingestion with {num_threads} threads (queue capacity: {queue_capacity})...");

    let start_time = Instant::now();
    let total_ingested = Arc::new(AtomicUsize::new(0));
    let last_logged_milestone = Arc::new(AtomicUsize::new(0));

    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<Document>>(queue_capacity);
    let reader_data_path = data_path.clone();
    let reader_conversion_threads = num_threads.max(1);
    let reader_handle: tokio::task::JoinHandle<Result<(), CliError>> = tokio::task::spawn_blocking(move || {
        let reader: Box<dyn DatasetReader> = data::create_reader(&reader_data_path);
        reader.read_in_batches(batch_size, reader_conversion_threads, &mut |batch| {
            tx.blocking_send(batch)
                .map_err(|_| crate::data::DataError::Invalid("ingest consumer channel closed".into()))
        })?;
        Ok(())
    });

    let mut tasks: JoinSet<Result<usize, CliError>> = JoinSet::new();
    let mut ingest_failures = 0usize;

    while let Some(batch) = rx.recv().await {
        while tasks.len() >= num_threads.max(1) {
            if let Some(res) = tasks.join_next().await {
                record_ingest_outcome(res?, &total_ingested, &last_logged_milestone, row_count, start_time, &mut ingest_failures);
            }
        }
        let engine = engine.clone();
        let index_name = index_name.clone();
        let id_field = id_field.clone();
        tasks.spawn(async move {
            retry_with_backoff(
                || {
                    let engine = engine.clone();
                    let index_name = index_name.clone();
                    let id_field = id_field.clone();
                    let batch = batch.clone();
                    async move { engine.ingest(batch, &index_name, id_field.as_deref()).await }
                },
                None, // infinite retries: critical for data integrity, matches the Java version
                1000,
                |_e| true,
            )
            .await
            .map_err(CliError::from)
        });
    }
    while let Some(res) = tasks.join_next().await {
        record_ingest_outcome(res?, &total_ingested, &last_logged_milestone, row_count, start_time, &mut ingest_failures);
    }
    reader_handle.await??;

    if ingest_failures > 0 {
        return Err(CliError::Message(format!(
            "Data loading completed with {ingest_failures} failed ingest batch(es); refusing to report success"
        )));
    }

    let final_count = total_ingested.load(Ordering::SeqCst);
    if final_count as u64 != row_count {
        return Err(CliError::Message(format!(
            "Ingested {final_count} documents but data file row count is {row_count} (incomplete load)"
        )));
    }

    if load_config.await_index_ready {
        tracing::info!("Waiting for index '{index_name}' to be ready (background merges/optimisation to settle)...");
        engine.await_index_ready(&index_name).await?;
        tracing::info!("Index ready; load command complete.");
    }

    let total_time_sec = start_time.elapsed().as_secs_f64();
    tracing::info!("{}", "=".repeat(80));
    tracing::info!("Data loading complete!");
    tracing::info!("  Total ingested: {final_count} documents");
    tracing::info!("  Total time: {total_time_sec:.1} sec ({:.1} min)", total_time_sec / 60.0);
    tracing::info!("  Average rate: {:.0} docs/sec", final_count as f64 / total_time_sec.max(1e-6));
    tracing::info!("  Final count: {} documents", engine.document_count(&index_name).await.unwrap_or(0));
    tracing::info!("{}", "=".repeat(80));

    Ok(())
}

fn record_ingest_outcome(
    outcome: Result<usize, CliError>,
    total_ingested: &Arc<AtomicUsize>,
    last_logged_milestone: &Arc<AtomicUsize>,
    row_count: u64,
    start_time: Instant,
    ingest_failures: &mut usize,
) {
    match outcome {
        Ok(ingested) => {
            let total = total_ingested.fetch_add(ingested, Ordering::SeqCst) + ingested;
            let current_milestone = total / INGEST_MILESTONE_DOC_STEP;
            let previous = last_logged_milestone.load(Ordering::SeqCst);
            if current_milestone > previous
                && last_logged_milestone
                    .compare_exchange(previous, current_milestone, Ordering::SeqCst, Ordering::SeqCst)
                    .is_ok()
            {
                let elapsed = start_time.elapsed().as_secs_f64();
                let progress = 100.0 * total as f64 / row_count.max(1) as f64;
                let rate = total as f64 / elapsed.max(1e-6);
                tracing::info!("Progress: {total}/{row_count} docs ({progress:.1}%) | Rate: {rate:.0} docs/sec");
            }
        }
        Err(e) => {
            *ingest_failures += 1;
            tracing::error!("Failed to ingest batch: {e}");
        }
    }
}

/// Blocks until `index_name` is no longer reported by the engine, or errors if the deadline
/// passes. Engines can return success from a delete before all nodes agree it's gone.
async fn wait_until_index_absent(engine: &Arc<dyn BenchmarkEngine>, index_name: &str) -> Result<(), CliError> {
    let deadline = Instant::now() + INDEX_ABSENT_DEADLINE;
    let mut polls = 0u32;
    while engine.index_exists(index_name).await? {
        if Instant::now() > deadline {
            return Err(CliError::Message(format!(
                "Timed out after 3m waiting for index '{index_name}' to disappear after delete; \
                 cluster may still be processing the delete"
            )));
        }
        if polls.is_multiple_of(20) {
            tracing::info!("Waiting for index '{index_name}' to be fully removed from the cluster...");
        }
        polls += 1;
        tokio::time::sleep(INDEX_ABSENT_POLL).await;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{DataMappingConfig, DatasetConfig, PathConfig};
    use crate::testing::MockBenchmarkEngine;
    use std::collections::HashMap;
    use std::io::Write;

    fn ndjson_fixture(lines: &[&str]) -> tempfile::NamedTempFile {
        let mut f = tempfile::Builder::new().suffix(".ndjson").tempfile().unwrap();
        for line in lines {
            writeln!(f, "{line}").unwrap();
        }
        f
    }

    fn test_config(data_path: &str) -> JingraConfig {
        let dataset = DatasetConfig {
            index_name: Some("demo-index".to_string()),
            schema_name: Some("demo-schema".to_string()),
            path: Some(PathConfig {
                data_path: Some(data_path.to_string()),
                ..Default::default()
            }),
            data_mapping: Some(DataMappingConfig { id_field: Some("id".to_string()) }),
            ..Default::default()
        };
        JingraConfig {
            engine: Some("qdrant".to_string()),
            dataset: Some("demo".to_string()),
            qdrant: Some(HashMap::new()),
            datasets: Some(HashMap::from([("demo".to_string(), dataset)])),
            load: Some(crate::config::LoadConfig {
                threads: Some(2),
                batch_size: Some(2),
                queue_capacity: Some(4),
                await_index_ready: false,
            }),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn wait_until_index_absent_returns_once_engine_reports_gone() {
        let engine: Arc<dyn BenchmarkEngine> = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec![]));
        // MockBenchmarkEngine::index_exists always returns false, so this resolves immediately.
        wait_until_index_absent(&engine, "some-index").await.unwrap();
    }

    #[tokio::test]
    async fn run_load_ingests_all_rows_and_reports_final_count() {
        let f = ndjson_fixture(&[
            r#"{"id": 1, "embedding": [1.0]}"#,
            r#"{"id": 2, "embedding": [2.0]}"#,
            r#"{"id": 3, "embedding": [3.0]}"#,
        ]);
        let config = test_config(f.path().to_str().unwrap());
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec![]));

        run_load_with_engine(&config, engine.clone()).await.unwrap();
        assert_eq!(engine.ingest_calls.load(Ordering::SeqCst), 2); // batch_size=2 over 3 rows -> 2 batches
    }

    #[tokio::test]
    async fn run_load_deletes_and_waits_out_a_pre_existing_index() {
        let f = ndjson_fixture(&[r#"{"id": 1, "embedding": [1.0]}"#]);
        let config = test_config(f.path().to_str().unwrap());
        let engine = Arc::new(MockBenchmarkEngine::index_exists_once_then_absent());

        run_load_with_engine(&config, engine.clone()).await.unwrap();
        assert_eq!(engine.delete_index_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn run_load_errors_when_row_count_does_not_match_ingested_count() {
        let f = ndjson_fixture(&[r#"{"id": 1, "embedding": [1.0]}"#, r#"{"id": 2, "embedding": [2.0]}"#]);
        let config = test_config(f.path().to_str().unwrap());
        // Simulates documents silently dropped during ingest (e.g. missing vector field),
        // matching Java's behavior -- so total ingested ends up less than the row count.
        let engine = Arc::new(MockBenchmarkEngine::under_reporting_ingest());

        let err = run_load_with_engine(&config, engine).await.unwrap_err();
        assert!(err.to_string().contains("incomplete load"));
    }

    #[tokio::test]
    async fn run_load_errors_when_data_file_missing_and_no_url_configured() {
        let config = test_config("/no/such/file.ndjson");
        let engine = Arc::new(MockBenchmarkEngine::with_fixed_ids(vec![]));
        let err = run_load_with_engine(&config, engine).await.unwrap_err();
        assert!(err.to_string().contains("Data file not found"));
    }
}
