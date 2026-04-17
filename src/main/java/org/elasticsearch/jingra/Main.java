package org.elasticsearch.jingra;

import org.elasticsearch.jingra.bootstrap.JvmShutdown;
import org.elasticsearch.jingra.cli.AnalyzeCommand;
import org.elasticsearch.jingra.cli.EvalCommand;
import org.elasticsearch.jingra.cli.LoadCommand;
import org.elasticsearch.jingra.config.ConfigLoader;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.utils.LoggingConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntConsumer;

/**
 * Entry point: parses CLI args, loads config, dispatches to commands.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Logs a banner line with {@code JINGRA_VERSION} (or {@code unknown}) and the
     * command title.
     */
    private static void logHeader() {
        String line = "=".repeat(80);
        logger.info(line);
        logger.info("JINGRA v{}", jingraVersionFromEnv());
        logger.info(line);
    }

    private static String jingraVersionFromEnv() {
        String v = System.getenv("JINGRA_VERSION");
        if (v == null) {
            return "unknown";
        }
        String t = v.trim();
        return t.isEmpty() ? "unknown" : t;
    }

    @FunctionalInterface
    public interface CommandHandler {
        void run(JingraConfig config) throws Exception;
    }

    /**
     * Invoked when {@link #runMain(String[])} returns non-zero. Default delegates
     * to {@link JvmShutdown#exit(int)};
     * same-package tests replace this to record the code without terminating the
     * JVM.
     */
    static IntConsumer nonZeroExitAction = JvmShutdown::exit;

    public static void main(String[] args) {
        int code = runMain(args);
        if (code != 0) {
            nonZeroExitAction.accept(code);
        }
    }

    /**
     * Default command handlers for {@link #runMain(String[])}; tests may replace temporarily.
     */
    static CommandHandler defaultLoadHandler = LoadCommand::run;
    static CommandHandler defaultEvalHandler = EvalCommand::run;
    static CommandHandler defaultAnalyzeHandler = AnalyzeCommand::run;

    /**
     * @return 0 on success, 1 on usage or runtime error
     */
    static int runMain(String[] args) {
        return runMain(args, defaultLoadHandler, defaultEvalHandler, defaultAnalyzeHandler);
    }

    /**
     * Same as {@link #runMain(String[])} but allows tests to inject no-op handlers.
     */
    static int runMain(String[] args, CommandHandler loadHandler, CommandHandler evalHandler, CommandHandler analyzeHandler) {
        logHeader();

        if (args.length < 2) {
            printUsage();
            return 1;
        }

        String command = args[0];
        String configPath = args[1];

        try {
            JingraConfig config = ConfigLoader.loadFromFile(configPath, command);

            // Apply logging configuration if specified
            LoggingConfigurator.configure(config);

            switch (command) {
                case "load" -> loadHandler.run(config);
                case "eval" -> evalHandler.run(config);
                case "analyze" -> analyzeHandler.run(config);
                default -> {
                    logger.error("Unknown command: {}", command);
                    printUsage();
                    return 1;
                }
            }
            return 0;
        } catch (Exception e) {
            logger.error("Command failed", e);
            return 1;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar jingra.jar <command> <config-file>");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  load <config>      Load data into the search engine");
        System.out.println("  eval <config>      Run benchmark evaluation");
        System.out.println("  analyze <config>   Generate analysis reports and comparison CSVs from benchmark results");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar jingra.jar load config.yaml");
        System.out.println("  java -jar jingra.jar eval config.yaml");
        System.out.println("  java -jar jingra.jar analyze config.yaml");
    }
}
