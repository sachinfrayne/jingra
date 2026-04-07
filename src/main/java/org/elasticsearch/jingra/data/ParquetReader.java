package org.elasticsearch.jingra.data;

import org.elasticsearch.jingra.model.Document;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.schema.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Parquet files and converts rows to generic Document objects.
 */
public class ParquetReader {
    private static final Logger logger = LoggerFactory.getLogger(ParquetReader.class);

    private final String filePath;
    private final Configuration hadoopConfig;

    public ParquetReader(String filePath) {
        this.filePath = filePath;
        this.hadoopConfig = new Configuration();
        // Disable verbose Hadoop logging
        hadoopConfig.set("hadoop.home.dir", "/");
    }

    /**
     * Read all documents from the Parquet file.
     *
     * @return list of documents
     * @throws IOException if reading fails
     */
    public List<Document> readAll() throws IOException {
        return readAll(-1);
    }

    /**
     * Read documents from the Parquet file with optional limit.
     *
     * @param limit maximum number of documents to read (-1 for all)
     * @return list of documents
     * @throws IOException if reading fails
     */
    public List<Document> readAll(int limit) throws IOException {
        List<Document> documents = new ArrayList<>();

        logger.info("Reading Parquet file: {}", filePath);
        Path path = new Path(filePath);

        try (org.apache.parquet.hadoop.ParquetReader<org.apache.avro.generic.GenericRecord> reader =
                     org.apache.parquet.avro.AvroParquetReader
                             .<org.apache.avro.generic.GenericRecord>builder(HadoopInputFile.fromPath(path, hadoopConfig))
                             .build()) {

            org.apache.avro.generic.GenericRecord record;
            int count = 0;

            while ((record = reader.read()) != null) {
                Document doc = convertAvroRecordToDocument(record);
                documents.add(doc);
                count++;

                if (limit > 0 && count >= limit) {
                    break;
                }
            }

            logger.info("Finished reading {} documents from Parquet file", count);
        }

        return documents;
    }

    /**
     * Read documents in batches for efficient processing.
     *
     * @param batchSize size of each batch
     * @param consumer consumer to process each batch
     * @throws IOException if reading fails
     */
    public void readInBatches(int batchSize, BatchConsumer consumer) throws IOException {
        logger.info("Reading Parquet file in batches: {}", filePath);
        Path path = new Path(filePath);

        try (org.apache.parquet.hadoop.ParquetReader<org.apache.avro.generic.GenericRecord> reader =
                     org.apache.parquet.avro.AvroParquetReader
                             .<org.apache.avro.generic.GenericRecord>builder(HadoopInputFile.fromPath(path, hadoopConfig))
                             .build()) {

            List<Document> batch = new ArrayList<>(batchSize);
            org.apache.avro.generic.GenericRecord record;
            int totalCount = 0;

            while ((record = reader.read()) != null) {
                Document doc = convertAvroRecordToDocument(record);
                batch.add(doc);

                if (batch.size() >= batchSize) {
                    consumer.accept(batch);
                    totalCount += batch.size();
                    batch = new ArrayList<>(batchSize);
                }
            }

            // Process remaining documents
            if (!batch.isEmpty()) {
                consumer.accept(batch);
                totalCount += batch.size();
            }

            logger.info("Finished processing {} documents from Parquet file", totalCount);
        }
    }

    /**
     * Get the schema of the Parquet file.
     *
     * @return Parquet schema
     * @throws IOException if reading fails
     */
    public MessageType getSchema() throws IOException {
        Path path = new Path(filePath);
        try (ParquetFileReader reader = ParquetFileReader.open(HadoopInputFile.fromPath(path, hadoopConfig))) {
            return reader.getFooter().getFileMetaData().getSchema();
        }
    }

    /**
     * Convert an Avro GenericRecord to a Document.
     */
    private Document convertAvroRecordToDocument(org.apache.avro.generic.GenericRecord record) {
        Document doc = new Document();

        for (org.apache.avro.Schema.Field field : record.getSchema().getFields()) {
            String fieldName = field.name();
            Object value = record.get(fieldName);

            if (value != null) {
                Object convertedValue = convertAvroValue(value);
                if (convertedValue != null) {
                    doc.put(fieldName, convertedValue);
                }
            }
        }

        return doc;
    }

    /**
     * Recursively convert Avro values to Java types that can be serialized to JSON.
     */
    private Object convertAvroValue(Object value) {
        if (value == null) {
            return null;
        }

        // Handle Avro arrays (GenericData.Array extends ArrayList)
        if (value instanceof org.apache.avro.generic.GenericData.Array) {
            List<?> avroArray = (List<?>) value;
            List<Object> javaList = new ArrayList<>(avroArray.size());
            for (Object item : avroArray) {
                Object converted = convertAvroValue(item);
                if (converted != null) {
                    javaList.add(converted);
                }
            }
            return javaList;
        }

        // Handle regular Lists that might contain Avro objects
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Object> javaList = new ArrayList<>(list.size());
            for (Object item : list) {
                Object converted = convertAvroValue(item);
                if (converted != null) {
                    javaList.add(converted);
                }
            }
            return javaList;
        }

        // Handle nested GenericRecords
        if (value instanceof org.apache.avro.generic.GenericRecord) {
            org.apache.avro.generic.GenericRecord record = (org.apache.avro.generic.GenericRecord) value;

            // Check if this is a wrapper record with a single field (common in Avro for nullable array elements)
            // Examples: {element: value}, {item: value}, etc.
            if (record.getSchema().getFields().size() == 1) {
                // Extract the actual value from the wrapper (unwrap it)
                String fieldName = record.getSchema().getFields().get(0).name();
                Object fieldValue = record.get(fieldName);
                return convertAvroValue(fieldValue);
            }

            // For other nested records, convert to a map
            Map<String, Object> map = new HashMap<>();
            for (org.apache.avro.Schema.Field field : record.getSchema().getFields()) {
                Object fieldValue = record.get(field.name());
                if (fieldValue != null) {
                    map.put(field.name(), convertAvroValue(fieldValue));
                }
            }
            return map;
        }

        // Handle Maps (can occur from previous GenericRecord conversion)
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            // If it's a single-entry map with "item" or "element" key, unwrap it
            if (map.size() == 1) {
                Object key = map.keySet().iterator().next();
                if ("item".equals(key) || "element".equals(key)) {
                    Object unwrapped = map.get(key);
                    return convertAvroValue(unwrapped);
                }
            }
            // Otherwise, recursively convert the map
            Map<String, Object> convertedMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                convertedMap.put(String.valueOf(entry.getKey()), convertAvroValue(entry.getValue()));
            }
            return convertedMap;
        }

        // Handle primitive types and strings
        if (value instanceof CharSequence) {
            return value.toString();
        }

        // Return primitives as-is (numbers, booleans, etc.)
        return value;
    }

    /**
     * Functional interface for consuming batches of documents.
     */
    @FunctionalInterface
    public interface BatchConsumer {
        void accept(List<Document> batch) throws IOException;
    }

    /**
     * Get the row count of the Parquet file without reading all data.
     *
     * @return number of rows
     * @throws IOException if reading fails
     */
    public long getRowCount() throws IOException {
        Path path = new Path(filePath);
        try (ParquetFileReader reader = ParquetFileReader.open(HadoopInputFile.fromPath(path, hadoopConfig))) {
            return reader.getRecordCount();
        }
    }
}
