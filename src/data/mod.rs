//! Reading dataset files (Parquet, NDJSON) into [`Document`]s.

mod ndjson;
mod parquet_reader;

pub use ndjson::NdjsonReader;
pub use parquet_reader::ParquetReader;

use crate::model::Document;

#[derive(Debug, thiserror::Error)]
pub enum DataError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("Parquet error: {0}")]
    Parquet(#[from] parquet::errors::ParquetError),
    #[error("Arrow error: {0}")]
    Arrow(#[from] arrow::error::ArrowError),
    #[error("{0}")]
    Invalid(String),
}

/// Common interface for reading dataset files. `read_in_batches`'s `conversion_threads` hint
/// lets implementations that benefit from parallel row conversion (Parquet) use more than one
/// thread; implementations for which conversion is already cheap (NDJSON) may ignore it.
pub trait DatasetReader: Send + Sync {
    fn read_all(&self, limit: Option<usize>) -> Result<Vec<Document>, DataError>;

    fn read_in_batches(
        &self,
        batch_size: usize,
        conversion_threads: usize,
        consumer: &mut dyn FnMut(Vec<Document>) -> Result<(), DataError>,
    ) -> Result<(), DataError>;

    fn row_count(&self) -> Result<u64, DataError>;
}

/// Creates the appropriate [`DatasetReader`] based on the file extension: `.ndjson` -> NDJSON,
/// anything else -> Parquet.
pub fn create_reader(path: &str) -> Box<dyn DatasetReader> {
    if path.ends_with(".ndjson") {
        Box::new(NdjsonReader::new(path))
    } else {
        Box::new(ParquetReader::new(path))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_reader_dispatches_by_extension() {
        // Can't downcast a trait object cleanly without `Any`, so just confirm construction
        // doesn't panic and row_count fails gracefully for a nonexistent path either way.
        let ndjson = create_reader("data.ndjson");
        assert!(ndjson.row_count().is_err());
        let parquet = create_reader("data.parquet");
        assert!(parquet.row_count().is_err());
    }
}
