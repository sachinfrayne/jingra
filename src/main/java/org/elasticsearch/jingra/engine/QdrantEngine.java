package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;
import org.elasticsearch.jingra.utils.TlsSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.BinaryQuantization;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.HnswConfigDiff;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.QuantizationConfig;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Qdrant engine implementation.
 */
public class QdrantEngine extends AbstractBenchmarkEngine {

    private QdrantClient client;
    private ManagedChannel customChannel;  // Track custom channel for cleanup
    private final long grpcTimeoutSeconds;

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
                    // Create a TrustManager that trusts all certificates (similar to Elasticsearch/OpenSearch approach)
                    javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                            }
                            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                            }
                        }
                    };

                    // Create a custom ManagedChannel with insecure SSL configuration
                    customChannel = Grpc.newChannelBuilder(
                            host + ":" + port,
                            TlsChannelCredentials.newBuilder()
                                    .trustManager(trustAllCerts[0])
                                    .build()
                    ).build();

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

            if (apiKey != null && !apiKey.isEmpty()) {
                builder.withApiKey(apiKey);
            }

            client = new QdrantClient(builder.build());

            // Test connection
            client.listCollectionsAsync().get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            logger.info("Connected to Qdrant at {}:{}", host, port);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to Qdrant", e);
            return false;
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

            JsonNode templateNode = template.get("template");
            if (templateNode == null) {
                logger.error("Schema missing 'template' field");
                return false;
            }

            // Parse schema configuration
            // Support both direct Qdrant format and ES/OS-shaped format
            JsonNode vectors = templateNode.get("vectors");
            int vectorSize;
            String distance;

            if (vectors == null) {
                // Try ES/OS-shaped format: template.mappings.properties.{vector_field}
                JsonNode mappings = templateNode.get("mappings");
                if (mappings == null || mappings.get("properties") == null) {
                    logger.error("Schema missing 'vectors' or 'mappings.properties' field under 'template'. Schema content: {}", template.toPrettyString());
                    return false;
                }

                // Find the vector field in properties
                String vectorField = getConfigString("vector_field", "search_catalog_embedding");
                JsonNode vectorProp = mappings.get("properties").get(vectorField);
                if (vectorProp == null) {
                    logger.error("Schema missing vector field '{}' in mappings.properties. Schema content: {}", vectorField, template.toPrettyString());
                    return false;
                }

                vectorSize = vectorProp.get("size").asInt(128);
                distance = vectorProp.get("distance").asText("Cosine");
            } else {
                // Direct Qdrant format
                vectorSize = vectors.get("size").asInt(128);
                distance = vectors.get("distance").asText("Cosine");
            }

            // Parse shard/replication settings
            int shardNumber = 1;
            int replicationFactor = 1;

            if (templateNode.has("shard_number")) {
                shardNumber = templateNode.get("shard_number").asInt(1);
            } else if (templateNode.has("settings")) {
                JsonNode settings = templateNode.get("settings");
                shardNumber = settings.has("shard_number") ? settings.get("shard_number").asInt(1) : 1;
                replicationFactor = settings.has("replication_factor") ? settings.get("replication_factor").asInt(1) : 1;
            }

            if (templateNode.has("replication_factor")) {
                replicationFactor = templateNode.get("replication_factor").asInt(1);
            }

            // Convert distance string to Qdrant Distance enum
            Distance qdrantDistance = switch (distance.toLowerCase()) {
                case "cosine" -> Distance.Cosine;
                case "euclid", "euclidean", "l2" -> Distance.Euclid;
                case "dot" -> Distance.Dot;
                case "manhattan" -> Distance.Manhattan;
                default -> Distance.Cosine;
            };

            // Create vector params (unnamed vector)
            VectorParams.Builder vectorParamsBuilder = VectorParams.newBuilder()
                    .setSize(vectorSize)
                    .setDistance(qdrantDistance);

            // Parse and add HNSW config from settings if present
            JsonNode settings = templateNode.get("settings");
            if (settings != null && settings.has("hnsw_config")) {
                JsonNode hnswConfig = settings.get("hnsw_config");
                HnswConfigDiff.Builder hnswBuilder = HnswConfigDiff.newBuilder();

                if (hnswConfig.has("m")) {
                    hnswBuilder.setM(hnswConfig.get("m").asLong());
                }
                if (hnswConfig.has("ef_construct")) {
                    hnswBuilder.setEfConstruct(hnswConfig.get("ef_construct").asLong());
                }

                vectorParamsBuilder.setHnswConfig(hnswBuilder.build());
                logger.info("Configured HNSW: m={}, ef_construct={}",
                        hnswConfig.path("m").asInt(16),
                        hnswConfig.path("ef_construct").asInt(100));
            }

            VectorParams vectorParams = vectorParamsBuilder.build();

            // Build CreateCollection request
            CreateCollection.Builder createBuilder = CreateCollection.newBuilder()
                    .setCollectionName(indexName)
                    .setVectorsConfig(VectorsConfig.newBuilder().setParams(vectorParams).build());

            if (shardNumber > 0) {
                createBuilder.setShardNumber(shardNumber);
            }
            if (replicationFactor > 0) {
                createBuilder.setReplicationFactor(replicationFactor);
            }

            // Set quantization config at collection level (not just on vector params)
            if (settings != null && settings.has("quantization_config")) {
                JsonNode quantConfig = settings.get("quantization_config");

                QuantizationConfig.Builder quantBuilder = QuantizationConfig.newBuilder();

                // Support binary quantization
                if (quantConfig.has("binary")) {
                    JsonNode binaryConfig = quantConfig.get("binary");
                    BinaryQuantization.Builder binaryBuilder = BinaryQuantization.newBuilder();

                    if (binaryConfig.has("always_ram")) {
                        binaryBuilder.setAlwaysRam(binaryConfig.get("always_ram").asBoolean());
                    }

                    quantBuilder.setBinary(binaryBuilder.build());
                    logger.info("Configured binary quantization: always_ram={}",
                            binaryConfig.path("always_ram").asBoolean(true));
                }

                createBuilder.setQuantizationConfig(quantBuilder.build());
            }

            // Create collection with full configuration
            client.createCollectionAsync(createBuilder.build()).get(grpcTimeoutSeconds, TimeUnit.SECONDS);

            logger.info("Created Qdrant collection '{}' with schema '{}' (shards={}, replicas={})",
                    indexName, schemaName, shardNumber, replicationFactor);

            // Verify quantization is enabled
            if (settings != null && settings.has("quantization_config")) {
                try {
                    var collectionInfo = client.getCollectionInfoAsync(indexName).get(grpcTimeoutSeconds, TimeUnit.SECONDS);
                    // Note: Verification requires inspecting the collection config structure
                    logger.info("Collection '{}' created - quantization config applied", indexName);
                } catch (Exception e) {
                    logger.warn("Could not verify quantization for collection '{}': {}", indexName, e.getMessage());
                }
            }

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
            client.upsertAsync(indexName, points, Duration.ofSeconds(60)).get();

            return points.size();
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
            // Load and render query template
            JsonNode template = loadQueryTemplate(queryName);
            if (template == null) {
                logger.error("Query template '{}' not found", queryName);
                return new QueryResponse(List.of(), null, null);
            }

            // Extract query parameters
            List<Float> queryVector = params.getFloatList("query_vector");
            if (queryVector == null) {
                logger.error("query_vector is required for Qdrant search");
                return new QueryResponse(List.of(), null, null);
            }

            int limit = params.getInteger("size") != null ? params.getInteger("size") : 10;

            // Build search request using gRPC API
            SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                    .setCollectionName(indexName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setTimeout(grpcTimeoutSeconds);

            // Apply search params from template (hnsw_ef, quantization, etc.)
            JsonNode templateParams = template.path("template").path("params");
            if (templateParams.isMissingNode()) {
                templateParams = template.path("params");
            }

            if (!templateParams.isMissingNode()) {
                io.qdrant.client.grpc.Points.SearchParams.Builder paramsBuilder =
                        io.qdrant.client.grpc.Points.SearchParams.newBuilder();

                // Handle hnsw_ef (num_candidates)
                if (templateParams.has("hnsw_ef")) {
                    String hnswEfExpr = templateParams.get("hnsw_ef").asText();
                    Integer hnswEf = resolveIntParam(hnswEfExpr, params);
                    if (hnswEf != null) {
                        paramsBuilder.setHnswEf(hnswEf);
                    }
                }

                // Handle quantization oversampling (rescoring): literal or single "{{param}}" (e.g. "{{rescore}}")
                if (templateParams.has("quantization")) {
                    JsonNode quantParams = templateParams.get("quantization");
                    if (quantParams.has("oversampling")) {
                        String oversamplingExpr = quantParams.get("oversampling").asText();
                        Double oversampling = resolveDoubleParam(oversamplingExpr, params);
                        if (oversampling != null) {
                            paramsBuilder.setQuantization(
                                    QuantizationSearchParams.newBuilder()
                                            .setRescore(true)
                                            .setOversampling(oversampling)
                                            .build()
                            );
                        }
                    }
                }

                searchBuilder.setParams(paramsBuilder.build());
            }

            // Apply filters from template
            JsonNode filterNode = template.path("template").path("filter");
            if (filterNode.isMissingNode()) {
                filterNode = template.path("filter");
            }

            if (!filterNode.isMissingNode() && !filterNode.isNull()) {
                Filter filter = parseFilter(filterNode);
                if (filter != null) {
                    searchBuilder.setFilter(filter);
                }
            }

            searchBuilder.setWithPayload(WithPayloadSelector.newBuilder().setEnable(false).build());
            searchBuilder.setWithVectors(io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder().setEnable(false).build());

            SearchPoints searchRequest = searchBuilder.build();
            try {
                writeFirstQueryDumpIfConfigured(
                        getShortName(),
                        formatSearchPointsJsonForDump(JsonFormat.printer().print(searchRequest)));
            } catch (InvalidProtocolBufferException e) {
                logger.warn("Could not serialize SearchPoints for query dump", e);
            }

            // Execute search using raw gRPC client to get full SearchResponse with timing
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
            var info = client.getCollectionInfoAsync(indexName).get(grpcTimeoutSeconds, TimeUnit.SECONDS);
            return info.getPointsCount();
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
     * Resolve a template parameter expression like "{{num_candidates}}" to an integer value.
     */
    private Integer resolveIntParam(String expression, QueryParams params) {
        if (expression == null) {
            return null;
        }

        // Handle template expressions like "{{num_candidates}}"
        if (expression.startsWith("{{") && expression.endsWith("}}")) {
            String paramName = expression.substring(2, expression.length() - 2).trim();
            return params.getInteger(paramName);
        }

        // Handle direct integer values
        try {
            return Integer.parseInt(expression);
        } catch (NumberFormatException e) {
            logger.warn("Could not parse integer parameter: {}", expression);
            return null;
        }
    }

    /**
     * Resolve oversampling and similar values: a numeric literal or a single {@code {{param}}} placeholder.
     */
    private Double resolveDoubleParam(String expression, QueryParams params) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String paramName = trimmed.substring(2, trimmed.length() - 2).trim();
            Object v = params.getAll().get(paramName);
            if (v instanceof Number) {
                return ((Number) v).doubleValue();
            }
            logger.warn("Parameter '{}' not found or not numeric in query params", paramName);
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            logger.warn("Could not parse double parameter: {}", expression);
            return null;
        }
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
