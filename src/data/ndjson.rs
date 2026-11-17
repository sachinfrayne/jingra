use super::{DataError, DatasetReader};
use crate::model::Document;
use std::fs::File;
use std::io::{BufRead, BufReader};

/// Reads newline-delimited JSON files, one [`Document`] per non-blank line. Cheap enough that
/// `conversion_threads` in [`DatasetReader::read_in_batches`] is ignored.
pub struct NdjsonReader {
    path: String,
}

impl NdjsonReader {
    pub fn new(path: impl Into<String>) -> Self {
        Self { path: path.into() }
    }

    fn open(&self) -> Result<BufReader<File>, DataError> {
        Ok(BufReader::new(File::open(&self.path)?))
    }

    fn parse_line(line: &str) -> Result<Document, DataError> {
        let map: serde_json::Map<String, serde_json::Value> = serde_json::from_str(line)?;
        Ok(Document::new(map.into_iter().collect()))
    }
}

impl DatasetReader for NdjsonReader {
    fn read_all(&self, limit: Option<usize>) -> Result<Vec<Document>, DataError> {
        let reader = self.open()?;
        let mut docs = Vec::new();
        for line in reader.lines() {
            let line = line?;
            if line.trim().is_empty() {
                continue;
            }
            docs.push(Self::parse_line(&line)?);
            if let Some(l) = limit
                && docs.len() >= l
            {
                break;
            }
        }
        Ok(docs)
    }

    fn read_in_batches(
        &self,
        batch_size: usize,
        _conversion_threads: usize,
        consumer: &mut dyn FnMut(Vec<Document>) -> Result<(), DataError>,
    ) -> Result<(), DataError> {
        let reader = self.open()?;
        let mut batch = Vec::with_capacity(batch_size);
        for line in reader.lines() {
            let line = line?;
            if line.trim().is_empty() {
                continue;
            }
            batch.push(Self::parse_line(&line)?);
            if batch.len() >= batch_size {
                consumer(std::mem::replace(&mut batch, Vec::with_capacity(batch_size)))?;
            }
        }
        if !batch.is_empty() {
            consumer(batch)?;
        }
        Ok(())
    }

    fn row_count(&self) -> Result<u64, DataError> {
        let reader = self.open()?;
        let mut count = 0u64;
        for line in reader.lines() {
            let line = line?;
            if !line.trim().is_empty() {
                count += 1;
            }
        }
        Ok(count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_ndjson(lines: &[&str]) -> tempfile::NamedTempFile {
        let mut f = tempfile::Builder::new().suffix(".ndjson").tempfile().unwrap();
        for line in lines {
            writeln!(f, "{line}").unwrap();
        }
        f
    }

    #[test]
    fn read_all_parses_every_non_blank_line() {
        let f = write_ndjson(&[
            r#"{"id": 1, "embedding": [1.0, 2.0]}"#,
            "",
            r#"{"id": 2, "embedding": [3.0, 4.0]}"#,
        ]);
        let reader = NdjsonReader::new(f.path().to_str().unwrap());
        let docs = reader.read_all(None).unwrap();
        assert_eq!(docs.len(), 2);
        assert_eq!(docs[0].get_id_string("id"), Some("1".to_string()));
        assert_eq!(docs[1].get_float_list("embedding"), Some(vec![3.0, 4.0]));
    }

    #[test]
    fn read_all_respects_limit() {
        let f = write_ndjson(&[r#"{"id": 1}"#, r#"{"id": 2}"#, r#"{"id": 3}"#]);
        let reader = NdjsonReader::new(f.path().to_str().unwrap());
        let docs = reader.read_all(Some(2)).unwrap();
        assert_eq!(docs.len(), 2);
    }

    #[test]
    fn read_in_batches_chunks_by_batch_size_including_final_partial_batch() {
        let f = write_ndjson(&[
            r#"{"id": 1}"#,
            r#"{"id": 2}"#,
            r#"{"id": 3}"#,
            r#"{"id": 4}"#,
            r#"{"id": 5}"#,
        ]);
        let reader = NdjsonReader::new(f.path().to_str().unwrap());
        let mut batch_sizes = Vec::new();
        reader
            .read_in_batches(2, 1, &mut |batch| {
                batch_sizes.push(batch.len());
                Ok(())
            })
            .unwrap();
        assert_eq!(batch_sizes, vec![2, 2, 1]);
    }

    #[test]
    fn row_count_counts_non_blank_lines() {
        let f = write_ndjson(&[r#"{"id": 1}"#, "", r#"{"id": 2}"#]);
        let reader = NdjsonReader::new(f.path().to_str().unwrap());
        assert_eq!(reader.row_count().unwrap(), 2);
    }

    #[test]
    fn missing_file_returns_io_error() {
        let reader = NdjsonReader::new("/no/such/file.ndjson");
        assert!(matches!(reader.read_all(None), Err(DataError::Io(_))));
    }

    #[test]
    fn malformed_line_returns_json_error() {
        let f = write_ndjson(&["not json"]);
        let reader = NdjsonReader::new(f.path().to_str().unwrap());
        assert!(matches!(reader.read_all(None), Err(DataError::Json(_))));
    }
}
