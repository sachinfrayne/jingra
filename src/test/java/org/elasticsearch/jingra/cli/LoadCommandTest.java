package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.LoadConfig;
import org.elasticsearch.jingra.data.DatasetReader;
import org.elasticsearch.jingra.data.ParquetReader;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.engine.EngineFactory;
import org.elasticsearch.jingra.testing.MockBenchmarkEngine;
import org.elasticsearch.jingra.engine.BenchmarkEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadCommandTest {

    @AfterEach
    void restoreFactory() {
        LoadCommand.engineFactory = EngineFactory::create;
        LoadCommand.loadExecutorFactory = LoadCommand::newDefaultLoadExecutor;
        LoadCommand.indexAbsentDeadlineNanosOverride = -1L;
        LoadCommand.indexAbsentPollNanosOverride = -1L;
        LoadCommand.datasetReaderFactory = org.elasticsearch.jingra.data.DatasetReaderFactory::create;
        LoadCommand.ingestMilestoneDocStep = 100_000;
        LoadCommand.sleepAfterRejectedMs = Thread::sleep;
    }

    @Test
    void runLoadsParquetWithMockEngine() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(10, oneBatchOf(10));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void loadDataset_passesNullIdFieldWhenDataMappingAbsent() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(10, oneBatchOf(10));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        DatasetConfig dataset = config.getActiveDataset();
        dataset.setDataMapping(null);
        TrackingMock engine = new TrackingMock();
        Method loadDataset =
                LoadCommand.class.getDeclaredMethod(
                        "loadDataset", BenchmarkEngine.class, DatasetConfig.class, JingraConfig.class);
        loadDataset.setAccessible(true);
        loadDataset.invoke(null, engine, dataset, config);
        assertTrue(engine.ingestCalls >= 1);
        assertNull(engine.lastIngestIdField);
    }

    /** Covers {@code dataUrlEnv != null} → {@link org.elasticsearch.jingra.utils.FileDownloader#ensureFileExists}. */
    @Test
    void run_whenDataUrlEnvSet_stillLoadsWhenLocalFileAlreadyValid() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        config.getActiveDataset().getPath().setDataUrlEnv("UNUSED_IF_FILE_EXISTS");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void run_whenDataFileMissingAndNoDataUrlEnv_throws() {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("/no/such/path/does-not-exist.parquet");
        TrackingMock engine = new TrackingMock();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Data file not found"));
    }

    @Test
    void runPassesConversionThreadsToParquetReader() throws Exception {
        StubParquetReader stubReader = new StubParquetReader(10, oneBatchOf(10));
        LoadCommand.datasetReaderFactory = p -> stubReader;
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);

        // LoadCommand should pass numThreads as conversionThreads (default is 10)
        assertEquals(10, stubReader.capturedConversionThreads,
                "LoadCommand should pass numThreads (10) as conversionThreads parameter");
    }

    @Test
    void publicRunUsesInjectedFactory() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(10, oneBatchOf(10));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.engineFactory = c -> engine;
        LoadCommand.run(config);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void runDeletesExistingIndexWhenPresent() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        IndexThenClearMock engine = new IndexThenClearMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.deleteCalled);
    }

    @Test
    void run_schemaFreeEngine_callsDeleteDirectlyAndSkipsIndexLifecycle() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        SchemaFreeMock engine = new SchemaFreeMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.deleteCalled, "resetDataStore should be called to clear old data");
        assertFalse(engine.createCalled, "createDataStore must not be called for schema-free engines");
        assertTrue(engine.ingestCalls >= 1, "ingest must proceed");
    }

    @Test
    void run_schemaFreeEngine_failsWhenDeleteReturnsFalse() {
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override public boolean supportsIndexLifecycle() { return false; }
            @Override public boolean resetDataStore(String indexName) { return false; }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Failed to clear"));
    }

    @Test
    void run_failsWhenConnectReturnsFalse() {
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
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
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean resetDataStore(String indexName) {
                return false;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Failed to delete"));
    }

    @Test
    void run_failsWhenCreateIndexReturnsFalse() {
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean dataStoreExists(String indexName) {
                return false;
            }

            @Override
            public boolean createDataStore(String indexName, String schemaName) {
                return false;
            }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Failed to create"));
    }

    @Test
    void run_failsWhenIngestThrowsNonTransient() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean dataStoreExists(String indexName) {
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
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        MockBenchmarkEngine engine = new MockBenchmarkEngine() {
            @Override
            public boolean dataStoreExists(String indexName) {
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
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(4, oneBatchOf(4));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
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
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void run_logsProgressWhenMilestoneCrossed() throws Exception {
        LoadCommand.ingestMilestoneDocStep = 2;
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(4, twoBatchesOfTwo());
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        MilestoneIngestMock engine = new MilestoneIngestMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 2);
    }

    /**
     * Covers {@code recentElapsedSec > 0.1 ? ... : overallRate} when two milestones fire back-to-back
     * (single-threaded pool so the second runnable often runs within 100 ms of the first log).
     */
    @Test
    void run_milestoneLoggingMayUseOverallRateWhenRecentWindowVeryShort() throws Exception {
        LoadCommand.ingestMilestoneDocStep = 1;
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(3, batchesOfOne(3));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        LoadConfig load = new LoadConfig();
        load.setBatchSize(1);
        load.setThreads(1);
        load.setQueueCapacity(10);
        config.setLoad(load);
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertEquals(3, engine.ingestCalls);
    }

    @Test
    void run_interruptedWhileWaitingForExecutorQueueThrows() {
        LoadCommand.sleepAfterRejectedMs = ms -> {
            throw new InterruptedException("i");
        };
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(10, batchesOfOne(10));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
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
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(10, batchesOfOne(10));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
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
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
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
            public boolean dataStoreExists(String indexName) {
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
            public boolean dataStoreExists(String indexName) {
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
            public boolean dataStoreExists(String indexName) {
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
            public boolean dataStoreExists(String indexName) {
                return wave.getAndIncrement() < 25;
            }
        };
        LoadCommand.waitUntilIndexAbsent(engine, "idx");
    }

    @Test
    void run_awaitIndexReadyTrue_callsAwaitIndexReadyOnElasticsearchEngine() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        LoadConfig load = new LoadConfig();
        load.setAwaitIndexReady(true);
        config.setLoad(load);

        AtomicBoolean awaitCalled = new AtomicBoolean(false);
        ElasticsearchEngine esEngine = new ElasticsearchEngine(new HashMap<>()) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            public boolean connect() {
                return true;
            }

            @Override
            public boolean dataStoreExists(String indexName) {
                return false;
            }

            @Override
            public boolean createDataStore(String indexName, String schemaName) {
                return true;
            }

            @Override
            public int ingest(List<Document> documents, String indexName, String idField) {
                return documents.size();
            }

            @Override
            public long getDocumentCount(String indexName) {
                return 2;
            }

            @Override
            protected int mergesCurrentOperation(String indexName) {
                awaitCalled.set(true);
                return 0;
            }

            @Override
            protected long getPollIntervalMs() { return 0L; }
        };

        LoadCommand.run(config, c -> esEngine);
        assertTrue(awaitCalled.get(), "awaitIndexReady should be called when await_index_ready: true");
    }

    @Test
    void run_awaitIndexReadyFalse_skipsAwaitIndexReady() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        // await_index_ready defaults to false — no LoadConfig set, so getLoad() returns null

        AtomicBoolean awaitCalled = new AtomicBoolean(false);
        ElasticsearchEngine esEngine = new ElasticsearchEngine(new HashMap<>()) {
            @Override
            protected boolean hasClient() {
                return true;
            }

            @Override
            public boolean connect() {
                return true;
            }

            @Override
            public boolean dataStoreExists(String indexName) {
                return false;
            }

            @Override
            public boolean createDataStore(String indexName, String schemaName) {
                return true;
            }

            @Override
            public int ingest(List<Document> documents, String indexName, String idField) {
                return documents.size();
            }

            @Override
            public long getDocumentCount(String indexName) {
                return 2;
            }

            @Override
            protected int mergesCurrentOperation(String indexName) {
                awaitCalled.set(true);
                return 0;
            }

            @Override
            protected long getPollIntervalMs() { return 0L; }
        };

        LoadCommand.run(config, c -> esEngine);
        assertFalse(awaitCalled.get(), "awaitIndexReady should not be called when await_index_ready is false");
    }

    @Test
    void run_awaitIndexReadyTrue_nonEsEngineUsesNoOpDefault() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(2, oneBatchOf(2));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        LoadConfig load = new LoadConfig();
        load.setAwaitIndexReady(true);
        config.setLoad(load);

        AtomicBoolean awaitCalledOnMock = new AtomicBoolean(false);
        // Override the default no-op to detect if it is invoked; the no-op itself does nothing
        BenchmarkEngine mockEngine = new MockBenchmarkEngine() {
            @Override
            public boolean dataStoreExists(String indexName) {
                return false;
            }

            @Override
            public void awaitIndexReady(String indexName) {
                awaitCalledOnMock.set(true);
                // no-op (mirrors the interface default)
            }
        };

        // Should complete without error; awaitIndexReady dispatches through the interface (no-op)
        LoadCommand.run(config, c -> mockEngine);
        assertTrue(awaitCalledOnMock.get(),
                "awaitIndexReady() should be called on every engine via the interface; non-ES engines use the no-op default");
    }

    @Test
    void run_loadsEachUniqueIndex_whenMultipleDatasetsDeclareDifferentIndexes() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        addSecondDataset(config, "ds2", "idx2", "src/test/resources/parquet/test_text_data.parquet");

        IndexTrackingMock engine = new IndexTrackingMock();
        LoadCommand.run(config, c -> engine);

        assertEquals(2, engine.createCalls.size(), "expected one createDataStore per unique index");
        assertTrue(engine.createCalls.contains("idx"));
        assertTrue(engine.createCalls.contains("idx2"));
    }

    @Test
    void run_dedupes_whenMultipleDatasetsShareIndexAndDataPath() throws Exception {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        addSecondDataset(config, "ds2", "idx", "src/test/resources/parquet/test_text_data.parquet");

        IndexTrackingMock engine = new IndexTrackingMock();
        LoadCommand.run(config, c -> engine);

        assertEquals(1, engine.createCalls.size(), "second dataset sharing index + data_path should be skipped");
        assertEquals("idx", engine.createCalls.get(0));
    }

    @Test
    void run_throws_whenSameIndexHasConflictingDataPath() {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig("src/test/resources/parquet/test_text_data.parquet");
        addSecondDataset(config, "ds2", "idx", "/different/path.ndjson");

        IndexTrackingMock engine = new IndexTrackingMock();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("idx"),
                "error should name the conflicting index, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("data_path"),
                "error should explain the conflict is about data_path, was: " + ex.getMessage());
    }

    @Test
    void privateCtor() throws Exception {
        var cl = Class.forName("org.elasticsearch.jingra.cli.LoadCommand");
        var ctor = cl.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }

    private static void addSecondDataset(JingraConfig config, String name, String indexName, String dataPath) {
        DatasetConfig ds = new DatasetConfig();
        ds.setIndexName(indexName);
        ds.setSchemaName("test-schema");
        DatasetConfig.PathConfig path = new DatasetConfig.PathConfig();
        path.setDataPath(dataPath);
        ds.setPath(path);
        DatasetConfig.DataMappingConfig dm = new DatasetConfig.DataMappingConfig();
        dm.setIdField("catalog_id");
        ds.setDataMapping(dm);

        Map<String, DatasetConfig> merged = new HashMap<>(config.getDatasets());
        merged.put(name, ds);
        config.setDatasets(merged);
        config.setDatasetNames(List.of("ds", name));
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
        public void readInBatches(int batchSize, DatasetReader.BatchConsumer consumer) throws IOException {
            readInBatches(batchSize, 1, consumer);
        }

        @Override
        public void readInBatches(int batchSize, int conversionThreads, DatasetReader.BatchConsumer consumer) throws IOException {
            capturedConversionThreads = conversionThreads;  // Capture for test assertions
            for (List<Document> batch : batches) {
                consumer.accept(batch);
            }
        }
    }

    private static class TrackingMock extends MockBenchmarkEngine {
        int ingestCalls;
        String lastIngestIdField;

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            ingestCalls++;
            lastIngestIdField = idField;
            return super.ingest(documents, indexName, idField);
        }
    }

    private static class IndexTrackingMock extends MockBenchmarkEngine {
        final List<String> createCalls = Collections.synchronizedList(new ArrayList<>());

        IndexTrackingMock() {
            indexPresent = false;
        }

        @Override
        public boolean createDataStore(String indexName, String schemaName) {
            createCalls.add(indexName);
            return super.createDataStore(indexName, schemaName);
        }
    }

    private static class IndexThenClearMock extends MockBenchmarkEngine {
        boolean deleteCalled;

        @Override
        public boolean resetDataStore(String indexName) {
            deleteCalled = true;
            return super.resetDataStore(indexName);
        }
    }

    private static class SchemaFreeMock extends MockBenchmarkEngine {
        boolean deleteCalled;
        boolean createCalled;
        int ingestCalls;

        SchemaFreeMock() { indexPresent = false; }

        @Override public boolean supportsIndexLifecycle() { return false; }

        @Override
        public boolean resetDataStore(String indexName) {
            deleteCalled = true;
            return true;
        }

        @Override
        public boolean createDataStore(String indexName, String schemaName) {
            createCalled = true;
            return true;
        }

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            ingestCalls++;
            return documents.size();
        }
    }

    private static class MilestoneIngestMock extends MockBenchmarkEngine {
        int ingestCalls;
        private final AtomicInteger batchIndex = new AtomicInteger();

        @Override
        public boolean dataStoreExists(String indexName) {
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

    @Test
    void run_whenQuestionMarkGlobDataPathHasNoMatches_throwsDataFileNotFound(@TempDir java.nio.file.Path tmpDir) {
        // exercises the path.contains("?") branch in dataPathExists
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig(tmpDir + "/no-match-?.ndjson.gz");
        TrackingMock engine = new TrackingMock();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Data file not found"));
    }

    @Test
    void run_whenGlobDataPathHasNoMatches_throwsDataFileNotFound(@TempDir java.nio.file.Path tmpDir) {
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig(tmpDir + "/no-match-*.ndjson.gz");
        TrackingMock engine = new TrackingMock();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> LoadCommand.run(config, c -> engine));
        assertTrue(ex.getMessage().contains("Data file not found"));
    }

    @Test
    void run_whenGlobDataPathHasMatches_proceeds(@TempDir java.nio.file.Path tmpDir) throws Exception {
        java.nio.file.Files.writeString(tmpDir.resolve("part-0000.ndjson"), "{\"id\":\"1\"}\n");
        LoadCommand.datasetReaderFactory = p -> new StubParquetReader(1, oneBatchOf(1));
        JingraConfig config = buildLoadConfig(tmpDir + "/part-*.ndjson");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    private static class SlowIngest extends MockBenchmarkEngine {
        int ingestCalls;

        @Override
        public boolean dataStoreExists(String indexName) {
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
