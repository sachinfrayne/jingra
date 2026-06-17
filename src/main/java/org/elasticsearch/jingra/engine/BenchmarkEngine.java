package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.config.DatasetConfig;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.model.Document;
import org.elasticsearch.jingra.model.QueryParams;
import org.elasticsearch.jingra.model.QueryResponse;

import java.util.List;
import java.util.Map;

/**
 * Core abstraction for benchmarkable engines.
 * Supports vector search engines, observability platforms, time-series databases, etc.
 */
public interface BenchmarkEngine extends AutoCloseable {

    /**
     * Connect to the engine.
     *
     * @return true if connection successful
     */
    boolean connect();

    /**
     * Create an index/collection/table with the given schema.
     * Engines that have no concept of named indices (e.g. time-series backends) can rely on
     * this default no-op which signals success without doing anything.
     *
     * @param indexName the name of the index
     * @param schemaName the name of the schema template
     * @return true if created successfully
     */
    default boolean createDataStore(String indexName, String schemaName) {
        return true;
    }

    /**
     * Create a data store for the given dataset, using all dataset-level config
     * (schema name, ILM policy, index template, etc.).
     * Default delegates to {@link #createDataStore(String, String)}.
     */
    default boolean createDataStore(String indexName, org.elasticsearch.jingra.config.DatasetConfig dataset) {
        return createDataStore(indexName, dataset != null ? dataset.getSchemaName() : null);
    }

    /**
     * Check if an index exists.
     *
     * @param indexName the index name
     * @return true if the index exists
     */
    boolean dataStoreExists(String indexName);

    /**
     * Delete an index.
     *
     * @param indexName the index name
     * @return true if deletion successful
     */
    boolean resetDataStore(String indexName);

    /**
     * Ingest documents into the index.
     * The engine is agnostic to the data source - documents are provided by data readers.
     *
     * @param documents list of documents to ingest
     * @param indexName the index name
     * @param idField optional field name to use as document ID
     * @return number of documents successfully ingested
     */
    int ingest(List<Document> documents, String indexName, String idField);

    /**
     * Insert documents using {@code op_type: create}. No explicit document ID is set; the engine
     * derives identity from the document content (e.g. Elasticsearch TSID for data streams).
     * Required for data streams which reject {@code index} operations.
     *
     * <p>Default delegates to {@link #ingest} so engines that do not distinguish between
     * create and index get the existing behaviour for free.</p>
     */
    default int create(List<Document> documents, String indexName, String idField) {
        return ingest(documents, indexName, idField);
    }

    /**
     * Execute a query against the engine.
     *
     * @param indexName the index name
     * @param queryName the query template name
     * @param params query parameters
     * @return query response with results and metrics
     */
    QueryResponse query(String indexName, String queryName, QueryParams params);

    /**
     * Get the count of documents in an index.
     *
     * @param indexName the index name
     * @return document count
     */
    long getDocumentCount(String indexName);

    /**
     * Get the engine name (e.g., "elasticsearch", "opensearch", "qdrant").
     *
     * @return engine name
     */
    String getEngineName();

    /**
     * Get the short name for the engine (e.g., "es", "os", "qd").
     *
     * @return short name
     */
    String getShortName();

    /**
     * Get the engine version.
     *
     * @return version string
     */
    String getVersion();

    /**
     * Get additional metadata about the index configuration.
     *
     * @param indexName the index name
     * @return metadata map (e.g., vector type, compression, etc.)
     */
    Map<String, String> getIndexMetadata(String indexName);

    /**
     * Get the schema template as a Map (for storing in benchmark results).
     * Returns the "template" object containing mappings and settings.
     *
     * @param schemaName the schema name
     * @return schema template as Map with mappings and settings, or null if not found
     */
    Map<String, Object> getSchemaTemplate(String schemaName);

    /**
     * Wait until the index is ready for querying (all background optimisation / merges have settled).
     * Engines that do not need to wait can rely on this default no-op implementation.
     *
     * @param indexName the index to wait on
     */
    default void awaitIndexReady(String indexName) {
        // no-op by default — only implemented by engines that need to wait for background work
    }

    /**
     * Whether this engine uses the index lifecycle (create / exists-check / delete) that
     * {@code LoadCommand} manages.  Schema-full engines (Elasticsearch, OpenSearch, Qdrant)
     * return {@code true}; schema-free / TSDB engines (Prometheus) return {@code false} and
     * handle data clearing themselves inside {@link #resetDataStore}.
     */
    default boolean supportsIndexLifecycle() {
        return true;
    }

    /**
     * Whether this engine can drive data ingest via the {@code metricsgenreceiver} binary
     * instead of the standard file-batch path. When {@code true} and {@code load.metricsgen}
     * is configured, {@code LoadCommand} calls {@link #customLoad} instead of reading files.
     */
    default boolean supportsCustomLoad() {
        return false;
    }

    /**
     * Drive data ingest via an external generator (e.g. {@code metricsgenreceiver}).
     * Called by {@code LoadCommand} only when {@link #supportsCustomLoad()} is {@code true}
     * and {@code load.metricsgen} is present in config. The engine is responsible for
     * launching the binary, streaming its output, and returning the number of data points
     * (or documents) ingested so that the summary log can report them.
     *
     * @param config    full benchmark config (for load.metricsgen settings)
     * @param dataset   the dataset being loaded (for index name, etc.)
     * @param indexName the resolved index / data-stream name
     * @return number of data points ingested
     */
    default int customLoad(JingraConfig config, DatasetConfig dataset, String indexName) throws Exception {
        throw new UnsupportedOperationException(
                getEngineName() + " does not implement customLoad; override supportsCustomLoad() and customLoad()");
    }
}
