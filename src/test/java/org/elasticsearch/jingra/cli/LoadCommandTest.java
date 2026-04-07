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
