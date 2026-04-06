package org.elasticsearch.jingra.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexRequest;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.elasticsearch.jingra.utils.ElasticsearchClientFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch engine implementation.
 */
public class ElasticsearchEngine extends AbstractBenchmarkEngine {

    private ElasticsearchClient client;
    private co.elastic.clients.transport.rest5_client.low_level.Rest5Client restClient;
    private String lastQueryJson = null;  // Store last query for reporting
    private String lastIndexName = null;

    public ElasticsearchEngine(Map<String, Object> config) {
        super(config);
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
            // Use shared factory for consistent client configuration
            ElasticsearchClientFactory.ElasticsearchClientWrapper wrapper =
                    ElasticsearchClientFactory.createClient(url, user, password);

            this.client = wrapper.getClient();
            this.restClient = wrapper.getRestClient();

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
    public boolean createIndex(String indexName, String schemaName) {
        if (client == null) {
            logger.error("Elasticsearch client not initialized");
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

            // Create index with schema
            String schemaJson = objectMapper.writeValueAsString(templateNode);
            CreateIndexRequest request = CreateIndexRequest.of(b -> b
                    .index(indexName)
                    .withJson(new StringReader(schemaJson))
            );

            client.indices().create(request);
            logger.info("Created Elasticsearch index '{}' with schema '{}'", indexName, schemaName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to create index '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public boolean indexExists(String indexName) {
        if (client == null) {
            return false;
        }
        try {
            return client.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
        } catch (Exception e) {
            logger.error("Failed to check if index exists", e);
            return false;
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        if (client == null) {
            logger.error("Elasticsearch client not initialized");
            return false;
        }

        try {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(indexName)));
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
    public int ingest(List<Document> documents, String indexName, String idField) {
        if (client == null) {
            logger.error("Elasticsearch client not initialized");
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

            BulkResponse response = client.bulk(bulkBuilder.build());

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
        if (client == null) {
            logger.error("Elasticsearch client not initialized");
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
            SearchResponse<Map> response = client.search(s -> s
                    .index(indexName)
                    .withJson(new StringReader(queryJson)),
                    Map.class
            );
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

    @Override
    public long getDocumentCount(String indexName) {
        if (client == null) {
            return 0;
        }
        try {
            return client.count(c -> c.index(indexName)).count();
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
        if (client == null) {
            return "unknown";
        }
        try {
            return client.info().version().number();
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
     */
    private String formatJsonForDisplay(String json) {
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

        if (client == null) {
            return metadata;
        }

        try {
            var indexResponse = client.indices().get(GetIndexRequest.of(b -> b.index(indexName)));
            var indexInfo = indexResponse.get(indexName);

            if (indexInfo != null && indexInfo.mappings() != null) {
                JsonNode mappingTree = objectMapper.valueToTree(indexInfo.mappings());
                String vt = VectorTypeInference.firstElasticsearchVectorType(mappingTree);
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
