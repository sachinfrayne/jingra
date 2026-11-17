//! Opens the `object_store` backing a `results_uri`. Shared by the results sink (`bench`) and
//! the results reader (`report`) so both interpret a URI identically.
//!
//! `object_store::parse_url` rejects the relative `file://./results` form that jingra documents
//! and uses as its default (a URL host of `.` is not a path), so `file://` URIs are resolved here
//! against the current directory and handed to `LocalFileSystem` directly. Every other scheme
//! (`s3://`, `gs://`, `az://`, `memory://`, ...) goes through `parse_url` unchanged.

use super::OutputError;
use object_store::local::LocalFileSystem;
use object_store::path::Path as ObjectPath;
use object_store::ObjectStore;
use std::path::PathBuf;
use url::Url;

/// What to do when a `file://` location does not exist yet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalDir {
    /// Create it (writers: `bench`).
    Create,
    /// Fail with a clear message (readers: `report`, where a missing directory is almost always
    /// a typo and silently creating it would hide that).
    MustExist,
}

/// The filesystem path of a `file://` URI, or `None` for any other scheme. Both
/// `file:///abs/dir` and the relative `file://./dir` are accepted.
pub fn local_path_from_uri(uri: &str) -> Option<PathBuf> {
    let rest = uri.strip_prefix("file://")?;
    // `file://localhost/abs/path` is the same as `file:///abs/path`.
    let rest = rest.strip_prefix("localhost").filter(|r| r.starts_with('/')).unwrap_or(rest);
    if rest.is_empty() {
        return None;
    }
    // URIs percent-encode characters such as spaces (`my%20results`); decode them to the real
    // path. Sequences that are not valid escapes are left as they are.
    Some(PathBuf::from(percent_encoding::percent_decode_str(rest).decode_utf8_lossy().into_owned()))
}

pub fn open_store(uri: &str, local_dir: LocalDir) -> Result<(Box<dyn ObjectStore>, ObjectPath), OutputError> {
    if let Some(path) = local_path_from_uri(uri) {
        return open_local(uri, &path, local_dir);
    }
    let url = Url::parse(uri).map_err(|e| {
        OutputError::Message(format!(
            "invalid results_uri '{uri}': {e} (expected a URL such as file://./results, s3://bucket/prefix, gs://bucket/prefix or az://container/prefix)"
        ))
    })?;
    let (store, prefix) = object_store::parse_url(&url)
        .map_err(|e| OutputError::Message(format!("failed to open results_uri '{uri}': {e}")))?;
    Ok((store, prefix))
}

fn open_local(
    uri: &str,
    path: &std::path::Path,
    local_dir: LocalDir,
) -> Result<(Box<dyn ObjectStore>, ObjectPath), OutputError> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map_err(|e| OutputError::Message(format!("cannot resolve results_uri '{uri}': {e}")))?
            .join(path)
    };
    match local_dir {
        LocalDir::Create => std::fs::create_dir_all(&absolute)
            .map_err(|e| OutputError::Message(format!("cannot create results directory {}: {e}", absolute.display())))?,
        LocalDir::MustExist if !absolute.is_dir() => {
            return Err(OutputError::Message(format!(
                "results directory {} (from results_uri '{uri}') does not exist; has `jingra bench` run with this results_uri?",
                absolute.display()
            )));
        }
        LocalDir::MustExist => {}
    }
    let store = LocalFileSystem::new_with_prefix(&absolute)
        .map_err(|e| OutputError::Message(format!("failed to open results_uri '{uri}': {e}")))?;
    Ok((Box::new(store), ObjectPath::default()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use object_store::ObjectStoreExt;

    #[test]
    fn local_path_from_uri_accepts_absolute_and_relative_file_uris() {
        assert_eq!(local_path_from_uri("file:///var/results"), Some(PathBuf::from("/var/results")));
        assert_eq!(local_path_from_uri("file://./results"), Some(PathBuf::from("./results")));
        assert_eq!(local_path_from_uri("file://results"), Some(PathBuf::from("results")));
    }

    #[test]
    fn local_path_from_uri_decodes_percent_escapes_and_strips_localhost() {
        assert_eq!(local_path_from_uri("file:///tmp/my%20results"), Some(PathBuf::from("/tmp/my results")));
        assert_eq!(local_path_from_uri("file://localhost/var/r"), Some(PathBuf::from("/var/r")));
        // Not an escape sequence: left alone.
        assert_eq!(local_path_from_uri("file://./100%"), Some(PathBuf::from("./100%")));
    }

    #[test]
    fn local_path_from_uri_is_none_for_other_schemes_and_empty_paths() {
        assert_eq!(local_path_from_uri("s3://bucket/prefix"), None);
        assert_eq!(local_path_from_uri("memory:///"), None);
        assert_eq!(local_path_from_uri("file://"), None);
        assert_eq!(local_path_from_uri("not a url"), None);
    }

    #[tokio::test]
    async fn the_documented_default_results_uri_opens_and_is_writable() {
        // Regression: `file://./results` (the documented default) used to fail with
        // "Unable to recognise URL". Use a relative dir under the package root (the test cwd).
        let rel = format!("target/jingra-test-results-{}", uuid::Uuid::new_v4());
        let uri = format!("file://./{rel}");

        let (store, prefix) = open_store(&uri, LocalDir::Create).unwrap();
        store.put(&prefix.join("a.json"), b"{}".to_vec().into()).await.unwrap();

        assert!(std::env::current_dir().unwrap().join(&rel).join("a.json").exists());
        std::fs::remove_dir_all(std::env::current_dir().unwrap().join(&rel)).unwrap();
    }

    #[test]
    fn open_store_creates_a_missing_directory_when_asked() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("nested").join("results");
        open_store(&format!("file://{}", target.display()), LocalDir::Create).unwrap();
        assert!(target.is_dir());
    }

    #[test]
    fn open_store_reports_a_missing_directory_when_it_must_exist() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("typo");
        let Err(err) = open_store(&format!("file://{}", target.display()), LocalDir::MustExist) else {
            panic!("a missing directory must be an error for readers");
        };
        assert!(err.to_string().contains("does not exist"), "{err}");
        assert!(!target.exists(), "readers must not create the directory");
    }

    #[test]
    fn open_store_rejects_text_that_is_not_a_url() {
        let Err(err) = open_store("not a url", LocalDir::Create) else {
            panic!("must reject non-URLs");
        };
        assert!(err.to_string().contains("expected a URL"), "{err}");
    }

    #[test]
    fn open_store_passes_other_schemes_through_to_object_store() {
        assert!(open_store("memory:///", LocalDir::Create).is_ok());
    }
}
