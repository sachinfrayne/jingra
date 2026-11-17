//! Runs the benchmark: executes queries under warmup/measurement rounds and computes
//! recall/precision/latency/throughput metrics.

mod evaluator;
mod metrics;

pub use evaluator::{resolve_run_id, BenchmarkEvaluator, EvaluationError};
pub use metrics::{MetricsCalculator, QueryResult};
