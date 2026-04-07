package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.ConfigLoader;
import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.LoadConfig;
import org.elasticsearch.jingra.data.ParquetReader;
import org.elasticsearch.jingra.engine.BenchmarkEngine;
import org.elasticsearch.jingra.engine.EngineFactory;
import org.elasticsearch.jingra.utils.FileDownloader;
import org.elasticsearch.jingra.utils.RetryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Loads dataset into the configured engine (index create + parallel ingest).
 */
public final class LoadCommand {
    private static final Logger logger = LoggerFactory.getLogger(LoadCommand.class);

    /** Poll interval while waiting for the cluster to finish removing an index after delete. */
    private static final long INDEX_ABSENT_POLL_NS = TimeUnit.MILLISECONDS.toNanos(500L);

    /** Max time to wait for {@code indexExists == false} after a successful delete API call. */
    private static final long INDEX_ABSENT_DEADLINE_NS = TimeUnit.MINUTES.toNanos(3L);

    private LoadCommand() {}

    /** Default engine factory for {@link #run(JingraConfig)}; tests may replace temporarily. */
    static Function<JingraConfig, BenchmarkEngine> engineFactory = EngineFactory::create;

    /**
     * Constructs the {@link ParquetReader} for the dataset path. Tests may substitute a stub to avoid filesystem I/O.
     */
    static Function<String, ParquetReader> parquetReaderFactory = ParquetReader::new;

    /**
     * Default executor for parallel ingest; tests may replace to simulate termination failure.
     * Signature: (threads, queueCapacity) to match {@link LoadCommand#run} pool sizing.
     */
    static BiFunction<Integer, Integer, ExecutorService> loadExecutorFactory = LoadCommand::newDefaultLoadExecutor;

    /**
     * When non-negative, overrides {@link #INDEX_ABSENT_DEADLINE_NS} in {@link #waitUntilIndexAbsent} for bounded tests.
     */
    static volatile long indexAbsentDeadlineNanosOverride = -1L;

    /**
     * When non-negative, overrides {@link #INDEX_ABSENT_POLL_NS} in {@link #waitUntilIndexAbsent} for bounded tests.
     */
    static volatile long indexAbsentPollNanosOverride = -1L;

    /**
     * Documents ingested per progress milestone log line. Production default matches the hard-coded milestone width.
     */
    static volatile int ingestMilestoneDocStep = 100_000;

    /**
     * Wait after a full executor queue before retrying submit. Tests may throw {@link InterruptedException} to cover
     * the interrupt path; default is {@link Thread#sleep(long)}.
     */
    @FunctionalInterface
    interface SleepAfterRejectedMs {
        void sleep(long ms) throws InterruptedException;
    }

    static SleepAfterRejectedMs sleepAfterRejectedMs = Thread::sleep;

    static ExecutorService newDefaultLoadExecutor(int numThreads, int queueCapacity) {
        return new ThreadPoolExecutor(
                numThreads, numThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity)
        );
    }

    /**
     * Whether to run milestone progress logging for this ingest step. Uses the same compare-and-set as inline code:
     * another concurrent ingest may advance {@code lastLoggedMilestone} between {@link AtomicInteger#get} and
     * {@link AtomicInteger#compareAndSet}, so the CAS can fail even when {@code currentMilestone > lastMilestone}.
     */
    static boolean shouldLogMilestoneProgress(AtomicInteger lastLoggedMilestone, int currentMilestone, int lastMilestone) {
        return currentMilestone > lastMilestone && lastLoggedMilestone.compareAndSet(lastMilestone, currentMilestone);
    }

    /**
     * Blocks until {@code indexName} is no longer reported by the engine, or throws if the deadline passes.
     * OpenSearch/Elasticsearch can return from delete before all nodes agree the index is gone.
     */
    static void waitUntilIndexAbsent(BenchmarkEngine engine, String indexName) {
        long deadlineNs = indexAbsentDeadlineNanosOverride >= 0
                ? indexAbsentDeadlineNanosOverride
                : INDEX_ABSENT_DEADLINE_NS;
        long pollNs = indexAbsentPollNanosOverride >= 0
                ? indexAbsentPollNanosOverride
                : INDEX_ABSENT_POLL_NS;
        long deadline = System.nanoTime() + deadlineNs;
        int polls = 0;
        while (engine.indexExists(indexName)) {
            if (System.nanoTime() > deadline) {
                throw new RuntimeException(
                        "Timed out after 3m waiting for index '" + indexName + "' to disappear after delete; "
                                + "cluster may still be processing the delete");
            }
            if ((polls++ % 20) == 0) {
                logger.info("Waiting for index '{}' to be fully removed from the cluster...", indexName);
            }
            LockSupport.parkNanos(pollNs);
        }
    }

    public static void run(JingraConfig config) throws Exception {
        run(config, engineFactory);
    }

    /**
     * Package-private for tests that supply a mock {@link BenchmarkEngine}.
     */
    static void run(JingraConfig config, Function<JingraConfig, BenchmarkEngine> engineFactory) throws Exception {

        ConfigLoader.validateForLoad(config);

        BenchmarkEngine engine = engineFactory.apply(config);
        DatasetConfig dataset = config.getActiveDataset();

        try {
            logger.info("Connecting to {}...", engine.getEngineName());
            if (!engine.connect()) {
                throw new RuntimeException("Failed to connect to engine");
            }

            String indexName = dataset.getIndexName();
            if (engine.indexExists(indexName)) {
                logger.info("Index '{}' already exists - deleting...", indexName);
                if (!engine.deleteIndex(indexName)) {
                    throw new RuntimeException("Failed to delete existing index");
                }
                logger.info("Index delete request accepted; waiting until index is gone before create...");
                waitUntilIndexAbsent(engine, indexName);
                logger.info("Index deleted successfully (no longer reported by cluster)");
            }

            logger.info("Creating index '{}'...", indexName);
            if (!engine.createIndex(indexName, dataset.getSchemaName())) {
                throw new RuntimeException("Failed to create index");
            }
            logger.info("Index created successfully");

            String dataPath = dataset.getPath().getDataPath();
            String dataUrlEnv = dataset.getPath().getDataUrlEnv();
            FileDownloader.ensureFileExists(dataPath, dataUrlEnv);
            logger.info("Loading data from: {}", dataPath);

            ParquetReader reader = parquetReaderFactory.apply(dataPath);
            long rowCount = reader.getRowCount();
            logger.info("Parquet file contains {} documents", rowCount);

            String idField = dataset.getDataMapping().getIdField();
            LoadConfig load = config.getLoad();
            int batchSize = load != null ? load.batchSizeOrDefault() : 10_000;
            int numThreads = load != null ? load.threadsOrDefault() : 10;
            int queueCapacity = load != null ? load.queueCapacityOrDefault() : 20;

            // Retry configuration for transient failures (timeouts, connection issues)
            // Use Integer.MAX_VALUE for infinite retries - critical for benchmarking data integrity
            int maxIngestRetries = Integer.MAX_VALUE;
            long ingestRetryBackoffMs = 1000;

            AtomicInteger totalIngested = new AtomicInteger(0);
            AtomicInteger ingestBatchFailures = new AtomicInteger(0);
            AtomicInteger lastLoggedMilestone = new AtomicInteger(0);
            long startTime = System.currentTimeMillis();
            long[] lastLogTime = {startTime};

            ExecutorService executor = loadExecutorFactory.apply(numThreads, queueCapacity);

            // Use same number of threads for Parquet conversion as for ES ingestion
            // This ensures conversion keeps pace with ingestion without excessive thread contention
            int conversionThreads = numThreads;

            logger.info("Starting parallel ingestion with {} threads (queue capacity: {})...", numThreads, queueCapacity);
            logger.info("Using {} threads for parallel Parquet conversion", conversionThreads);

            reader.readInBatches(batchSize, conversionThreads, batch -> {
                while (true) {
                    try {
                        executor.submit(() -> {
                            try {
                                // Wrap ingest with retry logic - applies to ALL engines automatically
                                int ingested = RetryHelper.executeWithRetry(
                                    () -> engine.ingest(batch, indexName, idField),
                                    maxIngestRetries,
                                    ingestRetryBackoffMs
                                );
                                int total = totalIngested.addAndGet(ingested);

                                int step = ingestMilestoneDocStep > 0 ? ingestMilestoneDocStep : 100_000;
                                int currentMilestone = total / step;
                                int lastMilestone = lastLoggedMilestone.get();

                                if (shouldLogMilestoneProgress(lastLoggedMilestone, currentMilestone, lastMilestone)) {
                                    long now = System.currentTimeMillis();
                                    double elapsedSec = (now - startTime) / 1000.0;
                                    double recentElapsedSec = (now - lastLogTime[0]) / 1000.0;
                                    double overallRate = total / elapsedSec;
                                    double recentRate = recentElapsedSec > 0.1 ? step / recentElapsedSec : overallRate;
                                    double progress = 100.0 * total / rowCount;
                                    // Use recent rate for ETA since it better reflects current throughput
                                    // when performance degrades over time (GC pressure, index growth, etc.)
                                    double remainingSec = (rowCount - total) / recentRate;

                                    logger.info("Progress: {}/{} docs ({}%) | Rate: {} docs/sec (recent: {}) | ETA: {} min",
                                            total, rowCount,
                                            String.format("%.1f", progress),
                                            String.format("%.0f", overallRate),
                                            String.format("%.0f", recentRate),
                                            String.format("%.1f", remainingSec / 60));
                                    lastLogTime[0] = now;
                                }
                            } catch (Exception e) {
                                ingestBatchFailures.incrementAndGet();
                                logger.error("Failed to ingest batch", e);
                            }
                        });
                        break;
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        try {
                            sleepAfterRejectedMs.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for executor queue", ie);
                        }
                    }
                }
            });

            logger.info("Waiting for all batches to complete...");
            executor.shutdown();
            boolean finished = executor.awaitTermination(2, TimeUnit.HOURS);
            if (!finished) {
                logger.error("Load executor did not finish within 2 hours; forcing shutdown");
                executor.shutdownNow();
                throw new IllegalStateException("Data loading timed out after 2 hours");
            }
            if (ingestBatchFailures.get() > 0) {
                throw new IllegalStateException(
                        "Data loading completed with " + ingestBatchFailures.get() + " failed ingest batch(es); refusing to report success");
            }

            long endTime = System.currentTimeMillis();
            double totalTimeSec = (endTime - startTime) / 1000.0;
            int finalCount = totalIngested.get();
            double avgRate = finalCount / totalTimeSec;

            if (finalCount != rowCount) {
                throw new IllegalStateException(
                        String.format("Ingested %d documents but parquet row count is %d (incomplete load)", finalCount, rowCount));
            }

            logger.info("=".repeat(80));
            logger.info("Data loading complete!");
            logger.info("  Total ingested: {} documents", finalCount);
            logger.info("  Total time: {} ({} min)",
                    String.format("%.1f sec", totalTimeSec),
                    String.format("%.1f", totalTimeSec / 60));
            logger.info("  Average rate: {} docs/sec", String.format("%.0f", avgRate));
            logger.info("  Final count: {} documents", engine.getDocumentCount(indexName));
            logger.info("=".repeat(80));

        } finally {
            engine.close();
        }
    }
}
