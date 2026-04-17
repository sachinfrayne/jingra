package org.elasticsearch.jingra.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CsvExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void compareRecallForSort_coversNullAndNonNullOrderings() {
        assertEquals(0, CsvExporter.compareRecallForSort(null, null));
        assertTrue(CsvExporter.compareRecallForSort(null, 0.5) > 0, "null recall sorts after non-null");
        assertTrue(CsvExporter.compareRecallForSort(0.5, null) < 0, "non-null recall sorts before null");
        assertEquals(0, CsvExporter.compareRecallForSort(0.7, 0.7));
        assertTrue(CsvExporter.compareRecallForSort(0.5, 0.9) < 0);
        assertTrue(CsvExporter.compareRecallForSort(0.9, 0.5) > 0);
    }

    @Test
    void exportDetailedComparison_createsFile() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)
        );

        exporter.exportDetailedComparison(comparisons, "test_comparison.csv");

        Path csvFile = tempDir.resolve("test_comparison.csv");
        assertTrue(Files.exists(csvFile));
    }

    @Test
    void exportDetailedComparison_writesCorrectHeaders() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)
        );

        exporter.exportDetailedComparison(comparisons, "test.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("test.csv"));
        assertTrue(lines.get(0).contains("RecallAtN"));
        assertTrue(lines.get(0).contains("ParamKey"));
        assertTrue(lines.get(0).contains("Engine"));
        assertTrue(lines.get(0).contains("Recall"));
        assertTrue(lines.get(0).contains("Latency"));
        assertTrue(lines.get(0).contains("Throughput"));
    }

    @Test
    void exportDetailedComparison_writesBaselineAndTargetRows() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "elasticsearch", 0.95, 5.0, 200.0, "qdrant", 0.93, 4.0, 250.0)
        );

        exporter.exportDetailedComparison(comparisons, "test.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("test.csv"));

        // Should have header + 2 data rows (baseline + target)
        assertEquals(3, lines.size());

        // Check baseline row
        assertTrue(lines.get(1).contains("elasticsearch"));
        assertTrue(lines.get(1).contains("0.95"));

        // Check target row
        assertTrue(lines.get(2).contains("qdrant"));
        assertTrue(lines.get(2).contains("0.93"));
    }

    @Test
    void exportDetailedComparison_includesSpeedupMetrics() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        // Baseline: latency=10.0, throughput=100.0
        // Target: latency=5.0, throughput=200.0
        // Latency speedup = 10/5 = 2.0, Throughput speedup = 200/100 = 2.0
        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "es", 0.95, 10.0, 100.0, "qd", 0.93, 5.0, 200.0)
        );

        exporter.exportDetailedComparison(comparisons, "test.csv");

        String content = Files.readString(tempDir.resolve("test.csv"));

        // Should contain speedup values
        assertTrue(content.contains("2.0") || content.contains("2.00"));
    }

    @Test
    void exportDetailedComparison_handlesNullLatency() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "es", 0.95, null, 200.0, "qd", 0.93, null, 250.0)
        );

        exporter.exportDetailedComparison(comparisons, "test.csv");

        String content = Files.readString(tempDir.resolve("test.csv"));

        // Should contain N/A or empty for null latency
        assertTrue(content.contains("N/A") || content.contains(",,") || content.contains(",\n"));
    }

    @Test
    void exportDetailedComparison_handlesMultipleComparisons() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0),
                createComparison("recall@100", "k=200", "es", 0.97, 6.0, 180.0, "qd", 0.96, 5.0, 220.0)
        );

        exporter.exportDetailedComparison(comparisons, "test.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("test.csv"));

        // Header + 2 comparisons * 2 rows each = 5 lines
        assertEquals(5, lines.size());
    }

    @Test
    void exportSummaryComparison_createsFile() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        Map<String, ComparisonResult> comparisons = Map.of(
                "recall@100", createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)
        );

        exporter.exportSummaryComparison(comparisons, "summary.csv");

        Path csvFile = tempDir.resolve("summary.csv");
        assertTrue(Files.exists(csvFile));
    }

    @Test
    void exportSummaryComparison_writesCorrectFormat() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        Map<String, ComparisonResult> comparisons = new HashMap<>();
        comparisons.put("recall@100", createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0));
        comparisons.put("recall@10", createComparison("recall@10", "k=50", "es", 0.85, 3.0, 300.0, "qd", 0.83, 2.0, 400.0));

        exporter.exportSummaryComparison(comparisons, "summary.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("summary.csv"));

        // Header + 2 data rows
        assertEquals(3, lines.size());

        // Check header
        assertTrue(lines.get(0).contains("RecallAtN"));
        assertTrue(lines.get(0).contains("BaselineEngine"));
        assertTrue(lines.get(0).contains("TargetEngine"));
        assertTrue(lines.get(0).contains("Speedup"));
    }

    @Test
    void exportThroughputComparison_createsFile() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        Map<String, ComparisonResult> comparisons = Map.of(
                "recall@100", createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)
        );

        exporter.exportThroughputComparison(comparisons, "throughput.csv");

        Path csvFile = tempDir.resolve("throughput.csv");
        assertTrue(Files.exists(csvFile));
    }

    @Test
    void exportThroughputComparison_includesThroughputData() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        Map<String, ComparisonResult> comparisons = Map.of(
                "recall@100", createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)
        );

        exporter.exportThroughputComparison(comparisons, "throughput.csv");

        String content = Files.readString(tempDir.resolve("throughput.csv"));

        assertTrue(content.contains("200"));  // baseline throughput
        assertTrue(content.contains("250"));  // target throughput
    }

    @Test
    void exportSummaryComparison_formatsInfinityAndNaNLatencySpeedups() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        ComparisonResult infinityLatency = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("k=100")
                .baselineEngine("es")
                .baselineRecall(0.9)
                .baselineLatency(10.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.88)
                .targetLatency(0.0)
                .targetThroughput(200.0)
                .build();

        ComparisonResult nanLatency = ComparisonResult.builder()
                .recallAtN("recall@10")
                .paramKey("k=50")
                .baselineEngine("es")
                .baselineRecall(0.8)
                .baselineLatency(null)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.78)
                .targetLatency(5.0)
                .targetThroughput(200.0)
                .build();

        Map<String, ComparisonResult> comparisons = new HashMap<>();
        comparisons.put("recall@100", infinityLatency);
        comparisons.put("recall@10", nanLatency);

        exporter.exportSummaryComparison(comparisons, "summary.csv");

        String content = Files.readString(tempDir.resolve("summary.csv"));
        assertTrue(content.contains("Infinity"), "expected Infinity for zero target latency");
        assertTrue(content.contains("N/A"), "expected N/A for NaN latency speedup");
    }

    @Test
    void exportDetailedComparison_formatsInfinityLatencySpeedupOnTargetRow() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        ComparisonResult c = ComparisonResult.builder()
                .recallAtN("recall@100")
                .paramKey("k=100")
                .baselineEngine("es")
                .baselineRecall(0.95)
                .baselineLatency(5.0)
                .baselineThroughput(100.0)
                .targetEngine("qd")
                .targetRecall(0.93)
                .targetLatency(0.0)
                .targetThroughput(250.0)
                .build();

        exporter.exportDetailedComparison(List.of(c), "test.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("test.csv"));
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("Infinity"));
    }

    @Test
    void usesExistingOutputDirectoryWithoutCreatingAgain() throws IOException {
        Path existing = tempDir.resolve("already_here");
        Files.createDirectories(existing);
        assertTrue(Files.exists(existing));

        CsvExporter exporter = new CsvExporter(existing.toString());
        exporter.exportDetailedComparison(
                List.of(createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)),
                "out.csv"
        );

        assertTrue(Files.exists(existing.resolve("out.csv")));
    }

    @Test
    void createsOutputDirectoryIfNotExists() throws IOException {
        Path newDir = tempDir.resolve("new_output");
        assertFalse(Files.exists(newDir));

        CsvExporter exporter = new CsvExporter(newDir.toString());

        List<ComparisonResult> comparisons = List.of(
                createComparison("recall@100", "k=100", "es", 0.95, 5.0, 200.0, "qd", 0.93, 4.0, 250.0)
        );

        exporter.exportDetailedComparison(comparisons, "test.csv");

        assertTrue(Files.exists(newDir));
        assertTrue(Files.exists(newDir.resolve("test.csv")));
    }

    @Test
    void exportAllResults_fallsBackToClientLatencyForServerMetricColumn() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "es", "1.0", "vector_search", "ds", "k=1", Map.of());
        r.addMetric("recall", 0.9);
        r.addMetric("latency_median", 12.5);
        r.addMetric("throughput", 50.0);

        exporter.exportAllResults(List.of(r), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String line = Files.readAllLines(tempDir.resolve("out.csv")).get(1);
        assertTrue(line.contains("12.5000") || line.contains("12.5"));
    }

    @Test
    void exportAllResults_usesServerThroughputWhenPresentForServerLatencyMetric() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "es", "1.0", "vector_search", "ds", "k=1", Map.of());
        r.addMetric("recall", 0.9);
        r.addMetric("server_latency_median", 3.0);
        r.addMetric("server_throughput", 77.0);
        r.addMetric("throughput", 99.0);

        exporter.exportAllResults(List.of(r), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String line = Files.readAllLines(tempDir.resolve("out.csv")).get(1);
        assertTrue(line.contains("77.0000") || line.contains("77.0"), "throughput column prefers server_throughput when set");
    }

    @Test
    void exportAllResults_usesThroughputWhenServerThroughputAbsentButFirstMetricIsServer() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "es", "1.0", "vector_search", "ds", "k=1", Map.of());
        r.addMetric("recall", 0.9);
        r.addMetric("server_latency_median", 3.0);
        r.addMetric("throughput", 99.0);

        exporter.exportAllResults(List.of(r), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String line = Files.readAllLines(tempDir.resolve("out.csv")).get(1);
        assertTrue(line.contains("99.0000") || line.contains("99.0"));
    }

    @Test
    void exportAllResults_skipsServerLatencyFallbackWhenMetricIsNotServerPrefixed() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "es", "1.0", "vector_search", "ds", "k=1", Map.of());
        r.addMetric("recall", 0.9);
        r.addMetric("throughput", 1.0);

        exporter.exportAllResults(List.of(r), "recall@100", List.of("latency_p99"), "elasticsearch", "out.csv");

        assertTrue(Files.readString(tempDir.resolve("out.csv")).contains("N/A"));
    }

    @Test
    void exportAllResults_columnNamesCapitalizeSingleCharacterUnderscoreSegments() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "es", "1.0", "vector_search", "ds", "k=1", Map.of());
        r.addMetric("recall", 0.9);
        r.addMetric("x_median", 1.0);
        r.addMetric("throughput", 1.0);

        exporter.exportAllResults(List.of(r), "recall@100", List.of("x_median"), "elasticsearch", "out.csv");

        assertTrue(Files.readString(tempDir.resolve("out.csv")).contains("X_Median"));
    }

    @Test
    void exportAllResults_writesSpeedupOnBaselineRowWhenTwoEnginesShareRoundedRecall() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        // Same %.2f recall bucket so speedup index has two engines
        org.elasticsearch.jingra.model.BenchmarkResult es = bench(
                "elasticsearch", "k=a", 0.95, 5.0, 200.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench(
                "qdrant", "k=b", 0.95, 10.0, 100.0);

        exporter.exportAllResults(
                List.of(es, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"));
        String esRow = lines.stream().filter(l -> l.contains("elasticsearch")).findFirst().orElseThrow();
        String qdRow = lines.stream().filter(l -> l.contains("qdrant")).findFirst().orElseThrow();
        assertTrue(esRow.contains(",2") || esRow.endsWith("2"), "baseline speedup other/baseline = 10/5=2");
        assertFalse(qdRow.replace("\"", "").matches(".*,\\d+$"), "target row should not end with numeric speedup");
    }

    @Test
    void exportAllResults_leavesSpeedupEmptyWhenOnlyOneEngineAtRecall() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult es = bench("elasticsearch", "k=1", 0.95, 5.0, 100.0);

        exporter.exportAllResults(
                List.of(es), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String line = Files.readAllLines(tempDir.resolve("out.csv")).get(1).replace("\"", "");
        assertFalse(line.matches(".*,\\d+$"));
    }

    @Test
    void exportAllResults_leavesSpeedupEmptyWhenThreeEnginesAtSameRoundedRecall() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        List<org.elasticsearch.jingra.model.BenchmarkResult> results = List.of(
                bench("elasticsearch", "k=1", 0.95, 5.0, 100.0),
                bench("qdrant", "k=2", 0.95, 10.0, 50.0),
                bench("opensearch", "k=3", 0.95, 8.0, 60.0)
        );

        exporter.exportAllResults(
                results, "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String esLine = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("elasticsearch"))
                .findFirst()
                .orElseThrow();
        assertFalse(esLine.replace("\"", "").matches(".*,\\d+$"));
    }

    @Test
    void exportAllResults_leavesSpeedupEmptyWhenBaselineThroughputIsZero() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        // Create ES result with zero throughput
        org.elasticsearch.jingra.model.BenchmarkResult es = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        es.addMetric("recall", 0.95);
        es.addMetric("server_latency_median", 5.0);
        es.addMetric("throughput", 0.0);

        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=2", 0.95, 10.0, 50.0);

        exporter.exportAllResults(
                List.of(es, qd), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String esLine = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("elasticsearch"))
                .findFirst()
                .orElseThrow();
        assertFalse(esLine.replace("\"", "").matches(".*,\\d+$"));
    }

    @Test
    void exportAllResults_leavesSpeedupEmptyWhenOtherEngineThroughputMissing() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult es = bench("elasticsearch", "k=1", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "qdrant", "1.0", "vector_search", "ds", "k=2", Map.of());
        qd.addMetric("recall", 0.95);
        qd.addMetric("server_latency_median", 10.0);
        // No throughput metric

        exporter.exportAllResults(
                List.of(es, qd), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String esLine = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("elasticsearch"))
                .findFirst()
                .orElseThrow();
        assertFalse(esLine.replace("\"", "").matches(".*,\\d+$"));
    }

    @Test
    void exportAllResults_leavesSpeedupEmptyWhenBaselineMissingFromSpeedupIndex() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult es = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=es", Map.of());
        es.addMetric("recall", 0.95);
        es.addMetric("throughput", 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=1", 0.95, 5.0, 80.0);
        org.elasticsearch.jingra.model.BenchmarkResult os = bench("opensearch", "k=2", 0.95, 10.0, 50.0);

        exporter.exportAllResults(
                List.of(es, qd, os), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        String esLine = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("elasticsearch"))
                .findFirst()
                .orElseThrow();
        assertFalse(esLine.replace("\"", "").matches(".*,\\d+$"));
    }

    @Test
    void exportAllResults_recallRoundedColumnIsNaWhenRecallMissing() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        r.addMetric("server_latency_median", 5.0);
        r.addMetric("throughput", 100.0);

        exporter.exportAllResults(
                List.of(r), "recall@100", List.of("server_latency_median"), "elasticsearch", "out.csv");

        assertTrue(Files.readString(tempDir.resolve("out.csv")).contains("N/A"));
    }

    @Test
    void exportAllResults_buildSpeedupIndexKeepsHigherThroughputPerEngineAtSameRecall() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esLow = bench("elasticsearch", "k=low", 0.95, 20.0, 50.0);
        org.elasticsearch.jingra.model.BenchmarkResult esHigh = bench("elasticsearch", "k=high", 0.95, 10.0, 150.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 10.0, 100.0);

        exporter.exportAllResults(
                List.of(esLow, esHigh, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String esRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("elasticsearch") && l.contains("k=high"))
                .findFirst()
                .orElseThrow();
        assertTrue(esRow.contains("10.0000") || esRow.contains("10.0"));
        assertTrue(esRow.contains(",1") || esRow.endsWith("1"), "10/10 -> speedup 1");
    }

    @Test
    void exportAllResults_keepsHigherThroughputWhenDuplicateEngineAndRecall() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esLow = bench("elasticsearch", "k=low", 0.95, 10.0, 50.0);
        org.elasticsearch.jingra.model.BenchmarkResult esHigh = bench("elasticsearch", "k=high", 0.95, 10.0, 200.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 20.0, 100.0);

        exporter.exportAllResults(
                List.of(esLow, esHigh, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String highRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=high"))
                .findFirst()
                .orElseThrow();
        assertTrue(highRow.contains(",2") || highRow.endsWith("2"), "20/10=2 using best throughput row's latency");
    }

    @Test
    void exportAllResults_prefersRowWithThroughputWhenPriorRowHadNoThroughput() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esNoTp = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=a", Map.of());
        esNoTp.addMetric("recall", 0.95);
        esNoTp.addMetric("server_latency_median", 10.0);
        org.elasticsearch.jingra.model.BenchmarkResult esWithTp = bench("elasticsearch", "k=b", 0.95, 10.0, 300.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 20.0, 100.0);

        exporter.exportAllResults(
                List.of(esNoTp, esWithTp, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String row = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=b"))
                .findFirst()
                .orElseThrow();
        assertTrue(row.contains(",3") || row.endsWith("3"), "300/100=3; k=b selected because k=a has no throughput");

        // k=a row should NOT have speedup (no throughput, so not in index)
        String noTpRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=a"))
                .findFirst()
                .orElseThrow();
        assertFalse(noTpRow.replace("\"", "").matches(".*,\\d+$"), "k=a has no throughput, not in index");
    }

    @Test
    void exportAllResults_doesNotReplaceWhenNewThroughputIsLower() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esHighFirst = bench("elasticsearch", "k=first", 0.95, 10.0, 500.0);
        org.elasticsearch.jingra.model.BenchmarkResult esLowSecond = bench("elasticsearch", "k=second", 0.95, 5.0, 50.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 20.0, 100.0);

        exporter.exportAllResults(
                List.of(esHighFirst, esLowSecond, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String firstRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=first"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstRow.contains(",5") || firstRow.endsWith("5"), "500/100=5; k=first has highest throughput so it gets speedup");

        // k=second row should NOT have speedup (not the indexed row)
        String secondRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=second"))
                .findFirst()
                .orElseThrow();
        assertFalse(secondRow.replace("\"", "").matches(".*,\\d+$"), "k=second not in index, no speedup");
    }

    @Test
    void exportAllResults_doesNotReplaceWhenNewThroughputTiesIndexedRow() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esFirst = bench("elasticsearch", "k=first", 0.95, 10.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult esSecond = bench("elasticsearch", "k=second", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 20.0, 50.0);

        exporter.exportAllResults(
                List.of(esFirst, esSecond, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String firstRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=first"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstRow.contains(",2") || firstRow.endsWith("2"), "100/50=2; k=first stays indexed when k=second ties throughput");

        String secondRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=second"))
                .findFirst()
                .orElseThrow();
        assertFalse(secondRow.replace("\"", "").matches(".*,\\d+$"), "tie does not replace indexed row");
    }

    /**
     * First {@code getMetricAsDouble("throughput")} returns {@code firstThroughputRead}; later reads return null.
     * Exercises {@code buildSpeedupIndex} when {@code existingThroughput == null} while an engine bucket already exists.
     */
    @Test
    void exportAllResults_buildSpeedupIndexReplacesWhenExistingThroughputRereadIsNull() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esOdd =
                benchThroughputSecondReadNull("elasticsearch", "k=odd", 0.95, 10.0, 40.0);
        org.elasticsearch.jingra.model.BenchmarkResult esWin =
                bench("elasticsearch", "k=win", 0.95, 5.0, 300.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 20.0, 100.0);

        exporter.exportAllResults(
                List.of(esOdd, esWin, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String winRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=win"))
                .findFirst()
                .orElseThrow();
        assertTrue(winRow.contains(",3") || winRow.endsWith("3"), "300/100=3 after replacing row whose re-read throughput is null");

        String oddRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=odd"))
                .findFirst()
                .orElseThrow();
        assertFalse(oddRow.replace("\"", "").matches(".*,\\d+$"), "k=odd is not the indexed row after merge");
    }

    @Test
    void exportAllResults_leavesSpeedupEmptyWhenCurrentEngineMissingFromIndexButTwoEnginesPresent()
            throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult es = bench("elasticsearch", "k=es", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=qd", 0.95, 10.0, 80.0);
        org.elasticsearch.jingra.model.BenchmarkResult osNoTp = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "opensearch", "1.0", "vector_search", "ds", "k=os", Map.of());
        osNoTp.addMetric("recall", 0.95);
        osNoTp.addMetric("server_latency_median", 8.0);

        exporter.exportAllResults(
                List.of(es, qd, osNoTp),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String osLine = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("opensearch"))
                .findFirst()
                .orElseThrow();
        assertFalse(osLine.replace("\"", "").matches(".*,\\d+$"), "engine not in index -> indexedResult null");
    }

    @Test
    void calculateSpeedup_returnsEmptyWhenBaselineThroughputMissingFromIndexedResults() throws Exception {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult baseline = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        baseline.addMetric("recall", 0.95);
        baseline.addMetric("server_latency_median", 5.0);
        // No throughput metric
        org.elasticsearch.jingra.model.BenchmarkResult other = bench("qdrant", "k=2", 0.95, 10.0, 50.0);

        Map<String, Map<String, org.elasticsearch.jingra.model.BenchmarkResult>> index = new HashMap<>();
        Map<String, org.elasticsearch.jingra.model.BenchmarkResult> atRecall = new LinkedHashMap<>();
        atRecall.put("elasticsearch", baseline);
        atRecall.put("qdrant", other);
        index.put("0.95", atRecall);

        Method m = CsvExporter.class.getDeclaredMethod(
                "calculateSpeedup",
                org.elasticsearch.jingra.model.BenchmarkResult.class,
                String.class,
                Map.class);
        m.setAccessible(true);
        String out = (String) m.invoke(exporter, baseline, "0.95", index);
        assertEquals("", out);
    }

    @Test
    void exportAllResults_keepsIndexedRowWhenDuplicateHasNullThroughput() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esWithTp = bench("elasticsearch", "k=with", 0.95, 10.0, 400.0);
        org.elasticsearch.jingra.model.BenchmarkResult esNoTp = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=no", Map.of());
        esNoTp.addMetric("recall", 0.95);
        esNoTp.addMetric("server_latency_median", 5.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = bench("qdrant", "k=q", 0.95, 20.0, 100.0);

        exporter.exportAllResults(
                List.of(esWithTp, esNoTp, qd),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        String withRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=with"))
                .findFirst()
                .orElseThrow();
        assertTrue(withRow.contains(",4") || withRow.endsWith("4"), "400/100=4; k=with has throughput, k=no doesn't so it's ignored");

        // k=no row should NOT have speedup (no throughput, so not in index)
        String noRow = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("k=no"))
                .findFirst()
                .orElseThrow();
        assertFalse(noRow.replace("\"", "").matches(".*,\\d+$"), "k=no has no throughput, not in index");
    }

    @Test
    void calculateSpeedup_findsOtherEngineWhenBaselineListedFirstInMap() throws Exception {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult baseline = bench("elasticsearch", "k=1", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult other = bench("qdrant", "k=2", 0.95, 10.0, 50.0);
        Map<String, org.elasticsearch.jingra.model.BenchmarkResult> atRecall = new LinkedHashMap<>();
        atRecall.put("elasticsearch", baseline);
        atRecall.put("qdrant", other);
        Map<String, Map<String, org.elasticsearch.jingra.model.BenchmarkResult>> index = Map.of("0.95", atRecall);

        Method m = CsvExporter.class.getDeclaredMethod(
                "calculateSpeedup",
                org.elasticsearch.jingra.model.BenchmarkResult.class,
                String.class,
                Map.class);
        m.setAccessible(true);
        assertEquals("2", m.invoke(exporter, baseline, "0.95", index));
    }

    @Test
    void calculateSpeedup_findsOtherEngineWhenBaselineListedSecondInMap() throws Exception {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult baseline = bench("elasticsearch", "k=1", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult other = bench("qdrant", "k=2", 0.95, 10.0, 50.0);
        Map<String, org.elasticsearch.jingra.model.BenchmarkResult> atRecall = new LinkedHashMap<>();
        atRecall.put("qdrant", other);
        atRecall.put("elasticsearch", baseline);
        Map<String, Map<String, org.elasticsearch.jingra.model.BenchmarkResult>> index = Map.of("0.95", atRecall);

        Method m = CsvExporter.class.getDeclaredMethod(
                "calculateSpeedup",
                org.elasticsearch.jingra.model.BenchmarkResult.class,
                String.class,
                Map.class);
        m.setAccessible(true);
        assertEquals("2", m.invoke(exporter, baseline, "0.95", index));
    }

    @Test
    void calculateSpeedup_returnsEmptyWhenNoNonBaselineEntryInMap() throws Exception {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult r1 = bench("elasticsearch", "k=1", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult r2 = bench("elasticsearch", "k=2", 0.95, 10.0, 50.0);
        Map<String, org.elasticsearch.jingra.model.BenchmarkResult> corrupt = new AbstractMap<>() {
            @Override
            public Set<Entry<String, org.elasticsearch.jingra.model.BenchmarkResult>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, org.elasticsearch.jingra.model.BenchmarkResult>> iterator() {
                        return List.<Entry<String, org.elasticsearch.jingra.model.BenchmarkResult>>of(
                                Map.entry("elasticsearch", r1),
                                Map.entry("elasticsearch", r2)
                        ).iterator();
                    }

                    @Override
                    public int size() {
                        return 2;
                    }
                };
            }
        };
        Map<String, Map<String, org.elasticsearch.jingra.model.BenchmarkResult>> index = Map.of("0.95", corrupt);

        Method m = CsvExporter.class.getDeclaredMethod(
                "calculateSpeedup",
                org.elasticsearch.jingra.model.BenchmarkResult.class,
                String.class,
                Map.class);
        m.setAccessible(true);
        assertEquals("", m.invoke(exporter, r1, "0.95", index));
    }

    @Test
    void calculateSpeedup_returnsEmptyWhenOtherThroughputMissing() throws Exception {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult baseline = bench("elasticsearch", "k=1", 0.95, 5.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult other = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "qdrant", "1.0", "vector_search", "ds", "k=2", Map.of());
        other.addMetric("recall", 0.95);
        other.addMetric("server_latency_median", 10.0);
        // No throughput metric

        Map<String, Map<String, org.elasticsearch.jingra.model.BenchmarkResult>> index = new HashMap<>();
        Map<String, org.elasticsearch.jingra.model.BenchmarkResult> atRecall = new LinkedHashMap<>();
        atRecall.put("elasticsearch", baseline);
        atRecall.put("qdrant", other);
        index.put("0.95", atRecall);

        Method m = CsvExporter.class.getDeclaredMethod(
                "calculateSpeedup",
                org.elasticsearch.jingra.model.BenchmarkResult.class,
                String.class,
                Map.class);
        m.setAccessible(true);
        String out = (String) m.invoke(exporter, baseline, "0.95", index);
        assertEquals("", out);
    }

    @Test
    void exportAllResults_findSpeedupMetricPrefersLatencyMedianWhenServerAbsent() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult es = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "elasticsearch", "1.0", "vector_search", "ds", "k=1", Map.of());
        es.addMetric("recall", 0.95);
        es.addMetric("latency_median", 4.0);
        es.addMetric("throughput", 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", "qdrant", "1.0", "vector_search", "ds", "k=2", Map.of());
        qd.addMetric("recall", 0.95);
        qd.addMetric("latency_median", 8.0);
        qd.addMetric("throughput", 50.0);

        exporter.exportAllResults(
                List.of(es, qd),
                "recall@100",
                List.of("latency_median", "latency_p99"),
                "elasticsearch",
                "out.csv");

        String esLine = Files.readAllLines(tempDir.resolve("out.csv")).stream()
                .filter(l -> l.contains("elasticsearch"))
                .findFirst()
                .orElseThrow();
        assertTrue(esLine.contains(",2") || esLine.endsWith("2"), "8/4=2");
    }

    @Test
    void exportAllResults_dumpsAllDataPoints() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        // Create results with different param_keys and metrics
        org.elasticsearch.jingra.model.BenchmarkResult es1 = createResult(
                "elasticsearch", "k=2000_num_candidates=2000", 0.9747, 5292.3, 7100.5, 2.985);
        org.elasticsearch.jingra.model.BenchmarkResult qd1 = createResult(
                "qdrant", "num_candidates=10000_rescore=100", 0.9299, 2512.3, 3500.2, 6.291);

        List<org.elasticsearch.jingra.model.BenchmarkResult> results = List.of(es1, qd1);
        List<String> latencyMetrics = List.of("server_latency_median", "latency_avg");

        exporter.exportAllResults(results, "recall@100", latencyMetrics, "elasticsearch", "all_results.csv");

        assertTrue(Files.exists(tempDir.resolve("all_results.csv")));
        List<String> lines = Files.readAllLines(tempDir.resolve("all_results.csv"));

        // Header + 2 data rows
        assertEquals(3, lines.size());

        String header = lines.get(0);
        assertTrue(header.contains("RecallAtN"));
        assertTrue(header.contains("Engine"));
        assertTrue(header.contains("ParamKey"));
        assertTrue(header.contains("Recall"));
        assertTrue(header.contains("Server_Latency_Median"));
        assertTrue(header.contains("Latency_Avg"));
        assertTrue(header.contains("Throughput"));

        // Check both engines present
        String content = Files.readString(tempDir.resolve("all_results.csv"));
        assertTrue(content.contains("elasticsearch"));
        assertTrue(content.contains("qdrant"));
        assertTrue(content.contains("0.9747"));
        assertTrue(content.contains("0.9299"));
    }

    private static org.elasticsearch.jingra.model.BenchmarkResult bench(
            String engine, String paramKey, double recall, double serverLatencyMedian, double throughput) {
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", engine, "1.0", "vector_search", "ds", paramKey, Map.of());
        r.addMetric("recall", recall);
        r.addMetric("server_latency_median", serverLatencyMedian);
        r.addMetric("throughput", throughput);
        return r;
    }

    private static org.elasticsearch.jingra.model.BenchmarkResult benchWithoutRecall(
            String engine, String paramKey, double serverLatencyMedian, double throughput) {
        org.elasticsearch.jingra.model.BenchmarkResult r = new org.elasticsearch.jingra.model.BenchmarkResult(
                "run", engine, "1.0", "vector_search", "ds", paramKey, Map.of());
        r.addMetric("server_latency_median", serverLatencyMedian);
        r.addMetric("throughput", throughput);
        return r;
    }

    private static org.elasticsearch.jingra.model.BenchmarkResult benchThroughputSecondReadNull(
            String engine,
            String paramKey,
            double recall,
            double serverLatencyMedian,
            double firstThroughputRead) {
        org.elasticsearch.jingra.model.BenchmarkResult r =
                new org.elasticsearch.jingra.model.BenchmarkResult(
                        "run", engine, "1.0", "vector_search", "ds", paramKey, Map.of()) {
                    private int throughputGetCount;

                    @Override
                    public Double getMetricAsDouble(String name) {
                        if ("throughput".equals(name)) {
                            throughputGetCount++;
                            if (throughputGetCount == 1) {
                                return firstThroughputRead;
                            }
                            return null;
                        }
                        return super.getMetricAsDouble(name);
                    }
                };
        r.addMetric("recall", recall);
        r.addMetric("server_latency_median", serverLatencyMedian);
        return r;
    }

    private org.elasticsearch.jingra.model.BenchmarkResult createResult(
            String engine, String paramKey, double recall, double serverLatency, double latencyAvg, double throughput) {
        org.elasticsearch.jingra.model.BenchmarkResult result = new org.elasticsearch.jingra.model.BenchmarkResult(
                "test-run", engine, "1.0", "vector_search", "test-dataset", paramKey, java.util.Map.of());
        result.addMetric("recall", recall);
        result.addMetric("server_latency_median", serverLatency);
        result.addMetric("latency_avg", latencyAvg);
        result.addMetric("throughput", throughput);
        result.addMetadata("recall_label", "recall@100");
        return result;
    }

    // Helper method
    private ComparisonResult createComparison(
            String recallAtN,
            String paramKey,
            String baselineEngine,
            double baselineRecall,
            Double baselineLatency,
            Double baselineThroughput,
            String targetEngine,
            double targetRecall,
            Double targetLatency,
            Double targetThroughput
    ) {
        return ComparisonResult.builder()
                .recallAtN(recallAtN)
                .paramKey(paramKey)
                .baselineEngine(baselineEngine)
                .baselineRecall(baselineRecall)
                .baselineLatency(baselineLatency)
                .baselineThroughput(baselineThroughput)
                .targetEngine(targetEngine)
                .targetRecall(targetRecall)
                .targetLatency(targetLatency)
                .targetThroughput(targetThroughput)
                .build();
    }

    @Test
    void exportSpeedupSummary_skipsEngineNotInSpeedupIndexWhenBucketHasExactlyTwoOtherEngines() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult es =
                bench("es", "k=1", 0.751, 10.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd =
                bench("qdrant", "ef=1", 0.749, 15.0, 80.0);
        org.elasticsearch.jingra.model.BenchmarkResult os =
                new org.elasticsearch.jingra.model.BenchmarkResult(
                        "run", "opensearch", "1.0", "vector_search", "ds", "k=os", Map.of());
        os.addMetric("recall", 0.75);
        os.addMetric("latency_median", 5.0);

        exporter.exportSpeedupSummary(
                List.of(es, qd, os), "recall@100", List.of("latency_median"), "elasticsearch", "summary.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("summary.csv"));
        assertEquals(3, lines.size(), "header + es + qdrant only; opensearch shares recall bucket but is not in index");
        assertTrue(lines.stream().noneMatch(l -> l.contains("opensearch")));
    }

    @Test
    void exportSpeedupSummary_skipsSameEngineRowWhenParamKeyIsNotIndexedSelection() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult esLow =
                bench("es", "k=low", 0.751, 20.0, 50.0);
        org.elasticsearch.jingra.model.BenchmarkResult esHigh =
                bench("es", "k=high", 0.751, 10.0, 200.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd =
                bench("qdrant", "ef=1", 0.749, 15.0, 80.0);

        exporter.exportSpeedupSummary(
                List.of(esLow, esHigh, qd), "recall@100", List.of("latency_median"), "elasticsearch", "summary.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("summary.csv"));
        assertEquals(3, lines.size());
        assertTrue(lines.stream().noneMatch(l -> l.contains("k=low")), "lower-throughput duplicate is not the indexed row");
        assertTrue(lines.stream().anyMatch(l -> l.contains("k=high")));
    }

    @Test
    void exportSpeedupSummary_includesBothEnginesForEachComparison() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        // Create results where only some have matching rounded recalls
        org.elasticsearch.jingra.model.BenchmarkResult es1 = bench("es", "k=10", 0.751, 10.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd1 = bench("qdrant", "ef=20", 0.749, 15.0, 80.0);

        // This one has no match (qdrant is alone at 0.85)
        org.elasticsearch.jingra.model.BenchmarkResult qd2 = bench("qdrant", "ef=30", 0.85, 20.0, 50.0);

        List<org.elasticsearch.jingra.model.BenchmarkResult> results = List.of(es1, qd1, qd2);

        exporter.exportSpeedupSummary(results, "recall@100", List.of("latency_median"), "elasticsearch", "summary.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("summary.csv"));

        // Header + 2 rows (both engines at 0.75)
        assertEquals(3, lines.size(), "Should have header and 2 rows (both engines compared at 0.75)");

        // Rows are sorted by recall (0.749 < 0.751), so qdrant comes first
        // First row: qdrant (recall 0.749, slower engine, no speedup)
        assertTrue(lines.get(1).contains("qdrant"));
        assertTrue(lines.get(1).contains("0.75"));

        // Second row: elasticsearch (recall 0.751, faster engine, with speedup)
        assertTrue(lines.get(2).contains("es"));
        assertTrue(lines.get(2).contains("0.75"));
    }

    /**
     * Exercises recall sort comparator: non-null recalls ordered ascending, then rows with missing recall
     * (nulls last; two null-recall rows compare equal and keep stable order).
     */
    @Test
    void exportAllResults_ordersNullRecallRowsAfterRowsWithRecall() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult naFirst =
                benchWithoutRecall("elasticsearch", "k=na1", 5.0, 10.0);
        org.elasticsearch.jingra.model.BenchmarkResult withRecall =
                bench("qdrant", "k=mid", 0.88, 4.0, 50.0);
        org.elasticsearch.jingra.model.BenchmarkResult naSecond =
                benchWithoutRecall("opensearch", "k=na2", 6.0, 11.0);

        exporter.exportAllResults(
                List.of(naFirst, withRecall, naSecond),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"));
        assertEquals(4, lines.size(), "header + 3 rows");
        assertTrue(lines.get(1).contains("qdrant"), "lowest recall row first");
        assertTrue(lines.get(1).contains("0.88"));
        assertTrue(lines.get(2).contains("elasticsearch"), "null-recall rows after measured recall, stable order");
        assertTrue(lines.get(3).contains("opensearch"));
    }

    @Test
    void exportAllResults_sortComparatorBothRecallsNullComparesEqual() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult a = benchWithoutRecall("elasticsearch", "k=a", 1.0, 10.0);
        org.elasticsearch.jingra.model.BenchmarkResult b = benchWithoutRecall("qdrant", "k=b", 2.0, 20.0);

        exporter.exportAllResults(
                List.of(a, b),
                "recall@100",
                List.of("server_latency_median"),
                "elasticsearch",
                "out.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"));
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("elasticsearch"));
        assertTrue(lines.get(2).contains("qdrant"));
    }

    @Test
    void exportSpeedupSummary_sortComparatorCoversNullRecallOrdering() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());
        org.elasticsearch.jingra.model.BenchmarkResult na =
                benchWithoutRecall("elasticsearch", "k=na", 1.0, 10.0);
        org.elasticsearch.jingra.model.BenchmarkResult es =
                bench("es", "k=10", 0.751, 10.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd =
                bench("qdrant", "ef=20", 0.749, 15.0, 80.0);

        exporter.exportSpeedupSummary(
                List.of(na, es, qd), "recall@100", List.of("latency_median"), "elasticsearch", "summary.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("summary.csv"));
        assertEquals(3, lines.size(), "header + two rows (both engines at shared 0.75 bucket)");
        // Sorted by recall: qdrant (0.749) comes before es (0.751)
        assertTrue(lines.get(1).contains("qdrant"));
        assertTrue(lines.get(2).contains("es"));
    }

    @Test
    void exportSpeedupSummary_sortsByRecallAscending() throws IOException {
        CsvExporter exporter = new CsvExporter(tempDir.toString());

        // Create results with multiple matching recalls, but add them out of order
        org.elasticsearch.jingra.model.BenchmarkResult es2 = bench("es", "k=30", 0.851, 12.0, 90.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd2 = bench("qdrant", "ef=40", 0.849, 18.0, 60.0);

        org.elasticsearch.jingra.model.BenchmarkResult es1 = bench("es", "k=20", 0.751, 10.0, 100.0);
        org.elasticsearch.jingra.model.BenchmarkResult qd1 = bench("qdrant", "ef=30", 0.749, 15.0, 80.0);

        // Add in high->low order
        List<org.elasticsearch.jingra.model.BenchmarkResult> results = List.of(es2, qd2, es1, qd1);

        exporter.exportSpeedupSummary(results, "recall@100", List.of("latency_median"), "elasticsearch", "summary.csv");

        List<String> lines = Files.readAllLines(tempDir.resolve("summary.csv"));

        // Header + 4 rows (2 engines at each of 2 recall levels)
        assertEquals(5, lines.size(), "Should have header and 4 rows (both engines at 2 recall levels)");

        // First two data rows should have lower recall (0.75)
        assertTrue(lines.get(1).contains("0.75"), "First row should have recall 0.75");
        assertTrue(lines.get(2).contains("0.75"), "Second row should have recall 0.75");

        // Last two data rows should have higher recall (0.85)
        assertTrue(lines.get(3).contains("0.85"), "Third row should have recall 0.85");
        assertTrue(lines.get(4).contains("0.85"), "Fourth row should have recall 0.85");
    }
}
