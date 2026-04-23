package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.analysis.PlotGenerator;
import org.elasticsearch.jingra.config.AnalysisConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeCommandTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreDefaults() {
        AnalyzeCommand.resultsEngineFactory = AnalyzeCommand::createResultsEngine;
        AnalyzeCommand.plotGeneratorFactory = (out, versions) -> new PlotGenerator(out, versions);
    }

    @Test
    void run_connectsToResultsCluster() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(mockEngine.connectCalled, "Should connect to results cluster");
    }

    @Test
    void run_queriesByRunId() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertEquals("test-run-123", mockEngine.queriedRunId);
    }

    @Test
    void run_exportsDetailedComparisonCsvs() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        // Add results with recall@100 and recall@10
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));
        mockEngine.addResult(createResult("elasticsearch", "k=50", 0.85, 3.0, "recall@10"));
        mockEngine.addResult(createResult("qdrant", "k=50", 0.83, 2.0, "recall@10"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        // Should create detailed comparison CSVs for each recall@N
        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
        assertTrue(Files.exists(tempDir.resolve("recall@10_full_results.csv")));
    }

    @Test
    void run_exportsSummaryComparisonCsv() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(tempDir.resolve("summary_comparison.csv")));
    }

    @Test
    void run_generatesRecallVsLatencyPlotsWhenPlotsEnabled() throws Exception {
        JingraConfig config = createValidConfig();
        config.getAnalysis().setGeneratePlots(true);
        config.getAnalysis().setLatencyMetrics(List.of("latency_median", "latency_avg"));

        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().endsWith(".png")));
    }

    @Test
    void run_continuesWhenOnePlotGenerationThrows() throws Exception {
        AtomicInteger plotCalls = new AtomicInteger();
        AnalyzeCommand.plotGeneratorFactory = (out, versions) -> new PlotGenerator(out) {
            @Override
            public void generateRecallVsLatencyPlot(
                    Map<String, List<BenchmarkResult>> resultsByEngine,
                    String recallAtN,
                    String latencyMetric) throws IOException {
                if (plotCalls.incrementAndGet() == 2) {
                    throw new IOException("simulated plot failure");
                }
                super.generateRecallVsLatencyPlot(resultsByEngine, recallAtN, latencyMetric);
            }
        };

        JingraConfig config = createValidConfig();
        config.getAnalysis().setGeneratePlots(true);
        config.getAnalysis().setLatencyMetrics(List.of("latency_median", "latency_avg"));

        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().endsWith(".png")));
        assertEquals(2, plotCalls.get());
    }

    @Test
    void run_generatesThroughputOverviewWithMismatchedRecallLevels() throws Exception {
        JingraConfig config = createValidConfig();
        config.getAnalysis().setGeneratePlots(true);
        config.getAnalysis().setLatencyMetrics(List.of("latency_median", "latency_avg"));

        MockResultsEngine mockEngine = new MockResultsEngine();
        // Both engines with overlapping recall values in 0.7-0.9 range
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.851, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=50", 0.849, 2.0, "recall@10"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.list(tempDir).anyMatch(p -> p.getFileName().toString().startsWith("recall_vs_")));
        // Overview should be generated with overlapping recall values that round to 0.85
        assertTrue(Files.exists(tempDir.resolve("throughput_overview.png")));
    }

    @Test
    void run_logsWarningWhenThroughputOverviewThrows() throws Exception {
        AnalyzeCommand.plotGeneratorFactory = (out, versions) -> new PlotGenerator(out) {
            @Override
            public void generateThroughputOverview(Map<String, List<BenchmarkResult>> data)
                    throws IOException {
                throw new IOException("simulated overview failure");
            }
        };

        JingraConfig config = createValidConfig();
        config.getAnalysis().setGeneratePlots(true);
        config.getAnalysis().setLatencyMetrics(List.of("latency_median", "latency_avg"));

        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
        assertFalse(Files.exists(tempDir.resolve("throughput_overview.png")));
    }

    @Test
    void run_exportsThroughputComparisonCsv() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(tempDir.resolve("throughput_comparison.csv")));
    }

    @Test
    void run_createsOutputDirectory() throws Exception {
        Path newDir = tempDir.resolve("new_output");
        assertFalse(Files.exists(newDir));

        JingraConfig config = createValidConfig();
        config.getAnalysis().setOutputDirectory(newDir.toString());

        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(newDir));
    }

    @Test
    void run_closesEngineAfterCompletion() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(mockEngine.closeCalled, "Should close engine after completion");
    }

    @Test
    void run_closesEngineOnException() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.failConnect = true;

        assertThrows(Exception.class, () -> AnalyzeCommand.run(config, cfg -> mockEngine));

        assertTrue(mockEngine.closeCalled, "Should close engine even on exception");
    }

    @Test
    void run_warnsWhenEngineHasNoResults() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        // Only elasticsearch results, no qdrant
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));

        // Should not throw, just warn
        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(mockEngine.closeCalled);
    }

    @Test
    void snapshotFiles_returnsEmptyWhenDirectoryDoesNotExist() throws Exception {
        Method m = AnalyzeCommand.class.getDeclaredMethod("snapshotFiles", File.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> names = (Set<String>) m.invoke(null, tempDir.resolve("missing_dir_xyz").toFile());
        assertTrue(names.isEmpty());
    }

    @Test
    void snapshotFiles_returnsEmptyWhenPathIsAFile() throws Exception {
        Path f = Files.createTempFile(tempDir, "not-a-dir", ".txt");
        Method m = AnalyzeCommand.class.getDeclaredMethod("snapshotFiles", File.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> names = (Set<String>) m.invoke(null, f.toFile());
        assertTrue(names.isEmpty());
    }

    @Test
    void findGeneratedFiles_returnsEmptyWhenDirectoryDoesNotExist() throws Exception {
        Method m = AnalyzeCommand.class.getDeclaredMethod("findGeneratedFiles", File.class, Set.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> generated = (Set<String>) m.invoke(
                null, tempDir.resolve("absent_dir_for_find").toFile(), Set.<String>of());
        assertTrue(generated.isEmpty());
    }

    @Test
    void findGeneratedFiles_returnsEmptyWhenPathIsAFile() throws Exception {
        Path f = Files.createTempFile(tempDir, "findgen-file", ".dat");
        Method m = AnalyzeCommand.class.getDeclaredMethod("findGeneratedFiles", File.class, Set.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> generated = (Set<String>) m.invoke(null, f.toFile(), Set.<String>of());
        assertTrue(generated.isEmpty());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void snapshotFiles_treatsNullListFilesAsEmpty() throws Exception {
        Path d = Files.createTempDirectory(tempDir, "no-read-perm");
        Files.createFile(d.resolve("child.txt"));
        Set<PosixFilePermission> noRead = EnumSet.of(
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        );
        Set<PosixFilePermission> restore = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        );
        try {
            Files.setPosixFilePermissions(d, noRead);
            Method m = AnalyzeCommand.class.getDeclaredMethod("snapshotFiles", File.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) m.invoke(null, d.toFile());
            assertTrue(names.isEmpty());
        } finally {
            Files.setPosixFilePermissions(d, restore);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void findGeneratedFiles_treatsNullListFilesAsEmpty() throws Exception {
        Path d = Files.createTempDirectory(tempDir, "no-read-find");
        Files.createFile(d.resolve("child.txt"));
        Set<PosixFilePermission> noRead = EnumSet.of(
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        );
        Set<PosixFilePermission> restore = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        );
        try {
            Files.setPosixFilePermissions(d, noRead);
            Method m = AnalyzeCommand.class.getDeclaredMethod("findGeneratedFiles", File.class, Set.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> generated = (Set<String>) m.invoke(null, d.toFile(), Set.<String>of());
            assertTrue(generated.isEmpty());
        } finally {
            Files.setPosixFilePermissions(d, restore);
        }
    }

    @Test
    void findGeneratedFiles_listsOnlyNewFilesComparedToSnapshot() throws Exception {
        Method snap = AnalyzeCommand.class.getDeclaredMethod("snapshotFiles", File.class);
        Method find = AnalyzeCommand.class.getDeclaredMethod("findGeneratedFiles", File.class, Set.class);
        snap.setAccessible(true);
        find.setAccessible(true);

        Set<String> before = (Set<String>) snap.invoke(null, tempDir.toFile());
        Files.writeString(tempDir.resolve("new_export.csv"), "h\n");

        @SuppressWarnings("unchecked")
        Set<String> generated = (Set<String>) find.invoke(null, tempDir.toFile(), before);

        assertTrue(generated.contains("new_export.csv"));
    }

    @Test
    void createResultsEngine_buildsElasticsearchEngineFromClusterConfig() {
        ElasticsearchEngine engine = AnalyzeCommand.createResultsEngine(Map.of("url", "http://localhost:9200"));
        assertNotNull(engine);
    }

    @Test
    void run_delegatesSingleArgOverloadToResultsEngineFactory() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.resultsEngineFactory = cfg -> mockEngine;
        AnalyzeCommand.run(config);

        assertTrue(mockEngine.connectCalled);
        assertTrue(mockEngine.closeCalled);
    }

    @Test
    void run_returnsEarlyWhenNoResultsForRunId() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(mockEngine.closeCalled);
        assertFalse(Files.exists(tempDir.resolve("summary_comparison.csv")));
    }

    @Test
    void run_skipsSummaryWhenMaxRecallPointsHaveNoOverlappingParamKey() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        // Same recall@100 bucket: ES highest recall at k=200, Qdrant highest at k=100 — max-point pair won't match by param_key
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.90, 5.0, "recall@100"));
        mockEngine.addResult(createResult("elasticsearch", "k=200", 0.95, 6.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=200", 0.88, 7.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
        assertFalse(Files.exists(tempDir.resolve("summary_comparison.csv")));
        assertFalse(Files.exists(tempDir.resolve("throughput_comparison.csv")));
    }

    @Test
    void run_exportsAllResultsEvenWhenOnlyOneEngineHasData() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        // Both engines have recall@100
        mockEngine.addResult(createResult("elasticsearch", "k=2500_num_candidates=2500_rescore=3_size=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "num_candidates=12000_rescore=120_size=100", 0.93, 4.0, "recall@100"));

        // Only elasticsearch has recall@10
        mockEngine.addResult(createResult("elasticsearch", "k=1000_num_candidates=1000_rescore=2_size=100", 0.85, 3.0, "recall@10"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        // Both CSVs should be created (new behavior: export all available data)
        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
        assertTrue(Files.exists(tempDir.resolve("recall@10_full_results.csv")));

        // Verify recall@10 CSV has only elasticsearch result
        List<String> recall10Lines = Files.readAllLines(tempDir.resolve("recall@10_full_results.csv"));
        assertEquals(2, recall10Lines.size()); // Header + 1 data row
        assertTrue(recall10Lines.get(1).contains("elasticsearch"));
    }

    @Test
    void run_doesNotListGeneratedFilesWhenNothingExportedForRecallLabels() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResultWithoutRecallLabel("elasticsearch", "k=100", 0.95, 5.0));
        mockEngine.addResult(createResultWithoutRecallLabel("qdrant", "k=100", 0.93, 4.0));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(mockEngine.closeCalled);
        assertFalse(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
    }

    @Test
    void run_snapshotSkipsSubdirectoriesWhenDetectingNewFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("nested_subdir"));
        Files.writeString(tempDir.resolve("already_present.txt"), "x");

        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();
        mockEngine.addResult(createResult("elasticsearch", "k=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "k=100", 0.93, 4.0, "recall@100"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
    }

    @Test
    void run_exportsAllResultsWhenOnlyTargetEngineHasData() throws Exception {
        JingraConfig config = createValidConfig();
        MockResultsEngine mockEngine = new MockResultsEngine();

        mockEngine.addResult(createResult("elasticsearch", "k=2500_num_candidates=2500_rescore=3_size=100", 0.95, 5.0, "recall@100"));
        mockEngine.addResult(createResult("qdrant", "num_candidates=12000_rescore=120_size=100", 0.93, 4.0, "recall@100"));
        // recall@10: only qdrant (new behavior: still export)
        mockEngine.addResult(createResult("qdrant", "num_candidates=5000_rescore=50_size=100", 0.83, 2.0, "recall@10"));

        AnalyzeCommand.run(config, cfg -> mockEngine);

        // Both CSVs should be created (new behavior: export all available data)
        assertTrue(Files.exists(tempDir.resolve("recall@100_full_results.csv")));
        assertTrue(Files.exists(tempDir.resolve("recall@10_full_results.csv")));

        // Verify recall@10 CSV has only qdrant result
        List<String> recall10Lines = Files.readAllLines(tempDir.resolve("recall@10_full_results.csv"));
        assertEquals(2, recall10Lines.size()); // Header + 1 data row
        assertTrue(recall10Lines.get(1).contains("qdrant"));
    }

    // Helper methods

    private JingraConfig createValidConfig() {
        JingraConfig config = new JingraConfig();
        config.setEngine("elasticsearch");
        config.setDataset("test-dataset");

        AnalysisConfig ac = new AnalysisConfig();
        ac.setRunId("test-run-123");
        ac.setEngines(List.of("elasticsearch", "qdrant"));
        ac.setResultsCluster(Map.of("url", "http://localhost:9200"));
        ac.setOutputDirectory(tempDir.toString());
        ac.setLatencyMetrics(List.of("latency_median"));
        ac.setGeneratePlots(false);  // Skip plot generation

        config.setAnalysis(ac);
        return config;
    }

    private BenchmarkResult createResult(String engine, String paramKey, double recall, double latency, String recallLabel) {
        BenchmarkResult result = new BenchmarkResult(
                "test-run-123",
                engine,
                "1.0",
                "vector_search",
                "test-dataset",
                paramKey,
                Map.of()
        );

        result.addMetric("recall", recall);
        result.addMetric("latency_median", latency);
        result.addMetric("latency_avg", latency);
        result.addMetric("throughput", 100.0 / latency);  // Derive throughput from latency
        result.addMetadata("recall_label", recallLabel);

        return result;
    }

    private BenchmarkResult createResultWithoutRecallLabel(String engine, String paramKey, double recall, double latency) {
        BenchmarkResult result = new BenchmarkResult(
                "test-run-123",
                engine,
                "1.0",
                "vector_search",
                "test-dataset",
                paramKey,
                Map.of()
        );
        result.addMetric("recall", recall);
        result.addMetric("latency_median", latency);
        result.addMetric("throughput", 100.0 / latency);
        return result;
    }

    // Mock Results Engine
    private static class MockResultsEngine extends ElasticsearchEngine {
        boolean connectCalled = false;
        boolean closeCalled = false;
        boolean failConnect = false;
        String queriedRunId = null;
        private final List<BenchmarkResult> results = new java.util.ArrayList<>();

        public MockResultsEngine() {
            super(Map.of("url", "http://localhost:9200"));
        }

        public void addResult(BenchmarkResult result) {
            results.add(result);
        }

        @Override
        public boolean connect() {
            connectCalled = true;
            if (failConnect) {
                return false;
            }
            return true;
        }

        @Override
        public co.elastic.clients.elasticsearch.core.SearchResponse<Map> search(String indexName, String queryJson) {
            // Extract run_id from query JSON
            if (queryJson.contains("run_id")) {
                queriedRunId = "test-run-123";  // Simplified - just record that we queried
            }

            // Convert results to SearchResponse format
            java.util.List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = results.stream()
                    .map(r -> co.elastic.clients.elasticsearch.core.search.Hit.<Map>of(h -> h
                            .id("test-id")
                            .index(indexName)
                            .source(r.toMap())))
                    .toList();

            return co.elastic.clients.elasticsearch.core.SearchResponse.of(s -> s
                    .timedOut(false)
                    .took(1L)
                    .shards(co.elastic.clients.elasticsearch._types.ShardStatistics.of(sh ->
                            sh.total(1).successful(1).failed(0)))
                    .hits(co.elastic.clients.elasticsearch.core.search.HitsMetadata.of(h -> h
                            .hits(hits)
                            .total(co.elastic.clients.elasticsearch.core.search.TotalHits.of(t ->
                                    t.value(hits.size()).relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq))))));
        }

        @Override
        public void close() {
            closeCalled = true;
        }
    }
}
