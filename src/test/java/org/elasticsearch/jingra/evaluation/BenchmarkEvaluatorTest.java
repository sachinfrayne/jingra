package org.elasticsearch.jingra.evaluation;

import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.EvaluationConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.model.BenchmarkResult;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.engine.BenchmarkEngine;
import org.elasticsearch.jingra.model.QueryResponse;
import org.elasticsearch.jingra.output.ResultsSink;
import org.elasticsearch.jingra.testing.MockBenchmarkEngine;
import org.elasticsearch.jingra.testing.MockResultsSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BenchmarkEvaluator.
 * Uses test queries parquet file created in src/test/resources/test_queries.parquet
 */
class BenchmarkEvaluatorTest {

    private BenchmarkEvaluator evaluator;
    private MockBenchmarkEngine mockEngine;
    private MockResultsSink mockSink;
    private JingraConfig jingraConfig;

    @BeforeEach
    void setUp() {
        // Create mock engine that returns predictable results
        mockEngine = new MockBenchmarkEngine();

        // Create mock sink to capture results
        mockSink = new MockResultsSink();

        // Create jingra config with dataset and evaluation config
        jingraConfig = new JingraConfig();
        jingraConfig.setEngine("mock");
        jingraConfig.setDataset("test-dataset");

        // Create dataset config
        org.elasticsearch.jingra.config.DatasetConfig datasetConfig = new org.elasticsearch.jingra.config.DatasetConfig();
        datasetConfig.setIndexName("test-index");
        datasetConfig.setQueryName("test-query");

        // Set path config with queries path
        org.elasticsearch.jingra.config.DatasetConfig.PathConfig pathConfig = new org.elasticsearch.jingra.config.DatasetConfig.PathConfig();
        pathConfig.setQueriesPath("src/test/resources/test_queries.parquet");
        datasetConfig.setPath(pathConfig);

        // Set queries mapping config
        org.elasticsearch.jingra.config.DatasetConfig.QueriesMappingConfig queriesMapping = new org.elasticsearch.jingra.config.DatasetConfig.QueriesMappingConfig();
        queriesMapping.setQueryVectorField("query_vector");
        queriesMapping.setGroundTruthField("ground_truth");
        queriesMapping.setConditionsField("meta_conditions");
        datasetConfig.setQueriesMapping(queriesMapping);

        // Create parameter groups
        Map<String, Object> params1 = new HashMap<>();
        params1.put("k", 10);
        params1.put("size", 10);
        Map<String, List<Map<String, Object>>> paramGroups = new HashMap<>();
        paramGroups.put("default", List.of(params1));
        datasetConfig.setParamGroups(paramGroups);

        // Add dataset to config
        Map<String, org.elasticsearch.jingra.config.DatasetConfig> datasets = new HashMap<>();
        datasets.put("test-dataset", datasetConfig);
        jingraConfig.setDatasets(datasets);

        // Create evaluation config
        EvaluationConfig evalConfig = new EvaluationConfig();
        evalConfig.setWarmupRounds(0);
        evalConfig.setMeasurementRounds(1);
        evalConfig.setWarmupWorkers(1);
        evalConfig.setMeasurementWorkers(1);
        jingraConfig.setEvaluation(evalConfig);

        evaluator = new BenchmarkEvaluator(
                jingraConfig,
                mockEngine,
                List.of(mockSink)
        );
    }

    @Test
    void testConstructor() {
        assertNotNull(evaluator);
    }

    @Test
    void testRunEvaluation_executesQueriesAndCalculatesMetrics() throws Exception {
        // Run evaluation
        evaluator.runEvaluation();

        // Verify queries were executed
        assertTrue(mockEngine.queryCount > 0, "Should execute queries");

        // Verify results were written
        assertTrue(mockSink.resultCount > 0, "Should write results");
    }

    @Test
    void testRunEvaluation_multipleParameterGroups() throws Exception {
        // Configure multiple parameter groups
        Map<String, Object> params1 = new HashMap<>();
        params1.put("k", 10);
        params1.put("size", 10);

        Map<String, Object> params2 = new HashMap<>();
        params2.put("k", 20);
        params2.put("size", 20);

        org.elasticsearch.jingra.config.DatasetConfig datasetConfig = jingraConfig.getActiveDataset();
        Map<String, List<Map<String, Object>>> paramGroups = new HashMap<>();
        paramGroups.put("default", List.of(params1, params2));
        datasetConfig.setParamGroups(paramGroups);

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Should have results for both parameter groups
        assertTrue(mockSink.resultCount >= 2, "Should write results for each parameter group");
    }

    @Test
    void testRunEvaluation_warmupRoundsExecute() throws Exception {
        EvaluationConfig evalConfig = jingraConfig.getEvaluation();
        evalConfig.setWarmupRounds(2);
        evalConfig.setMeasurementRounds(1);

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Warmup + measurement rounds should execute
        assertTrue(mockEngine.queryCount >= 10, "Should execute warmup and measurement queries");
    }

    @Test
    void testRunEvaluation_handlesQueryErrors() {
        mockEngine.shouldFail = true;

        assertThrows(IllegalStateException.class, () -> evaluator.runEvaluation());
    }

    @Test
    void testRunEvaluation_passesQueryVector() throws Exception {
        evaluator.runEvaluation();

        // Verify query vectors were passed
        assertFalse(mockEngine.receivedVectors.isEmpty(), "Should pass query vectors");
        assertEquals(128, mockEngine.receivedVectors.get(0).size(), "Vector should have 128 dimensions");
    }

    @Test
    void testRunEvaluation_passesMetaConditions() throws Exception {
        evaluator.runEvaluation();

        // Verify meta conditions were passed
        assertFalse(mockEngine.receivedParams.isEmpty(), "Should pass query parameters");
    }

    @Test
    void testRunEvaluation_calculatesAllMetrics() throws Exception {
        evaluator.runEvaluation();

        // Verify all metrics are present in results
        assertTrue(mockSink.resultCount > 0, "Should calculate and write metrics");
    }

    @Test
    void testRunEvaluation_usesConfiguredWorkerCounts() throws Exception {
        EvaluationConfig evalConfig = jingraConfig.getEvaluation();
        evalConfig.setWarmupWorkers(2);
        evalConfig.setMeasurementWorkers(4);

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Verify execution completed
        assertTrue(mockEngine.queryCount > 0, "Should execute with configured worker counts");
    }

    @Test
    void testRunEvaluation_emptyParamGroups_returnsEarly() throws Exception {
        jingraConfig.getActiveDataset().setParamGroups(new HashMap<>());
        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();
        assertEquals(0, mockEngine.queryCount);
    }

    @Test
    void testRunEvaluation_nullParamGroups_returnsEarly() throws Exception {
        jingraConfig.getActiveDataset().setParamGroups(null);
        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();
        assertEquals(0, mockEngine.queryCount);
    }

    @Test
    void testRunEvaluation_warmupFailuresThrowIllegalState() {
        jingraConfig.getEvaluation().setWarmupRounds(1);
        jingraConfig.getEvaluation().setMeasurementRounds(0);
        mockEngine.shouldFail = true;
        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> evaluator.runEvaluation());
        assertTrue(ex.getMessage().contains("Warmup"));
    }

    @Test
    void testRunEvaluation_addsVectorTypeMetadataWhenPresent() throws Exception {
        MockBenchmarkEngine engineWithMeta = new MockBenchmarkEngine() {
            @Override
            public Map<String, String> getIndexMetadata(String indexName) {
                return Map.of("vector_type", "dense_vector");
            }
        };
        final BenchmarkResult[] last = new BenchmarkResult[1];
        MockResultsSink sink = new MockResultsSink() {
            @Override
            public void writeResult(BenchmarkResult result) {
                super.writeResult(result);
                last[0] = result;
            }
        };
        evaluator = new BenchmarkEvaluator(jingraConfig, engineWithMeta, List.of(sink));
        evaluator.runEvaluation();
        assertEquals("dense_vector", last[0].getMetadata().get("vector_type"));
    }

    /**
     * {@link Document#getFloatList} treats {@code List<Double>} as numbers, so the double-conversion
     * path in {@code parseQueryDocumentsFromDocuments} is only reachable if float list is null but
     * double list is not (e.g. synthetic document for tests).
     */
    private static final class DocumentDoubleVectorOnly extends Document {
        @Override
        public List<Float> getFloatList(String field) {
            if ("query_vector".equals(field)) {
                return null;
            }
            return super.getFloatList(field);
        }

        @Override
        public List<Double> getDoubleList(String field) {
            if ("query_vector".equals(field)) {
                return List.of(0.25d, 0.5d);
            }
            return super.getDoubleList(field);
        }
    }

    @Test
    void parseQueryDocumentsFromDocuments_convertsDoubleListWhenFloatListUnavailable() throws Exception {
        Document d = new DocumentDoubleVectorOnly();
        d.put("ground_truth", List.of("a"));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(1, q.size());
        assertEquals(List.of(0.25f, 0.5f), qdVector(q.get(0)));
    }

    @Test
    void parseQueryDocumentsFromDocuments_skipsNullVector() throws Exception {
        Document d = new Document();
        d.put("ground_truth", List.of("x"));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(0, q.size());
    }

    @Test
    void parseQueryDocumentsFromDocuments_skipsUnexpectedVectorType() throws Exception {
        Document d = new Document();
        d.put("query_vector", List.of("not", "numbers"));
        d.put("ground_truth", List.of("a"));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(0, q.size());
    }

    /** Empty list as raw vector: float/double accessors null, raw list is empty (logging uses N/A, no first-element line). */
    private static final class DocumentEmptyListRawVector extends Document {
        @Override
        public List<Float> getFloatList(String field) {
            if ("query_vector".equals(field)) {
                return null;
            }
            return super.getFloatList(field);
        }

        @Override
        public List<Double> getDoubleList(String field) {
            if ("query_vector".equals(field)) {
                return null;
            }
            return super.getDoubleList(field);
        }

        @Override
        public Object get(String field) {
            if ("query_vector".equals(field)) {
                return List.of();
            }
            return super.get(field);
        }
    }

    @Test
    void parseQueryDocumentsFromDocuments_skipsEmptyListRawVector() throws Exception {
        Document d = new DocumentEmptyListRawVector();
        d.put("ground_truth", List.of("a"));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(0, q.size());
    }

    /** Non-list raw value (e.g. numeric scalar) for logging branch where {@code instanceof List} is false. */
    private static final class DocumentIntegerRawVector extends Document {
        @Override
        public List<Float> getFloatList(String field) {
            return "query_vector".equals(field) ? null : super.getFloatList(field);
        }

        @Override
        public List<Double> getDoubleList(String field) {
            return "query_vector".equals(field) ? null : super.getDoubleList(field);
        }

        @Override
        public Object get(String field) {
            return "query_vector".equals(field) ? 42 : super.get(field);
        }
    }

    @Test
    void parseQueryDocumentsFromDocuments_skipsNonListRawVector() throws Exception {
        Document d = new DocumentIntegerRawVector();
        d.put("ground_truth", List.of("a"));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(0, q.size());
    }

    @Test
    void parseQueryDocumentsFromDocuments_groundTruthNullBecomesEmptyList() throws Exception {
        Document d = new Document();
        d.put("query_vector", List.of(1f, 2f));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(1, q.size());
        assertTrue(qdGroundTruth(q.get(0)).isEmpty());
    }

    @Test
    void parseQueryDocumentsFromDocuments_conditionsAsMap() throws Exception {
        Document d = new Document();
        d.put("query_vector", List.of(1f));
        d.put("ground_truth", List.of());
        d.put("meta_conditions", Map.of("k", "v"));
        List<Object> q = invokeParse(List.of(d));
        assertEquals(Map.of("k", "v"), qdMetaConditions(q.get(0)));
    }

    @Test
    void parseQueryDocumentsFromDocuments_conditionsAsValidJsonString() throws Exception {
        Document d = new Document();
        d.put("query_vector", List.of(1f));
        d.put("ground_truth", List.of());
        d.put("meta_conditions", "{\"region\":\"us\"}");
        List<Object> q = invokeParse(List.of(d));
        assertEquals("us", ((Map<?, ?>) qdMetaConditions(q.get(0))).get("region"));
    }

    @Test
    void parseQueryDocumentsFromDocuments_invalidJsonStringUsesEmptyMap() throws Exception {
        Document d = new Document();
        d.put("query_vector", List.of(1f));
        d.put("ground_truth", List.of());
        d.put("meta_conditions", "not-json{");
        List<Object> q = invokeParse(List.of(d));
        assertTrue(qdMetaConditions(q.get(0)).isEmpty());
    }

    @Test
    void parseQueryDocumentsFromDocuments_conditionsNonMapNonStringLeavesMetaNull() throws Exception {
        Document d = new Document();
        d.put("query_vector", List.of(1f));
        d.put("ground_truth", List.of());
        d.put("meta_conditions", 42);
        List<Object> q = invokeParse(List.of(d));
        assertNull(qdMetaConditions(q.get(0)));
    }

    @Test
    void buildQueryMetric_emptyRetrievedAndGroundTruth_andNullServerLatency() throws Exception {
        Class<?> qdClass = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$QueryDocument");
        Constructor<?> ctor = qdClass.getDeclaredConstructor(List.class, List.class, Map.class);
        ctor.setAccessible(true);
        Object qd = ctor.newInstance(List.of(1f), List.of("gt"), Map.of("f", "c"));
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("gt"), List.of(), 12.0, null);
        Map<String, Object> metric = invokeBuildQueryMetric(
                evaluator, res, qd, Map.of("k", 1));
        assertEquals(0.0, metric.get("precision"));
        assertEquals(0.0, metric.get("recall"));
        assertFalse(metric.containsKey("server_latency_ms"));
        assertEquals(Map.of("f", "c"), metric.get("meta_conditions"));
    }

    @Test
    void buildQueryMetric_omitsMetaConditionsWhenNull() throws Exception {
        Class<?> qdClass = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$QueryDocument");
        Constructor<?> ctor = qdClass.getDeclaredConstructor(List.class, List.class, Map.class);
        ctor.setAccessible(true);
        Object qd = ctor.newInstance(List.of(1f), List.of("a"), null);
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 1.0, 2L);
        Map<String, Object> metric = invokeBuildQueryMetric(evaluator, res, qd, Map.of());
        assertFalse(metric.containsKey("meta_conditions"));
        assertEquals(2L, metric.get("server_latency_ms"));
        assertEquals(1.0, metric.get("recall"));
    }

    @Test
    void buildQueryMetric_recallBranchWhenGroundTruthEmpty() throws Exception {
        Class<?> qdClass = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$QueryDocument");
        Constructor<?> ctor = qdClass.getDeclaredConstructor(List.class, List.class, Map.class);
        ctor.setAccessible(true);
        Object qd = ctor.newInstance(List.of(1f), List.of(), null);
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of(), List.of("x"), 1.0, null);
        Map<String, Object> metric = invokeBuildQueryMetric(evaluator, res, qd, Map.of());
        assertEquals(0.0, metric.get("recall"));
    }

    @Test
    void calculateMetrics_omitsServerLatencyBlockWhenAbsent() throws Exception {
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 10.0, null);
        BenchmarkResult br = invokeCalculateMetrics(List.of(res), 1000L);
        assertNull(br.getMetrics().get("server_latency_avg"));
    }

    @Test
    void calculateMetrics_includesServerLatencyBlockWhenPresent() throws Exception {
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 10.0, 7L);
        BenchmarkResult br = invokeCalculateMetrics(List.of(res), 1000L);
        assertNotNull(br.getMetrics().get("server_latency_avg"));
    }

    @Test
    void calculateMetrics_totalWallMsZeroOmitsAggregateThroughputModel() throws Exception {
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 10.0, null);
        BenchmarkResult br = invokeCalculateMetrics(List.of(res), 0L);
        assertNull(br.getMetrics().get("throughput_aggregate_latency_model"));
    }

    @Test
    void calculateMetrics_totalWallMsPositiveIncludesAggregateThroughputModel() throws Exception {
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 10.0, null);
        BenchmarkResult br = invokeCalculateMetrics(List.of(res), 500L);
        assertNotNull(br.getMetrics().get("throughput_aggregate_latency_model"));
    }

    @Test
    void calculateMetrics_setsSchemaWhenSchemaNameConfiguredAndTemplateFound() throws Exception {
        org.elasticsearch.jingra.config.DatasetConfig datasetConfig = jingraConfig.getActiveDataset();
        datasetConfig.setSchemaName("schema-a");

        MockBenchmarkEngine engineWithSchema = new MockBenchmarkEngine() {
            @Override
            public Map<String, Object> getSchemaTemplate(String schemaName) {
                return Map.of("mappings", Map.of("properties", Map.of("f", Map.of("type", "keyword"))));
            }
        };

        BenchmarkEvaluator ev = new BenchmarkEvaluator(jingraConfig, engineWithSchema, List.of(mockSink));
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 10.0, null);
        BenchmarkResult br = invokeCalculateMetrics(ev, List.of(res), 1000L);
        assertNotNull(br.getSchema());
        assertTrue(br.getSchema().containsKey("mappings"));
    }

    @Test
    void calculateMetrics_doesNotSetSchemaWhenSchemaNameConfiguredButTemplateMissing() throws Exception {
        org.elasticsearch.jingra.config.DatasetConfig datasetConfig = jingraConfig.getActiveDataset();
        datasetConfig.setSchemaName("schema-missing");

        MockBenchmarkEngine engineNoSchema = new MockBenchmarkEngine() {
            @Override
            public Map<String, Object> getSchemaTemplate(String schemaName) {
                return null;
            }
        };

        BenchmarkEvaluator ev = new BenchmarkEvaluator(jingraConfig, engineNoSchema, List.of(mockSink));
        MetricsCalculator.QueryResult res = new MetricsCalculator.QueryResult(
                List.of("a"), List.of("a"), 10.0, null);
        BenchmarkResult br = invokeCalculateMetrics(ev, List.of(res), 1000L);
        assertNull(br.getSchema());
    }

    @SuppressWarnings("unchecked")
    private List<Object> invokeParse(List<Document> docs) throws Exception {
        Method m = BenchmarkEvaluator.class.getDeclaredMethod("parseQueryDocumentsFromDocuments", List.class, DatasetConfig.class);
        m.setAccessible(true);
        return (List<Object>) m.invoke(evaluator, docs, jingraConfig.getActiveDataset());
    }

    @SuppressWarnings("unchecked")
    private static List<Float> qdVector(Object qd) throws Exception {
        Field f = qd.getClass().getDeclaredField("queryVector");
        f.setAccessible(true);
        return (List<Float>) f.get(qd);
    }

    @SuppressWarnings("unchecked")
    private static List<String> qdGroundTruth(Object qd) throws Exception {
        Field f = qd.getClass().getDeclaredField("groundTruth");
        f.setAccessible(true);
        return (List<String>) f.get(qd);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> qdMetaConditions(Object qd) throws Exception {
        Field f = qd.getClass().getDeclaredField("metaConditions");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(qd);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeBuildQueryMetric(
            BenchmarkEvaluator ev,
            MetricsCalculator.QueryResult res,
            Object qd,
            Map<String, Object> params
    ) throws Exception {
        Class<?> qdClass = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$QueryDocument");
        Method m = BenchmarkEvaluator.class.getDeclaredMethod(
                "buildQueryMetric", MetricsCalculator.QueryResult.class, qdClass, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(ev, res, qd, params);
    }

    private BenchmarkResult invokeCalculateMetrics(List<MetricsCalculator.QueryResult> results, long totalWallMs) throws Exception {
        return invokeCalculateMetrics(evaluator, results, totalWallMs);
    }

    @Test
    void testConstructor_usesCustomRunIdFromConfig() throws Exception {
        // Configure custom run_id
        jingraConfig.getEvaluation().setRunId("my-custom-run-2026");

        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Verify custom run_id is used in results
        assertTrue(mockSink.resultCount > 0);
        BenchmarkResult result = mockSink.lastResult;
        assertNotNull(result);
        assertEquals("my-custom-run-2026", result.getRunId());
    }

    @Test
    void testConstructor_generatesRunIdWhenNotConfigured() throws Exception {
        // Do not set run_id in config
        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        evaluator.runEvaluation();

        // Verify generated run_id matches timestamp pattern
        assertTrue(mockSink.resultCount > 0);
        BenchmarkResult result = mockSink.lastResult;
        assertNotNull(result);
        String runId = result.getRunId();
        assertNotNull(runId);
        // Pattern: yyyyMMdd-HHmmss (e.g., 20260408-143022)
        assertTrue(runId.matches("\\d{8}-\\d{6}"),
            "Generated run_id should match timestamp pattern, got: " + runId);
    }

    @Test
    void testConstructor_generatesRunIdWhenEvaluationConfigIsNull() throws Exception {
        // Set evaluation config to null
        jingraConfig.setEvaluation(null);

        // Verify constructor doesn't throw NPE when evalConfig is null
        evaluator = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));

        // Use reflection to verify run_id was generated
        Field runIdField = BenchmarkEvaluator.class.getDeclaredField("runId");
        runIdField.setAccessible(true);
        String runId = (String) runIdField.get(evaluator);

        assertNotNull(runId);
        assertTrue(runId.matches("\\d{8}-\\d{6}"),
            "Generated run_id should match timestamp pattern even when evalConfig is null, got: " + runId);
    }

    @Test
    void executeQuery_skipsMetaConditionsWhenNull() throws Exception {
        Class<?> qdClass = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$QueryDocument");
        Constructor<?> ctor = qdClass.getDeclaredConstructor(List.class, List.class, Map.class);
        ctor.setAccessible(true);
        Object qd = ctor.newInstance(List.of(1f, 2f), List.of("a"), null);
        Method m = BenchmarkEvaluator.class.getDeclaredMethod(
                "executeQuery", qdClass, DatasetConfig.class, Map.class);
        m.setAccessible(true);
        int before = mockEngine.queryCount;
        m.invoke(evaluator, qd, jingraConfig.getActiveDataset(), Map.of("size", 10));
        assertEquals(before + 1, mockEngine.queryCount);
        QueryParams last = mockEngine.receivedParams.get(mockEngine.receivedParams.size() - 1);
        assertNull(last.get("meta_conditions"));
    }

    private BenchmarkResult invokeCalculateMetrics(
            BenchmarkEvaluator ev,
            List<MetricsCalculator.QueryResult> results,
            long totalWallMs
    ) throws Exception {
        Method m = BenchmarkEvaluator.class.getDeclaredMethod(
                "calculateMetrics",
                List.class,
                long.class,
                DatasetConfig.class,
                String.class,
                String.class,
                Map.class);
        m.setAccessible(true);
        Map<String, Object> params = Map.of("size", 10);
        return (BenchmarkResult) m.invoke(
                ev,
                results,
                totalWallMs,
                jingraConfig.getActiveDataset(),
                "default",
                "k=10",
                params);
    }

    static final class CountingResultsSink extends MockResultsSink {
        int queryMetricsBatchCalls;

        @Override
        public void writeQueryMetricsBatch(List<Map<String, Object>> queryMetrics) {
            queryMetricsBatchCalls++;
        }
    }

    static final class BenchmarkEvaluatorWithInjectableExecutor extends BenchmarkEvaluator {
        private final IntFunction<ExecutorService> poolFactory;

        BenchmarkEvaluatorWithInjectableExecutor(
                JingraConfig config,
                BenchmarkEngine engine,
                List<ResultsSink> sinks,
                IntFunction<ExecutorService> poolFactory) {
            super(config, engine, sinks);
            this.poolFactory = poolFactory;
        }

        @Override
        ExecutorService createQueryExecutor(int numWorkers) {
            return poolFactory.apply(numWorkers);
        }
    }

    static final class AwaitNeverTerminatesPool extends ThreadPoolExecutor {
        private volatile boolean firstAwait = true;

        AwaitNeverTerminatesPool(int threads) {
            super(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (firstAwait) {
                firstAwait = false;
                return false;
            }
            return super.awaitTermination(timeout, unit);
        }
    }

    static final class InterruptOnAwaitPool extends ThreadPoolExecutor {
        private volatile boolean firstAwait = true;

        InterruptOnAwaitPool(int threads) {
            super(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (firstAwait) {
                firstAwait = false;
                throw new InterruptedException("test interrupt");
            }
            return super.awaitTermination(timeout, unit);
        }
    }

    static final class FailOnceEngine extends MockBenchmarkEngine {
        private int calls;

        @Override
        public QueryResponse query(String indexName, String queryName, QueryParams params) {
            if (calls++ == 0) {
                throw new RuntimeException("simulated query failure");
            }
            return super.query(indexName, queryName, params);
        }
    }

    @Test
    void executeQueries_collectResultsFalse_emptyResultsAndNoQueryMetricsBatch() throws Exception {
        List<Object> queries = buildQueryDocuments(2);
        CountingResultsSink sink = new CountingResultsSink();
        BenchmarkEvaluator ev = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(sink));
        Object outcome = invokeExecuteQueries(ev, queries, false, 1);
        assertEquals(0, outcomeResults(outcome).size());
        assertEquals(0, outcomeFailures(outcome));
        assertEquals(0, sink.queryMetricsBatchCalls);
    }

    @Test
    void executeQueries_oneTaskFailure_incrementsFailuresWithoutThrowing() throws Exception {
        List<Object> queries = buildQueryDocuments(3);
        FailOnceEngine engine = new FailOnceEngine();
        BenchmarkEvaluator ev = new BenchmarkEvaluator(jingraConfig, engine, List.of(mockSink));
        Object outcome = invokeExecuteQueries(ev, queries, true, 1);
        assertEquals(1, outcomeFailures(outcome));
        assertEquals(2, outcomeResults(outcome).size());
    }

    @Test
    void executeQueries_progressLogWhenCompletedReachesMultipleOf1000() throws Exception {
        List<Object> queries = buildQueryDocuments(1000);
        BenchmarkEvaluator ev = new BenchmarkEvaluator(jingraConfig, mockEngine, List.of(mockSink));
        Object outcome = invokeExecuteQueries(ev, queries, true, 1);
        assertEquals(0, outcomeFailures(outcome));
        assertEquals(1000, outcomeResults(outcome).size());
    }

    @Test
    void executeQueries_whenAwaitTerminationReturnsFalse_throwsIllegalStateAndSetsInterrupt() throws Exception {
        AwaitNeverTerminatesPool pool = new AwaitNeverTerminatesPool(1);
        BenchmarkEvaluator ev = new BenchmarkEvaluatorWithInjectableExecutor(
                jingraConfig, mockEngine, List.of(mockSink), n -> pool);
        List<Object> queries = buildQueryDocuments(1);
        try {
            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> invokeExecuteQueries(ev, queries, false, 1));
            IllegalStateException ex = assertInstanceOf(IllegalStateException.class, wrapped.getCause());
            assertEquals("Query executor did not finish within 1 hour", ex.getMessage());
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void executeQueries_whenAwaitTerminationInterrupted_throwsIllegalStateWithCause() throws Exception {
        InterruptOnAwaitPool pool = new InterruptOnAwaitPool(1);
        BenchmarkEvaluator ev = new BenchmarkEvaluatorWithInjectableExecutor(
                jingraConfig, mockEngine, List.of(mockSink), n -> pool);
        List<Object> queries = buildQueryDocuments(1);
        try {
            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> invokeExecuteQueries(ev, queries, false, 1));
            IllegalStateException ex = assertInstanceOf(IllegalStateException.class, wrapped.getCause());
            assertEquals("Interrupted while waiting for query executor", ex.getMessage());
            assertInstanceOf(InterruptedException.class, ex.getCause());
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static List<Object> buildQueryDocuments(int n) throws Exception {
        Class<?> qdClass = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$QueryDocument");
        Constructor<?> ctor = qdClass.getDeclaredConstructor(List.class, List.class, Map.class);
        ctor.setAccessible(true);
        List<Float> vec = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            vec.add(0.25f);
        }
        List<Object> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(ctor.newInstance(vec, List.of("doc-" + i), null));
        }
        return list;
    }

    private static Object invokeExecuteQueries(
            BenchmarkEvaluator ev,
            List<Object> queries,
            boolean collectResults,
            int workers
    ) throws Exception {
        Method m = BenchmarkEvaluator.class.getDeclaredMethod(
                "executeQueries",
                List.class,
                DatasetConfig.class,
                Map.class,
                int.class,
                boolean.class);
        m.setAccessible(true);
        return m.invoke(ev, queries, jingraConfigFromEvaluator(ev), Map.of("k", 10, "size", 10), workers, collectResults);
    }

    private static DatasetConfig jingraConfigFromEvaluator(BenchmarkEvaluator ev) throws Exception {
        Field f = BenchmarkEvaluator.class.getDeclaredField("config");
        f.setAccessible(true);
        JingraConfig jc = (JingraConfig) f.get(ev);
        return jc.getActiveDataset();
    }

    @SuppressWarnings("unchecked")
    private static List<MetricsCalculator.QueryResult> outcomeResults(Object outcome) throws Exception {
        Class<?> oc = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$ExecuteQueriesOutcome");
        Field f = oc.getDeclaredField("results");
        f.setAccessible(true);
        return (List<MetricsCalculator.QueryResult>) f.get(outcome);
    }

    private static int outcomeFailures(Object outcome) throws Exception {
        Class<?> oc = Class.forName("org.elasticsearch.jingra.evaluation.BenchmarkEvaluator$ExecuteQueriesOutcome");
        Field f = oc.getDeclaredField("failures");
        f.setAccessible(true);
        return (Integer) f.get(outcome);
    }

}
