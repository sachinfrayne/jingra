package org.elasticsearch.jingra.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Reads NDJSON (newline-delimited JSON) files and converts each line to a {@link Document}.
 *
 * <p>Supports plain {@code .ndjson} and gzip-compressed {@code .ndjson.gz} files.
 * Accepts a list of paths so that glob-expanded sets of chunk files are treated as one
 * logical dataset — files are streamed in the order they are supplied.</p>
 *
 * <p>The {@code conversionThreads} hint accepted by
 * {@link #readInBatches(int, int, DatasetReader.BatchConsumer)} is ignored —
 * JSON line parsing is fast enough to do single-threaded.</p>
 */
public class NdjsonReader implements DatasetReader {
    private static final Logger logger = LoggerFactory.getLogger(NdjsonReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final List<String> filePaths;

    public NdjsonReader(String filePath) {
        this.filePaths = List.of(filePath);
    }

    public NdjsonReader(List<String> filePaths) {
        this.filePaths = List.copyOf(filePaths);
    }

    @Override
    public List<Document> readAll() throws IOException {
        return readAll(-1);
    }

    @Override
    public List<Document> readAll(int limit) throws IOException {
        List<Document> documents = limit > 0 ? new ArrayList<>(limit) : new ArrayList<>();
        logger.info("Reading {} NDJSON file(s)", filePaths.size());

        for (String path : filePaths) {
            if (limit > 0 && documents.size() >= limit) {
                break;
            }

            logger.info("Reading: {}", path);
            try (BufferedReader reader = openReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }

                    documents.add(parseLine(line));

                    if (limit > 0 && documents.size() >= limit) {
                        break;
                    }
                }
            }
        }

        logger.info("Finished reading {} documents", documents.size());
        return documents;
    }

    @Override
    public void readInBatches(int batchSize, BatchConsumer consumer) throws IOException {
        readInBatches(batchSize, 1, consumer);
    }

    @Override
    public void readInBatches(int batchSize, int conversionThreads, BatchConsumer consumer) throws IOException {
        logger.info("Reading {} NDJSON file(s) in batches of {}", filePaths.size(), batchSize);
        List<Document> batch = new ArrayList<>(batchSize);
        int total = 0;

        for (String path : filePaths) {
            logger.info("Reading: {}", path);
            try (BufferedReader reader = openReader(path)) {
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
        }

        if (!batch.isEmpty()) {
            consumer.accept(batch);
            total += batch.size();
        }

        logger.info("Finished processing {} documents", total);
    }

    @Override
    public long getRowCount() throws IOException {
        if (filePaths.size() == 1) {
            return countLinesInFile(filePaths.get(0));
        }
        int threads = Math.min(filePaths.size(), Runtime.getRuntime().availableProcessors());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<Long>> futures = filePaths.stream()
                    .map(path -> pool.submit(() -> countLinesInFile(path)))
                    .collect(java.util.stream.Collectors.toList());
            long total = 0;
            for (java.util.concurrent.Future<Long> f : futures) {
                total += f.get();
            }
            return total;
        } catch (Exception e) {
            throw new IOException("Parallel row count failed", e);
        } finally {
            pool.shutdown();
        }
    }

    @Override
    public Optional<Instant> findMaxTimestamp() throws IOException {
        Instant max = null;
        for (String path : filePaths) {
            try (BufferedReader reader = openReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Map<String, Object> map = MAPPER.readValue(line, MAP_TYPE);
                    Object ts = map.get("@timestamp");
                    if (ts instanceof String s) {
                        try {
                            Instant t = Instant.parse(s);
                            if (max == null || t.isAfter(max)) max = t;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return Optional.ofNullable(max);
    }

    private long countLinesInFile(String path) throws IOException {
        long count = 0;
        try (BufferedReader reader = openReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) count++;
            }
        }
        return count;
    }

    private static BufferedReader openReader(String path) throws IOException {
        InputStream in = Files.newInputStream(Paths.get(path));
        if (path.endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private Document parseLine(String line) throws IOException {
        Map<String, Object> map = MAPPER.readValue(line, MAP_TYPE);
        return new Document(map);
    }
}
