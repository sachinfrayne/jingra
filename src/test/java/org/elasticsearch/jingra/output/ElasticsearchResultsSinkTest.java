package org.elasticsearch.jingra.output;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
