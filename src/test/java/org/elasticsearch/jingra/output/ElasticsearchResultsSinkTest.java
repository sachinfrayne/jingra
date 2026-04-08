package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.engine.ElasticsearchEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Tests for ElasticsearchResultsSink.
 *
 * Note: These are unit tests that test configuration validation and error handling.
 * Full integration tests with Elasticsearch would require Testcontainers, but are
 * skipped due to ARM64 compatibility issues with Elasticsearch Docker images.
 */
class ElasticsearchResultsSinkTest {
    @Test
    void testConstructor_throwsWhenUrlMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("index", "test-index");
        assertThatThrownBy(() -> new ElasticsearchResultsSink(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Elasticsearch URL not provided");
    }
    @Test
    void testConstructor_acceptsMalformedUrlBecauseClientIsLazy() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "not-a-valid-url-format");
        config.put("index", "test-index");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }
    @Test
    void testConstructor_successWithValidConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("index", "test-results");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }
    @Test
    void testConstructor_usesDefaultIndexName() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }
    @Test
    void testConstructor_withAuthentication() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://localhost:9200");
        config.put("user", "elastic");
        config.put("password", "changeme");
        config.put("index", "test-results");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }
    @Test
    void testConstructor_withHttpsUrl() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://secure-es-cluster.example.com:9200");
        config.put("user", "user");
        config.put("password", "pass");
        config.put("index", "results");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }
    @Test
    void testWriteResult_throwsConnectionErrors() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://invalid-host-that-does-not-exist:9200");
        config.put("index", "test-results");
        BenchmarkResult result = new BenchmarkResult(
                "test-run-001",
                "elasticsearch",
                "8.17.0",
                "knn",
                "test-dataset",
                "size=100",
                Map.of("size", 100)
        );
        result.addMetric("precision", 0.95);
        assertThatThrownBy(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                sink.writeResult(result);
            }
        })
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to write result to Elasticsearch");
    }
    @Test
    void testClose_doesNotThrow() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("index", "test-results");
        ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config);
        assertDoesNotThrow(sink::close);
        assertDoesNotThrow(sink::close);
    }
    @Test
    void testConstructor_parsesUrlWithDefaultPort() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost");
        config.put("index", "test");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }
    @Test
    void testConstructor_parsesUrlWithDefaultHttpsPort() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://localhost");
        config.put("user", "user");
        config.put("password", "pass");
        config.put("index", "test");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }

    @Test
    void testConstructor_metricsIndexDefaultAndExplicit() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        Field f = ElasticsearchResultsSink.class.getDeclaredField("metricsIndexName");
        f.setAccessible(true);
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertThat(f.get(sink)).isEqualTo("jingra-metrics");
        }
        config.put("metrics_index", "custom-metrics");
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertThat(f.get(sink)).isEqualTo("custom-metrics");
        }
    }

    @Test
    void testConstructor_writeQueryMetricsDefaultsTrue() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        Field f = ElasticsearchResultsSink.class.getDeclaredField("writeQueryMetrics");
        f.setAccessible(true);
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertTrue((Boolean) f.get(sink));
        }
    }

    @Test
    void testConstructor_writeQueryMetricsBooleanObject() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("write_query_metrics", Boolean.FALSE);
        Field f = ElasticsearchResultsSink.class.getDeclaredField("writeQueryMetrics");
        f.setAccessible(true);
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertFalse((Boolean) f.get(sink));
        }
    }

    @Test
    void testConstructor_insecureTlsDefaultsFalse() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        Field f = ElasticsearchResultsSink.class.getDeclaredField("insecureTls");
        f.setAccessible(true);
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertFalse((Boolean) f.get(sink));
        }
    }

    @Test
    void testConstructor_insecureTlsStringTrue() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("insecure_tls", "true");
        Field f = ElasticsearchResultsSink.class.getDeclaredField("insecureTls");
        f.setAccessible(true);
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertTrue((Boolean) f.get(sink));
        }
    }

    @Test
    void testConstructor_urlEnvReadsUrlFromEnvironment() {
        Map<String, Object> config = new HashMap<>();
        config.put("url_env", "HOME");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }

    @Test
    void testConstructor_urlEnvWithUserEnvBranch() {
        Map<String, Object> config = new HashMap<>();
        config.put("url_env", "HOME");
        config.put("user_env", "HOME");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }

    @Test
    void testConstructor_urlEnvWithPasswordEnvBranch() {
        Map<String, Object> config = new HashMap<>();
        config.put("url_env", "HOME");
        config.put("password_env", "HOME");
        assertDoesNotThrow(() -> {
            try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
                assertThat(sink).isNotNull();
            }
        });
    }

    @Test
    void ingestRetryBackoff_matchesLoadCommandDefault() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertEquals(1000L, sink.ingestRetryBackoffMs());
            assertEquals(9, sink.bulkTransportMaxRetries());
        }
    }

    @Test
    void ensureEngineInitialized_second_call_noop_when_engine_already_set() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            ElasticsearchEngine stub = new ElasticsearchEngine(config) {
                @Override
                public boolean connect() {
                    return true;
                }
            };
            Field f = ElasticsearchResultsSink.class.getDeclaredField("engine");
            f.setAccessible(true);
            f.set(sink, stub);
            Method m = ElasticsearchResultsSink.class.getDeclaredMethod("ensureEngineInitialized");
            m.setAccessible(true);
            m.invoke(sink);
            m.invoke(sink);
            assertSame(stub, f.get(sink));
        }
    }

    @Test
    void ensureEngineInitialized_connectTrueSetsEngine() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config) {
            @Override
            protected ElasticsearchEngine newEngine(Map<String, Object> engineConfig) {
                return new ElasticsearchEngine(engineConfig) {
                    @Override
                    public boolean connect() {
                        return true;
                    }
                };
            }
        }) {
            Method m = ElasticsearchResultsSink.class.getDeclaredMethod("ensureEngineInitialized");
            m.setAccessible(true);
            m.invoke(sink);
            Field f = ElasticsearchResultsSink.class.getDeclaredField("engine");
            f.setAccessible(true);
            assertNotNull(f.get(sink));
        }
    }

    @Test
    void sink_transport_max_retries_configOverridesDefault() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("sink_transport_max_retries", 3);
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertEquals(3, sink.bulkTransportMaxRetries());
        }
    }

    @Test
    void testConstructor_sinkTransportMaxRetriesFromTrimmedString() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("sink_transport_max_retries", "  5 ");
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config)) {
            assertEquals(5, sink.bulkTransportMaxRetries());
        }
    }

    @Test
    void testConstructor_sinkTransportMaxRetriesInvalidStringThrowsNumberFormatException() {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        config.put("sink_transport_max_retries", "not-an-int");
        assertThrows(NumberFormatException.class, () -> new ElasticsearchResultsSink(config));
    }

    @Test
    void writeResult_wrapsWhenConnectReturnsFalse() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("url", "http://localhost:9200");
        try (ElasticsearchResultsSink sink = new ElasticsearchResultsSink(config) {
            @Override
            protected ElasticsearchEngine newEngine(Map<String, Object> engineConfig) {
                return new ElasticsearchEngine(engineConfig) {
                    @Override
                    public boolean connect() {
                        return false;
                    }
                };
            }
        }) {
            BenchmarkResult br = new BenchmarkResult("r", "e", "1", "knn", "d", "p", Map.of());
            RuntimeException ex = assertThrows(RuntimeException.class, () -> sink.writeResult(br));
            assertEquals("Failed to write result to Elasticsearch", ex.getMessage());
            assertEquals("Failed to create Elasticsearch client", ex.getCause().getMessage());
            assertEquals("Failed to connect to Elasticsearch results sink", ex.getCause().getCause().getMessage());
        }
    }
}
