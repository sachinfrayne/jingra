package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.elasticsearch.jingra.utils.TlsSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.JsonWithInt.NullValue;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.grpc.Points.WriteOrderingType;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant engine implementation.
 */
public class QdrantEngine extends AbstractBenchmarkEngine {

    private QdrantClient client;
    private ManagedChannel customChannel;  // Track custom channel for cleanup
    private final long grpcTimeoutSeconds;
    private String restBaseUrl;
    private String restApiKey;
    private java.net.http.HttpClient restHttpClient;
    private final ConcurrentHashMap<String, CompiledQueryTemplate> compiledQueryTemplates = new ConcurrentHashMap<>();

    private static final WithPayloadSelector NO_PAYLOAD =
            WithPayloadSelector.newBuilder().setEnable(false).build();
    private static final WithPayloadSelector WITH_PAYLOAD =
            WithPayloadSelector.newBuilder().setEnable(true).build();
    private static final io.qdrant.client.grpc.Points.WithVectorsSelector NO_VECTORS =
            io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder().setEnable(false).build();
    private static final io.qdrant.client.grpc.Points.WithVectorsSelector WITH_VECTORS =
            io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder().setEnable(true).build();

    private record IntExpr(Integer literal, String paramName) {
        Integer resolve(QueryParams params) {
            if (literal != null) {
                return literal;
            }
            return paramName != null ? params.getInteger(paramName) : null;
        }
    }

    private record DoubleExpr(Double literal, String paramName) {
        Double resolve(QueryParams params) {
            if (literal != null) {
                return literal;
            }
            if (paramName == null) {
                return null;
            }
            Object v = params.getAll().get(paramName);
            return v instanceof Number ? ((Number) v).doubleValue() : null;
        }
    }

    private record VectorExpr(List<Float> literal, String paramName) {
        List<Float> resolve(QueryParams params) {
            if (literal != null) {
                return literal;
            }
            return paramName != null ? params.getFloatList(paramName) : null;
        }
    }

    private record CompiledQueryTemplate(
            VectorExpr vector,
            IntExpr limit,
            IntExpr hnswEf,
            boolean exact,
            boolean quantRescore,
            DoubleExpr quantOversampling,
            Filter staticFilter,
            boolean withPayload,
            boolean withVector
    ) {}

    private static IntExpr compileIntExpr(String expression) {
        if (expression == null) {
            return new IntExpr(null, null);
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String paramName = trimmed.substring(2, trimmed.length() - 2).trim();
            return new IntExpr(null, paramName.isEmpty() ? null : paramName);
        }
        try {
            return new IntExpr(Integer.parseInt(trimmed), null);
        } catch (NumberFormatException e) {
            return new IntExpr(null, null);
        }
    }

    private static DoubleExpr compileDoubleExpr(String expression) {
        if (expression == null) {
            return new DoubleExpr(null, null);
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String paramName = trimmed.substring(2, trimmed.length() - 2).trim();
            return new DoubleExpr(null, paramName.isEmpty() ? null : paramName);
        }
        try {
            return new DoubleExpr(Double.parseDouble(trimmed), null);
        } catch (NumberFormatException e) {
            return new DoubleExpr(null, null);
        }
    }

    /**
     * Legacy test helper for resolving template integer expressions.
     * Kept for compatibility with same-package unit tests that reflectively invoke it.
     */
    @SuppressWarnings("unused")
    private Integer resolveIntParam(String expression, QueryParams params) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String paramName = trimmed.substring(2, trimmed.length() - 2).trim();
            return paramName.isEmpty() ? null : params.getInteger(paramName);
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Legacy test helper for resolving template double expressions.
     * Kept for compatibility with same-package unit tests that reflectively invoke it.
     */
    @SuppressWarnings("unused")
    private Double resolveDoubleParam(String expression, QueryParams params) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String paramName = trimmed.substring(2, trimmed.length() - 2).trim();
            if (paramName.isEmpty()) {
                return null;
            }
            Object v = params.getAll().get(paramName);
            return v instanceof Number ? ((Number) v).doubleValue() : null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static VectorExpr compileVectorExpr(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new VectorExpr(null, null);
        }
        if (node.isTextual()) {
            String trimmed = node.asText().trim();
            if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
                String paramName = trimmed.substring(2, trimmed.length() - 2).trim();
                return new VectorExpr(null, paramName.isEmpty() ? null : paramName);
            }
            return new VectorExpr(null, null);
        }
        if (node.isArray()) {
            ArrayList<Float> v = new ArrayList<>(node.size());
            for (JsonNode e : node) {
                if (!e.isNumber()) {
                    return new VectorExpr(null, null);
                }
                v.add(e.floatValue());
            }
            return new VectorExpr(List.copyOf(v), null);
        }
        return new VectorExpr(null, null);
    }

    public QdrantEngine(Map<String, Object> config) {
        super(config);
        // Configurable gRPC timeout (default: 30 seconds)
        this.grpcTimeoutSeconds = config.containsKey("grpc_timeout_seconds")
                ? ((Number) config.get("grpc_timeout_seconds")).longValue()
                : 30L;
    }

    /**
     * Whether API methods that require an initialized client may run.
     */
    protected boolean hasClient() {
        return client != null;
    }

    /** Exposes configured gRPC timeout for same-package tests. */
    protected long configuredGrpcTimeoutSeconds() {
        return grpcTimeoutSeconds;
    }

    /**
     * Builds the gRPC {@link ManagedChannel} when an {@code https} URL is used together with
     * {@link TlsSettings#insecureTlsEnabled()}. Subclasses may override to simulate construction failures.
     */
    protected ManagedChannel buildInsecureTlsManagedChannel(String host, int port,
            javax.net.ssl.X509TrustManager trustAll) throws Exception {
        return Grpc.newChannelBuilder(
                host + ":" + port,
                TlsChannelCredentials.newBuilder()
                        .trustManager(trustAll)
                        .build()
        ).build();
    }

    /**
     * Serializes {@link SearchPoints} to protobuf JSON for optional first-query dumps.
     * Subclasses may override to simulate {@link InvalidProtocolBufferException}.
     */
    protected String printSearchPointsForDump(SearchPoints searchRequest) throws InvalidProtocolBufferException {
        return JsonFormat.printer().print(searchRequest);
    }

    @Override
    public boolean connect() {
        // Check for direct config values first (useful for testing), then fall back to env vars
        String url = getConfigString("url", null);
        String apiKey = getConfigString("api_key", null);

        // If not in direct config, read from environment variables
        if (url == null) {
            String urlEnv = getConfigString("url_env", "QDRANT_URL");
            String apiKeyEnv = getConfigString("api_key_env", "QDRANT_API_KEY");

            url = getEnv(urlEnv, null);
            apiKey = getEnv(apiKeyEnv, "");

            if (url == null) {
                logger.error("Qdrant URL not set in config or environment: {}", urlEnv);
                return false;
            }
        }

        // Default API key if still null
        if (apiKey == null) {
            apiKey = "";
        }

        try {
            boolean useTls = url.startsWith("https://");

            String host = url.replace("http://", "").replace("https://", "");
            int port = 6334;

            if (host.contains(":")) {
                String[] parts = host.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            }

            if (useTls) {
                logger.info("Using TLS for Qdrant gRPC (https URL); ensure port matches your Qdrant gRPC TLS endpoint");
            }

            QdrantGrpcClient.Builder builder;

            // Configure insecure TLS if enabled (trust self-signed certificates)
            if (useTls && TlsSettings.insecureTlsEnabled()) {
                logger.warn("JINGRA_INSECURE_TLS enabled: accepting self-signed certificates (insecure, MITM possible)");
                try {
                    javax.net.ssl.X509TrustManager trustAll = TlsSettings.insecureTrustAllX509TrustManager();

                    customChannel = buildInsecureTlsManagedChannel(host, port, trustAll);

                    // Create QdrantGrpcClient with the custom channel
                    builder = QdrantGrpcClient.newBuilder(customChannel);
                } catch (Exception e) {
                    logger.error("Failed to configure insecure SSL for Qdrant", e);
                    return false;
                }
            } else {
                // Use default builder without custom SSL
                builder = QdrantGrpcClient.newBuilder(host, port, useTls);
            }

            if (!apiKey.isEmpty()) {
                builder.withApiKey(apiKey);
            }

            client = new QdrantClient(builder.build());

            // Store REST base URL for collection creation.
            // Allow explicit override via "rest_url" config (required when Testcontainers maps ports).
            // Otherwise default to standard Qdrant HTTP port 6333.
            String restUrlOverride = getConfigString("rest_url", null);
            if (restUrlOverride != null) {
                restBaseUrl = restUrlOverride;
            } else {
                String scheme = useTls ? "https" : "http";
                restBaseUrl = scheme + "://" + host + ":6333";
            }
            restApiKey = apiKey;

            // Build REST HTTP client (stored so it can be replaced in tests)
            java.net.http.HttpClient.Builder httpClientBuilder = java.net.http.HttpClient.newBuilder();
            if (useTls && TlsSettings.insecureTlsEnabled()) {
                javax.net.ssl.X509TrustManager trustAll = TlsSettings.insecureTrustAllX509TrustManager();
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, new javax.net.ssl.TrustManager[]{trustAll}, null);
                httpClientBuilder.sslContext(sslContext);
            }
            restHttpClient = httpClientBuilder.build();

            // Test connection
            client.listCollectionsAsync().get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            logger.info("Connected to Qdrant at {}:{}", host, port);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to Qdrant", e);
            return false;
        }
    }

    /**
     * Returns a copy of templateNode with vectors.distance normalized to Qdrant's capitalized form
     * (e.g. "dot" → "Dot", "l2" / "euclidean" → "Euclid"). Unknown values default to "Cosine".
     * Returns the original node unchanged if no normalization is needed.
     */
    private JsonNode normalizeVectorDistance(JsonNode templateNode) {
        JsonNode vectors = templateNode.get("vectors");
        if (vectors == null || !vectors.has("distance")) {
            return templateNode;
        }
        String raw = vectors.get("distance").asText("");
        String normalized = switch (raw.toLowerCase()) {
            case "cosine" -> "Cosine";
            case "euclid", "euclidean", "l2" -> "Euclid";
            case "dot" -> "Dot";
            case "manhattan" -> "Manhattan";
            default -> "Cosine";
        };
        if (normalized.equals(raw)) {
            return templateNode;
        }
        com.fasterxml.jackson.databind.node.ObjectNode copy = templateNode.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get("vectors")).put("distance", normalized);
        return copy;
    }

    /**
     * Converts an ES/OS-format schema (mappings.properties) into a minimal Qdrant REST schema.
     * Returns null and logs an error if the vector field cannot be found.
     */
    private JsonNode convertMappingsToQdrantSchema(JsonNode templateNode) {
        JsonNode mappings = templateNode.get("mappings");
        JsonNode properties = mappings != null ? mappings.get("properties") : null;
        if (properties == null) {
            logger.error("ES/OS-format schema missing 'mappings.properties'. Schema: {}", templateNode.toPrettyString());
            return null;
        }
        String vectorField = getConfigString("vector_field", "search_catalog_embedding");
        JsonNode vectorProp = properties.get(vectorField);
        if (vectorProp == null) {
            logger.error("ES/OS-format schema missing vector field '{}' in mappings.properties. Schema: {}",
                    vectorField, templateNode.toPrettyString());
            return null;
        }
        int vectorSize = vectorProp.path("size").asInt(128);
        String rawDistance = vectorProp.path("distance").asText("Cosine");
        String distance = switch (rawDistance.toLowerCase()) {
            case "cosine" -> "Cosine";
            case "euclid", "euclidean", "l2" -> "Euclid";
            case "dot" -> "Dot";
            case "manhattan" -> "Manhattan";
            default -> "Cosine";
        };
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode qdrantSchema = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode vectorsNode = mapper.createObjectNode();
        vectorsNode.put("size", vectorSize);
        vectorsNode.put("distance", distance);
        qdrantSchema.set("vectors", vectorsNode);
        return qdrantSchema;
    }

    private void createCollectionViaRest(String indexName, JsonNode templateNode) throws Exception {
        java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(restBaseUrl + "/collections/" + indexName))
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(templateNode.toString()));

        if (restApiKey != null && !restApiKey.isEmpty()) {
            reqBuilder.header("api-key", restApiKey);
        }

        java.net.http.HttpResponse<String> response = restHttpClient
                .send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Qdrant REST PUT /collections/" + indexName
                    + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    @Override
    public boolean createIndex(String indexName, String schemaName) {
        if (!hasClient()) {
            logger.error("Qdrant client not initialized");
            return false;
        }

        try {
            // Check if collection already exists
            if (indexExists(indexName)) {
                logger.warn("Collection '{}' already exists", indexName);
                return false;
            }

            // Load schema template
            JsonNode template = loadSchemaTemplate(schemaName);
            if (template == null) {
                logger.error("Schema template '{}' not found", schemaName);
                return false;
            }

            // Support both legacy Jingra-wrapped schemas ({name, template:{...}}) and direct Qdrant-console schemas ({...}).
            JsonNode templateNode = template.has("template") ? template.get("template") : template;

            // Pre-process the schema before sending to Qdrant REST:
            // 1. Require either "vectors" (direct format) or "mappings" (ES/OS format).
            // 2. Normalize the distance string to Qdrant's expected capitalization
            //    (e.g. "dot" -> "Dot", "l2" -> "Euclid") so schemas can use lowercase aliases.
            JsonNode schemaForRest;
            if (templateNode.has("vectors")) {
                schemaForRest = normalizeVectorDistance(templateNode);
            } else if (templateNode.has("mappings")) {
                schemaForRest = convertMappingsToQdrantSchema(templateNode);
                if (schemaForRest == null) {
                    return false;
                }
            } else {
                logger.error("Schema must have 'vectors' (direct Qdrant format) or 'mappings' (ES/OS format). Schema: {}",
                        templateNode.toPrettyString());
                return false;
            }

            // Create collection by posting the schema JSON directly to the Qdrant REST API.
            // This means any field in the schema file is passed through as-is — no code
            // changes needed when Qdrant adds new quantization or config options.
            createCollectionViaRest(indexName, schemaForRest);

            logger.info("Created Qdrant collection '{}' with schema '{}'", indexName, schemaName);

            // Create payload indexes if specified
            // Support both direct format (payload_indexes array) and ES/OS format (mappings.properties)
            if (templateNode.has("payload_indexes")) {
                // Direct format
                JsonNode payloadIndexes = templateNode.get("payload_indexes");
                for (JsonNode indexSpec : payloadIndexes) {
                    String fieldName = indexSpec.get("field_name").asText();
                    String fieldSchema = indexSpec.get("field_schema").asText();
                    createPayloadIndex(indexName, fieldName, fieldSchema);
                }
            } else if (templateNode.has("mappings")) {
                // ES/OS format - create indexes for non-vector fields
                JsonNode properties = templateNode.get("mappings").get("properties");
                String vectorField = getConfigString("vector_field", "search_catalog_embedding");

                properties.fields().forEachRemaining(entry -> {
                    String fieldName = entry.getKey();
                    JsonNode fieldDef = entry.getValue();

                    // Skip the vector field itself
                    if (fieldName.equals(vectorField)) {
                        return;
                    }

                    String fieldType = fieldDef.get("type").asText();
                    try {
                        createPayloadIndex(indexName, fieldName, fieldType);
                    } catch (Exception e) {
                        logger.warn("Failed to create payload index for field '{}': {}", fieldName, e.getMessage());
                    }
                });
            }

            return true;
        } catch (Exception e) {
            logger.error("Failed to create collection '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public boolean indexExists(String indexName) {
        if (!hasClient()) {
            return false;
        }
        try {
            var collections = client.listCollectionsAsync().get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            return collections.stream()
                    .anyMatch(c -> c.equals(indexName));
        } catch (Exception e) {
            logger.error("Failed to check if collection exists", e);
            return false;
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        if (!hasClient()) {
            logger.error("Qdrant client not initialized");
            return false;
        }

        try {
            client.deleteCollectionAsync(indexName).get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            logger.info("Deleted Qdrant collection '{}'", indexName);
            return true;
        } catch (Exception e) {
            if (isNotFound(e)) {
                logger.info("Collection '{}' did not exist (delete idempotent)", indexName);
                return true;
            }
            logger.error("Failed to delete collection '{}'", indexName, e);
            return false;
        }
    }

    @Override
    public int ingest(List<Document> documents, String indexName, String idField) {
        if (!hasClient()) {
            logger.error("Qdrant client not initialized");
            return 0;
        }

        try {
            List<PointStruct> points = new ArrayList<>();
            String vectorField = getConfigString("vector_field", "search_catalog_embedding");

            for (Document doc : documents) {
                // Get document ID from idField (e.g., "catalog_id")
                // Qdrant supports both integer and UUID point IDs
                PointId pointId;
                String docIdStr;

                if (idField != null && doc.containsField(idField)) {
                    Object idValue = doc.get(idField);

                    // Try to use as integer ID if possible (more efficient)
                    try {
                        long numId = (idValue instanceof Number)
                                ? ((Number) idValue).longValue()
                                : Long.parseLong(idValue.toString());
                        pointId = PointId.newBuilder().setNum(numId).build();
                        docIdStr = String.valueOf(numId);
                    } catch (NumberFormatException e) {
                        // Not a number, treat as UUID string
                        String uuidStr = idValue.toString();
                        try {
                            java.util.UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException ex) {
                            // Not a valid UUID, generate one deterministically
                            uuidStr = java.util.UUID.nameUUIDFromBytes(uuidStr.getBytes()).toString();
                        }
                        pointId = PointId.newBuilder().setUuid(uuidStr).build();
                        docIdStr = uuidStr;
                    }
                } else {
                    // No idField specified, generate random UUID
                    String uuidStr = java.util.UUID.randomUUID().toString();
                    pointId = PointId.newBuilder().setUuid(uuidStr).build();
                    docIdStr = uuidStr;
                }

                // Get vector
                List<Float> vector = doc.getFloatList(vectorField);
                if (vector == null) {
                    // Try double list and convert
                    List<Double> doubleVec = doc.getDoubleList(vectorField);
                    if (doubleVec != null) {
                        vector = doubleVec.stream().map(Double::floatValue).toList();
                    }
                }

                if (vector == null) {
                    logger.warn("Document {} missing vector field '{}'", docIdStr, vectorField);
                    continue;
                }

                // Build payload (all fields except vector)
                Map<String, Value> payload = new HashMap<>();
                for (Map.Entry<String, Object> entry : doc.getFields().entrySet()) {
                    if (!entry.getKey().equals(vectorField)) {
                        payload.put(entry.getKey(), convertToQdrantValue(entry.getValue()));
                    }
                }

                // Create point
                PointStruct point = PointStruct.newBuilder()
                        .setId(pointId)
                        .setVectors(Vectors.newBuilder().setVector(
                                Vector.newBuilder().addAllData(vector).build()
                        ).build())
                        .putAllPayload(payload)
                        .build();

                points.add(point);
            }

            // Upsert points (API changed in 1.17.0 - third parameter is Duration)
            return runOnceWithTransportReconnect(() -> {
                client.upsertAsync(indexName, points, Duration.ofSeconds(60)).get();
                return points.size();
            });
        } catch (Exception e) {
            logger.error("Failed to ingest documents", e);
            return 0;
        }
    }

    @Override
    public QueryResponse query(String indexName, String queryName, QueryParams params) {
        if (!hasClient()) {
            logger.error("Qdrant client not initialized");
            return new QueryResponse(List.of(), null, null);
        }

        try {
            // Load query template (cached; avoid per-query I/O / JSON parse)
            JsonNode template = loadQueryTemplateCached(queryName);
            if (template == null) {
                logger.error("Query template '{}' not found", queryName);
                return new QueryResponse(List.of(), null, null);
            }

            CompiledQueryTemplate compiled = compiledQueryTemplates.computeIfAbsent(queryName, ignored -> compileTemplate(template));

            // Resolve request from query JSON (source of truth), with {{placeholders}} filled from QueryParams
            List<Float> queryVector = compiled.vector != null ? compiled.vector.resolve(params) : null;
            if (queryVector == null) {
                // Back-compat: older harnesses always supply query_vector directly
                queryVector = params.getFloatList("query_vector");
            }
            if (queryVector == null) {
                logger.error("query_vector is required for Qdrant search");
                return new QueryResponse(List.of(), null, null);
            }

            Integer limit = compiled.limit != null ? compiled.limit.resolve(params) : null;
            if (limit == null) {
                // Back-compat: older harnesses always supply size directly
                limit = params.getInteger("size");
            }
            if (limit == null) {
                limit = 10;
            }

            // Build search request using gRPC API
            SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                    .setCollectionName(indexName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setTimeout(grpcTimeoutSeconds);

            // Apply search params (compiled once; resolve cheap per-query)
            io.qdrant.client.grpc.Points.SearchParams.Builder paramsBuilder =
                    io.qdrant.client.grpc.Points.SearchParams.newBuilder();
            if (compiled.exact) {
                paramsBuilder.setExact(true);
            } else {
                Integer hnswEf = compiled.hnswEf != null ? compiled.hnswEf.resolve(params) : null;
                if (hnswEf != null) {
                    paramsBuilder.setHnswEf(hnswEf);
                }
                Double oversampling = compiled.quantOversampling != null ? compiled.quantOversampling.resolve(params) : null;
                if (oversampling != null) {
                    paramsBuilder.setQuantization(
                            QuantizationSearchParams.newBuilder()
                                    .setRescore(compiled.quantRescore)
                                    .setOversampling(oversampling)
                                    .build()
                    );
                }
            }
            searchBuilder.setParams(paramsBuilder.build());

            if (compiled.staticFilter != null) {
                searchBuilder.setFilter(compiled.staticFilter);
            }

            searchBuilder.setWithPayload(compiled.withPayload ? WITH_PAYLOAD : NO_PAYLOAD);
            searchBuilder.setWithVectors(compiled.withVector ? WITH_VECTORS : NO_VECTORS);

            SearchPoints searchRequest = searchBuilder.build();
            if (shouldWriteFirstQueryDump()) {
                try {
                    writeFirstQueryDumpIfConfigured(
                            getShortName(),
                            formatSearchPointsJsonForDump(printSearchPointsForDump(searchRequest)));
                } catch (InvalidProtocolBufferException e) {
                    logger.warn("Could not serialize SearchPoints for query dump", e);
                }
            }

            // Execute search using raw gRPC client to get full SearchResponse with timing
            return runOnceWithTransportReconnect(() -> {
                long startTime = System.nanoTime();
                io.qdrant.client.grpc.Points.SearchResponse fullResponse = client.grpcClient()
                        .points()
                        .search(searchRequest)
                        .get(grpcTimeoutSeconds, TimeUnit.SECONDS);
                double clientLatencyMs = (System.nanoTime() - startTime) / 1_000_000.0;

                // Extract server latency (time is in seconds, convert to milliseconds)
                // Round to nearest millisecond instead of truncating
                Long serverLatencyMs = Math.round(fullResponse.getTime() * 1000.0);

                // Extract document IDs (handle both UUID and numeric IDs)
                List<String> documentIds = new ArrayList<>();
                for (var scoredPoint : fullResponse.getResultList()) {
                    PointId id = scoredPoint.getId();
                    String docId;
                    switch (id.getPointIdOptionsCase()) {
                        case UUID -> docId = id.getUuid();
                        case NUM -> docId = String.valueOf(id.getNum());
                        default -> {
                            logger.warn("Unknown point ID type: {}", id.getPointIdOptionsCase());
                            docId = id.toString();
                        }
                    }
                    documentIds.add(docId);
                }

                return new QueryResponse(documentIds, clientLatencyMs, serverLatencyMs);
            });
        } catch (Exception e) {
            logger.error("Query execution failed", e);
            return new QueryResponse(List.of(), null, null);
        }
    }

    private CompiledQueryTemplate compileTemplate(JsonNode template) {
        JsonNode templateNode = template.path("template");
        if (templateNode.isMissingNode()) {
            templateNode = template;
        }

        JsonNode vectorNode = templateNode.get("vector");
        VectorExpr vector =
                (vectorNode == null || vectorNode.isNull()) ? null : compileVectorExpr(vectorNode);

        JsonNode limitNode = templateNode.get("limit");
        IntExpr limit = limitNode == null || limitNode.isNull()
                ? null
                : compileIntExpr(limitNode.asText());

        // Apply search params from template (hnsw_ef, quantization, etc.)
        JsonNode templateParams = templateNode.path("params");

        boolean exact = templateParams.has("exact") && templateParams.get("exact").asBoolean(false);

        IntExpr hnswEf = null;
        DoubleExpr oversampling = null;
        boolean rescore = true;
        if (!exact) {
            if (templateParams.has("hnsw_ef")) {
                hnswEf = compileIntExpr(templateParams.get("hnsw_ef").asText());
            }
            if (templateParams.has("quantization")) {
                JsonNode quantParams = templateParams.get("quantization");
                if (quantParams.has("rescore")) {
                    rescore = quantParams.get("rescore").asBoolean(true);
                }
                if (quantParams.has("oversampling")) {
                    oversampling = compileDoubleExpr(quantParams.get("oversampling").asText());
                }
            }
        }

        // Compile static filter if present (assumes no per-query placeholders inside filter)
        JsonNode filterNode = templateNode.path("filter");
        Filter staticFilter = null;
        if (!filterNode.isMissingNode() && !filterNode.isNull()) {
            staticFilter = parseFilter(filterNode);
        }

        boolean withPayload = templateNode.path("with_payload").asBoolean(false);
        boolean withVector = templateNode.path("with_vector").asBoolean(false);

        return new CompiledQueryTemplate(vector, limit, hnswEf, exact, rescore, oversampling, staticFilter, withPayload, withVector);
    }

    @Override
    public long getDocumentCount(String indexName) {
        if (!hasClient()) {
            return 0;
        }
        try {
            return runOnceWithTransportReconnect(() -> {
                var info = client.getCollectionInfoAsync(indexName).get(grpcTimeoutSeconds, TimeUnit.SECONDS);
                return info.getPointsCount();
            });
        } catch (Exception e) {
            logger.error("Failed to get document count", e);
            return 0;
        }
    }

    @Override
    public String getEngineName() {
        return "qdrant";
    }

    @Override
    public String getShortName() {
        return "qd";
    }

    @Override
    public String getVersion() {
        if (!hasClient()) {
            return "unknown";
        }
        try {
            var health = client.healthCheckAsync().get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            return health.getVersion();
        } catch (Exception e) {
            logger.error("Failed to get version", e);
            return "unknown";
        }
    }

    @Override
    public Map<String, String> getIndexMetadata(String indexName) {
        Map<String, String> metadata = new HashMap<>();

        if (!hasClient()) {
            return metadata;
        }

        try {
            var info = client.getCollectionInfoAsync(indexName).get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            if (info != null) {
                metadata.put("points_count", String.valueOf(info.getPointsCount()));
            }
        } catch (Exception e) {
            logger.error("Failed to get collection metadata", e);
        }

        return metadata;
    }

    @Override
    public void close() throws Exception {
        if (hasClient()) {
            client.close();
        }
        if (customChannel != null) {
            customChannel.shutdown();
            customChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Parse filter from JSON template into Qdrant Filter object.
     * Supports the format from k8s config: filter.must[].{key, match.value}
     */
    private Filter parseFilter(JsonNode filterNode) {
        try {
            Filter.Builder filterBuilder = Filter.newBuilder();

            // Handle "must" conditions (AND logic)
            if (filterNode.has("must") && filterNode.get("must").isArray()) {
                for (JsonNode condition : filterNode.get("must")) {
                    if (condition.has("key") && condition.has("match")) {
                        String key = condition.get("key").asText();
                        JsonNode matchNode = condition.get("match");

                        if (matchNode.has("value")) {
                            JsonNode valueNode = matchNode.get("value");
                            Match match = buildMatch(valueNode);

                            if (match != null) {
                                FieldCondition fieldCondition = FieldCondition.newBuilder()
                                        .setKey(key)
                                        .setMatch(match)
                                        .build();

                                filterBuilder.addMust(
                                        Condition.newBuilder()
                                                .setField(fieldCondition)
                                                .build()
                                );
                            }
                        }
                    }
                }
            }

            Filter filter = filterBuilder.build();
            return filter.getMustCount() > 0 ? filter : null;
        } catch (Exception e) {
            logger.error("Failed to parse filter from template", e);
            return null;
        }
    }

    /**
     * Build a Match object from a JSON value node.
     */
    private Match buildMatch(JsonNode valueNode) {
        Match.Builder matchBuilder = Match.newBuilder();

        if (valueNode.isBoolean()) {
            matchBuilder.setBoolean(valueNode.asBoolean());
        } else if (valueNode.isIntegralNumber()) {
            matchBuilder.setInteger(valueNode.asLong());
        } else if (valueNode.isNumber()) {
            // Floating point numbers are not directly supported in Match
            // Convert to integer if whole number, otherwise to string
            double value = valueNode.asDouble();
            if (value == Math.floor(value)) {
                matchBuilder.setInteger((long) value);
            } else {
                matchBuilder.setKeyword(String.valueOf(value));
            }
        } else if (valueNode.isTextual()) {
            matchBuilder.setKeyword(valueNode.asText());
        } else {
            logger.warn("Unsupported match value type: {}", valueNode.getNodeType());
            return null;
        }

        return matchBuilder.build();
    }

    /**
     * Create a payload index for a field to enable filtering.
     */
    private void createPayloadIndex(String indexName, String fieldName, String fieldType) throws Exception {
        PayloadSchemaType schemaType = switch (fieldType.toLowerCase()) {
            case "keyword" -> PayloadSchemaType.Keyword;
            case "integer" -> PayloadSchemaType.Integer;
            case "float" -> PayloadSchemaType.Float;
            case "bool", "boolean" -> PayloadSchemaType.Bool;
            case "geo" -> PayloadSchemaType.Geo;
            case "text" -> PayloadSchemaType.Text;
            default -> PayloadSchemaType.Keyword;
        };

        client.createPayloadIndexAsync(
                indexName,
                fieldName,
                schemaType,
                PayloadIndexParams.newBuilder().build(),
                true,
                WriteOrderingType.Weak,
                Duration.ofSeconds(60)
        ).get();

        logger.info("Created payload index on '{}' (type: {})", fieldName, fieldType);
    }

    /**
     * Protobuf {@link JsonFormat} emits int64/uint64 as JSON strings and always includes
     * {@code collectionName}. For dumps we omit the collection (caller already knows it), and
     * coerce integer fields to JSON numbers for readability.
     */
    private String formatSearchPointsJsonForDump(String protoJson) {
        try {
            JsonNode rootNode = objectMapper.readTree(protoJson);
            if (!(rootNode instanceof ObjectNode root)) {
                return protoJson;
            }
            root.remove("collectionName");
            coerceQuotedIntegral(root, "limit");
            coerceQuotedIntegral(root, "timeout");
            JsonNode params = root.get("params");
            if (params instanceof ObjectNode paramsObj) {
                coerceQuotedIntegral(paramsObj, "hnswEf");
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            logger.warn("Could not normalize SearchPoints JSON for dump: {}", e.toString());
            return protoJson;
        }
    }

    private static void coerceQuotedIntegral(ObjectNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            return;
        }
        try {
            node.put(field, Long.parseLong(v.asText()));
        } catch (NumberFormatException ignored) {
            // leave as-is
        }
    }

    private static boolean isNotFound(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof StatusRuntimeException sre) {
                return sre.getStatus().getCode() == Status.NOT_FOUND.getCode();
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Detects transient gRPC transport failures where reopening the channel often succeeds
     * (e.g. {@code INTERNAL: Encountered end-of-stream mid-frame} with the Java client against Qdrant).
     */
    private static boolean isBrokenGrpcTransport(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof StatusRuntimeException sre) {
                Status.Code code = sre.getStatus().getCode();
                String desc = sre.getStatus().getDescription();
                if (code == Status.Code.UNAVAILABLE) {
                    return true;
                }
                if (code == Status.Code.INTERNAL && desc != null && desc.contains("end-of-stream")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void resetTransportAfterFailure() {
        try {
            if (hasClient()) {
                client.close();
            }
        } catch (Exception e) {
            logger.debug("Ignoring error closing Qdrant client during transport reset: {}", e.toString());
        }
        client = null;
        if (customChannel != null) {
            try {
                if (!customChannel.isShutdown()) {
                    customChannel.shutdown();
                }
                if (!customChannel.awaitTermination(5, TimeUnit.SECONDS)) {
                    customChannel.shutdownNow();
                }
            } catch (Exception e) {
                logger.debug("Ignoring error shutting down gRPC channel during transport reset: {}", e.toString());
            }
            customChannel = null;
        }
    }

    private boolean reconnectToQdrant() {
        resetTransportAfterFailure();
        return connect();
    }

    private <T> T runOnceWithTransportReconnect(Callable<T> action) throws Exception {
        try {
            return action.call();
        } catch (Exception e) {
            if (isBrokenGrpcTransport(e) && reconnectToQdrant()) {
                return action.call();
            }
            throw e;
        }
    }

    /**
     * Convert a Java object to Qdrant Value.
     */
    private Value convertToQdrantValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        } else if (obj instanceof Boolean) {
            return Value.newBuilder().setBoolValue((Boolean) obj).build();
        } else if (obj instanceof Integer) {
            return Value.newBuilder().setIntegerValue((Integer) obj).build();
        } else if (obj instanceof Long) {
            return Value.newBuilder().setIntegerValue((Long) obj).build();
        } else if (obj instanceof Double) {
            return Value.newBuilder().setDoubleValue((Double) obj).build();
        } else if (obj instanceof String) {
            return Value.newBuilder().setStringValue((String) obj).build();
        } else {
            // Default to string representation
            return Value.newBuilder().setStringValue(obj.toString()).build();
        }
    }
}
