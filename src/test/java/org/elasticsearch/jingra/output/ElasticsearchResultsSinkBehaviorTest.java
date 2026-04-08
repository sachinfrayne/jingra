package org.elasticsearch.jingra.output;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElasticsearchResultsSinkBehaviorTest {

    @Mock
    ElasticsearchClient client;

    @Test
    void writeResult_usesEngineIngestBulk() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        config.put("index", "idx");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        BulkResponseItem item = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("idx").status(201));
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of(item)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(ok);

        BenchmarkResult br = new BenchmarkResult("r", "e", "1", "knn", "d", "p", Map.of());
        sink.writeResult(br);

        verify(client, times(1)).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_skipsWhenDisabled() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        config.put("index", "idx");
        config.put("write_query_metrics", false);
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        sink.writeQueryMetricsBatch(List.of(Map.of("k", "v")));
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_stringFalse() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        config.put("write_query_metrics", "false");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        sink.writeQueryMetricsBatch(List.of(Map.of("k", "v")));
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_emptyNoop() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        sink.writeQueryMetricsBatch(List.of());
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_nullNoop() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        sink.writeQueryMetricsBatch(null);
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_bulkSuccess() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        BulkResponse br = mock(BulkResponse.class);
        when(br.errors()).thenReturn(false);
        when(client.bulk(any(BulkRequest.class))).thenReturn(br);
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("x", i);
            metrics.add(row);
        }
        sink.writeQueryMetricsBatch(metrics);
        verify(client, atLeastOnce()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void urlEnvMissingThrows() {
        Map<String, Object> config = new HashMap<>();
        config.put("url_env", "ENV_VAR_THAT_DOES_NOT_EXIST_" + System.nanoTime());
        config.put("index", "i");
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchResultsSink(config));
    }

    @Test
    void reflectsRetryHelpers() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        Method isRetryableStatus = ElasticsearchResultsSink.class.getDeclaredMethod("isRetryableBulkStatus", int.class);
        isRetryableStatus.setAccessible(true);
        assertTrue((Boolean) isRetryableStatus.invoke(sink, 429));
        assertTrue((Boolean) isRetryableStatus.invoke(sink, 500));
        assertTrue((Boolean) isRetryableStatus.invoke(sink, 502));
        assertTrue((Boolean) isRetryableStatus.invoke(sink, 503));
        assertTrue((Boolean) isRetryableStatus.invoke(sink, 504));
        assertFalse((Boolean) isRetryableStatus.invoke(sink, 400));

        Method isRetryableType = ElasticsearchResultsSink.class.getDeclaredMethod("isRetryableBulkErrorType", String.class);
        isRetryableType.setAccessible(true);
        assertTrue((Boolean) isRetryableType.invoke(sink, "es_rejected_execution_exception"));
        assertFalse((Boolean) isRetryableType.invoke(sink, (String) null));
        assertFalse((Boolean) isRetryableType.invoke(sink, "other"));
    }

    @Test
    void initializeEngine_wrapsFactoryFailure() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config) {
            @Override
            protected ElasticsearchEngine newEngine(Map<String, Object> engineConfig) {
                throw new RuntimeException("no client");
            }
        };
        try {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> sink.writeResult(new BenchmarkResult("r", "e", "1", "knn", "d", "p", Map.of())));
            assertEquals("Failed to write result to Elasticsearch", ex.getMessage());
            assertEquals("Failed to create Elasticsearch client", ex.getCause().getMessage());
            assertEquals("no client", ex.getCause().getCause().getMessage());
        } finally {
            sink.close();
        }
    }

    @Test
    void writeBatchWithRetries_bulkThrowsThenSucceeds() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        when(client.bulk(any(BulkRequest.class))).thenThrow(new java.io.IOException("transient")).thenReturn(ok);
        List<Map<String, Object>> batch = List.of(Map.of("a", 1), Map.of("b", 2));
        assertEquals(2, invokeWriteBatchWithRetries(sink, "midx", batch, 2));
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_bulkThrowsOnFinalAttempt() throws Exception {
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(baseConfig()) {
            @Override
            long ingestRetryBackoffMs() {
                return 0L;
            }

            @Override
            int bulkTransportMaxRetries() {
                return 1;
            }
        };
        injectEngine(sink, client);
        when(client.bulk(any(BulkRequest.class))).thenThrow(new java.io.IOException("fail"));
        List<Map<String, Object>> batch = List.of(new HashMap<>(Map.of("a", 1)));
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> invokeWriteBatchWithRetries(sink, "midx", batch, 2));
        assertEquals("fail", ex.getCause().getCause().getMessage());
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_errorsFalseReturnsImmediately() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        when(client.bulk(any(BulkRequest.class))).thenReturn(ok);
        List<Map<String, Object>> batch = List.of(Map.of("x", 1));
        assertEquals(1, invokeWriteBatchWithRetries(sink, "midx", batch, 2));
        verify(client, times(1)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_permanentItemFailureNoRetry() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponseItem bad = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").error(
                ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad"))).status(400));
        BulkResponse err = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(bad)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(err);
        assertEquals(0, invokeWriteBatchWithRetries(sink, "midx", List.of(Map.of("k", "v")), 5));
        verify(client, times(1)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_retryableThenSuccess() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponseItem r429 = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").error(
                ErrorCause.of(e -> e.type("es_rejected_execution_exception").reason("rej"))).status(429));
        BulkResponse err = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(r429, r429)));
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        when(client.bulk(any(BulkRequest.class))).thenReturn(err).thenReturn(ok);
        List<Map<String, Object>> batch = List.of(Map.of("a", 1), Map.of("b", 2));
        assertEquals(2, invokeWriteBatchWithRetries(sink, "midx", batch, 5));
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_mixedSuccessAndRetryableThenSuccess() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponseItem okItem = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").status(201));
        BulkResponseItem r429 = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").error(
                ErrorCause.of(e -> e.type("es_rejected_execution_exception").reason("r"))).status(429));
        BulkResponse mixed = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(okItem, r429)));
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        when(client.bulk(any(BulkRequest.class))).thenReturn(mixed).thenReturn(ok);
        List<Map<String, Object>> batch = List.of(Map.of("a", 1), Map.of("b", 2));
        assertEquals(2, invokeWriteBatchWithRetries(sink, "midx", batch, 5));
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_exhaustsRetriesWithRetryableFailures() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponseItem r429 = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").error(
                ErrorCause.of(e -> e.type("es_rejected_execution_exception").reason("r"))).status(429));
        BulkResponse err = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(r429)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(err);
        assertEquals(0, invokeWriteBatchWithRetries(sink, "midx", List.of(Map.of("a", 1)), 2));
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeBatchWithRetries_retryableByErrorTypeNotStatus() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponseItem bad = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx")
                .error(ErrorCause.of(e -> e.type("unavailable_shards_exception").reason("shards")))
                .status(400));
        BulkResponse err = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(bad)));
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        when(client.bulk(any(BulkRequest.class))).thenReturn(err).thenReturn(ok);
        assertEquals(1, invokeWriteBatchWithRetries(sink, "midx", List.of(Map.of("a", 1)), 5));
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void writeQueryMetricsBatch_splitsBatchesOver100() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponse ok = BulkResponse.of(b -> b.errors(false).took(1).items(List.of()));
        when(client.bulk(any(BulkRequest.class))).thenReturn(ok);
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            metrics.add(new HashMap<>(Map.of("i", i)));
        }
        sink.writeQueryMetricsBatch(metrics);
        verify(client, times(2)).bulk(any(BulkRequest.class));
        String ts = (String) metrics.get(0).get("@timestamp");
        assertNotNull(ts);
        assertEquals(ts, metrics.get(100).get("@timestamp"));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_partialBatchFailureLogsErrorBranch() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        BulkResponseItem bad = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").error(
                ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad"))).status(400));
        BulkResponse err = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(bad, bad, bad)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(err);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new HashMap<>(Map.of("a", 1)));
        rows.add(new HashMap<>(Map.of("b", 2)));
        rows.add(new HashMap<>(Map.of("c", 3)));
        sink.writeQueryMetricsBatch(rows);
        verify(client, times(1)).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_batchThrowsAfterRetriesAddsAllToFailed() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        injectEngine(sink, client);
        when(client.bulk(any(BulkRequest.class))).thenThrow(new java.io.IOException("bulk down"));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new HashMap<>(Map.of("a", 1)));
        rows.add(new HashMap<>(Map.of("b", 2)));
        rows.add(new HashMap<>(Map.of("c", 3)));
        sink.writeQueryMetricsBatch(rows);
        verify(client, times(10)).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_whenDisabledAndNullMetrics_logsZeroSizeBranch() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("write_query_metrics", false);
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectEngine(sink, client);
        sink.writeQueryMetricsBatch(null);
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    private static Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        config.put("index", "idx");
        return config;
    }

    private static int invokeWriteBatchWithRetries(
            ElasticsearchResultsSink sink,
            String indexName,
            List<Map<String, Object>> batch,
            int maxAttempts
    ) throws Exception {
        Method m = ElasticsearchResultsSink.class.getDeclaredMethod(
                "writeBatchWithRetries", String.class, List.class, int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(sink, indexName, batch, maxAttempts);
    }

    @Test
    void writeBatchWithRetries_emptyBatchReturnsZero() throws Exception {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        assertEquals(0, invokeWriteBatchWithRetries(sink, "midx", List.of(), 5));
        verifyNoInteractions(client);
    }

    @Test
    void writeBatchWithRetries_maxRoundsInvalidThrows() {
        NoSleepSink sink = new NoSleepSink(baseConfig());
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> invokeWriteBatchWithRetries(sink, "midx", List.of(Map.of("a", 1)), 0));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertEquals("maxRounds must be >= 1", ex.getCause().getMessage());
    }

    @Test
    void writeBatchWithRetries_interruptDuringRoundBackoffThrows() throws Exception {
        Map<String, Object> cfg = baseConfig();
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(cfg) {
            @Override
            long ingestRetryBackoffMs() {
                return 500L;
            }

            @Override
            int bulkTransportMaxRetries() {
                return 9;
            }
        };
        injectEngine(sink, client);
        BulkResponseItem r429 = BulkResponseItem.of(b -> b.operationType(OperationType.Index).index("midx").error(
                ErrorCause.of(e -> e.type("es_rejected_execution_exception").reason("rej"))).status(429));
        BulkResponse err = BulkResponse.of(b -> b.errors(true).took(1).items(List.of(r429)));
        CountDownLatch firstBulkReturned = new CountDownLatch(1);
        when(client.bulk(any(BulkRequest.class))).thenAnswer(inv -> {
            firstBulkReturned.countDown();
            return err;
        });
        AtomicReference<Throwable> fromWorker = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                invokeWriteBatchWithRetries(sink, "midx", List.of(Map.of("a", 1)), 5);
            } catch (Throwable t) {
                fromWorker.set(t);
            }
        });
        worker.start();
        assertTrue(firstBulkReturned.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive());
        Throwable t = fromWorker.get();
        assertNotNull(t);
        Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
        assertInstanceOf(RuntimeException.class, cause);
        assertEquals("Interrupted during metrics bulk backoff", cause.getMessage());
        assertInstanceOf(InterruptedException.class, cause.getCause());
    }

    /** Zero {@link ElasticsearchResultsSink#ingestRetryBackoffMs()} → no real delay between item-retry rounds. */
    private static final class NoSleepSink extends ElasticsearchResultsSink {
        NoSleepSink(Map<String, Object> config) {
            super(config);
        }

        @Override
        long ingestRetryBackoffMs() {
            return 0L;
        }
    }

    private static void injectEngine(ElasticsearchResultsSink sink, ElasticsearchClient client) throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:9200");
        cfg.put("insecure_tls", false);
        ElasticsearchEngine engine = new TestEngine(cfg, client);
        Field f = ElasticsearchResultsSink.class.getDeclaredField("engine");
        f.setAccessible(true);
        f.set(sink, engine);
    }

    private static final class TestEngine extends ElasticsearchEngine {
        private final ElasticsearchClient client;

        TestEngine(Map<String, Object> config, ElasticsearchClient client) {
            super(config);
            this.client = client;
        }

        @Override
        protected boolean hasClient() {
            return true;
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        protected BulkResponse bulkOperation(BulkRequest request) throws Exception {
            return client.bulk(request);
        }
    }
}
