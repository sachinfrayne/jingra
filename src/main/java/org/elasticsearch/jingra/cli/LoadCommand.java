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
     * Blocks until {@code indexName} is no longer reported by the engine, or throws if the deadline passes.
     * OpenSearch/Elasticsearch can return from delete before all nodes agree the index is gone.
     */
    static void waitUntilIndexAbsent(BenchmarkEngine engine, String indexName) {
        long deadline = System.nanoTime() + INDEX_ABSENT_DEADLINE_NS;
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
            LockSupport.parkNanos(INDEX_ABSENT_POLL_NS);
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

            ParquetReader reader = new ParquetReader(dataPath);
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

            ExecutorService executor = new ThreadPoolExecutor(
                    numThreads, numThreads,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity)
            );

            logger.info("Starting parallel ingestion with {} threads (queue capacity: {})...", numThreads, queueCapacity);

            reader.readInBatches(batchSize, batch -> {
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

                                int currentMilestone = total / 100000;
                                int lastMilestone = lastLoggedMilestone.get();

                                if (currentMilestone > lastMilestone && lastLoggedMilestone.compareAndSet(lastMilestone, currentMilestone)) {
                                    long now = System.currentTimeMillis();
                                    double elapsedSec = (now - startTime) / 1000.0;
                                    double recentElapsedSec = (now - lastLogTime[0]) / 1000.0;
                                    double overallRate = total / elapsedSec;
                                    double recentRate = recentElapsedSec > 0.1 ? 100000 / recentElapsedSec : overallRate;
                                    double progress = 100.0 * total / rowCount;
                                    double remainingSec = (rowCount - total) / overallRate;

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
                            Thread.sleep(100);
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
