package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.LoadConfig;
import org.elasticsearch.jingra.data.ParquetReader;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.engine.EngineFactory;
import org.elasticsearch.jingra.testing.MockBenchmarkEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadCommandTest {

    @AfterEach
    void restoreFactory() {
        LoadCommand.engineFactory = EngineFactory::create;
        LoadCommand.loadExecutorFactory = LoadCommand::newDefaultLoadExecutor;
        LoadCommand.indexAbsentDeadlineNanosOverride = -1L;
        LoadCommand.indexAbsentPollNanosOverride = -1L;
        LoadCommand.parquetReaderFactory = ParquetReader::new;
        LoadCommand.ingestMilestoneDocStep = 100_000;
        LoadCommand.sleepAfterRejectedMs = Thread::sleep;
    }

    @Test
    void runLoadsParquetWithMockEngine() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(10, oneBatchOf(10));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void runPassesConversionThreadsToParquetReader() throws Exception {
        StubParquetReader stubReader = new StubParquetReader(10, oneBatchOf(10));
        LoadCommand.parquetReaderFactory = p -> stubReader;
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);

        // LoadCommand should pass numThreads as conversionThreads (default is 10)
        assertEquals(10, stubReader.capturedConversionThreads,
                "LoadCommand should pass numThreads (10) as conversionThreads parameter");
    }

    @Test
    void publicRunUsesInjectedFactory() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(10, oneBatchOf(10));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.engineFactory = c -> engine;
        LoadCommand.run(config);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void runDeletesExistingIndexWhenPresent() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        IndexThenClearMock engine = new IndexThenClearMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.deleteCalled);
    }

    @Test
    void run_failsWhenConnectReturnsFalse() {
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean connect() {
                return false;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Failed to connect"));
    }

    @Test
    void run_failsWhenDeleteIndexReturnsFalse() {
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean deleteIndex(String indexName) {
                return false;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Failed to delete"));
    }

    @Test
    void run_failsWhenCreateIndexReturnsFalse() {
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean indexExists(String indexName) {
                return false;
            }

            @Override
            public boolean createIndex(String indexName, String schemaName) {
                return false;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Failed to create"));
    }

    @Test
    void run_failsWhenIngestThrowsNonTransient() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean indexExists(String indexName) {
                return false;
            }

            @Override
            public int ingest(List<Document> documents, String indexName, String idField) {
                throw new RuntimeException("simulated ingest failure");
            }
        };
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("failed ingest batch"));
    }

    @Test
    void run_failsWhenIngestedCountDoesNotMatchParquetRowCount() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean indexExists(String indexName) {
                return false;
            }

            @Override
            public int ingest(List<Document> documents, String indexName, String idField) {
                return Math.max(0, documents.size() - 1);
            }
        };
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("incomplete load"));
    }

    @Test
    void run_withExplicitLoadConfigUsesBatchThreadsAndQueue() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(4, oneBatchOf(4));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        LoadConfig load = new LoadConfig();
        load.setBatchSize(2);
        load.setThreads(2);
        load.setQueueCapacity(4);
        config.setLoad(load);
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void run_ingestMilestoneNonPositiveUsesDefaultStep() throws Exception {
        LoadCommand.ingestMilestoneDocStep = 0;
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void run_logsProgressWhenMilestoneCrossed() throws Exception {
        LoadCommand.ingestMilestoneDocStep = 2;
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(4, twoBatchesOfTwo());
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        MilestoneIngestMock engine = new MilestoneIngestMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 2);
    }

    @Test
    void run_interruptedWhileWaitingForExecutorQueueThrows() {
        LoadCommand.sleepAfterRejectedMs = ms -> {
            throw new InterruptedException("i");
        };
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(10, batchesOfOne(10));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        LoadConfig load = new LoadConfig();
        load.setBatchSize(1);
        load.setThreads(1);
        load.setQueueCapacity(1);
        config.setLoad(load);
        SlowIngest engine = new SlowIngest();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Interrupted while waiting for executor queue"));
        assertTrue(ex.getCause() instanceof InterruptedException);
    }

    @Test
    void run_rejectedExecutionRetriesUntilSubmitSucceeds() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(10, batchesOfOne(10));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        LoadConfig load = new LoadConfig();
        load.setBatchSize(1);
        load.setThreads(1);
        load.setQueueCapacity(1);
        config.setLoad(load);

        SlowIngest engine = new SlowIngest();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void run_executorAwaitTerminationFalseThrowsIllegalState() throws Exception {
        LoadCommand.parquetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();

        ExecutorService exec = new AbstractExecutorService() {
            private volatile boolean stopped;

            @Override
            public void shutdown() {
                stopped = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                return Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return stopped;
            }

            @Override
            public boolean isTerminated() {
                return stopped;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };

        LoadCommand.loadExecutorFactory = (t, q) -> exec;

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("timed out after 2 hours"));
    }

    @Test
    void shouldLogMilestoneProgress_falseWhenCurrentMilestoneNotGreaterThanLast() {
        AtomicInteger lastLoggedMilestone = new AtomicInteger(7);
        assertFalse(LoadCommand.shouldLogMilestoneProgress(lastLoggedMilestone, 6, 7));
        assertEquals(7, lastLoggedMilestone.get());
    }

    @Test
    void shouldLogMilestoneProgress_trueWhenCompareAndSetSucceeds() {
        AtomicInteger lastLoggedMilestone = new AtomicInteger(5);
        assertTrue(LoadCommand.shouldLogMilestoneProgress(lastLoggedMilestone, 6, 5));
        assertEquals(6, lastLoggedMilestone.get());
    }

    @Test
    void shouldLogMilestoneProgress_falseWhenCompareAndSetFailsDespiteCurrentGreaterThanLast() {
        AtomicInteger lastLoggedMilestone = new AtomicInteger(5);
        assertFalse(LoadCommand.shouldLogMilestoneProgress(lastLoggedMilestone, 6, 4));
        assertEquals(5, lastLoggedMilestone.get());
    }

    @Test
    void waitUntilIndexAbsentReturnsWhenAlreadyGone() {
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean indexExists(String indexName) {
                return false;
            }
        };
        LoadCommand.waitUntilIndexAbsent(engine, "x");
    }

    @Test
    void waitUntilIndexAbsentPollsUntilGone() {
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            int calls;

            @Override
            public boolean indexExists(String indexName) {
                return calls++ < 5;
            }
        };
        LoadCommand.waitUntilIndexAbsent(engine, "idx");
    }

    /**
     * Demonstrates that ETA calculated from overall average rate becomes inaccurate
     * when throughput degrades over time. This test simulates the real-world scenario
     * where early processing is fast (4400 docs/sec) but later slows down (3400 docs/sec).
     *
     * Example from actual logs:
     * - At 10.5% (2.2M docs), rate=4376 docs/sec, ETA=71.7 min
     * - At 35.2% (7.4M docs), rate=3418 docs/sec, ETA=66.4 min
     * - Expected: ETA should drop ~18 min (25% progress), but only dropped 5 min
     */
    @Test
    void etaCalculationWithDegradingThroughput() {
        // Scenario: 21M total docs, currently at 7.4M (35.2% complete)
        long totalDocs = 21_015_300;
        long docsProcessed = 7_400_000;
        long docsRemaining = totalDocs - docsProcessed; // 13,615,300 remaining

        // Overall average rate includes the faster early performance
        double overallRate = 3418.0; // Current overall avg from logs

        // Recent rate reflects current degraded throughput
        double recentRate = 3418.0; // Recent window rate

        // Current calculation (using overall rate)
        double etaUsingOverall = docsRemaining / overallRate / 60.0; // in minutes

        // Proposed calculation (using recent rate for more accurate ETA)
        double etaUsingRecent = docsRemaining / recentRate / 60.0; // in minutes

        // When throughput is stable, both should be similar
        assertEquals(66.4, etaUsingOverall, 0.1);
        assertEquals(66.4, etaUsingRecent, 0.1);

        // Now simulate degrading throughput scenario:
        // Earlier we were processing at 4376 docs/sec, now at 3418 docs/sec
        double earlyRate = 4376.0;
        double currentRate = 3418.0;

        // If overall rate is still influenced by the faster early rate
        double overallRateWithHistory = 3800.0; // Weighted avg between 4376 and 3418

        double optimisticEta = docsRemaining / overallRateWithHistory / 60.0;
        double realisticEta = docsRemaining / currentRate / 60.0;

        // The optimistic ETA will be lower (faster completion)
        assertTrue(optimisticEta < realisticEta,
            String.format("Optimistic ETA %.1f should be < realistic ETA %.1f when throughput degrades",
                optimisticEta, realisticEta));

        // The difference can be significant (5-10+ minutes)
        double difference = realisticEta - optimisticEta;
        assertTrue(difference > 5.0,
            String.format("ETA difference should be substantial (>5 min) but was %.1f min", difference));
    }

    @Test
    void waitUntilIndexAbsent_timesOutWhenIndexNeverDisappears() {
        LoadCommand.indexAbsentDeadlineNanosOverride = TimeUnit.MILLISECONDS.toNanos(30L);
        LoadCommand.indexAbsentPollNanosOverride = TimeUnit.MILLISECONDS.toNanos(1L);
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean indexExists(String indexName) {
                return true;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.waitUntilIndexAbsent(engine, "idx"));
        assertTrue(ex.getMessage().contains("Timed out"));
    }

    @Test
    void waitUntilIndexAbsent_logsEveryTwentyPolls() {
        LoadCommand.indexAbsentDeadlineNanosOverride = TimeUnit.SECONDS.toNanos(5L);
        LoadCommand.indexAbsentPollNanosOverride = 1_000_000L;
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            private final AtomicInteger wave = new AtomicInteger();

            @Override
            public boolean indexExists(String indexName) {
                return wave.getAndIncrement() < 25;
            }
        };
        LoadCommand.waitUntilIndexAbsent(engine, "idx");
    }

    @Test
    void privateCtor() throws Exception {
        var cl = Class.forName("org.elasticsearch.jingra.cli.LoadCommand");
        var ctor = cl.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }

    private static JingraConfig buildLoadConfig(String dataPath) {
        JingraConfig jingraConfig = new JingraConfig();
        jingraConfig.setEngine("mock");
        jingraConfig.setDataset("ds");

        DatasetConfig ds = new DatasetConfig();
        ds.setIndexName("idx");
        ds.setSchemaName("test-schema");
        DatasetConfig.PathConfig path = new DatasetConfig.PathConfig();
        path.setDataPath(dataPath);
        ds.setPath(path);
        DatasetConfig.DataMappingConfig dm = new DatasetConfig.DataMappingConfig();
        dm.setIdField("catalog_id");
        dm.setVectorField("embedding");
        ds.setDataMapping(dm);

        Map<String, DatasetConfig> datasets = new HashMap<>();
        datasets.put("ds", ds);
        jingraConfig.setDatasets(datasets);
        return jingraConfig;
    }

    private static List<List<Document>> oneBatchOf(int n) {
        List<Document> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Document d = new Document();
            d.put("catalog_id", "id" + i);
            batch.add(d);
        }
        return List.of(batch);
    }

    private static List<List<Document>> batchesOfOne(int n) {
        List<List<Document>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Document d = new Document();
            d.put("catalog_id", "id" + i);
            out.add(List.of(d));
        }
        return out;
    }

    private static List<List<Document>> twoBatchesOfTwo() {
        List<Document> b1 = new ArrayList<>();
        b1.add(doc("a"));
        b1.add(doc("b"));
        List<Document> b2 = new ArrayList<>();
        b2.add(doc("c"));
        b2.add(doc("d"));
        return List.of(b1, b2);
    }

    private static Document doc(String catalogId) {
        Document d = new Document();
        d.put("catalog_id", catalogId);
        return d;
    }

    private static class StubParquetReader extends ParquetReader {
        private final long rowCount;
        private final List<List<Document>> batches;
        int capturedConversionThreads = -1;  // Track what LoadCommand passes

        StubParquetReader(long rowCount, List<List<Document>> batches) {
            super("unused");
            this.rowCount = rowCount;
            this.batches = batches;
        }

        @Override
        public long getRowCount() throws IOException {
            return rowCount;
        }

        @Override
        public void readInBatches(int batchSize, ParquetReader.BatchConsumer consumer) throws IOException {
            // Shouldn't be called anymore - LoadCommand uses 3-param version
            readInBatches(batchSize, 1, consumer);
        }

        @Override
        public void readInBatches(int batchSize, int conversionThreads, ParquetReader.BatchConsumer consumer) throws IOException {
            capturedConversionThreads = conversionThreads;  // Capture for test assertions
            for (List<Document> batch : batches) {
                consumer.accept(batch);
            }
        }
    }

    private static class TrackingMock extends MockBenchmarkEngine {
        int ingestCalls;

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            ingestCalls++;
            return super.ingest(documents, indexName, idField);
        }
    }

    private static class IndexThenClearMock extends MockBenchmarkEngine {
        boolean deleteCalled;

        @Override
        public boolean deleteIndex(String indexName) {
            deleteCalled = true;
            return super.deleteIndex(indexName);
        }
    }

    private static class MilestoneIngestMock extends MockBenchmarkEngine {
        int ingestCalls;
        private final AtomicInteger batchIndex = new AtomicInteger();

        @Override
        public boolean indexExists(String indexName) {
            return false;
        }

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            ingestCalls++;
            if (batchIndex.getAndIncrement() == 0) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return documents.size();
        }
    }

    private static class SlowIngest extends MockBenchmarkEngine {
        int ingestCalls;

        @Override
        public boolean indexExists(String indexName) {
            return false;
        }

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            ingestCalls++;
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return super.ingest(documents, indexName, idField);
        }
    }
}
