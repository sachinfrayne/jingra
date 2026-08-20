//! Retry-with-backoff for transient failures. Ported from the Java `RetryHelper`: capped
//! exponential backoff (`backoff_ms * 2^min(attempt, 6)`, capped at 60s), with `max_retries:
//! None` meaning infinite retries -- used for ingest, where giving up silently would be worse
//! than blocking.

use std::future::Future;
use std::time::Duration;

const MAX_BACKOFF_MS: u64 = 60_000;

/// Delay before the next retry. `attempt` is 0-based (the wait after the first failure uses 0).
pub fn compute_retry_delay_ms(attempt: u32, backoff_ms: u64) -> u64 {
    let exponential = backoff_ms.saturating_mul(1u64 << attempt.min(6));
    exponential.min(MAX_BACKOFF_MS)
}

/// Retries `operation` while `is_transient` returns true for its error, waiting
/// [`compute_retry_delay_ms`] between attempts. `max_retries: None` retries forever.
pub async fn retry_with_backoff<T, E, F, Fut>(
    mut operation: F,
    max_retries: Option<u32>,
    backoff_ms: u64,
    is_transient: impl Fn(&E) -> bool,
) -> Result<T, E>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<T, E>>,
{
    let mut attempt: u32 = 0;
    loop {
        match operation().await {
            Ok(v) => return Ok(v),
            Err(e) => {
                if !is_transient(&e) {
                    return Err(e);
                }
                let delay = compute_retry_delay_ms(attempt, backoff_ms);
                tracing::warn!(
                    "Transient failure on attempt {} (max: {}). Retrying in {delay}ms...",
                    attempt + 1,
                    max_retries.map(|m| m.to_string()).unwrap_or_else(|| "unlimited".to_string())
                );
                tokio::time::sleep(Duration::from_millis(delay)).await;
                attempt += 1;
                if let Some(max) = max_retries
                    && attempt > max
                {
                    return Err(e);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    #[test]
    fn compute_retry_delay_ms_grows_exponentially_then_caps() {
        assert_eq!(compute_retry_delay_ms(0, 1000), 1000);
        assert_eq!(compute_retry_delay_ms(1, 1000), 2000);
        assert_eq!(compute_retry_delay_ms(2, 1000), 4000);
        assert_eq!(compute_retry_delay_ms(6, 1000), 64_000.min(MAX_BACKOFF_MS));
        assert_eq!(compute_retry_delay_ms(20, 1000), MAX_BACKOFF_MS);
    }

    #[tokio::test(start_paused = true)]
    async fn retries_transient_failures_until_success() {
        let attempts = AtomicU32::new(0);
        let result: Result<u32, &str> = retry_with_backoff(
            || {
                let n = attempts.fetch_add(1, Ordering::SeqCst);
                async move { if n < 2 { Err("transient") } else { Ok(42) } }
            },
            Some(5),
            10,
            |_e: &&str| true,
        )
        .await;
        assert_eq!(result, Ok(42));
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn non_transient_failure_returns_immediately_without_retry() {
        let attempts = AtomicU32::new(0);
        let result: Result<u32, &str> = retry_with_backoff(
            || {
                attempts.fetch_add(1, Ordering::SeqCst);
                async { Err("permanent") }
            },
            Some(5),
            10,
            |_e: &&str| false,
        )
        .await;
        assert_eq!(result, Err("permanent"));
        assert_eq!(attempts.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn gives_up_after_max_retries_exhausted() {
        let attempts = AtomicU32::new(0);
        let result: Result<u32, &str> = retry_with_backoff(
            || {
                attempts.fetch_add(1, Ordering::SeqCst);
                async { Err("transient") }
            },
            Some(2),
            1,
            |_e: &&str| true,
        )
        .await;
        assert_eq!(result, Err("transient"));
        // Initial attempt + 2 retries = 3 calls.
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
    }
}
