package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.config.JingraConfig;

/**
 * Builds {@link BenchmarkEngine} instances from configuration.
 */
public final class EngineFactory {

    private EngineFactory() {}

    public static BenchmarkEngine create(JingraConfig config) {
        String engineName = config.getEngine().toLowerCase();
        return switch (engineName) {
            case "elasticsearch" -> new ElasticsearchEngine(config.getEngineConfig());
            case "opensearch" -> new OpenSearchEngine(config.getEngineConfig());
            case "qdrant" -> new QdrantEngine(config.getEngineConfig());
            default -> throw new IllegalArgumentException("Unknown engine: " + engineName);
        };
    }
}
