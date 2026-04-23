package org.elasticsearch.jingra.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads NDJSON (newline-delimited JSON) files and converts each line to a {@link Document}.
 *
 * <p>Each non-blank line must be a valid JSON object. The {@code conversionThreads} hint
 * accepted by {@link #readInBatches(int, int, DatasetReader.BatchConsumer)} is ignored —
 * JSON line parsing is fast enough to do single-threaded.</p>
 */
public class NdjsonReader implements DatasetReader {
    private static final Logger logger = LoggerFactory.getLogger(NdjsonReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String filePath;

    public NdjsonReader(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public List<Document> readAll() throws IOException {
        return readAll(-1);
    }

    @Override
    public List<Document> readAll(int limit) throws IOException {
        List<Document> documents = new ArrayList<>();
        logger.info("Reading NDJSON file: {}", filePath);

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                documents.add(parseLine(line));
                if (limit > 0 && documents.size() >= limit) break;
            }
        }

        logger.info("Finished reading {} documents from NDJSON file", documents.size());
        return documents;
    }

    @Override
    public void readInBatches(int batchSize, BatchConsumer consumer) throws IOException {
        readInBatches(batchSize, 1, consumer);
    }

    @Override
    public void readInBatches(int batchSize, int conversionThreads, BatchConsumer consumer) throws IOException {
        logger.info("Reading NDJSON file in batches of {}: {}", batchSize, filePath);
        List<Document> batch = new ArrayList<>(batchSize);
        int total = 0;

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(parseLine(line));
                if (batch.size() >= batchSize) {
                    consumer.accept(batch);
                    total += batch.size();
                    batch = new ArrayList<>(batchSize);
                }
            }
        }

        if (!batch.isEmpty()) {
            consumer.accept(batch);
            total += batch.size();
        }

        logger.info("Finished processing {} documents from NDJSON file", total);
    }

    @Override
    public long getRowCount() throws IOException {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) count++;
            }
        }
        return count;
    }

    private Document parseLine(String line) throws IOException {
        Map<String, Object> map = MAPPER.readValue(line, MAP_TYPE);
        return new Document(map);
    }
}
