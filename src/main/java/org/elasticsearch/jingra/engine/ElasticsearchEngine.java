package org.elasticsearch.jingra.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.MetricsgenConfig;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.elasticsearch.jingra.utils.TlsSettings;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Elasticsearch engine implementation.
 */
public class ElasticsearchEngine extends AbstractBenchmarkEngine {

    private ElasticsearchClient client;
    private co.elastic.clients.transport.rest5_client.low_level.Rest5Client restClient;
    private String baseUrl;

    public ElasticsearchEngine(Map<String, Object> config) {
        super(config);
    }

    /**
     * Whether API methods that require an initialized client may run.
     * Overridden in same-package tests to exercise branches without a real {@link ElasticsearchClient}.
     */
    protected boolean hasClient() {
        return client != null;
    }

    protected boolean dataStoreExistsOperation(String indexName) throws Exception {
        return client.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
    }

    protected void resetDataStoreOperation(String indexName) throws Exception {
        client.indices().delete(DeleteIndexRequest.of(b -> b.index(indexName)));
    }

    protected BulkResponse bulkOperation(BulkRequest request) throws Exception {
        return client.bulk(request);
    }

    /**
     * Bulk-index arbitrary maps (e.g. query metrics from {@link org.elasticsearch.jingra.output.ElasticsearchResultsSink}).
     * Same transport as {@link #ingest}; callers apply {@link org.elasticsearch.jingra.utils.RetryHelper} at the edge if needed.
     */
    public BulkResponse bulkIndexMaps(String indexName, List<Map<String, Object>> documents) throws Exception {
        if (!hasClient()) {
            throw new IllegalStateException("Elasticsearch client not initialized");
        }
        if (documents.isEmpty()) {
            return BulkResponse.of(b -> b.errors(false).took(0).items(List.of()));
        }
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (Map<String, Object> doc : documents) {
            bulkBuilder.operations(op -> op.index(idx -> idx.index(indexName).document(doc)));
        }
        return bulkOperation(bulkBuilder.build());
    }

    protected long countOperation(String indexName) throws Exception {
        return client.count(c -> c.index(indexName)).count();
    }

    protected String versionOperation() throws Exception {
        return client.info().version().number();
    }

    protected GetIndexResponse getIndexResponseOperation(String indexName) throws Exception {
        return client.indices().get(GetIndexRequest.of(b -> b.index(indexName)));
    }

    // ── Data stream support ───────────────────────────────────────────────────────

    boolean isDataStream() {
        return getConfigBoolean("data_stream", false);
    }

    protected boolean dataStreamExistsOperation(String name) throws Exception {
        Response response = restClient.performRequest(new Request("GET", "/_data_stream/" + name));
        return response.getStatusCode() == 200;
    }

    private static void requireOk(Response response, String description) throws Exception {
        int status = response.getStatusCode();
        if (status >= 300) {
            String body = new String(response.getEntity().getContent().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            throw new Exception(description + " failed with HTTP " + status + ": " + body);
        }
    }

    protected void applyIlmPolicyOperation(String policyName, String policyJson) throws Exception {
        Request req = new Request("PUT", "/_ilm/policy/" + policyName);
        req.setJsonEntity(policyJson);
        requireOk(restClient.performRequest(req), "PUT /_ilm/policy/" + policyName);
    }

    protected void applyIndexTemplateOperation(String templateName, String templateJson) throws Exception {
        Request req = new Request("PUT", "/_index_template/" + templateName);
        req.setJsonEntity(templateJson);
        requireOk(restClient.performRequest(req), "PUT /_index_template/" + templateName);
    }

    protected void applyComponentTemplateOperation(String templateName, String templateJson) throws Exception {
        Request req = new Request("PUT", "/_component_template/" + templateName);
        req.setJsonEntity(templateJson);
        requireOk(restClient.performRequest(req), "PUT /_component_template/" + templateName);
    }

    protected void createDataStreamOperation(String name) throws Exception {
        requireOk(restClient.performRequest(new Request("PUT", "/_data_stream/" + name)),
                "PUT /_data_stream/" + name);
    }

    protected void deleteDataStreamOperation(String name) throws Exception {
        requireOk(restClient.performRequest(new Request("DELETE", "/_data_stream/" + name)),
                "DELETE /_data_stream/" + name);
    }

    protected String loadIlmFile(String filename) throws java.io.IOException {
        java.io.File file = new java.io.File(JINGRA_CONFIG_DIR + "/ilm/" + filename);
        if (!file.exists()) {
            logger.error("ILM file '{}' not found in {}/ilm/", filename, JINGRA_CONFIG_DIR);
            return null;
        }
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    protected void createDataStoreOperation(String indexName, String schemaJson) throws Exception {
        // Use the low-level REST client so the schema JSON is sent as raw bytes.
        // The typed CreateIndexRequest.withJson() round-trips through the client's object model,
        // which rejects fields unknown to the current client version (e.g. 'bits' in
        // DenseVectorIndexOptions when the client lags behind the server).
        co.elastic.clients.transport.rest5_client.low_level.Request request =
                new co.elastic.clients.transport.rest5_client.low_level.Request("PUT", "/" + indexName);
        request.setJsonEntity(schemaJson);
        restClient.performRequest(request);
    }

    protected int mergesCurrentOperation(String indexName) throws Exception {
        co.elastic.clients.transport.rest5_client.low_level.Request request =
                new co.elastic.clients.transport.rest5_client.low_level.Request(
                        "GET", "/" + indexName + "/_stats/merges");
        co.elastic.clients.transport.rest5_client.low_level.Response response = restClient.performRequest(request);
        byte[] bodyBytes = response.getEntity() != null ? response.getEntity().getContent().readAllBytes() : new byte[0];
        String bodyStr = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(bodyStr);
        com.fasterxml.jackson.databind.JsonNode current = json.path("indices").path(indexName)
                .path("primaries").path("merges").path("current");
        if (!current.isMissingNode()) {
            return current.asInt(0);
        }
        return json.path("_all").path("primaries").path("merges").path("current").asInt(0);
    }

    /** Hook for tests to override the poll sleep interval (in milliseconds). Default is 30 seconds. */
    protected long getPollIntervalMs() {
        return 30_000L;
    }

    @Override
    public void awaitIndexReady(String indexName) {
        if (!hasClient()) {
            throw new IllegalStateException("Elasticsearch client not initialized");
        }
        try {
            logger.info("Waiting for background merges to settle on index '{}'...", indexName);
            long startMs = System.currentTimeMillis();
            while (true) {
                int current = mergesCurrentOperation(indexName);
                if (current == 0) {
                    long elapsedMin = (System.currentTimeMillis() - startMs) / 60_000;
                    logger.info("Merges settled for index '{}'; elapsed {}m", indexName, elapsedMin);
                    return;
                }
                long elapsedMin = (System.currentTimeMillis() - startMs) / 60_000;
                logger.info("Merges in progress on '{}', current={}, elapsed {}m...", indexName, current, elapsedMin);
                Thread.sleep(getPollIntervalMs());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("awaitIndexReady failed on index '" + indexName + "'", e);
        }
    }

    protected SearchResponse<Map> searchOperation(String indexName, String queryJson) throws Exception {
        return client.search(s -> s
                        .index(indexName)
                        .withJson(new StringReader(queryJson)),
                Map.class
        );
    }

    /**
     * Execute a search query against the specified index.
     * Public wrapper for searchOperation to support external querying (e.g., for analysis).
     *
     * @param indexName the index to search
     * @param queryJson the query in JSON format
     * @return search response
     * @throws Exception if search fails
     */
    public SearchResponse<Map> search(String indexName, String queryJson) throws Exception {
        return searchOperation(indexName, queryJson);
    }

    /**
     * If {@code insecure_tls} is present in config, that value alone applies (so sinks can force
     * verified TLS even when {@link TlsSettings#insecureTlsEnabled()} is true). If absent, the
     * global insecure-TLS flag applies.
     */
    protected boolean resolveInsecureTls() {
        if (config.containsKey("insecure_tls")) {
            return getConfigBoolean("insecure_tls", false);
        }
        return TlsSettings.insecureTlsEnabled();
    }

    @Override
    public boolean connect() {
        // Check for direct config values first (useful for testing), then fall back to env vars
        String url = getConfigString("url", null);
        String user = getConfigString("user", null);
        String password = getConfigString("password", null);

        // If not in direct config, read from environment variables
        if (url == null) {
            String urlEnv = getConfigString("url_env", "ELASTICSEARCH_URL");
            String userEnv = getConfigString("user_env", "ELASTICSEARCH_USER");
            String passwordEnv = getConfigString("password_env", "ELASTICSEARCH_PASSWORD");

            url = getEnv(urlEnv, null);
            user = getEnv(userEnv, null);
            password = getEnv(passwordEnv, null);

            if (url == null) {
                logger.error("Elasticsearch URL not set in config or environment: {}", urlEnv);
                return false;
            }
        }

        try {
            boolean insecureTls = resolveInsecureTls();
            // Use shared factory for consistent client configuration
            ElasticsearchClientFactory.ElasticsearchClientWrapper wrapper =
                    ElasticsearchClientFactory.createClient(url, user, password, insecureTls);

            this.client = wrapper.getClient();
            this.restClient = wrapper.getRestClient();
            this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

            // Test connection
            client.info();
            logger.info("Connected to Elasticsearch at {}", url);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to Elasticsearch", e);
            return false;
        }
    }

    @Override
    public boolean createDataStore(String indexName, org.elasticsearch.jingra.config.DatasetConfig dataset) {
        if (isDataStream() && dataset != null) {
            // Null policy/template means "skip" — rely on whatever already exists in ES
            return createDataStreamWithNames(indexName, dataset.getIlmPolicy(),
                    dataset.getIndexTemplate(), dataset.getComponentTemplate());
        }
        return createDataStore(indexName, dataset != null ? dataset.getSchemaName() : null);
    }

    private boolean createDataStreamWithNames(String indexName, String policyName,
                                              String templateName, String componentTemplateName) {
        if (!hasClient()) {
            logger.error("Elasticsearch client not initialized");
            return false;
        }
        if (dataStoreExists(indexName)) {
            logger.warn("Data stream '{}' already exists", indexName);
            return false;
        }
        try {
            if (policyName != null) {
                String policyJson = loadIlmFile(policyName + ".json");
                if (policyJson == null) {
                    return false;
                }
                applyIlmPolicyOperation(policyName, policyJson);
            }
            if (templateName != null) {
                String templateJson = loadIlmFile(templateName + ".json");
                if (templateJson == null) {
                    return false;
                }
                applyIndexTemplateOperation(templateName, templateJson);
            }
            if (componentTemplateName != null) {
                String componentJson = loadIlmFile(componentTemplateName + ".json");
                if (componentJson == null) {
                    return false;
                }
                applyComponentTemplateOperation(componentTemplateName, componentJson);
            }
            createDataStreamOperation(indexName);
            logger.info("Created data stream '{}' (policy={}, template={}, componentTemplate={})",
                    indexName,
                    policyName != null ? policyName : "existing",
                    templateName != null ? templateName : "existing",
                    componentTemplateName != null ? componentTemplateName : "existing");
            return true;
        } catch (Exception e) {
            logger.error("Failed to create data stream '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public boolean createDataStore(String indexName, String schemaName) {
        if (!hasClient()) {
            logger.error("Elasticsearch client not initialized");
            return false;
        }

        if (isDataStream()) {
            if (dataStoreExists(indexName)) {
                logger.warn("Data stream '{}' already exists", indexName);
                return false;
            }
            try {
                String policyJson   = loadIlmFile(indexName + "-policy.json");
                String templateJson = loadIlmFile(indexName + "-template.json");
                if (policyJson == null || templateJson == null) {
                    return false;
                }
                applyIlmPolicyOperation(indexName + "-policy", policyJson);
                applyIndexTemplateOperation(indexName + "-template", templateJson);
                createDataStreamOperation(indexName);
                logger.info("Created data stream '{}' with ILM policy and index template", indexName);
                return true;
            } catch (Exception e) {
                logger.error("Failed to create data stream '{}'", indexName, e);
                return false;
            }
        }

        try {
            // Check if index already exists
            if (dataStoreExists(indexName)) {
                logger.warn("Index '{}' already exists", indexName);
                return false;
            }

            // Load schema template
            JsonNode template = loadSchemaTemplate(schemaName);
            if (template == null) {
                logger.error("Schema template '{}' not found", schemaName);
                return false;
            }

            // Source-of-truth schema files must be direct Elasticsearch create-index bodies (no Jingra wrapper).
            if (template.has("template") || template.has("name")) {
                logger.error("Wrapped schemas are not supported for Elasticsearch. Provide a direct create-index JSON body (no top-level 'name'/'template').");
                return false;
            }

            // Create index with schema
            String schemaJson = objectMapper.writeValueAsString(template);
            createDataStoreOperation(indexName, schemaJson);
            logger.info("Created Elasticsearch index '{}' with schema '{}'", indexName, schemaName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to create index '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public boolean dataStoreExists(String indexName) {
        if (!hasClient()) {
            return false;
        }
        try {
            if (isDataStream()) {
                return dataStreamExistsOperation(indexName);
            }
            return dataStoreExistsOperation(indexName);
        } catch (Exception e) {
            logger.error("Failed to check if index exists", e);
            return false;
        }
    }

    @Override
    public boolean resetDataStore(String indexName) {
        if (!hasClient()) {
            logger.error("Elasticsearch client not initialized");
            return false;
        }

        if (isDataStream()) {
            try {
                deleteDataStreamOperation(indexName);
                logger.info("Deleted data stream '{}'", indexName);
                return true;
            } catch (Exception e) {
                logger.info("Data stream '{}' delete: {} (treating as idempotent)", indexName, e.getMessage());
                return true;
            }
        }

        try {
            resetDataStoreOperation(indexName);
            logger.info("Deleted Elasticsearch index '{}'", indexName);
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                logger.info("Index '{}' did not exist (delete idempotent)", indexName);
                return true;
            }
            logger.error("Failed to delete index '{}'", indexName, e);
            return false;
        } catch (Exception e) {
            logger.error("Failed to delete index '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public int create(List<Document> documents, String indexName, String idField) {
        if (!hasClient()) {
            logger.error("Elasticsearch client not initialized");
            return 0;
        }
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            int count = 0;
            for (Document doc : documents) {
                Map<String, Object> fields = doc.getFields();
                bulkBuilder.operations(op -> op.create(c -> c.index(indexName).document(fields)));
                count++;
            }
            BulkResponse response = bulkOperation(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                        if (errorCount <= 5) {
                            logger.error("Bulk error: {}", item.error().reason());
                        }
                    }
                }
                logger.warn("Bulk ingestion had {} errors out of {} documents", errorCount, count);
                boolean failOnPartial = getConfigBoolean("ingest_fail_on_partial_errors", true);
                if (failOnPartial) {
                    throw new IllegalStateException(
                            "Bulk ingestion had " + errorCount + " errors out of " + count + " documents");
                }
                return count - errorCount;
            }
            return count;
        } catch (Exception e) {
            logger.error("Failed to create documents", e);
            throw new RuntimeException("Bulk create failed", e);
        }
    }

    // ── OTLP ingest ───────────────────────────────────────────────────────────────

    /**
     * Low-level POST to {@code /_otlp} (or a namespaced variant).
     * Overridden in tests to capture the request body without a real ES cluster.
     */
    protected int otlpOperation(byte[] protoBytes) throws Exception {
        Request req = new Request("POST", "/_otlp/v1/metrics");
        req.setEntity(new ByteArrayEntity(protoBytes, ContentType.create("application/x-protobuf")));
        req.setOptions(co.elastic.clients.transport.rest5_client.low_level.RequestOptions.DEFAULT
                .toBuilder()
                .addHeader("Content-Type", "application/x-protobuf")
                .build());
        Response resp = restClient.performRequest(req);
        requireOk(resp, "POST /_otlp/v1/metrics");
        return 0;
    }

    private boolean isOtlpMode() {
        return "otlp".equalsIgnoreCase(getConfigString("ingest_mode", null));
    }

    int ingestOtlp(List<Document> documents, String indexName) {
        String dataset   = getConfigString("otlp_dataset",   "jingra");
        String namespace = getConfigString("otlp_namespace",  "benchmark");
        int batchSize    = getConfigInt("otlp_batch_size", 1000);
        OtlpBatchConverter converter = new OtlpBatchConverter(dataset, namespace);
        int total = 0;
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Document> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));
            byte[] protoBytes = converter.convert(batch);
            try {
                otlpOperation(protoBytes);
                total += batch.size();
            } catch (Exception e) {
                logger.error("OTLP batch ingest failed at offset {}", i, e);
                throw new RuntimeException("OTLP ingest failed", e);
            }
        }
        return total;
    }

    @Override
    public int ingest(List<Document> documents, String indexName, String idField) {
        if (!hasClient()) {
            logger.error("Elasticsearch client not initialized");
            return 0;
        }

        if (isOtlpMode()) {
            return ingestOtlp(documents, indexName);
        }

        if (isDataStream()) {
            return create(documents, indexName, idField);
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            int count = 0;

            for (Document doc : documents) {
                Map<String, Object> fields = doc.getFields();

                // Set document ID if idField specified
                String docId = null;
                if (idField != null && doc.containsField(idField)) {
                    docId = doc.getString(idField);
                }

                final String finalDocId = docId;
                bulkBuilder.operations(op -> op
                        .index(idx -> {
                            idx.index(indexName)
                               .document(fields);
                            if (finalDocId != null) {
                                idx.id(finalDocId);
                            }
                            return idx;
                        })
                );
                count++;
            }

            BulkResponse response = bulkOperation(bulkBuilder.build());

            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                        if (errorCount <= 5) {
                            logger.error("Bulk error: {}", item.error().reason());
                        }
                    }
                }
                logger.warn("Bulk ingestion had {} errors out of {} documents", errorCount, count);
                boolean failOnPartial = getConfigBoolean("ingest_fail_on_partial_errors", true);
                if (failOnPartial) {
                    throw new IllegalStateException(
                            "Bulk ingestion had " + errorCount + " errors out of " + count + " documents");
                }
                return count - errorCount;
            }

            // No per-batch logging - progress is tracked in Main.java
            return count;
        } catch (Exception e) {
            logger.error("Failed to ingest documents", e);
            throw new RuntimeException("Bulk ingest failed", e);
        }
    }

    @Override
    public QueryResponse query(String indexName, String queryName, QueryParams params) {
        if (!hasClient()) {
            logger.error("Elasticsearch client not initialized");
            return new QueryResponse(List.of(), null, null);
        }

        try {
            // ESQL branch: detected by presence of a .esql template file
            if (hasEsqlTemplate(queryName)) {
                return executeEsqlQuery(queryName, params);
            }

            // Load and render query template (cached; avoid per-query I/O / JSON parse)
            JsonNode template = loadQueryTemplateCached(queryName);
            if (template == null) {
                logger.error("Query template '{}' not found", queryName);
                return new QueryResponse(List.of(), null, null);
            }

            // Source-of-truth query files must be direct Elasticsearch _search bodies (no Jingra wrapper).
            if (template.has("template") || template.has("name")) {
                logger.error("Wrapped queries are not supported for Elasticsearch. Provide a direct _search JSON body (no top-level 'name'/'template').");
                return new QueryResponse(List.of(), null, null);
            }

            String queryJson = renderDirectTemplate(template, params.getAll());
            if (shouldWriteFirstQueryDump()) {
                writeFirstQueryDumpIfConfigured(getShortName(), queryJson);
            }

            long startTime = System.nanoTime();
            SearchResponse<Map> response = searchOperation(indexName, queryJson);
            double clientLatencyMs = (System.nanoTime() - startTime) / 1_000_000.0;

            // Extract document IDs
            List<String> documentIds = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                documentIds.add(hit.id());
            }

            // Get server-side latency (took time)
            Long serverLatencyMs = response.took();

            return new QueryResponse(documentIds, clientLatencyMs, serverLatencyMs);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Query execution failed", e);
            return new QueryResponse(List.of(), null, null);
        }
    }

    private static String renderDirectTemplate(JsonNode templateNode, Map<String, Object> params) {
        try {
            String templateStr = objectMapper.writeValueAsString(templateNode);
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String placeholder = "\"{{" + entry.getKey() + "}}\"";
                String value;

                Object paramValue = entry.getValue();
                if (paramValue instanceof String) {
                    value = "\"" + paramValue + "\"";
                } else if (paramValue instanceof Number || paramValue instanceof Boolean) {
                    value = paramValue.toString();
                } else {
                    value = objectMapper.writeValueAsString(paramValue);
                }

                templateStr = templateStr.replace(placeholder, value);
            }
            return templateStr;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Elasticsearch query template", e);
        }
    }

    /**
     * Execute an ESQL query via {@code POST /_query} and return latency + first-row aggregate values.
     */
    @SuppressWarnings("unchecked")
    private QueryResponse executeEsqlQuery(String queryName, QueryParams params) throws IOException {
        String esql = renderEsqlTemplate(loadEsqlTemplate(queryName), params.getAll());
        String body = objectMapper.writeValueAsString(Map.of("query", esql));

        Request request = new Request("POST", "/_query");
        request.setJsonEntity(body);

        long start = System.nanoTime();
        Response response = restClient.performRequest(request);
        double clientLatencyMs = (System.nanoTime() - start) / 1_000_000.0;

        Map<String, Object> parsed = objectMapper.readValue(
                response.getEntity().getContent(), new TypeReference<Map<String, Object>>() {});
        Number took = (Number) parsed.get("took");

        // Flatten first result row into a named map: {"avg_load_1m": 0.45, ...}
        List<Map<String, Object>> columns = (List<Map<String, Object>>) parsed.get("columns");
        List<List<Object>> values = (List<List<Object>>) parsed.get("values");
        Map<String, Object> aggregates = new LinkedHashMap<>();
        if (columns != null && values != null && !values.isEmpty()) {
            List<Object> firstRow = values.get(0);
            for (int i = 0; i < columns.size() && i < firstRow.size(); i++) {
                aggregates.put((String) columns.get(i).get("name"), firstRow.get(i));
            }
        }

        return new QueryResponse(List.of(), clientLatencyMs,
                took != null ? took.longValue() : null, aggregates);
    }

    @Override
    public long getDocumentCount(String indexName) {
        if (!hasClient()) {
            return 0;
        }
        try {
            return countOperation(indexName);
        } catch (Exception e) {
            logger.error("Failed to get document count", e);
            return 0;
        }
    }

    @Override
    public String getEngineName() {
        return "elasticsearch";
    }

    @Override
    public String getShortName() {
        return "es";
    }

    @Override
    public String getVersion() {
        if (!hasClient()) {
            return "unknown";
        }
        try {
            return versionOperation();
        } catch (Exception e) {
            logger.error("Failed to get version", e);
            return "unknown";
        }
    }

    /** Package-private for unit tests (JaCoCo). */
    static String firstElasticsearchDenseVectorType(TypeMapping mapping) {
        if (mapping == null || mapping.properties() == null) {
            return null;
        }
        return firstDenseVectorInPropertyMap(mapping.properties());
    }

    /** Package-private for unit tests (JaCoCo); also used recursively from {@link #firstElasticsearchDenseVectorType}. */
    static String firstDenseVectorInPropertyMap(Map<String, Property> properties) {
        if (properties == null) {
            return null;
        }
        for (Property p : properties.values()) {
            if (p.isDenseVector()) {
                return "dense_vector";
            }
            if (p.isObject()) {
                String inner = firstDenseVectorInPropertyMap(p.object().properties());
                if (inner != null) {
                    return inner;
                }
            }
            if (p.isNested()) {
                String inner = firstDenseVectorInPropertyMap(p.nested().properties());
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, String> getIndexMetadata(String indexName) {
        Map<String, String> metadata = new HashMap<>();

        if (!hasClient()) {
            return metadata;
        }

        try {
            var indexResponse = getIndexResponseOperation(indexName);
            var indexInfo = indexResponse.get(indexName);

            if (indexInfo != null && indexInfo.mappings() != null) {
                TypeMapping tm = indexInfo.mappings();
                String vt = firstElasticsearchDenseVectorType(tm);
                if (vt == null) {
                    JsonNode mappingTree = objectMapper.valueToTree(tm);
                    vt = VectorTypeInference.firstElasticsearchVectorType(mappingTree);
                }
                if (vt != null) {
                    metadata.put("vector_type", vt);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get index metadata", e);
        }

        return metadata;
    }

    // ── Custom load (metricsgenreceiver) ─────────────────────────────────────────

    /** ES transform block: sets data-stream routing attributes and strips noise. */
    private static final String ES_TRANSFORM_BLOCK =
            "  transform:\n" +
            "    metric_statements:\n" +
            "      - context: resource\n" +
            "        statements:\n" +
            "          - set(attributes[\"data_stream.dataset\"], \"demo\")\n" +
            "          - set(attributes[\"data_stream.namespace\"], \"default\")\n" +
            "          - delete_key(attributes, \"host.ip\")\n" +
            "          - delete_key(attributes, \"host.mac\")";

    @Override
    public boolean supportsCustomLoad() {
        return true;
    }

    @Override
    public int customLoad(JingraConfig config, DatasetConfig dataset, String indexName) throws Exception {
        MetricsgenConfig cfg = config.getLoad().getMetricsgen();
        String otlpEndpoint = baseUrl + "/_otlp";
        String otelYaml = MetricsgenLoader.renderOtelConfig(
                cfg, otlpEndpoint, "otlphttp/elasticsearch", ES_TRANSFORM_BLOCK);

        Path tmpConfig = Files.createTempFile("metricsgen-", ".yaml");
        try {
            Files.writeString(tmpConfig, otelYaml);
            logger.info("Running metricsgenreceiver → {} (scale={}, window={})...",
                    otlpEndpoint, cfg.scaleOrDefault(), cfg.startNowMinusOrDefault());

            ScheduledExecutorService poller = startEsProgressPoller(indexName);
            try {
                MetricsgenLoader.ProcessResult result = runMetricsgenreceiver(tmpConfig, cfg);
                if (result.exitCode() != 0) {
                    throw new RuntimeException(
                            "metricsgenreceiver exited with code " + result.exitCode()
                                    + "\n" + result.stderr());
                }
                int dp   = MetricsgenLoader.parseDatapoints(result.stderr());
                double rate = MetricsgenLoader.parseRate(result.stderr());
                if (dp > 0) {
                    logger.info("Ingested {} data points ({} dp/s)", dp, String.format("%.0f", rate));
                }
                logger.info("Force-merging '{}' to 1 segment per shard...", indexName);
                forceMergeOperation(indexName);
                logger.info("Force-merge complete.");
                return dp;
            } finally {
                poller.shutdownNow();
                poller.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.deleteIfExists(tmpConfig);
        }
    }

    /**
     * Launches metricsgenreceiver with the given config file and returns its result.
     * Override in tests to inject canned stderr without a real subprocess.
     */
    protected MetricsgenLoader.ProcessResult runMetricsgenreceiver(Path configFile, MetricsgenConfig cfg) throws Exception {
        Path binary = MetricsgenLoader.resolveBinary(cfg.versionOrDefault());
        return MetricsgenLoader.runBinary(binary, configFile);
    }

    /**
     * Force-merges the target index / data stream to {@code max_num_segments=1}.
     * Blocks until the merge completes ({@code wait_for_completion=true}).
     * Protected for test overriding.
     * Called by {@link #awaitIndexReady} after custom load to compact the data before querying.
     * Protected for test overriding.
     */
    protected void forceMergeOperation(String indexName) throws Exception {
        Request req = new Request("POST", "/" + indexName + "/_forcemerge?max_num_segments=1&wait_for_completion=true");
        requireOk(restClient.performRequest(req), "POST /" + indexName + "/_forcemerge");
    }

    /** Initial delay for the ES progress poller, in milliseconds. Override in tests to fire immediately. */
    protected long esProgressPollerInitialDelayMs() { return 10_000L; }
    /** Poll interval for the ES progress poller, in milliseconds. */
    protected long esProgressPollerIntervalMs()     { return 10_000L; }

    /**
     * Called periodically during custom load to log ingest progress.
     * Protected so tests can invoke it directly without waiting for the scheduler.
     */
    @SuppressWarnings("unchecked")
    protected void logEsProgress(String indexName) {
        try {
            Request req = new Request("GET",
                    "/_cat/indices/" + indexName + "?format=json&h=docs.count,store.size");
            Response resp = restClient.performRequest(req);
            byte[] body = resp.getEntity().getContent().readAllBytes();
            List<Map<String, Object>> result = objectMapper.readValue(body,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (!result.isEmpty()) {
                Object docs = result.get(0).get("docs.count");
                Object size = result.get(0).get("store.size");
                logger.info("  ES: {} docs, {} stored", docs, size);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Returns the {@link Runnable} that the progress poller will fire on each tick.
     * Override in tests to assert the poller is wired correctly or to fire it directly.
     */
    protected Runnable esProgressPoller(String indexName) {
        return () -> logEsProgress(indexName);
    }

    private ScheduledExecutorService startEsProgressPoller(String indexName) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metricsgen-progress-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(esProgressPoller(indexName),
                esProgressPollerInitialDelayMs(), esProgressPollerIntervalMs(), TimeUnit.MILLISECONDS);
        return scheduler;
    }

    @Override
    public void close() throws Exception {
        if (restClient != null) {
            restClient.close();
        }
    }
}
