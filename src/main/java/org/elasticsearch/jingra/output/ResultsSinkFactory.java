package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.OutputConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link ResultsSink} instances from configuration.
 */
public final class ResultsSinkFactory {
    private static final Logger logger = LoggerFactory.getLogger(ResultsSinkFactory.class);

    private ResultsSinkFactory() {}

    public static List<ResultsSink> create(JingraConfig config) {
        List<ResultsSink> sinks = new ArrayList<>();
        sinks.add(new ConsoleResultsSink());

        OutputConfig output = config.getOutput();
        if (output != null && output.getSinks() != null) {
            for (OutputConfig.ResultsSinkConfig sinkConfig : output.getSinks()) {
                String type = sinkConfig.getType();
                if (type == null) {
                    logger.warn("Sink configuration missing 'type' field, skipping");
                    continue;
                }

                switch (type.toLowerCase()) {
                    case "elasticsearch" -> {
                        logger.info("Configuring Elasticsearch results sink");
                        Map<String, Object> sinkConfigMap = new HashMap<>();
                        if (sinkConfig.getConfig() != null) {
                            sinkConfigMap.putAll(sinkConfig.getConfig());
                        }
                        sinkConfigMap.put("write_query_metrics", sinkConfig.getWriteQueryMetrics());
                        sinks.add(new ElasticsearchResultsSink(sinkConfigMap));
                    }
                    default -> logger.warn("Unknown sink type: {}", type);
                }
            }
        }

        return sinks;
    }
}
