package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.config.JingraConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineFactoryTest {

    @Test
    void createsElasticsearch() {
        JingraConfig c = new JingraConfig();
        c.setEngine("elasticsearch");
        c.setElasticsearch(Map.of("url", "http://localhost:9200"));
        assertInstanceOf(ElasticsearchEngine.class, EngineFactory.create(c));
    }

    @Test
    void createsOpenSearch() {
        JingraConfig c = new JingraConfig();
        c.setEngine("OpenSearch");
        c.setOpensearch(Map.of("url", "http://localhost:9200"));
        assertInstanceOf(OpenSearchEngine.class, EngineFactory.create(c));
    }

    @Test
    void createsQdrant() {
        JingraConfig c = new JingraConfig();
        c.setEngine("qdrant");
        c.setQdrant(Map.of("url", "http://localhost:6333"));
        assertInstanceOf(QdrantEngine.class, EngineFactory.create(c));
    }

    @Test
    void unknownEngineThrows() {
        JingraConfig c = new JingraConfig();
        c.setEngine("unknown-engine");
        assertThrows(IllegalArgumentException.class, () -> EngineFactory.create(c));
    }

    @Test
    void privateCtor() throws Exception {
        var c = Class.forName("org.elasticsearch.jingra.engine.EngineFactory");
        var ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }
}
