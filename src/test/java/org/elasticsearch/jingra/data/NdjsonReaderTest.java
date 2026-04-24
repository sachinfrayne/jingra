package org.elasticsearch.jingra.data;

import org.elasticsearch.jingra.model.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NdjsonReaderTest {

    private static final String DOCS_PATH = "src/test/resources/ndjson/test_text_data.ndjson";
    private static final String TEXT_QUERIES_PATH = "src/test/resources/ndjson/test_text_queries.ndjson";
    private static final String VECTOR_DATA_PATH = "src/test/resources/ndjson/test_vector_data.ndjson";
    private static final String VECTOR_QUERIES_PATH = "src/test/resources/ndjson/test_vector_queries.ndjson";
    private static final String HYBRID_DATA_PATH = "src/test/resources/ndjson/test_hybrid_data.ndjson";
    private static final String HYBRID_QUERIES_PATH = "src/test/resources/ndjson/test_hybrid_queries.ndjson";

    @Test
    void readAll_returnsAllNonBlankLines() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> docs = reader.readAll();
        assertEquals(100, docs.size());
    }

    @Test
    void readAll_parsesFieldsCorrectly() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> docs = reader.readAll();

        Document first = docs.get(0);
        assertEquals("001", first.getString("id"));
        assertEquals("Wireless Bluetooth Headphones Pro", first.getString("title"));
    }

    @Test
    void readAll_parsesArrayField() throws IOException {
        NdjsonReader reader = new NdjsonReader(VECTOR_DATA_PATH);
        List<Document> docs = reader.readAll();

        Object embedding = docs.get(0).get("embedding");
        assertInstanceOf(List.class, embedding);
        List<?> embeddingList = (List<?>) embedding;
        assertEquals(10, embeddingList.size());
    }

    @Test
    void readAll_withLimit_respectsLimit() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> docs = reader.readAll(3);
        assertEquals(3, docs.size());
    }

    @Test
    void readAll_withNegativeLimit_returnsAll() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> docs = reader.readAll(-1);
        assertEquals(100, docs.size());
    }

    @Test
    void readAll_withZeroLimit_returnsAll() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> docs = reader.readAll(0);
        assertEquals(100, docs.size());
    }

    @Test
    void getRowCount_countsNonBlankLines() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        assertEquals(100, reader.getRowCount());
    }

    @Test
    void readInBatches_deliversAllDocuments() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> collected = new ArrayList<>();
        reader.readInBatches(2, batch -> collected.addAll(batch));
        assertEquals(100, collected.size());
    }

    @Test
    void readInBatches_respectsBatchSize() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Integer> batchSizes = new ArrayList<>();
        reader.readInBatches(2, batch -> batchSizes.add(batch.size()));

        // 100 docs with batch size 2 → 50 full batches of 2
        assertEquals(50, batchSizes.size());
        assertTrue(batchSizes.stream().allMatch(s -> s == 2));
    }

    @Test
    void readInBatches_withConversionThreadsHint_deliversAllDocuments() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Document> collected = new ArrayList<>();
        // conversionThreads hint is ignored for ndjson but must not break anything
        reader.readInBatches(3, 4, batch -> collected.addAll(batch));
        assertEquals(100, collected.size());
    }

    @Test
    void readAll_vectorField_parsedAsFloatList() throws IOException {
        NdjsonReader reader = new NdjsonReader(VECTOR_QUERIES_PATH);
        List<Document> queries = reader.readAll();
        assertEquals(10, queries.size());

        List<Float> embedding = queries.get(0).getFloatList("embedding");
        assertNotNull(embedding);
        assertEquals(10, embedding.size());
        assertEquals(1.0f, embedding.get(0), 1e-6f);
        assertEquals(0.0f, embedding.get(1), 1e-6f);
    }

    @Test
    void readAll_groundTruthField_parsedAsStringList() throws IOException {
        NdjsonReader reader = new NdjsonReader(VECTOR_QUERIES_PATH);
        List<Document> queries = reader.readAll();

        @SuppressWarnings("unchecked")
        List<String> groundTruth = (List<String>) queries.get(0).get("ground_truth");
        assertNotNull(groundTruth);
        assertEquals(10, groundTruth.size());
        assertEquals("001", groundTruth.get(0));
    }

    @Test
    void readAll_blankLinesAreSkipped(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("with-blanks.ndjson");
        Files.writeString(file, "\n{\"id\":\"a\"}\n\n{\"id\":\"b\"}\n\n");

        NdjsonReader reader = new NdjsonReader(file.toString());
        assertEquals(2, reader.readAll().size());
        assertEquals(2, reader.getRowCount());
    }

    @Test
    void getRowCount_returnsZeroWhenFileHasOnlyBlankLines(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("blanks-only.ndjson");
        Files.writeString(file, "\n  \n\t\n");
        assertEquals(0, new NdjsonReader(file.toString()).getRowCount());
    }

    /**
     * When the last flush empties the trailing batch, {@code if (!batch.isEmpty())}
     * must stay false —
     * line count exactly divisible by {@code batchSize}.
     */
    @Test
    void readInBatches_whenDocumentCountEqualsBatchSize_singleFlushNoTrailingEmptyBatch(@TempDir Path tmpDir)
            throws IOException {
        Path file = tmpDir.resolve("two.ndjson");
        Files.writeString(file, "{\"id\":\"a\"}\n{\"id\":\"b\"}\n");

        NdjsonReader reader = new NdjsonReader(file.toString());
        List<Integer> batchSizes = new ArrayList<>();
        reader.readInBatches(2, batch -> batchSizes.add(batch.size()));

        assertEquals(List.of(2), batchSizes);
    }

    @Test
    void readInBatches_emptyFile_doesNotInvokeConsumer(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("empty.ndjson");
        Files.writeString(file, "");

        NdjsonReader reader = new NdjsonReader(file.toString());
        List<Integer> batchSizes = new ArrayList<>();
        reader.readInBatches(3, batch -> batchSizes.add(batch.size()));

        assertTrue(batchSizes.isEmpty());
    }

    /**
     * Unlike an empty file (first {@code readLine} is {@code null}), blank-only files enter the
     * {@code while} body on {@code continue} without ever satisfying {@code batch.size() >= batchSize}.
     * Exercises the 3-arg overload used by {@link NdjsonReader#readInBatches(int, DatasetReader.BatchConsumer)}.
     */
    @Test
    void readInBatches_blankLinesOnly_threeArg_doesNotInvokeConsumer(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("blanks.ndjson");
        Files.writeString(file, "\n  \n\t\n");

        List<Integer> batchSizes = new ArrayList<>();
        new NdjsonReader(file.toString()).readInBatches(4, 2, batch -> batchSizes.add(batch.size()));

        assertTrue(batchSizes.isEmpty());
    }

    @Test
    void readInBatches_whenBatchSizeEqualsLineCount_singleFlushNoTail() throws IOException {
        NdjsonReader reader = new NdjsonReader(DOCS_PATH);
        List<Integer> batchSizes = new ArrayList<>();
        reader.readInBatches(100, 3, batch -> batchSizes.add(batch.size()));

        assertEquals(List.of(100), batchSizes);
    }

    @Test
    void textGroundTruthIdsExistInTextData() throws IOException {
        Set<String> docIds = new NdjsonReader(DOCS_PATH).readAll().stream()
                .map(d -> d.getString("id"))
                .collect(Collectors.toSet());
        for (Document query : new NdjsonReader(TEXT_QUERIES_PATH).readAll()) {
            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) query.get("ground_truth");
            for (String id : groundTruth) {
                assertTrue(docIds.contains(id),
                        "Ground truth ID '" + id + "' not found in " + DOCS_PATH);
            }
        }
    }

    @Test
    void vectorGroundTruthIdsExistInVectorData() throws IOException {
        Set<String> docIds = new NdjsonReader(VECTOR_DATA_PATH).readAll().stream()
                .map(d -> d.getString("id"))
                .collect(Collectors.toSet());
        for (Document query : new NdjsonReader(VECTOR_QUERIES_PATH).readAll()) {
            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) query.get("ground_truth");
            for (String id : groundTruth) {
                assertTrue(docIds.contains(id),
                        "Ground truth ID '" + id + "' not found in " + VECTOR_DATA_PATH);
            }
        }
    }

    @Test
    void hybridGroundTruthIdsExistInHybridData() throws IOException {
        Set<String> docIds = new NdjsonReader(HYBRID_DATA_PATH).readAll().stream()
                .map(d -> d.getString("id"))
                .collect(Collectors.toSet());
        for (Document query : new NdjsonReader(HYBRID_QUERIES_PATH).readAll()) {
            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) query.get("ground_truth");
            for (String id : groundTruth) {
                assertTrue(docIds.contains(id),
                        "Ground truth ID '" + id + "' not found in " + HYBRID_DATA_PATH);
            }
        }
    }

    @Test
    void readAll_hybridQueries_includeTextVectorAndGroundTruth() throws IOException {
        List<Document> queries = new NdjsonReader(HYBRID_QUERIES_PATH).readAll();
        assertEquals(10, queries.size());
        assertEquals("coffee", queries.get(0).getString("query_text"));
        assertEquals(10, queries.get(0).getFloatList("embedding").size());
        @SuppressWarnings("unchecked")
        List<String> gt = (List<String>) queries.get(0).get("ground_truth");
        assertEquals(List.of(
                "001", "002", "003", "004", "005", "006", "007", "008", "009", "010"), gt);
    }

    @Test
    void datasetReaderFactory_returnsNdjsonReaderForNdjsonExtension() {
        DatasetReader reader = DatasetReaderFactory.create("some/path/data.ndjson");
        assertInstanceOf(NdjsonReader.class, reader);
    }

    @Test
    void datasetReaderFactory_returnsParquetReaderForParquetExtension() {
        DatasetReader reader = DatasetReaderFactory.create("some/path/data.parquet");
        assertInstanceOf(ParquetReader.class, reader);
    }

    @Test
    void datasetReaderFactory_returnsParquetReaderForUnknownExtension() {
        DatasetReader reader = DatasetReaderFactory.create("some/path/data.csv");
        assertInstanceOf(ParquetReader.class, reader);
    }

    @Test
    void datasetReaderFactory_returnsParquetReaderWhenPathIsNull() {
        DatasetReader reader = DatasetReaderFactory.create(null);
        assertInstanceOf(ParquetReader.class, reader);
    }
}
