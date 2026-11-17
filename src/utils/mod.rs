//! Small cross-cutting helpers: retry-with-backoff, downloading a missing dataset file, and
//! logging setup driven by config.

mod file_downloader;
mod logging;
mod retry;

pub use file_downloader::ensure_file_exists;
pub use logging::configure_logging;
pub use retry::{compute_retry_delay_ms, retry_with_backoff};

#[derive(Debug, thiserror::Error)]
pub enum UtilsError {
    #[error("{0}")]
    Message(String),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}
