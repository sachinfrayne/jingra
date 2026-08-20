//! Pure metric math over a set of query results: precision/recall/F1/MRR, latency percentiles,
//! and throughput. Ported near verbatim from the Java `MetricsCalculator` -- this is exactly
//! the kind of logic that shouldn't change shape just because the language did.

use std::collections::HashSet;

/// One query's ground truth vs retrieved document IDs, with its latencies.
#[derive(Debug, Clone)]
pub struct QueryResult {
    pub ground_truth: Vec<String>,
    pub retrieved: Vec<String>,
    pub client_latency_ms: Option<f64>,
    pub server_latency_ms: Option<i64>,
}

impl QueryResult {
    pub fn new(
        ground_truth: Vec<String>,
        retrieved: Vec<String>,
        client_latency_ms: Option<f64>,
        server_latency_ms: Option<i64>,
    ) -> Self {
        Self {
            ground_truth,
            retrieved,
            client_latency_ms,
            server_latency_ms,
        }
    }
}

pub struct MetricsCalculator<'a> {
    results: &'a [QueryResult],
    /// When set (typically the wall-clock time for the measurement round), throughput is
    /// `queries / wall_time` (correct under concurrent workers). Otherwise throughput falls back
    /// to the sum-of-client-latencies model, which parallelism distorts.
    wall_clock_ms_for_throughput: Option<u64>,
}

impl<'a> MetricsCalculator<'a> {
    pub fn new(results: &'a [QueryResult], wall_clock_ms_for_throughput: Option<u64>) -> Self {
        Self {
            results,
            wall_clock_ms_for_throughput,
        }
    }

    pub fn precision(&self) -> f64 {
        if self.results.is_empty() {
            return 0.0;
        }
        self.results.iter().map(Self::query_precision).sum::<f64>() / self.results.len() as f64
    }

    pub fn recall(&self) -> f64 {
        if self.results.is_empty() {
            return 0.0;
        }
        self.results.iter().map(Self::query_recall).sum::<f64>() / self.results.len() as f64
    }

    pub fn f1(&self) -> f64 {
        let precision = self.precision();
        let recall = self.recall();
        if precision + recall == 0.0 {
            0.0
        } else {
            2.0 * (precision * recall) / (precision + recall)
        }
    }

    pub fn mrr(&self) -> f64 {
        if self.results.is_empty() {
            return 0.0;
        }
        self.results.iter().map(Self::query_reciprocal_rank).sum::<f64>() / self.results.len() as f64
    }

    pub fn latency_avg(&self) -> f64 {
        average(self.results.iter().filter_map(|r| r.client_latency_ms))
    }

    pub fn latency_median(&self) -> f64 {
        self.latency_percentile(50)
    }

    pub fn latency_percentile(&self, percentile: u32) -> f64 {
        let mut values: Vec<f64> = self.results.iter().filter_map(|r| r.client_latency_ms).collect();
        values.sort_by(|a, b| a.total_cmp(b));
        percentile_of_sorted(&values, percentile)
    }

    pub fn has_server_latencies(&self) -> bool {
        self.results.iter().any(|r| r.server_latency_ms.is_some())
    }

    pub fn server_latency_avg(&self) -> f64 {
        average(self.results.iter().filter_map(|r| r.server_latency_ms).map(|v| v as f64))
    }

    pub fn server_latency_median(&self) -> f64 {
        self.server_latency_percentile(50)
    }

    pub fn server_latency_percentile(&self, percentile: u32) -> f64 {
        let mut values: Vec<f64> = self
            .results
            .iter()
            .filter_map(|r| r.server_latency_ms)
            .map(|v| v as f64)
            .collect();
        values.sort_by(|a, b| a.total_cmp(b));
        percentile_of_sorted(&values, percentile)
    }

    /// Queries per second: wall-clock-based when available, otherwise
    /// `num_queries / sum(client_latencies)` (distorted by parallelism).
    pub fn throughput(&self) -> f64 {
        if self.results.is_empty() {
            return 0.0;
        }
        if let Some(wall_ms) = self.wall_clock_ms_for_throughput
            && wall_ms > 0
        {
            return self.results.len() as f64 * 1000.0 / wall_ms as f64;
        }
        self.throughput_aggregate_latency_model()
    }

    /// Legacy throughput model, exposed separately when wall-clock throughput is used so
    /// comparisons against older results remain possible.
    pub fn throughput_aggregate_latency_model(&self) -> f64 {
        if self.results.is_empty() {
            return 0.0;
        }
        let total_time_ms: f64 = self.results.iter().filter_map(|r| r.client_latency_ms).sum();
        if total_time_ms == 0.0 {
            0.0
        } else {
            self.results.len() as f64 * 1000.0 / total_time_ms
        }
    }

    fn query_precision(result: &QueryResult) -> f64 {
        if result.retrieved.is_empty() {
            return 0.0;
        }
        let ground_truth: HashSet<&str> = result.ground_truth.iter().map(String::as_str).collect();
        let relevant_retrieved = result.retrieved.iter().filter(|id| ground_truth.contains(id.as_str())).count();
        relevant_retrieved as f64 / result.retrieved.len() as f64
    }

    fn query_recall(result: &QueryResult) -> f64 {
        if result.ground_truth.is_empty() {
            return 0.0;
        }
        let retrieved: HashSet<&str> = result.retrieved.iter().map(String::as_str).collect();
        let relevant_retrieved = result.ground_truth.iter().filter(|id| retrieved.contains(id.as_str())).count();
        relevant_retrieved as f64 / result.ground_truth.len() as f64
    }

    fn query_reciprocal_rank(result: &QueryResult) -> f64 {
        if result.retrieved.is_empty() || result.ground_truth.is_empty() {
            return 0.0;
        }
        let ground_truth: HashSet<&str> = result.ground_truth.iter().map(String::as_str).collect();
        for (i, id) in result.retrieved.iter().enumerate() {
            if ground_truth.contains(id.as_str()) {
                return 1.0 / (i + 1) as f64;
            }
        }
        0.0
    }
}

fn average(values: impl Iterator<Item = f64>) -> f64 {
    let mut sum = 0.0;
    let mut count = 0usize;
    for v in values {
        sum += v;
        count += 1;
    }
    if count == 0 {
        0.0
    } else {
        sum / count as f64
    }
}

/// Linear-interpolation percentile over an already-sorted slice, matching the Java version's
/// `calculatePercentile`.
fn percentile_of_sorted(sorted_values: &[f64], percentile: u32) -> f64 {
    if sorted_values.is_empty() {
        return 0.0;
    }
    let index = (percentile as f64 / 100.0) * (sorted_values.len() - 1) as f64;
    let lower = index.floor() as usize;
    let upper = index.ceil() as usize;
    if lower == upper {
        return sorted_values[lower];
    }
    let weight = index - lower as f64;
    sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight
}

#[cfg(test)]
mod tests {
    use super::*;

    fn qr(ground_truth: &[&str], retrieved: &[&str], client_ms: Option<f64>, server_ms: Option<i64>) -> QueryResult {
        QueryResult::new(
            ground_truth.iter().map(|s| s.to_string()).collect(),
            retrieved.iter().map(|s| s.to_string()).collect(),
            client_ms,
            server_ms,
        )
    }

    #[test]
    fn empty_results_yield_zero_metrics() {
        let results: Vec<QueryResult> = vec![];
        let calc = MetricsCalculator::new(&results, None);
        assert_eq!(calc.precision(), 0.0);
        assert_eq!(calc.recall(), 0.0);
        assert_eq!(calc.f1(), 0.0);
        assert_eq!(calc.mrr(), 0.0);
        assert_eq!(calc.throughput(), 0.0);
    }

    #[test]
    fn precision_recall_f1_perfect_match() {
        let results = vec![qr(&["a", "b"], &["a", "b"], Some(10.0), None)];
        let calc = MetricsCalculator::new(&results, None);
        assert_eq!(calc.precision(), 1.0);
        assert_eq!(calc.recall(), 1.0);
        assert_eq!(calc.f1(), 1.0);
    }

    #[test]
    fn precision_recall_partial_overlap() {
        // retrieved 4, 2 relevant -> precision 0.5; ground truth 3, 2 found -> recall 2/3
        let results = vec![qr(&["a", "b", "c"], &["a", "b", "x", "y"], None, None)];
        let calc = MetricsCalculator::new(&results, None);
        assert_eq!(calc.precision(), 0.5);
        assert!((calc.recall() - 2.0 / 3.0).abs() < 1e-9);
    }

    #[test]
    fn precision_empty_retrieved_is_zero() {
        let results = vec![qr(&["a"], &[], None, None)];
        assert_eq!(MetricsCalculator::new(&results, None).precision(), 0.0);
    }

    #[test]
    fn recall_empty_ground_truth_is_zero() {
        let results = vec![qr(&[], &["a"], None, None)];
        assert_eq!(MetricsCalculator::new(&results, None).recall(), 0.0);
    }

    #[test]
    fn f1_zero_when_precision_and_recall_both_zero() {
        let results = vec![qr(&["a"], &["b"], None, None)];
        assert_eq!(MetricsCalculator::new(&results, None).f1(), 0.0);
    }

    #[test]
    fn mrr_finds_rank_of_first_relevant_item() {
        let results = vec![qr(&["c"], &["a", "b", "c", "d"], None, None)];
        assert!((MetricsCalculator::new(&results, None).mrr() - 1.0 / 3.0).abs() < 1e-9);
    }

    #[test]
    fn mrr_zero_when_no_relevant_item_found() {
        let results = vec![qr(&["z"], &["a", "b"], None, None)];
        assert_eq!(MetricsCalculator::new(&results, None).mrr(), 0.0);
    }

    #[test]
    fn latency_avg_median_and_percentiles() {
        let results = vec![
            qr(&[], &[], Some(10.0), None),
            qr(&[], &[], Some(20.0), None),
            qr(&[], &[], Some(30.0), None),
            qr(&[], &[], Some(40.0), None),
            qr(&[], &[], Some(50.0), None),
        ];
        let calc = MetricsCalculator::new(&results, None);
        assert_eq!(calc.latency_avg(), 30.0);
        assert_eq!(calc.latency_median(), 30.0);
        assert_eq!(calc.latency_percentile(0), 10.0);
        assert_eq!(calc.latency_percentile(100), 50.0);
    }

    #[test]
    fn server_latencies_absent_by_default() {
        let results = vec![qr(&[], &[], Some(1.0), None)];
        assert!(!MetricsCalculator::new(&results, None).has_server_latencies());
    }

    #[test]
    fn server_latencies_present_when_any_result_has_one() {
        let results = vec![qr(&[], &[], Some(1.0), None), qr(&[], &[], Some(2.0), Some(5))];
        let calc = MetricsCalculator::new(&results, None);
        assert!(calc.has_server_latencies());
        assert_eq!(calc.server_latency_avg(), 5.0);
        assert_eq!(calc.server_latency_median(), 5.0);
    }

    #[test]
    fn throughput_uses_wall_clock_when_provided() {
        let results = vec![qr(&[], &[], Some(100.0), None); 10];
        let calc = MetricsCalculator::new(&results, Some(1000));
        assert_eq!(calc.throughput(), 10.0);
    }

    #[test]
    fn throughput_falls_back_to_aggregate_latency_model_without_wall_clock() {
        // 2 queries, 100ms client latency each summed = 200ms -> 2 queries / 0.2s = 10 qps
        let results = vec![qr(&[], &[], Some(100.0), None), qr(&[], &[], Some(100.0), None)];
        let calc = MetricsCalculator::new(&results, None);
        assert_eq!(calc.throughput(), 10.0);
        assert_eq!(calc.throughput_aggregate_latency_model(), 10.0);
    }

    #[test]
    fn throughput_zero_wall_clock_falls_back_to_aggregate_model() {
        let results = vec![qr(&[], &[], Some(100.0), None)];
        let calc = MetricsCalculator::new(&results, Some(0));
        assert_eq!(calc.throughput(), calc.throughput_aggregate_latency_model());
    }

    #[test]
    fn throughput_zero_when_no_latencies_recorded() {
        let results = vec![qr(&[], &[], None, None)];
        assert_eq!(MetricsCalculator::new(&results, None).throughput(), 0.0);
    }
}
