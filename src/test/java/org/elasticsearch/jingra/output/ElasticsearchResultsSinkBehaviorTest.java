package org.elasticsearch.jingra.output;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElasticsearchResultsSinkBehaviorTest {

    @Mock
    ElasticsearchClient client;

    @Test
    void writeResult_usesInjectedClient() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        config.put("index", "idx");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectClient(sink, client);
        when(client.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        BenchmarkResult br = new BenchmarkResult("r", "e", "1", "knn", "d", "p", Map.of());
        sink.writeResult(br);

        verify(client, times(1)).index(any(IndexRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_skipsWhenDisabled() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        config.put("index", "idx");
        config.put("write_query_metrics", false);
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectClient(sink, client);
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
        injectClient(sink, client);
        sink.writeQueryMetricsBatch(List.of(Map.of("k", "v")));
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_emptyNoop() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectClient(sink, client);
        sink.writeQueryMetricsBatch(List.of());
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_nullNoop() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectClient(sink, client);
        sink.writeQueryMetricsBatch(null);
        verify(client, never()).bulk(any(BulkRequest.class));
        sink.close();
    }

    @Test
    void writeQueryMetricsBatch_bulkSuccess() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://127.0.0.1:9200");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        injectClient(sink, client);
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
        assertFalse((Boolean) isRetryableStatus.invoke(sink, 400));

        Method isRetryableType = ElasticsearchResultsSink.class.getDeclaredMethod("isRetryableBulkErrorType", String.class);
        isRetryableType.setAccessible(true);
        assertTrue((Boolean) isRetryableType.invoke(sink, "es_rejected_execution_exception"));
        assertFalse((Boolean) isRetryableType.invoke(sink, (String) null));
        assertFalse((Boolean) isRetryableType.invoke(sink, "other"));
    }

    private static void injectClient(ElasticsearchResultsSink sink, ElasticsearchClient client) throws Exception {
        Field f = ElasticsearchResultsSink.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(sink, client);
        Field f2 = ElasticsearchResultsSink.class.getDeclaredField("restClient");
        f2.setAccessible(true);
        f2.set(sink, null);
    }
}
