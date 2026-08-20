//! Recall-vs-metric tradeoff plots (the classic ann-benchmarks-style Pareto curve): one point
//! per stored result, X = recall, Y = the requested metric (throughput or a latency metric),
//! one series per engine present in the results. With one engine today this is a single curve;
//! once Elasticsearch/OpenSearch return, the same plot overlays their series for comparison.

use super::ReportError;
use crate::model::BenchmarkResult;
use plotters::prelude::*;
use std::collections::BTreeMap;
use std::path::Path;

const SERIES_COLORS: [&RGBColor; 5] = [&RED, &BLUE, &GREEN, &MAGENTA, &CYAN];

pub fn generate_tradeoff_plot(
    results: &[BenchmarkResult],
    metric_key: &str,
    y_label: &str,
    output_path: &Path,
) -> Result<(), ReportError> {
    let mut by_engine: BTreeMap<String, Vec<(f64, f64)>> = BTreeMap::new();
    for r in results {
        let (Some(&recall), Some(&y)) = (r.metrics.get("recall"), r.metrics.get(metric_key)) else {
            continue;
        };
        by_engine.entry(r.engine.clone()).or_default().push((recall, y));
    }
    for points in by_engine.values_mut() {
        points.sort_by(|a, b| a.0.total_cmp(&b.0));
    }

    let max_y = by_engine
        .values()
        .flatten()
        .map(|(_, y)| *y)
        .fold(0.0_f64, f64::max)
        .max(1.0);

    let root = SVGBackend::new(output_path, (900, 600)).into_drawing_area();
    root.fill(&WHITE).map_err(plot_err)?;

    let mut chart = ChartBuilder::on(&root)
        .caption(format!("Recall vs {y_label}"), ("sans-serif", 24))
        .margin(20)
        .x_label_area_size(40)
        .y_label_area_size(70)
        .build_cartesian_2d(0f64..1.05f64, 0f64..(max_y * 1.1))
        .map_err(plot_err)?;

    chart
        .configure_mesh()
        .x_desc("Recall")
        .y_desc(y_label)
        .draw()
        .map_err(plot_err)?;

    for (i, (engine, points)) in by_engine.iter().enumerate() {
        let color = SERIES_COLORS[i % SERIES_COLORS.len()];
        chart
            .draw_series(LineSeries::new(points.iter().copied(), color))
            .map_err(plot_err)?
            .label(engine.clone())
            .legend(move |(x, y)| PathElement::new(vec![(x, y), (x + 20, y)], color));
        chart
            .draw_series(points.iter().map(|(x, y)| Circle::new((*x, *y), 4, color.filled())))
            .map_err(plot_err)?;
    }

    if !by_engine.is_empty() {
        chart
            .configure_series_labels()
            .background_style(WHITE.mix(0.8))
            .border_style(BLACK)
            .draw()
            .map_err(plot_err)?;
    }

    root.present().map_err(plot_err)?;
    Ok(())
}

fn plot_err<E: std::fmt::Display>(e: E) -> ReportError {
    ReportError::Plot(e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn result(engine: &str, param_key: &str, recall: f64, throughput: f64) -> BenchmarkResult {
        let mut r = BenchmarkResult::new("run-1", engine, "1.18.0", "vector", "demo", param_key, HashMap::new());
        r.add_metric("recall", recall);
        r.add_metric("throughput", throughput);
        r
    }

    #[test]
    fn generate_tradeoff_plot_writes_a_non_empty_svg_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("plot.svg");
        let results = vec![
            result("qdrant", "hnsw_ef=1", 0.5, 5000.0),
            result("qdrant", "hnsw_ef=320", 0.99, 200.0),
        ];
        generate_tradeoff_plot(&results, "throughput", "Throughput (qps)", &path).unwrap();

        let contents = std::fs::read_to_string(&path).unwrap();
        assert!(contents.starts_with("<?xml") || contents.contains("<svg"));
        assert!(contents.len() > 100);
    }

    #[test]
    fn generate_tradeoff_plot_handles_no_matching_results_without_erroring() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("empty.svg");
        generate_tradeoff_plot(&[], "throughput", "Throughput (qps)", &path).unwrap();
        assert!(path.exists());
    }

    #[test]
    fn generate_tradeoff_plot_skips_results_missing_the_requested_metric() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("partial.svg");
        let mut r = BenchmarkResult::new("run-1", "qdrant", "1.18.0", "vector", "demo", "k=1", HashMap::new());
        r.add_metric("recall", 0.9); // no "server_latency_median"
        generate_tradeoff_plot(&[r], "server_latency_median", "Server latency (ms)", &path).unwrap();
        assert!(path.exists());
    }
}
