package org.elasticsearch.jingra.utils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone diagnostic: bulk-write probe against Elasticsearch (e.g. from K8s).
 * Not part of the {@code load}/{@code eval} CLI and not included in the shaded application JAR.
 * Run from the IDE using the test classpath, or {@code java -cp ...} with {@code target/test-classes}
 * plus main classes and dependencies.
 */
public class BulkTestUtility {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("RESULTS_ES_URL");
        String user = System.getenv("RESULTS_ES_USER");
        String password = System.getenv("RESULTS_ES_PASSWORD");

        if (url == null || user == null || password == null) {
            System.err.println("ERROR: Set RESULTS_ES_URL, RESULTS_ES_USER, RESULTS_ES_PASSWORD");
            System.exit(1);
        }

        System.out.println("================================================================================");
        System.out.println("K8s Bulk Test to Elastic Cloud");
        System.out.println("================================================================================");
        System.out.println("URL: " + url);
        System.out.println();

        ElasticsearchClientFactory.ElasticsearchClientWrapper wrapper =
                ElasticsearchClientFactory.createClient(url, user, password);
        ElasticsearchClient client = wrapper.getClient();

        String testIndex = "jingra-bulk-test-k8s";

        try {
            // Test 1: Connection
            System.out.println("1. Testing connection...");
            client.info();
            System.out.println("   ✅ Connection successful");
            System.out.println();

            // Test 2: Small batch first (10 docs)
            System.out.println("2. Testing small batch (10 docs with large arrays)...");
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (int i = 0; i < 10; i++) {
                Map<String, Object> queryMetric = new HashMap<>();

                // Context fields (matching production)
                queryMetric.put("run_id", "20260327-k8s-test");
                queryMetric.put("engine", "elasticsearch");
                queryMetric.put("engine_version", "8.17.0");
                queryMetric.put("dataset", "ecommerce-search-128");
                queryMetric.put("@timestamp", java.time.Instant.now().toString());

                // Params map
                Map<String, Object> params = new HashMap<>();
                params.put("size", 100);
                params.put("k", 250);
                params.put("num_candidates", 250);
                params.put("rescore", 1);
                queryMetric.put("params", params);

                // Meta conditions map
                Map<String, Object> metaConditions = new HashMap<>();
                metaConditions.put("category", "electronics");
                metaConditions.put("price_min", 10.0);
                metaConditions.put("price_max", 1000.0);
                queryMetric.put("meta_conditions", metaConditions);

                // Large arrays (100 document IDs each)
                List<String> groundTruth = new ArrayList<>();
                for (int j = 0; j < 100; j++) {
                    groundTruth.add("doc-" + (i * 1000 + j));
                }
                queryMetric.put("ground_truth", groundTruth);

                List<String> retrieved = new ArrayList<>();
                for (int j = 0; j < 100; j++) {
                    retrieved.add("doc-" + (i * 1000 + j + (j % 10)));
                }
                queryMetric.put("retrieved", retrieved);

                // Numeric fields
                queryMetric.put("num_ground_truth", groundTruth.size());
                queryMetric.put("num_retrieved", retrieved.size());
                queryMetric.put("client_latency_ms", 45.0 + Math.random() * 10);
                queryMetric.put("server_latency_ms", 30L + (long)(Math.random() * 5));
                queryMetric.put("precision", 0.85 + Math.random() * 0.1);
                queryMetric.put("recall", 0.90 + Math.random() * 0.08);
                queryMetric.put("relevant_retrieved", 85 + (int)(Math.random() * 10));

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(testIndex)
                                .document(queryMetric)
                        )
                );
            }

            BulkResponse response = client.bulk(bulkBuilder.build());

            if (response.errors()) {
                System.out.println("   ❌ Bulk had errors:");
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .limit(5)
                        .forEach(item -> System.out.println("      - " + item.error().reason()));
            } else {
                System.out.println("   ✅ Bulk write successful (" + response.items().size() + " docs)");
            }
            System.out.println();

            // Test 3: Find batch size threshold
            System.out.println("3. Finding batch size threshold...");
            int[] batchSizes = {5, 10, 20, 50, 100};

            for (int batchSize : batchSizes) {
                System.out.println("   Testing batch size: " + batchSize);
                BulkRequest.Builder thresholdBuilder = new BulkRequest.Builder();

                for (int i = 0; i < batchSize; i++) {
                    Map<String, Object> queryMetric = new HashMap<>();
                    queryMetric.put("run_id", "threshold-test");
                    queryMetric.put("engine", "elasticsearch");
                    queryMetric.put("@timestamp", java.time.Instant.now().toString());

                    Map<String, Object> params = new HashMap<>();
                    params.put("size", 100);
                    queryMetric.put("params", params);

                    List<String> groundTruth = new ArrayList<>();
                    for (int j = 0; j < 100; j++) {
                        groundTruth.add("doc-" + (i * 1000 + j));
                    }
                    queryMetric.put("ground_truth", groundTruth);

                    List<String> retrieved = new ArrayList<>();
                    for (int j = 0; j < 100; j++) {
                        retrieved.add("doc-" + (i * 1000 + j));
                    }
                    queryMetric.put("retrieved", retrieved);

                    queryMetric.put("client_latency_ms", 45.0);
                    queryMetric.put("precision", 0.85);

                    thresholdBuilder.operations(op -> op
                            .index(idx -> idx
                                    .index(testIndex)
                                    .document(queryMetric)
                            )
                    );
                }

                try {
                    BulkResponse thresholdResponse = client.bulk(thresholdBuilder.build());
                    if (thresholdResponse.errors()) {
                        System.out.println("      ❌ Batch size " + batchSize + " had errors");
                        break;
                    } else {
                        System.out.println("      ✅ Batch size " + batchSize + " succeeded");
                    }
                } catch (Exception e) {
                    System.out.println("      ❌ Batch size " + batchSize + " FAILED: " + e.getMessage());
                    break;
                }
            }
            System.out.println();

            // Test 4: Multiple sequential batches (like production)
            System.out.println("4. Testing 10 sequential batches (using successful batch size)...");
            int successfulBatches = 0;
            int failedBatches = 0;

            for (int batchNum = 0; batchNum < 10; batchNum++) {
                BulkRequest.Builder batchBuilder = new BulkRequest.Builder();

                for (int i = 0; i < 100; i++) {
                    Map<String, Object> queryMetric = new HashMap<>();
                    queryMetric.put("run_id", "20260327-k8s-test");
                    queryMetric.put("engine", "elasticsearch");
                    queryMetric.put("batch_num", batchNum);
                    queryMetric.put("@timestamp", java.time.Instant.now().toString());

                    Map<String, Object> params = new HashMap<>();
                    params.put("size", 100);
                    params.put("k", 250);
                    queryMetric.put("params", params);

                    List<String> groundTruth = new ArrayList<>();
                    for (int j = 0; j < 100; j++) {
                        groundTruth.add("doc-batch" + batchNum + "-" + (i * 1000 + j));
                    }
                    queryMetric.put("ground_truth", groundTruth);

                    List<String> retrieved = new ArrayList<>();
                    for (int j = 0; j < 100; j++) {
                        retrieved.add("doc-batch" + batchNum + "-" + (i * 1000 + j));
                    }
                    queryMetric.put("retrieved", retrieved);

                    queryMetric.put("client_latency_ms", 45.0);
                    queryMetric.put("precision", 0.85);

                    batchBuilder.operations(op -> op
                            .index(idx -> idx
                                    .index(testIndex)
                                    .document(queryMetric)
                            )
                    );
                }

                try {
                    BulkResponse batchResponse = client.bulk(batchBuilder.build());
                    if (batchResponse.errors()) {
                        failedBatches++;
                        System.out.println("   ❌ Batch " + (batchNum + 1) + "/10 had errors");
                    } else {
                        successfulBatches++;
                        if ((batchNum + 1) % 2 == 0) {
                            System.out.println("   ✅ Batch " + (batchNum + 1) + "/10 successful");
                        }
                    }
                } catch (Exception e) {
                    failedBatches++;
                    System.out.println("   ❌ Batch " + (batchNum + 1) + "/10 FAILED: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.out.println("      Cause: " + e.getCause().getMessage());
                    }
                }
            }

            System.out.println();
            System.out.println("   Summary: " + successfulBatches + " successful, " + failedBatches + " failed");

        } catch (Exception e) {
            System.out.println("   ❌ FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("      Cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            e.printStackTrace();
        } finally {
            wrapper.close();
        }

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("Test complete!");
        System.out.println("================================================================================");
    }
}
