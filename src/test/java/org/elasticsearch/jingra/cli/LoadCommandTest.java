package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.engine.EngineFactory;
import org.elasticsearch.jingra.testing.MockBenchmarkEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadCommandTest {

    @AfterEach
    void restoreFactory() {
        LoadCommand.engineFactory = EngineFactory::create;
    }

    @Test
    void runLoadsParquetWithMockEngine() throws Exception {
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void publicRunUsesInjectedFactory() throws Exception {
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        TrackingMock engine = new TrackingMock();
        LoadCommand.engineFactory = c -> engine;
        LoadCommand.run(config);
        assertTrue(engine.ingestCalls >= 1);
    }

    @Test
    void runDeletesExistingIndexWhenPresent() throws Exception {
        JingraConfig config = buildLoadConfig("src/test/resources/test_data.parquet");
        IndexThenClearMock engine = new IndexThenClearMock();
        LoadCommand.run(config, c -> engine);
        assertTrue(engine.deleteCalled);
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
    void privateCtor() throws Exception {
        var cl = Class.forName("org.elasticsearch.jingra.cli.LoadCommand");
        var ctor = cl.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }
}
