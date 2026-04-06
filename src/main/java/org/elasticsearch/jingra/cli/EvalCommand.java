package org.elasticsearch.jingra.cli;

import org.elasticsearch.jingra.config.ConfigLoader;
import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.engine.BenchmarkEngine;
import org.elasticsearch.jingra.engine.EngineFactory;
import org.elasticsearch.jingra.evaluation.BenchmarkEvaluator;
import org.elasticsearch.jingra.output.ResultsSink;
import org.elasticsearch.jingra.output.ResultsSinkFactory;
import org.elasticsearch.jingra.utils.FileDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * Runs benchmark evaluation against the configured engine.
 */
public final class EvalCommand {
    private static final Logger logger = LoggerFactory.getLogger(EvalCommand.class);

    private EvalCommand() {}

    /** Defaults for {@link #run(JingraConfig)}; tests may replace temporarily. */
    static Function<JingraConfig, BenchmarkEngine> engineFactory = EngineFactory::create;
    static Function<JingraConfig, List<ResultsSink>> sinkFactory = ResultsSinkFactory::create;

    public static void run(JingraConfig config) throws Exception {
        run(config, engineFactory, sinkFactory);
    }

    /**
     * Package-private for tests that supply mock engine / sinks.
     */
    static void run(
            JingraConfig config,
            Function<JingraConfig, BenchmarkEngine> engineFactory,
            Function<JingraConfig, List<ResultsSink>> sinkFactory) throws Exception {

        ConfigLoader.validateForEvaluation(config);

        BenchmarkEngine engine = engineFactory.apply(config);
        List<ResultsSink> sinks = sinkFactory.apply(config);

        try {
            logger.info("Connecting to {}...", engine.getEngineName());
            if (!engine.connect()) {
                throw new RuntimeException("Failed to connect to engine");
            }

            String indexName = config.getActiveDataset().getIndexName();
            if (!engine.indexExists(indexName)) {
                throw new RuntimeException("Index '" + indexName + "' does not exist. Run 'load' command first.");
            }

            long docCount = engine.getDocumentCount(indexName);
            logger.info("Index '{}' contains {} documents", indexName, docCount);

            DatasetConfig dataset = config.getActiveDataset();
            String queriesPath = dataset.getPath().getQueriesPath();
            String queriesUrlEnv = dataset.getPath().getQueriesUrlEnv();
            FileDownloader.ensureFileExists(queriesPath, queriesUrlEnv);

            BenchmarkEvaluator evaluator = new BenchmarkEvaluator(config, engine, sinks);
            evaluator.runEvaluation();

            logger.info("=".repeat(80));
            logger.info("Benchmark evaluation complete!");
            logger.info("=".repeat(80));

        } finally {
            engine.close();
            for (ResultsSink sink : sinks) {
                sink.close();
            }
        }
    }
}
