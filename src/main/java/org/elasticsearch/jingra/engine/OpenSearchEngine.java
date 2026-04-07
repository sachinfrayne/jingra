package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.elasticsearch.jingra.utils.TlsSettings;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenSearch engine implementation.
 */
public class OpenSearchEngine extends AbstractBenchmarkEngine {

    private OpenSearchClient client;
    private RestClient restClient;
    private String lastQueryJson = null;  // Store last query for reporting
    private String lastIndexName = null;

    public OpenSearchEngine(Map<String, Object> config) {
        super(config);
    }

    /**
     * Whether API methods that require an initialized client may run.
     * Overridden in same-package tests to exercise branches without a real {@link OpenSearchClient}.
     */
    protected boolean hasClient() {
        return client != null;
    }

    protected boolean indexExistsOperation(String indexName) throws Exception {
        return client.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
    }

    protected void deleteIndexOperation(String indexName) throws Exception {
        client.indices().delete(DeleteIndexRequest.of(b -> b.index(indexName)));
    }

    protected BulkResponse bulkOperation(BulkRequest request) throws Exception {
        return client.bulk(request);
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

    protected Response performRestRequest(Request request) throws IOException {
        return restClient.performRequest(request);
    }

    @Override
    public boolean connect() {
        // Check for direct config values first (useful for testing), then fall back to env vars
        String url = getConfigString("url", null);
        String user = getConfigString("user", null);
        String password = getConfigString("password", null);

        // If not in direct config, read from environment variables
        if (url == null) {
            String urlEnv = getConfigString("url_env", "OPENSEARCH_URL");
            String userEnv = getConfigString("user_env", "OPENSEARCH_USER");
            String passwordEnv = getConfigString("password_env", "OPENSEARCH_PASSWORD");

            url = getEnv(urlEnv, null);
            user = getEnv(userEnv, "admin");
            password = getEnv(passwordEnv, null);

            if (url == null) {
                logger.error("OpenSearch URL not set in config or environment: {}", urlEnv);
                return false;
            }
        }

        try {
            // Parse URL to create HttpHost (HttpClient 5 API)
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
            String hostname = uri.getHost();
            int port = uri.getPort() != -1 ? uri.getPort() : (scheme.equals("https") ? 443 : 9200);

            HttpHost host = new HttpHost(scheme, hostname, port);
            ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(30))
                    .setSocketTimeout(Timeout.ofSeconds(60))
                    .setTimeToLive(TimeValue.ofMinutes(2))
                    .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                    .build();

            PoolingAsyncClientConnectionManagerBuilder cmBuilder = PoolingAsyncClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(connectionConfig)
                    .setMaxConnTotal(50)
                    .setMaxConnPerRoute(50);

            boolean https = "https".equalsIgnoreCase(scheme);
            if (https && TlsSettings.insecureTlsEnabled()) {
                logger.warn("JINGRA_INSECURE_TLS is enabled: TLS verification is disabled (unsafe outside controlled environments)");
                SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial((chain, authType) -> true)
                        .build();
                cmBuilder.setTlsStrategy(ClientTlsStrategyBuilder.create()
                        .setSslContext(sslContext)
                        .setHostnameVerifier(org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE)
                        .build());
            }

            PoolingAsyncClientConnectionManager connectionManager = cmBuilder.build();

            RestClientBuilder builder = RestClient.builder(host)
                    .setRequestConfigCallback(requestConfigBuilder ->
                            requestConfigBuilder
                                    .setResponseTimeout(Timeout.ofMinutes(5))
                                    .setConnectTimeout(Timeout.ofSeconds(30))
                    );

            final String credUser = user;
            final String credPassword = password;
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                httpClientBuilder.setConnectionManager(connectionManager);
                if (credUser != null && credPassword != null) {
                    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    AuthScope authScope = new AuthScope(host);
                    UsernamePasswordCredentials credentials =
                            new UsernamePasswordCredentials(credUser, credPassword.toCharArray());
                    credentialsProvider.setCredentials(authScope, credentials);
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }
                return httpClientBuilder;
            });

            restClient = builder.build();
            RestClientTransport transport = new RestClientTransport(
                    restClient,
                    new JacksonJsonpMapper()
            );
            client = new OpenSearchClient(transport);

            // Test connection
            client.info();
            logger.info("Connected to OpenSearch at {}", url);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to OpenSearch", e);
            return false;
        }
    }

    @Override
    public boolean createIndex(String indexName, String schemaName) {
        if (!hasClient()) {
            logger.error("OpenSearch client not initialized");
            return false;
        }

        try {
            // Check if index already exists
            if (indexExists(indexName)) {
                logger.warn("Index '{}' already exists", indexName);
                return false;
            }

            // Load schema template
            JsonNode template = loadSchemaTemplate(schemaName);
            if (template == null) {
                logger.error("Schema template '{}' not found", schemaName);
                return false;
            }

            // Get the template content
            JsonNode templateNode = template.get("template");
            if (templateNode == null) {
                logger.error("Schema missing 'template' field");
                return false;
            }

            // Create index with schema using low-level REST client
            String schemaJson = objectMapper.writeValueAsString(templateNode);

            Request request = new Request("PUT", "/" + indexName);
            request.setJsonEntity(schemaJson);
            performRestRequest(request);
            logger.info("Created OpenSearch index '{}' with schema '{}'", indexName, schemaName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to create index '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public boolean indexExists(String indexName) {
        if (!hasClient()) {
            return false;
        }
        try {
            return indexExistsOperation(indexName);
        } catch (Exception e) {
            logger.error("Failed to check if index exists", e);
            return false;
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        if (!hasClient()) {
            logger.error("OpenSearch client not initialized");
            return false;
        }

        try {
            deleteIndexOperation(indexName);
            logger.info("Deleted OpenSearch index '{}'", indexName);
            return true;
        } catch (OpenSearchException e) {
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
    public int ingest(List<Document> documents, String indexName, String idField) {
        if (!hasClient()) {
            logger.error("OpenSearch client not initialized");
            return 0;
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

            return count;
        } catch (Exception e) {
            logger.error("Failed to ingest documents", e);
            throw new RuntimeException("Bulk ingest failed", e);
        }
    }

    @Override
    public QueryResponse query(String indexName, String queryName, QueryParams params) {
        if (!hasClient()) {
            logger.error("OpenSearch client not initialized");
            return new QueryResponse(List.of(), null, null);
        }

        try {
            // Load and render query template
            JsonNode template = loadQueryTemplate(queryName);
            if (template == null) {
                logger.error("Query template '{}' not found", queryName);
                return new QueryResponse(List.of(), null, null);
            }

            String queryJson = renderTemplate(template, params.getAll());

            // Store query for reporting (only first query)
            synchronized (this) {
                if (lastQueryJson == null) {
                    lastQueryJson = formatJsonForDisplay(queryJson);
                    lastIndexName = indexName;
                }
            }

            long startTime = System.nanoTime();
            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(queryJson);
            Response response = performRestRequest(request);
            double clientLatencyMs = (System.nanoTime() - startTime) / 1_000_000.0;

            // Parse response
            JsonNode responseJson = objectMapper.readTree(response.getEntity().getContent());

            // Extract document IDs
            List<String> documentIds = new ArrayList<>();
            JsonNode hits = responseJson.path("hits").path("hits");
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    String id = hit.path("_id").asText();
                    if (!id.isEmpty()) {
                        documentIds.add(id);
                    }
                }
            }

            // Get server-side latency (took time in milliseconds)
            Long serverLatencyMs = responseJson.path("took").asLong(0);

            return new QueryResponse(documentIds, clientLatencyMs, serverLatencyMs);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Query execution failed", e);
            return new QueryResponse(List.of(), null, null);
        }
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
        return "opensearch";
    }

    @Override
    public String getShortName() {
        return "os";
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

    @Override
    public String getLastQueryJson() {
        return lastQueryJson;
    }

    @Override
    public String getLastIndexName() {
        return lastIndexName;
    }

    /**
     * Format JSON for pretty-printed console display.
     * Package-private for tests in the same package.
     */
    private static String firstOpenSearchVectorType(TypeMapping mapping) {
        if (mapping == null || mapping.properties() == null) {
            return null;
        }
        for (Property p : mapping.properties().values()) {
            if (p.isKnnVector()) {
                return "knn_vector";
            }
        }
        return null;
    }

    String formatJsonForDisplay(String json) {
        try {
            ObjectMapper prettyMapper = new ObjectMapper();
            Object jsonObject = prettyMapper.readValue(json, Object.class);
            return prettyMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } catch (Exception e) {
            logger.warn("Failed to format query JSON for display", e);
            return json;
        }
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
                String vt = firstOpenSearchVectorType(tm);
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

    @Override
    public void close() throws Exception {
        if (restClient != null) {
            restClient.close();
        }
    }
}
