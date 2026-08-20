use super::{DataError, DatasetReader};
use crate::model::Document;
use arrow::json::writer::LineDelimitedWriter;
use arrow::record_batch::RecordBatch;
use parquet::arrow::arrow_reader::ParquetRecordBatchReaderBuilder;
use std::fs::File;

/// Reads Parquet files via arrow-rs, converting each row to a [`Document`].
///
/// Row-to-JSON conversion is delegated to `arrow_json`'s own Arrow->JSON writer rather than
/// hand-matching every Arrow `DataType` — it already handles nested lists/structs/nulls
/// correctly, which is exactly the class of bug the Java version's manual Avro-value-unwrapping
/// code existed to work around.
pub struct ParquetReader {
    path: String,
}

impl ParquetReader {
    pub fn new(path: impl Into<String>) -> Self {
        Self { path: path.into() }
    }

    fn open_builder(&self) -> Result<ParquetRecordBatchReaderBuilder<File>, DataError> {
        let file = File::open(&self.path)?;
        Ok(ParquetRecordBatchReaderBuilder::try_new(file)?)
    }

    fn batch_to_documents(batch: &RecordBatch) -> Result<Vec<Document>, DataError> {
        let mut buf: Vec<u8> = Vec::new();
        {
            let mut writer = LineDelimitedWriter::new(&mut buf);
            writer.write(batch)?;
            writer.finish()?;
        }
        let text = String::from_utf8(buf)
            .map_err(|e| DataError::Invalid(format!("non-UTF8 JSON from Arrow writer: {e}")))?;
        let mut docs = Vec::with_capacity(batch.num_rows());
        for line in text.lines() {
            if line.trim().is_empty() {
                continue;
            }
            let map: serde_json::Map<String, serde_json::Value> = serde_json::from_str(line)?;
            docs.push(Document::new(map.into_iter().collect()));
        }
        Ok(docs)
    }

    /// Converts one batch, splitting it into `conversion_threads` roughly-equal row slices and
    /// converting them in parallel scoped threads when that's worth it. Parquet decode into
    /// Arrow arrays remains single-threaded (the reader itself isn't `Sync`-shared here); this
    /// only parallelizes the Arrow->JSON->Document conversion step, mirroring the Java version's
    /// division of labor (single-threaded Parquet decode, parallel Avro-to-Document conversion).
    fn convert_batch(batch: RecordBatch, conversion_threads: usize) -> Result<Vec<Document>, DataError> {
        let rows = batch.num_rows();
        if conversion_threads <= 1 || rows < conversion_threads {
            return Self::batch_to_documents(&batch);
        }

        let chunk_len = rows.div_ceil(conversion_threads);
        let mut slices = Vec::new();
        let mut offset = 0;
        while offset < rows {
            let len = chunk_len.min(rows - offset);
            slices.push(batch.slice(offset, len));
            offset += len;
        }

        let results: Vec<Result<Vec<Document>, DataError>> = std::thread::scope(|scope| {
            let handles: Vec<_> = slices
                .iter()
                .map(|s| scope.spawn(|| Self::batch_to_documents(s)))
                .collect();
            handles
                .into_iter()
                .map(|h| h.join().expect("parquet conversion thread panicked"))
                .collect()
        });

        let mut docs = Vec::with_capacity(rows);
        for r in results {
            docs.extend(r?);
        }
        Ok(docs)
    }
}

impl DatasetReader for ParquetReader {
    fn read_all(&self, limit: Option<usize>) -> Result<Vec<Document>, DataError> {
        let reader = self.open_builder()?.build()?;
        let mut docs = Vec::new();
        for batch in reader {
            let batch = batch?;
            let mut converted = Self::batch_to_documents(&batch)?;
            if let Some(l) = limit {
                let remaining = l.saturating_sub(docs.len());
                converted.truncate(remaining);
            }
            docs.extend(converted);
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
        conversion_threads: usize,
        consumer: &mut dyn FnMut(Vec<Document>) -> Result<(), DataError>,
    ) -> Result<(), DataError> {
        if conversion_threads == 0 {
            return Err(DataError::Invalid(format!(
                "conversion_threads must be >= 1, got: {conversion_threads}"
            )));
        }
        let reader = self.open_builder()?.with_batch_size(batch_size).build()?;
        for batch in reader {
            let batch = batch?;
            if batch.num_rows() == 0 {
                continue;
            }
            let docs = Self::convert_batch(batch, conversion_threads)?;
            consumer(docs)?;
        }
        Ok(())
    }

    fn row_count(&self) -> Result<u64, DataError> {
        let builder = self.open_builder()?;
        Ok(builder.metadata().file_metadata().num_rows() as u64)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use arrow::array::{Float32Array, Int64Array, ListArray, StringArray};
    use arrow::buffer::OffsetBuffer;
    use arrow::datatypes::{DataType, Field, Schema};
    use parquet::arrow::ArrowWriter;
    use std::sync::Arc;

    /// Builds a small self-contained Parquet file (id: i64, embedding: list<float32>,
    /// label: utf8) so tests don't depend on committed binary fixtures.
    fn write_fixture(num_rows: i64) -> tempfile::NamedTempFile {
        let schema = Arc::new(Schema::new(vec![
            Field::new("id", DataType::Int64, false),
            Field::new(
                "embedding",
                DataType::List(Arc::new(Field::new("item", DataType::Float32, true))),
                false,
            ),
            Field::new("label", DataType::Utf8, false),
        ]));

        let ids: Vec<i64> = (0..num_rows).collect();
        let labels: Vec<String> = ids.iter().map(|i| format!("doc-{i}")).collect();
        let values: Vec<f32> = ids.iter().flat_map(|i| [*i as f32, *i as f32 + 0.5]).collect();
        let offsets = OffsetBuffer::new((0..=num_rows as i32).map(|i| i * 2).collect());
        let embedding = ListArray::new(
            Arc::new(Field::new("item", DataType::Float32, true)),
            offsets,
            Arc::new(Float32Array::from(values)),
            None,
        );

        let batch = RecordBatch::try_new(
            schema.clone(),
            vec![
                Arc::new(Int64Array::from(ids)),
                Arc::new(embedding),
                Arc::new(StringArray::from(labels)),
            ],
        )
        .unwrap();

        let f = tempfile::Builder::new().suffix(".parquet").tempfile().unwrap();
        let file = f.reopen().unwrap();
        let mut writer = ArrowWriter::try_new(file, schema, None).unwrap();
        writer.write(&batch).unwrap();
        writer.close().unwrap();
        f
    }

    #[test]
    fn read_all_converts_every_row() {
        let f = write_fixture(5);
        let reader = ParquetReader::new(f.path().to_str().unwrap());
        let docs = reader.read_all(None).unwrap();
        assert_eq!(docs.len(), 5);
        assert_eq!(docs[2].get_id_string("id"), Some("2".to_string()));
        assert_eq!(docs[2].get_float_list("embedding"), Some(vec![2.0, 2.5]));
        assert_eq!(docs[2].get_str("label"), Some("doc-2"));
    }

    #[test]
    fn read_all_respects_limit_across_batches() {
        let f = write_fixture(10);
        let reader = ParquetReader::new(f.path().to_str().unwrap());
        let docs = reader.read_all(Some(3)).unwrap();
        assert_eq!(docs.len(), 3);
    }

    #[test]
    fn row_count_matches_written_rows_without_reading_data() {
        let f = write_fixture(7);
        let reader = ParquetReader::new(f.path().to_str().unwrap());
        assert_eq!(reader.row_count().unwrap(), 7);
    }

    #[test]
    fn read_in_batches_respects_requested_batch_size() {
        let f = write_fixture(10);
        let reader = ParquetReader::new(f.path().to_str().unwrap());
        let mut batch_sizes = Vec::new();
        let mut total = 0;
        reader
            .read_in_batches(4, 1, &mut |batch| {
                batch_sizes.push(batch.len());
                total += batch.len();
                Ok(())
            })
            .unwrap();
        assert_eq!(total, 10);
        assert!(batch_sizes.iter().all(|&n| n <= 4));
    }

    #[test]
    fn read_in_batches_parallel_conversion_matches_single_threaded() {
        let f = write_fixture(20);
        let reader = ParquetReader::new(f.path().to_str().unwrap());

        let mut single = Vec::new();
        reader
            .read_in_batches(20, 1, &mut |batch| {
                single.extend(batch.into_iter().map(|d| d.get_id_string("id")));
                Ok(())
            })
            .unwrap();

        let mut parallel = Vec::new();
        reader
            .read_in_batches(20, 4, &mut |batch| {
                parallel.extend(batch.into_iter().map(|d| d.get_id_string("id")));
                Ok(())
            })
            .unwrap();

        assert_eq!(single, parallel);
    }

    #[test]
    fn read_in_batches_rejects_zero_conversion_threads() {
        let f = write_fixture(1);
        let reader = ParquetReader::new(f.path().to_str().unwrap());
        let err = reader.read_in_batches(10, 0, &mut |_| Ok(())).unwrap_err();
        assert!(matches!(err, DataError::Invalid(_)));
    }

    #[test]
    fn missing_file_returns_error() {
        let reader = ParquetReader::new("/no/such/file.parquet");
        assert!(reader.read_all(None).is_err());
    }
}
