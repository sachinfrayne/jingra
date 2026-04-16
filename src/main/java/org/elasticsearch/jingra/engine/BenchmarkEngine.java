package org.elasticsearch.jingra.engine;

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
     *
     * @param indexName the name of the index
     * @param schemaName the name of the schema template
     * @return true if created successfully
     */
    boolean createIndex(String indexName, String schemaName);

    /**
     * Check if an index exists.
     *
     * @param indexName the index name
     * @return true if the index exists
     */
    boolean indexExists(String indexName);

    /**
     * Delete an index.
     *
     * @param indexName the index name
     * @return true if deletion successful
     */
    boolean deleteIndex(String indexName);

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
}
