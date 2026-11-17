use super::ReportError;
use crate::model::BenchmarkResult;
use std::collections::BTreeSet;
use std::path::Path;

/// Writes one row per result, with a metric column for the union of every metric key present
/// across all results (blank where a given result doesn't have that metric -- e.g.
/// `server_latency_*` when the engine didn't report server-side timing).
pub fn export_csv(results: &[BenchmarkResult], path: &Path) -> Result<(), ReportError> {
    let metric_keys: Vec<String> = results
        .iter()
        .flat_map(|r| r.metrics.keys().cloned())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();

    let mut writer = csv::Writer::from_path(path)?;
    let mut header: Vec<String> = ["run_id", "engine", "engine_version", "dataset_type", "dataset_name", "param_key", "recall_label"]
        .into_iter()
        .map(String::from)
        .collect();
    header.extend(metric_keys.iter().cloned());
    writer.write_record(&header)?;

    for r in results {
        let mut row = vec![
            r.run_id.clone(),
            r.engine.clone(),
            r.engine_version.clone(),
            r.dataset_type.clone(),
            r.dataset_name.clone(),
            r.param_key.clone(),
            r.metadata.get("recall_label").cloned().unwrap_or_default(),
        ];
        for key in &metric_keys {
            row.push(r.metrics.get(key).map(|v| v.to_string()).unwrap_or_default());
        }
        writer.write_record(&row)?;
    }
    writer.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn result(param_key: &str, recall: f64, extra: Option<(&str, f64)>) -> BenchmarkResult {
        let mut r = BenchmarkResult::new("run-1", "qdrant", "1.18.0", "vector", "demo", param_key, HashMap::new());
        r.add_metric("recall", recall);
        r.add_metadata("recall_label", "recall@10");
        if let Some((k, v)) = extra {
            r.add_metric(k, v);
        }
        r
    }

    #[test]
    fn export_csv_writes_header_and_one_row_per_result() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.csv");
        let results = vec![result("hnsw_ef=32", 0.9, None), result("hnsw_ef=64", 0.95, None)];
        export_csv(&results, &path).unwrap();

        let contents = std::fs::read_to_string(&path).unwrap();
        let mut lines = contents.lines();
        assert_eq!(lines.next().unwrap(), "run_id,engine,engine_version,dataset_type,dataset_name,param_key,recall_label,recall");
        assert_eq!(lines.count(), 2);
    }

    #[test]
    fn export_csv_unions_metric_columns_across_results_leaving_blanks() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.csv");
        let results = vec![result("k=1", 0.9, Some(("server_latency_median", 5.0))), result("k=2", 0.95, None)];
        export_csv(&results, &path).unwrap();

        let contents = std::fs::read_to_string(&path).unwrap();
        let mut lines = contents.lines();
        let header = lines.next().unwrap();
        assert!(header.contains("server_latency_median"));
        let row_with_extra = lines.next().unwrap();
        let row_without_extra = lines.next().unwrap();
        assert!(row_with_extra.ends_with(",5"));
        assert!(row_without_extra.ends_with(",")); // blank trailing field for the missing metric
    }

    #[test]
    fn export_csv_handles_empty_results() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.csv");
        export_csv(&[], &path).unwrap();
        let contents = std::fs::read_to_string(&path).unwrap();
        assert_eq!(contents.lines().count(), 1); // header only
    }
}
