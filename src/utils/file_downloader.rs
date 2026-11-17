//! Downloads a dataset file from a URL (read from an env var) if it isn't already present
//! locally. Streams straight to disk; see [`download_once`].

use super::UtilsError;
use futures::StreamExt;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

/// Ensures `file_path` exists locally, downloading from the URL in env var `url_env` if it's
/// missing or (for `.parquet` files) corrupted, per the `PAR1` magic-byte check.
pub async fn ensure_file_exists(file_path: &str, url_env: &str) -> Result<(), UtilsError> {
    ensure_file_exists_from(file_path, url_env, std::env::var(url_env).ok()).await
}

/// [`ensure_file_exists`] with the env var's value passed in, so tests can exercise the download
/// paths without mutating the process-global environment (which is `unsafe` and races with
/// tests running in parallel).
async fn ensure_file_exists_from(file_path: &str, url_env: &str, url: Option<String>) -> Result<(), UtilsError> {
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

    let url = url.ok_or_else(|| {
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
        // tokio::fs::File hands writes to a background thread: without an explicit flush, this
        // function can return (and the caller read the file) before the data has landed.
        file.flush().await?;
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

    /// Serves each `(status, body)` pair to one accepted connection in order, on a background
    /// thread, over a real localhost TCP socket -- exercises the actual `reqwest` download path
    /// without pulling in a mocking-server dependency for what's a handful of tiny fixed
    /// responses.
    fn spawn_test_server(responses: Vec<(u16, &'static [u8])>) -> String {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for (status, body) in responses {
                let Ok((mut stream, _)) = listener.accept() else { return };

                // Read until the end of the request headers before responding: dropping the
                // socket while the client's request bytes are still unread can make the OS send
                // an RST instead of a graceful FIN, which some HTTP clients then surface as a
                // truncated/empty body even though the response was fully written and flushed.
                let mut received = Vec::new();
                let mut buf = [0u8; 512];
                while !received.windows(4).any(|w| w == b"\r\n\r\n") {
                    match stream.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => received.extend_from_slice(&buf[..n]),
                    }
                }

                let reason = if status == 200 { "OK" } else { "Error" };
                let header = format!("HTTP/1.1 {status} {reason}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                let _ = stream.write_all(header.as_bytes());
                let _ = stream.write_all(body);
                let _ = stream.flush();
                let _ = stream.shutdown(std::net::Shutdown::Write);
            }
        });
        format!("http://127.0.0.1:{port}")
    }

    #[tokio::test]
    async fn ensure_file_exists_downloads_when_missing() {
        let base_url = spawn_test_server(vec![(200, b"hello world")]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("downloaded.ndjson");

        ensure_file_exists_from(path.to_str().unwrap(), "JINGRA_TEST_DOWNLOAD_URL", Some(base_url)).await.unwrap();

        assert_eq!(std::fs::read(&path).unwrap(), b"hello world");
    }

    #[tokio::test]
    async fn ensure_file_exists_redownloads_a_corrupted_parquet_file() {
        let base_url = spawn_test_server(vec![(200, b"PAR1validPAR1")]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("data.parquet");
        std::fs::write(&path, b"not a real parquet file").unwrap();

        ensure_file_exists_from(path.to_str().unwrap(), "JINGRA_TEST_DOWNLOAD_URL", Some(base_url)).await.unwrap();

        assert_eq!(std::fs::read(&path).unwrap(), b"PAR1validPAR1");
    }

    #[tokio::test]
    async fn ensure_file_exists_fails_immediately_on_non_retryable_status() {
        let base_url = spawn_test_server(vec![(404, b"not found")]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("missing.ndjson");

        let err = ensure_file_exists_from(path.to_str().unwrap(), "JINGRA_TEST_DOWNLOAD_URL", Some(base_url))
            .await
            .unwrap_err();
        assert!(err.to_string().contains("404"));
        assert!(!path.exists());
    }

    #[tokio::test(start_paused = true)]
    async fn ensure_file_exists_retries_a_transient_5xx_then_succeeds() {
        let base_url = spawn_test_server(vec![(503, b"try again"), (200, b"eventually ok")]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("retried.ndjson");

        ensure_file_exists_from(path.to_str().unwrap(), "JINGRA_TEST_DOWNLOAD_URL", Some(base_url)).await.unwrap();

        assert_eq!(std::fs::read(&path).unwrap(), b"eventually ok");
    }
}
