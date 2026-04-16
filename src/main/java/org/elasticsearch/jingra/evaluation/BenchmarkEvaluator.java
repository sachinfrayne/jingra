package org.elasticsearch.jingra.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.EvaluationConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.data.ParquetReader;
import org.elasticsearch.jingra.engine.BenchmarkEngine;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.elasticsearch.jingra.output.ResultsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Evaluates benchmark performance by executing queries and calculating metrics.
 */
public class BenchmarkEvaluator {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkEvaluator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final JingraConfig config;
    private final BenchmarkEngine engine;
    private final List<ResultsSink> sinks;
    private final String runId;

    public BenchmarkEvaluator(JingraConfig config, BenchmarkEngine engine, List<ResultsSink> sinks) {
        this.config = config;
        this.engine = engine;
        this.sinks = sinks;
        // Use configured run_id if provided, otherwise generate timestamp-based one
        EvaluationConfig evalConfig = config.getEvaluation();
        this.runId = (evalConfig != null && evalConfig.getRunId() != null)
                ? evalConfig.getRunId()
                : generateRunId();
    }

    /**
     * Run the full benchmark evaluation.
     */
    public void runEvaluation() throws IOException {
        DatasetConfig dataset = config.getActiveDataset();
        EvaluationConfig evalConfig = config.getEvaluation();

        logger.info("Starting benchmark evaluation");
        logger.info("  Run ID: {}", runId);
        logger.info("  Engine: {}", engine.getEngineName());
        logger.info("  Dataset: {}", config.getDataset());

        // Load queries from Parquet
        String queriesPath = dataset.getPath().getQueriesPath();
        logger.info("Loading queries from: {}", queriesPath);

        List<QueryDocument> queries = loadQueries(queriesPath, dataset);
        logger.info("Loaded {} queries", queries.size());

        // Get parameter groups to evaluate
        Map<String, List<Map<String, Object>>> paramGroups = dataset.getParamGroups();
        if (paramGroups == null || paramGroups.isEmpty()) {
            logger.error("No parameter groups configured");
            return;
        }

        // Evaluate each parameter group
        for (Map.Entry<String, List<Map<String, Object>>> entry : paramGroups.entrySet()) {
            String recallLabel = entry.getKey();
            List<Map<String, Object>> paramsList = entry.getValue();

            logger.info("Evaluating parameter group: {}", recallLabel);

            for (Map<String, Object> params : paramsList) {
                evaluateParameterSet(queries, dataset, evalConfig, recallLabel, params);
            }
        }

        logger.info("Benchmark evaluation complete");
    }

    /**
     * Evaluate a single parameter set.
     */
    private void evaluateParameterSet(
            List<QueryDocument> queries,
            DatasetConfig dataset,
            EvaluationConfig evalConfig,
            String recallLabel,
            Map<String, Object> params
    ) {
        String paramKey = buildParamKey(params);
        logger.info("Evaluating parameter set: {}", paramKey);

        // Run warmup rounds
        logger.info("Running {} warmup rounds with {} workers...",
                evalConfig.getWarmupRounds(), evalConfig.getWarmupWorkers());

        for (int round = 1; round <= evalConfig.getWarmupRounds(); round++) {
            logger.info("Warmup round {}/{}", round, evalConfig.getWarmupRounds());
            ExecuteQueriesOutcome warm = executeQueries(queries, dataset, params, evalConfig.getWarmupWorkers(), false);
            if (warm.failures > 0) {
                throw new IllegalStateException("Warmup had " + warm.failures + " failed query(ies)");
            }
        }

        // Run measurement rounds and collect results
        logger.info("Running {} measurement rounds with {} workers...",
                evalConfig.getMeasurementRounds(), evalConfig.getMeasurementWorkers());

        List<MetricsCalculator.QueryResult> allResults = new ArrayList<>();
        long totalWallMs = 0;
        for (int round = 1; round <= evalConfig.getMeasurementRounds(); round++) {
            logger.info("Measurement round {}/{}", round, evalConfig.getMeasurementRounds());
            ExecuteQueriesOutcome out = executeQueries(queries, dataset, params,
                    evalConfig.getMeasurementWorkers(), true);
            if (out.failures > 0) {
                throw new IllegalStateException("Measurement round " + round + " had " + out.failures + " failed query(ies)");
            }
            allResults.addAll(out.results);
            totalWallMs += out.wallTimeMs;
        }

        // Calculate metrics
        logger.info("Calculating metrics from {} query results...", allResults.size());
        BenchmarkResult result = calculateMetrics(allResults, totalWallMs, dataset, recallLabel, paramKey, params);

        // Output results to all sinks
        for (ResultsSink sink : sinks) {
            sink.writeResult(result);
        }
    }

    /**
     * Creates the executor used for parallel query execution.
     * Same-package tests may override via subclass to inject a controlled pool.
     */
    ExecutorService createQueryExecutor(int numWorkers) {
        return Executors.newFixedThreadPool(numWorkers);
    }

    /**
     * Executes queries in bounded chunks to avoid retaining one {@link Future} per query for huge suites.
     */
    private ExecuteQueriesOutcome executeQueries(
            List<QueryDocument> queries,
            DatasetConfig dataset,
            Map<String, Object> params,
            int numWorkers,
            boolean collectResults
    ) {
        ExecutorService executor = createQueryExecutor(numWorkers);
        long startTime = System.currentTimeMillis();

        List<MetricsCalculator.QueryResult> results = new ArrayList<>();
        List<Map<String, Object>> queryMetrics = new ArrayList<>();
        int completed = 0;
        int failures = 0;

        int chunkSize = Math.max(numWorkers * 32, 256);
        try {
            for (int start = 0; start < queries.size(); start += chunkSize) {
                int end = Math.min(start + chunkSize, queries.size());
                List<Future<MetricsCalculator.QueryResult>> chunkFutures = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    final int idx = i;
                    chunkFutures.add(executor.submit(() -> executeQuery(queries.get(idx), dataset, params)));
                }
                for (int k = 0; k < chunkFutures.size(); k++) {
                    int queryIndex = start + k;
                    try {
                        MetricsCalculator.QueryResult result = chunkFutures.get(k).get();
                        if (collectResults) {
                            results.add(result);
                            QueryDocument q = queries.get(queryIndex);
                            queryMetrics.add(buildQueryMetric(result, q, params));
                        }
                        completed++;
                        if (completed % 1000 == 0) {
                            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                            double qps = completed / elapsed;
                            logger.info("Progress: {}/{} queries ({} qps)", completed, queries.size(), String.format("%.1f", qps));
                        }
                    } catch (Exception e) {
                        failures++;
                        logger.error("Query execution failed for query index {}", queryIndex, e);
                    }
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Query executor did not finish within 1 hour");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for query executor", e);
            }
        }

        long wallTimeMs = System.currentTimeMillis() - startTime;
        double totalTimeSec = wallTimeMs / 1000.0;
        logger.info("Completed {} queries in {}s ({} qps)",
                completed, String.format("%.2f", totalTimeSec),
                String.format("%.1f", completed / Math.max(totalTimeSec, 1e-6)));

        if (!queryMetrics.isEmpty()) {
            logger.info("Writing {} query metrics to sinks...", queryMetrics.size());
            for (ResultsSink sink : sinks) {
                sink.writeQueryMetricsBatch(queryMetrics);
            }
        }

        return new ExecuteQueriesOutcome(results, wallTimeMs, failures);
    }

    private static final class ExecuteQueriesOutcome {
        final List<MetricsCalculator.QueryResult> results;
        final long wallTimeMs;
        final int failures;

        ExecuteQueriesOutcome(List<MetricsCalculator.QueryResult> results, long wallTimeMs, int failures) {
            this.results = results;
            this.wallTimeMs = wallTimeMs;
            this.failures = failures;
        }
    }

    /**
     * Execute a single query.
     */
    private MetricsCalculator.QueryResult executeQuery(
            QueryDocument query,
            DatasetConfig dataset,
            Map<String, Object> benchmarkParams
    ) {
        // Build query parameters
        QueryParams queryParams = new QueryParams(benchmarkParams);
        queryParams.put("query_vector", query.queryVector);

        // Add metadata conditions if present
        if (query.metaConditions != null) {
            queryParams.put("meta_conditions", query.metaConditions);
        }

        // Execute query
        QueryResponse response = engine.query(
                dataset.getIndexName(),
                dataset.getQueryName(),
                queryParams
        );

        return new MetricsCalculator.QueryResult(
                query.groundTruth,
                response.getDocumentIds(),
                response.getClientLatencyMs(),
                response.getServerLatencyMs()
        );
    }

    /**
     * Load queries from Parquet file.
     */
    private List<QueryDocument> loadQueries(String path, DatasetConfig dataset) throws IOException {
        ParquetReader reader = new ParquetReader(path);
        List<Document> documents = reader.readAll();
        return parseQueryDocumentsFromDocuments(documents, dataset);
    }

    /**
     * Parses in-memory query rows using the same rules as {@link #loadQueries(String, DatasetConfig)}.
     * Same-package tests may invoke via reflection (return type uses private {@link QueryDocument}).
     */
    private List<QueryDocument> parseQueryDocumentsFromDocuments(List<Document> documents, DatasetConfig dataset) {
        String vectorField = dataset.getQueriesMapping().getQueryVectorField();
        String groundTruthField = dataset.getQueriesMapping().getGroundTruthField();
        String conditionsField = dataset.getQueriesMapping().getConditionsField();

        List<QueryDocument> queries = new ArrayList<>();
        int skippedQueries = 0;

        for (Document doc : documents) {
            List<Float> vector = doc.getFloatList(vectorField);
            if (vector == null) {
                List<Double> doubleVec = doc.getDoubleList(vectorField);
                if (doubleVec != null) {
                    vector = doubleVec.stream().map(Double::floatValue).collect(Collectors.toList());
                } else {
                    // Log the actual type to help debug
                    Object rawVector = doc.get(vectorField);
                    if (rawVector != null) {
                        logger.error("Vector field '{}' has unexpected type: {}. First element type: {}. Skipping query.",
                                vectorField,
                                rawVector.getClass().getName(),
                                rawVector instanceof List && !((List<?>)rawVector).isEmpty() ?
                                        ((List<?>)rawVector).get(0).getClass().getName() : "N/A");

                        // Print first element details for debugging
                        if (rawVector instanceof List && !((List<?>)rawVector).isEmpty()) {
                            Object firstElement = ((List<?>)rawVector).get(0);
                            logger.error("First element details: {}", firstElement);
                        }
                    } else {
                        logger.error("Vector field '{}' is null. Skipping query.", vectorField);
                    }
                    skippedQueries++;
                    continue; // Skip this query
                }
            }

            @SuppressWarnings("unchecked")
            List<String> groundTruth = (List<String>) doc.get(groundTruthField);

            // Handle conditions field - can be either Map or JSON string
            Map<String, Object> metaConditions = null;
            Object conditionsValue = doc.get(conditionsField);
            if (conditionsValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> conditionsMap = (Map<String, Object>) conditionsValue;
                metaConditions = conditionsMap;
            } else if (conditionsValue instanceof String) {
                // Parse JSON string to Map
                try {
                    String conditionsJson = (String) conditionsValue;
                    metaConditions = objectMapper.readValue(conditionsJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    logger.warn("Failed to parse conditions JSON: {}", e.getMessage());
                    metaConditions = new HashMap<>();
                }
            }

            queries.add(new QueryDocument(vector, groundTruth, metaConditions));
        }

        if (skippedQueries > 0) {
            logger.warn("Skipped {} queries due to invalid vectors", skippedQueries);
        }

        return queries;
    }

    /**
     * Calculate benchmark metrics from query results.
     */
    private BenchmarkResult calculateMetrics(
            List<MetricsCalculator.QueryResult> results,
            long totalWallMs,
            DatasetConfig dataset,
            String recallLabel,
            String paramKey,
            Map<String, Object> params
    ) {
        MetricsCalculator calculator = new MetricsCalculator(results, totalWallMs > 0 ? totalWallMs : null);

        BenchmarkResult result = new BenchmarkResult(
                runId,
                engine.getEngineName(),
                engine.getVersion(),
                "vector_search",
                config.getDataset(),
                paramKey,
                params
        );

        // Add quality metrics
        result.addMetric("precision", calculator.calculatePrecision());
        result.addMetric("recall", calculator.calculateRecall());
        result.addMetric("f1", calculator.calculateF1());
        result.addMetric("mrr", calculator.calculateMRR());

        // Add latency metrics (client-side)
        result.addMetric("latency_avg", calculator.calculateLatencyAvg());
        result.addMetric("latency_median", calculator.calculateLatencyMedian());
        result.addMetric("latency_p90", calculator.calculateLatencyPercentile(90));
        result.addMetric("latency_p95", calculator.calculateLatencyPercentile(95));
        result.addMetric("latency_p99", calculator.calculateLatencyPercentile(99));

        // Add server latency metrics if available
        if (calculator.hasServerLatencies()) {
            result.addMetric("server_latency_avg", calculator.calculateServerLatencyAvg());
            result.addMetric("server_latency_median", calculator.calculateServerLatencyMedian());
            result.addMetric("server_latency_p90", calculator.calculateServerLatencyPercentile(90));
            result.addMetric("server_latency_p95", calculator.calculateServerLatencyPercentile(95));
            result.addMetric("server_latency_p99", calculator.calculateServerLatencyPercentile(99));
        }

        result.addMetric("throughput", calculator.calculateThroughput());
        if (totalWallMs > 0) {
            result.addMetric("throughput_aggregate_latency_model", calculator.calculateThroughputAggregateLatencyModel());
        }
        result.addMetric("num_samples", results.size());

        // Add metadata
        result.addMetadata("recall_label", recallLabel);
        Map<String, String> metadata = engine.getIndexMetadata(dataset.getIndexName());
        String vt = metadata.get("vector_type");
        if (vt != null) {
            result.addMetadata("vector_type", vt);
        }

        // Add schema information
        String schemaName = dataset.getSchemaName();
        if (schemaName != null) {
            Map<String, Object> schema = engine.getSchemaTemplate(schemaName);
            if (schema != null) {
                result.setSchema(schema);
            }
        }

        return result;
    }

    private String buildParamKey(Map<String, Object> params) {
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("_"));
    }

    /**
     * Build a query metric map from query result.
     */
    private Map<String, Object> buildQueryMetric(
            MetricsCalculator.QueryResult result,
            QueryDocument query,
            Map<String, Object> params
    ) {
        Map<String, Object> metric = new HashMap<>();

        // Context
        metric.put("run_id", runId);
        metric.put("engine", engine.getEngineName());
        metric.put("engine_version", engine.getVersion());
        metric.put("dataset", config.getDataset());
        metric.put("params", params);

        // Query metadata
        if (query.metaConditions != null) {
            metric.put("meta_conditions", query.metaConditions);
        }

        // Results
        metric.put("ground_truth", result.groundTruth);
        metric.put("retrieved", result.retrieved);
        metric.put("num_ground_truth", result.groundTruth.size());
        metric.put("num_retrieved", result.retrieved.size());

        // Latencies
        metric.put("client_latency_ms", result.clientLatencyMs);
        if (result.serverLatencyMs != null) {
            metric.put("server_latency_ms", result.serverLatencyMs);
        }

        // Calculate per-query metrics
        int relevantRetrieved = (int) result.retrieved.stream()
                .filter(result.groundTruth::contains)
                .count();

        double precision = result.retrieved.isEmpty() ? 0.0 :
                (double) relevantRetrieved / result.retrieved.size();
        double recall = result.groundTruth.isEmpty() ? 0.0 :
                (double) relevantRetrieved / result.groundTruth.size();

        metric.put("precision", precision);
        metric.put("recall", recall);
        metric.put("relevant_retrieved", relevantRetrieved);

        return metric;
    }

    private String generateRunId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /**
     * Query document with vector and ground truth.
     */
    private static class QueryDocument {
        final List<Float> queryVector;
        final List<String> groundTruth;
        final Map<String, Object> metaConditions;

        QueryDocument(List<Float> queryVector, List<String> groundTruth, Map<String, Object> metaConditions) {
            this.queryVector = queryVector;
            this.groundTruth = groundTruth != null ? groundTruth : List.of();
            this.metaConditions = metaConditions;
        }
    }
}
