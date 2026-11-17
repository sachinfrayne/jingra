//! Recall-vs-metric Pareto plots (the classic ann-benchmarks-style tradeoff chart): every stored
//! result is a point (X = recall, Y = the requested metric), grouped into one series per
//! engine and dataset name. Each series draws its Pareto frontier (the points no other point
//! beats on both recall and the metric) as a bold line over the faint cloud of all its points.
//!
//! Running `bench` once per HNSW build configuration (`m`, `ef_construct`), each under its own
//! `dataset` key, therefore yields one frontier per build configuration on a single chart, with
//! the query-time sweep (`hnsw_ef`) tracing out each frontier.

use super::ReportError;
use crate::model::BenchmarkResult;
use plotters::coord::ranged1d::{AsRangedCoord, ValueFormatter};
use plotters::coord::Shift;
use plotters::style::text_anchor::{HPos, Pos, VPos};
use plotters::prelude::*;
use std::collections::BTreeMap;
use std::path::Path;

/// Returns the non-dominated points, sorted by ascending recall. A point is dominated when
/// another point has recall at least as high and a better-or-equal metric (higher when
/// `higher_y_is_better`, such as throughput; lower otherwise, such as latency).
pub fn pareto_frontier(points: &[(f64, f64)], higher_y_is_better: bool) -> Vec<(f64, f64)> {
    let mut sorted = points.to_vec();
    sorted.sort_by(|a, b| {
        b.0.total_cmp(&a.0).then_with(|| {
            if higher_y_is_better {
                b.1.total_cmp(&a.1)
            } else {
                a.1.total_cmp(&b.1)
            }
        })
    });

    let mut frontier = Vec::new();
    let mut best_y: Option<f64> = None;
    for (x, y) in sorted {
        let improves = match best_y {
            None => true,
            Some(best) if higher_y_is_better => y > best,
            Some(best) => y < best,
        };
        if improves {
            frontier.push((x, y));
            best_y = Some(y);
        }
    }
    frontier.reverse();
    frontier
}

/// One series per `engine / dataset_name`, holding `(recall, metric)` for every result that has
/// both metrics.
fn group_series(results: &[BenchmarkResult], metric_key: &str) -> BTreeMap<String, Vec<(f64, f64)>> {
    let mut series: BTreeMap<String, Vec<(f64, f64)>> = BTreeMap::new();
    for r in results {
        let (Some(&recall), Some(&y)) = (r.metrics.get("recall"), r.metrics.get(metric_key)) else {
            continue;
        };
        series
            .entry(format!("{} / {}", r.engine, r.dataset_name))
            .or_default()
            .push((recall, y));
    }
    series
}

/// X-axis range zoomed to the data (rounded down to the nearest 0.05 below the lowest recall),
/// so a sweep living in 0.90 to 1.00 isn't squashed into the right-hand sliver of a 0 to 1 axis.
fn recall_axis_range(points: &[(f64, f64)]) -> (f64, f64) {
    let Some(min_recall) = points.iter().map(|p| p.0).reduce(f64::min) else {
        return (0.0, 1.0);
    };
    let lower = (((min_recall - 0.02) * 20.0).floor() / 20.0).max(0.0);
    (lower, 1.0)
}

/// Orders strings with embedded numbers numerically (`m8` before `m16`), so legends list build
/// configurations by size rather than alphabetically.
fn natural_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    fn chunks(s: &str) -> Vec<(bool, &str)> {
        let mut out = Vec::new();
        let mut start = 0;
        let mut prev: Option<bool> = None;
        for (i, c) in s.char_indices() {
            let digit = c.is_ascii_digit();
            if prev.is_some_and(|p| p != digit) {
                out.push((prev.unwrap(), &s[start..i]));
                start = i;
            }
            prev = Some(digit);
        }
        if let Some(p) = prev {
            out.push((p, &s[start..]));
        }
        out
    }
    let (ca, cb) = (chunks(a), chunks(b));
    for ((a_digit, a_text), (b_digit, b_text)) in ca.iter().zip(cb.iter()) {
        let ord = match (a_digit, b_digit) {
            (true, true) => match (a_text.parse::<u64>(), b_text.parse::<u64>()) {
                (Ok(x), Ok(y)) => x.cmp(&y),
                _ => a_text.cmp(b_text),
            },
            _ => a_text.cmp(b_text),
        };
        if ord != std::cmp::Ordering::Equal {
            return ord;
        }
    }
    ca.len().cmp(&cb.len())
}

/// Axis tick label with just enough precision for its magnitude (`10000`, `3.1`, `0.46`) rather
/// than the `10000.0` a plain float formatter produces.
fn format_axis_value(v: f64) -> String {
    if v.abs() >= 10.0 {
        format!("{v:.0}")
    } else if v.abs() >= 1.0 {
        format!("{v:.1}")
    } else {
        format!("{v:.2}")
    }
}

/// Mantissas of the ticks drawn within each decade of a log axis (1, 2, 3, 5, 7).
const LOG_TICK_STEPS: [f64; 5] = [1.0, 2.0, 3.0, 5.0, 7.0];

/// Tick values for a log axis covering `min..max`: every 1/2/3/5/7 step in each decade within
/// the range. plotters' own log axis labels only whole decades (so a chart spanning 600 to
/// 15,000 got `1000 ... 10000` and nothing to read the top points against), hence the axis is
/// labelled by hand.
fn log_ticks(min: f64, max: f64) -> Vec<f64> {
    if !(min.is_finite() && max.is_finite() && min > 0.0 && max > min) {
        return Vec::new();
    }
    let mut ticks = Vec::new();
    let mut exp = min.log10().floor() as i32;
    while 10f64.powi(exp) <= max {
        for step in LOG_TICK_STEPS {
            // Divide for negative exponents so e.g. 3 / 100 is exactly the double nearest 0.03.
            let tick = if exp >= 0 { step * 10f64.powi(exp) } else { step / 10f64.powi(-exp) };
            if tick >= min && tick <= max {
                ticks.push(tick);
            }
        }
        exp += 1;
    }
    ticks
}

/// The smallest [`log_ticks`] value that is `>= v` (for `v > 0`), used to end the axis on a
/// labelled tick rather than in the middle of a gap.
fn next_tick_at_or_above(v: f64) -> f64 {
    log_ticks(v, v * 10.0).first().copied().unwrap_or(v)
}

/// Tick label: thousands separators for large values, no trailing zeros for fractions.
fn format_tick(v: f64) -> String {
    if v >= 1000.0 {
        let digits = format!("{v:.0}");
        let mut out = String::with_capacity(digits.len() + digits.len() / 3);
        for (i, c) in digits.chars().enumerate() {
            if i > 0 && (digits.len() - i) % 3 == 0 {
                out.push(',');
            }
            out.push(c);
        }
        out
    } else {
        let text = format!("{v:.4}");
        text.trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

const SERIES_COLORS: [RGBColor; 8] = [
    RGBColor(31, 119, 180),
    RGBColor(214, 39, 40),
    RGBColor(44, 160, 44),
    RGBColor(255, 127, 14),
    RGBColor(148, 103, 189),
    RGBColor(23, 190, 207),
    RGBColor(140, 86, 75),
    RGBColor(127, 127, 127),
];

pub fn generate_tradeoff_plot(
    results: &[BenchmarkResult],
    metric_key: &str,
    y_label: &str,
    output_path: &Path,
) -> Result<(), ReportError> {
    let series = group_series(results, metric_key);
    let higher_is_better = !metric_key.contains("latency");
    let all_points: Vec<(f64, f64)> = series.values().flatten().copied().collect();

    let (x_min, x_max) = recall_axis_range(&all_points);
    let x_range = x_min..(x_max + 0.005);
    let y_max = all_points.iter().map(|p| p.1).fold(0.0_f64, f64::max).max(1e-9);
    let y_min_positive = all_points
        .iter()
        .map(|p| p.1)
        .filter(|y| *y > 0.0)
        .fold(f64::INFINITY, f64::min);

    let root = SVGBackend::new(output_path, (1000, 650)).into_drawing_area();
    root.fill(&WHITE).map_err(plot_err)?;

    // Throughput spans orders of magnitude across a sweep, so it gets a log axis (as in
    // ann-benchmarks). Latency stays linear: server-side latency legitimately reports 0ms for
    // fast queries, which has no place on a log axis.
    let caption = format!("Recall vs {y_label}");
    if higher_is_better && y_min_positive.is_finite() {
        let (y_lo, y_hi) = (y_min_positive * 0.8, next_tick_at_or_above(y_max * 1.02));
        let y_range = (y_lo..y_hi).log_scale();
        let ticks = log_ticks(y_lo, y_hi);
        let spec = ChartSpec { caption: &caption, y_label, x_range, y_ticks: &ticks, series: &series, higher_is_better };
        draw(&root, &spec, y_range)?;
    } else {
        let spec = ChartSpec { caption: &caption, y_label, x_range, y_ticks: &[], series: &series, higher_is_better };
        draw(&root, &spec, 0.0..y_max * 1.1)?;
    }

    root.present().map_err(plot_err)?;
    Ok(())
}

/// Everything about a chart apart from the drawing surface and the y coordinate system (which
/// is generic: log for throughput, linear for latency).
struct ChartSpec<'a> {
    caption: &'a str,
    y_label: &'a str,
    x_range: std::ops::Range<f64>,
    /// Hand-drawn y ticks; empty means "let plotters label the axis".
    y_ticks: &'a [f64],
    series: &'a BTreeMap<String, Vec<(f64, f64)>>,
    higher_is_better: bool,
}

fn draw<Y>(
    root: &DrawingArea<SVGBackend<'_>, Shift>,
    spec: &ChartSpec<'_>,
    y_range: Y,
) -> Result<(), ReportError>
where
    Y: AsRangedCoord<Value = f64>,
    Y::CoordDescType: ValueFormatter<f64>,
{
    let ChartSpec { caption, y_label, x_range, y_ticks, series, higher_is_better } = spec;
    let (higher_is_better, y_ticks) = (*higher_is_better, *y_ticks);
    let (x_lo, x_hi) = (x_range.start, x_range.end);
    let mut chart = ChartBuilder::on(root)
        .caption(*caption, ("sans-serif", 26))
        .margin(20)
        .x_label_area_size(50)
        .y_label_area_size(80)
        .build_cartesian_2d(x_range.clone(), y_range)
        .map_err(plot_err)?;

    let hand_labelled = !y_ticks.is_empty();
    let mut mesh = chart.configure_mesh();
    // When the axis is hand-labelled, plotters' own (decade-only) labels are blanked rather than
    // disabled: `y_labels(0)` still leaves it drawing one.
    let plotters_y_label = |v: &f64| if hand_labelled { String::new() } else { format_axis_value(*v) };
    mesh.x_desc("Recall")
        .y_desc(*y_label)
        .x_label_formatter(&|v| format_axis_value(*v))
        .y_label_formatter(&plotters_y_label)
        .axis_desc_style(("sans-serif", 18))
        .label_style(("sans-serif", 15))
        .light_line_style(WHITE.mix(0.0));
    if hand_labelled {
        // Gridlines and labels are drawn below instead (see `log_ticks`).
        mesh.disable_y_mesh();
    }
    mesh.draw().map_err(plot_err)?;

    let tick_style = TextStyle::from(("sans-serif", 15).into_font())
        .color(&BLACK)
        .pos(Pos::new(HPos::Right, VPos::Center));
    for &tick in y_ticks {
        chart
            .draw_series(LineSeries::new([(x_lo, tick), (x_hi, tick)], BLACK.mix(0.1)))
            .map_err(plot_err)?;
        let (px, py) = chart.backend_coord(&(x_lo, tick));
        root.draw(&PathElement::new(vec![(px - 5, py), (px, py)], BLACK)).map_err(plot_err)?;
        root.draw(&Text::new(format_tick(tick), (px - 9, py), tick_style.clone())).map_err(plot_err)?;
    }

    let mut ordered: Vec<(&String, &Vec<(f64, f64)>)> = series.iter().collect();
    ordered.sort_by(|a, b| natural_cmp(a.0, b.0));
    for (i, (label, points)) in ordered.into_iter().enumerate() {
        let color = SERIES_COLORS[i % SERIES_COLORS.len()];
        let frontier = pareto_frontier(points, higher_is_better);

        // The full sweep as a faint cloud, so dominated configurations stay visible.
        chart
            .draw_series(points.iter().map(|(x, y)| Circle::new((*x, *y), 3, color.mix(0.25).filled())))
            .map_err(plot_err)?;

        chart
            .draw_series(LineSeries::new(frontier.iter().copied(), color.stroke_width(2)))
            .map_err(plot_err)?
            .label(label.as_str())
            .legend(move |(x, y)| PathElement::new(vec![(x, y), (x + 24, y)], color.stroke_width(2)));
        chart
            .draw_series(frontier.iter().map(|(x, y)| Circle::new((*x, *y), 5, color.filled())))
            .map_err(plot_err)?;
    }

    if !series.is_empty() {
        let position = if higher_is_better {
            SeriesLabelPosition::LowerLeft
        } else {
            SeriesLabelPosition::UpperLeft
        };
        chart
            .configure_series_labels()
            .position(position)
            .background_style(WHITE.mix(0.85))
            .border_style(BLACK)
            .draw()
            .map_err(plot_err)?;
    }
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
    fn log_ticks_cover_the_range_with_one_two_three_five_seven_steps() {
        assert_eq!(log_ticks(590.0, 19_400.0), vec![700.0, 1000.0, 2000.0, 3000.0, 5000.0, 7000.0, 10_000.0]);
        assert_eq!(log_ticks(1.0, 10.0), vec![1.0, 2.0, 3.0, 5.0, 7.0, 10.0]);
        assert_eq!(log_ticks(0.02, 0.2), vec![0.02, 0.03, 0.05, 0.07, 0.1, 0.2]);
    }

    #[test]
    fn next_tick_at_or_above_rounds_up_to_the_following_tick() {
        assert_eq!(next_tick_at_or_above(15_500.0), 20_000.0);
        assert_eq!(next_tick_at_or_above(10_000.0), 10_000.0);
        assert_eq!(next_tick_at_or_above(10_001.0), 20_000.0);
        assert_eq!(next_tick_at_or_above(0.04), 0.05);
        assert_eq!(next_tick_at_or_above(7.5), 10.0);
    }

    #[test]
    fn log_ticks_are_empty_for_a_degenerate_range() {
        assert!(log_ticks(0.0, 100.0).is_empty());
        assert!(log_ticks(-5.0, 100.0).is_empty());
        assert!(log_ticks(10.0, 10.0).is_empty());
        assert!(log_ticks(100.0, 10.0).is_empty());
        assert!(log_ticks(f64::NAN, 10.0).is_empty());
    }

    #[test]
    fn format_tick_groups_thousands_and_trims_fractions() {
        assert_eq!(format_tick(12_000.0), "12,000");
        assert_eq!(format_tick(1_000_000.0), "1,000,000");
        assert_eq!(format_tick(500.0), "500");
        assert_eq!(format_tick(2.0), "2");
        assert_eq!(format_tick(0.05), "0.05");
        assert_eq!(format_tick(0.3), "0.3");
    }

    #[test]
    fn throughput_chart_labels_every_tick_across_the_range_exactly_once() {
        // Regression: the log axis only labelled decades (with the top one duplicated), leaving
        // the highest points with no scale to read them against.
        let results = vec![
            result("qdrant", "ef=10", 0.5, 640.0),
            result("qdrant", "ef=20", 0.7, 3300.0),
            result("qdrant", "ef=30", 0.9, 15_500.0),
        ];
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("p.svg");
        generate_tradeoff_plot(&results, "throughput", "Throughput (qps)", &path).unwrap();
        let svg = std::fs::read_to_string(&path).unwrap();
        // 20,000 is the first tick above the highest point (15,500), so the axis extends to it.
        for label in ["1,000", "2,000", "5,000", "10,000", "20,000"] {
            // The SVG backend puts each text node on its own line.
            assert_eq!(svg.lines().filter(|l| l.trim() == label).count(), 1, "{label} should appear exactly once");
        }
    }

    #[test]
    fn pareto_frontier_keeps_only_non_dominated_points_when_higher_y_is_better() {
        let points = vec![(0.5, 100.0), (0.6, 300.0), (0.7, 200.0), (0.9, 50.0)];
        // (0.5, 100) is dominated by (0.6, 300): lower recall AND lower throughput.
        assert_eq!(
            pareto_frontier(&points, true),
            vec![(0.6, 300.0), (0.7, 200.0), (0.9, 50.0)]
        );
    }

    #[test]
    fn pareto_frontier_keeps_only_non_dominated_points_when_lower_y_is_better() {
        let points = vec![(0.5, 1.0), (0.6, 0.5), (0.9, 2.0), (0.8, 3.0)];
        // (0.8, 3.0) is dominated by (0.9, 2.0); (0.5, 1.0) by (0.6, 0.5).
        assert_eq!(pareto_frontier(&points, false), vec![(0.6, 0.5), (0.9, 2.0)]);
    }

    #[test]
    fn pareto_frontier_handles_empty_and_duplicate_points() {
        assert!(pareto_frontier(&[], true).is_empty());
        let dupes = vec![(0.9, 100.0), (0.9, 100.0)];
        assert_eq!(pareto_frontier(&dupes, true), vec![(0.9, 100.0)]);
    }

    #[test]
    fn pareto_frontier_keeps_the_better_y_at_equal_recall() {
        let points = vec![(0.9, 100.0), (0.9, 250.0)];
        assert_eq!(pareto_frontier(&points, true), vec![(0.9, 250.0)]);
    }

    #[test]
    fn group_series_separates_the_same_engine_by_dataset_name() {
        let mut a = result("qdrant", "ef=10", 0.8, 900.0);
        a.dataset_name = "m16_efc128".to_string();
        let mut b = result("qdrant", "ef=10", 0.9, 500.0);
        b.dataset_name = "m32_efc256".to_string();

        let series = group_series(&[a, b], "throughput");
        assert_eq!(series.len(), 2);
        assert!(series.contains_key("qdrant / m16_efc128"));
        assert!(series.contains_key("qdrant / m32_efc256"));
    }

    #[test]
    fn natural_cmp_orders_embedded_numbers_numerically() {
        let mut labels = vec!["qdrant / m16_efc100", "qdrant / m8_efc64", "qdrant / m32_efc200", "qdrant / m8_efc8"];
        labels.sort_by(|a, b| natural_cmp(a, b));
        assert_eq!(
            labels,
            vec!["qdrant / m8_efc8", "qdrant / m8_efc64", "qdrant / m16_efc100", "qdrant / m32_efc200"]
        );
    }

    #[test]
    fn format_axis_value_uses_just_enough_precision_for_the_magnitude() {
        assert_eq!(format_axis_value(10000.0), "10000");
        assert_eq!(format_axis_value(2500.4), "2500");
        assert_eq!(format_axis_value(12.0), "12");
        assert_eq!(format_axis_value(3.17), "3.2");
        assert_eq!(format_axis_value(0.456), "0.46");
        assert_eq!(format_axis_value(0.0), "0.00");
    }

    #[test]
    fn recall_axis_range_zooms_into_the_data_and_never_starts_below_zero() {
        assert_eq!(recall_axis_range(&[(0.93, 1.0), (0.99, 1.0)]), (0.90, 1.0));
        assert_eq!(recall_axis_range(&[(0.5, 1.0)]), (0.45, 1.0));
        assert_eq!(recall_axis_range(&[(0.01, 1.0)]), (0.0, 1.0));
        assert_eq!(recall_axis_range(&[]), (0.0, 1.0));
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
