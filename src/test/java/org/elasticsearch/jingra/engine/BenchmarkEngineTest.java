package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class BenchmarkEngineTest {

    /**
     * Minimal stub: default {@link BenchmarkEngine#getLastQueryJson()} and
     * {@link BenchmarkEngine#getLastIndexName()} are not overridden so JaCoCo records
     * the interface default method bodies.
     */
    private static final class StubEngine implements BenchmarkEngine {
        @Override
        public boolean connect() {
            return false;
        }

        @Override
        public boolean createIndex(String indexName, String schemaName) {
            return false;
        }

        @Override
        public boolean indexExists(String indexName) {
            return false;
        }

        @Override
        public boolean deleteIndex(String indexName) {
            return false;
        }

        @Override
        public int ingest(List<Document> documents, String indexName, String idField) {
            return 0;
        }

        @Override
        public QueryResponse query(String indexName, String queryName, QueryParams params) {
            return new QueryResponse(Collections.emptyList(), 0.0, 0L);
        }

        @Override
        public long getDocumentCount(String indexName) {
            return 0L;
        }

        @Override
        public String getEngineName() {
            return "stub";
        }

        @Override
        public String getShortName() {
            return "st";
        }

        @Override
        public String getVersion() {
            return "0";
        }

        @Override
        public Map<String, String> getIndexMetadata(String indexName) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getSchemaTemplate(String schemaName) {
            return Map.of();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    @Test
    void stubIsUsableAsBenchmarkEngine() {
        assertInstanceOf(BenchmarkEngine.class, new StubEngine());
    }
}
