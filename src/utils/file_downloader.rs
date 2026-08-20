//! Downloads a dataset file from a URL (read from an env var) if it isn't already present
//! locally. Ported from the Java `FileDownloader`, minus the throttled progress-logging (the
//! Rust version streams straight to disk without needing to; see [`download_once`]).

use super::UtilsError;
use futures::StreamExt;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

/// Ensures `file_path` exists locally, downloading from the URL in env var `url_env` if it's
/// missing or (for `.parquet` files) corrupted, per the `PAR1` magic-byte check.
pub async fn ensure_file_exists(file_path: &str, url_env: &str) -> Result<(), UtilsError> {
    let path = Path::new(file_path);

    if path.exists() {
        let len = std::fs::metadata(path)?.len();
        if len > 0 && is_valid_if_parquet(path)? {
            tracing::info!("File already exists locally: {file_path} ({len} bytes)");
            return Ok(());
        }
        tracing::warn!("Existing file is corrupted, deleting: {file_path}");
        std::fs::remove_file(path)?;
    }

    let url = std::env::var(url_env).map_err(|_| {
        UtilsError::Message(format!("File not found: {file_path} and no URL provided in env var: {url_env}"))
    })?;
    download_file(&url, path).await
}

/// Parquet files carry `PAR1` magic bytes at both start and end; other formats (NDJSON) are
/// trusted once they exist and are non-empty.
fn is_valid_if_parquet(path: &Path) -> Result<bool, UtilsError> {
    if path.extension().and_then(|e| e.to_str()) != Some("parquet") {
        return Ok(true);
    }
    let mut file = std::fs::File::open(path)?;
    if file.metadata()?.len() < 8 {
        return Ok(false);
    }
    let mut header = [0u8; 4];
    file.read_exact(&mut header)?;
    file.seek(SeekFrom::End(-4))?;
    let mut footer = [0u8; 4];
    file.read_exact(&mut footer)?;
    Ok(&header == b"PAR1" && &footer == b"PAR1")
}

async fn download_file(url: &str, path: &Path) -> Result<(), UtilsError> {
    tracing::info!("Downloading file from {url} to {}", path.display());
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let mut attempt = 0u32;
    loop {
        match download_once(url, path).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                attempt += 1;
                if !retryable_download_failure(&e.to_string()) || attempt == 3 {
                    return Err(e);
                }
                let backoff = 500 * attempt as u64;
                tracing::warn!("Download attempt {attempt} failed, retrying after {backoff}ms: {e}");
                tokio::time::sleep(Duration::from_millis(backoff)).await;
            }
        }
    }
}

fn retryable_download_failure(msg: &str) -> bool {
    msg.contains("HTTP 5") || msg.contains(" 502") || msg.contains(" 503") || msg.contains(" 504")
}

async fn download_once(url: &str, path: &Path) -> Result<(), UtilsError> {
    let response = reqwest::get(url).await.map_err(|e| UtilsError::Message(e.to_string()))?;
    let status = response.status();
    if !status.is_success() {
        return Err(UtilsError::Message(format!("Failed to download file. HTTP {status}")));
    }

    let mut file = tokio::fs::File::create(path).await?;
    let mut stream = response.bytes_stream();
    let result: Result<(), UtilsError> = async {
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| UtilsError::Message(e.to_string()))?;
            file.write_all(&chunk).await?;
        }
        Ok(())
    }
    .await;

    if result.is_err() {
        drop(file);
        let _ = tokio::fs::remove_file(path).await;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retryable_download_failure_matches_5xx_patterns() {
        assert!(retryable_download_failure("Failed to download file. HTTP 503 Service Unavailable"));
        assert!(retryable_download_failure("got 502 bad gateway"));
        assert!(!retryable_download_failure("Failed to download file. HTTP 404 Not Found"));
    }

    #[test]
    fn is_valid_if_parquet_accepts_non_parquet_extensions() {
        let f = tempfile::Builder::new().suffix(".ndjson").tempfile().unwrap();
        std::fs::write(f.path(), b"{}").unwrap();
        assert!(is_valid_if_parquet(f.path()).unwrap());
    }

    #[test]
    fn is_valid_if_parquet_rejects_missing_magic_bytes() {
        let f = tempfile::Builder::new().suffix(".parquet").tempfile().unwrap();
        std::fs::write(f.path(), b"not a real parquet file").unwrap();
        assert!(!is_valid_if_parquet(f.path()).unwrap());
    }

    #[test]
    fn is_valid_if_parquet_accepts_correct_magic_bytes() {
        let f = tempfile::Builder::new().suffix(".parquet").tempfile().unwrap();
        let mut contents = b"PAR1".to_vec();
        contents.extend_from_slice(b"...data...");
        contents.extend_from_slice(b"PAR1");
        std::fs::write(f.path(), &contents).unwrap();
        assert!(is_valid_if_parquet(f.path()).unwrap());
    }

    #[tokio::test]
    async fn ensure_file_exists_skips_download_when_file_already_valid() {
        let f = tempfile::Builder::new().suffix(".ndjson").tempfile().unwrap();
        std::fs::write(f.path(), b"{}").unwrap();
        // No env var set; if this tried to download it would error, so success proves the
        // existing-file fast path was taken.
        ensure_file_exists(f.path().to_str().unwrap(), "JINGRA_TEST_UNSET_DOWNLOAD_URL_XYZ")
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn ensure_file_exists_errors_when_missing_and_no_url_env_set() {
        let dir = tempfile::tempdir().unwrap();
        let missing_path = dir.path().join("does-not-exist.ndjson");
        let err = ensure_file_exists(missing_path.to_str().unwrap(), "JINGRA_TEST_UNSET_DOWNLOAD_URL_XYZ")
            .await
            .unwrap_err();
        assert!(matches!(err, UtilsError::Message(_)));
    }
}
