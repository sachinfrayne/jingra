use super::{OutputError, QueryMetricRecord, ResultsSink};
use crate::model::BenchmarkResult;
use async_trait::async_trait;

/// Always-on human-readable summary printed to stdout. Per-query metrics are not echoed here
/// (they can be large) -- only a count, matching the Java version's console sink behavior.
pub struct ConsoleResultsSink;

#[async_trait]
impl ResultsSink for ConsoleResultsSink {
    async fn write_result(&self, result: &BenchmarkResult) -> Result<(), OutputError> {
        println!(
            "[{}] engine={} ({}) dataset={} params={}",
            result.run_id, result.engine, result.engine_version, result.dataset_name, result.param_key
        );
        let mut metrics: Vec<(&String, &f64)> = result.metrics.iter().collect();
        metrics.sort_by_key(|(k, _)| k.as_str());
        for (key, value) in metrics {
            println!("  {key}: {value:.4}");
        }
        Ok(())
    }

    async fn write_query_metrics_batch(&self, metrics: &[QueryMetricRecord]) -> Result<(), OutputError> {
        if !metrics.is_empty() {
            println!("  (wrote {} per-query metric records)", metrics.len());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[tokio::test]
    async fn write_result_does_not_error() {
        let mut result = BenchmarkResult::new("run-1", "qdrant", "1.18.0", "vector", "demo", "k=1", HashMap::new());
        result.add_metric("recall", 0.9);
        assert!(ConsoleResultsSink.write_result(&result).await.is_ok());
    }

    #[tokio::test]
    async fn write_query_metrics_batch_handles_empty() {
        assert!(ConsoleResultsSink.write_query_metrics_batch(&[]).await.is_ok());
    }
}
