package org.elasticsearch.jingra.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.elasticsearch.jingra.utils.TlsSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating Elasticsearch clients with consistent configuration.
 * Used by {@link ElasticsearchEngine} (including the results sink, which runs an engine against the sink URL).
 */
public class ElasticsearchClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchClientFactory.class);

    private ElasticsearchClientFactory() {}

    /**
     * Trust-all strategy for insecure TLS mode. Package-private so tests can invoke
     * {@link TrustStrategy#isTrusted} and achieve full coverage without a live TLS handshake.
     */
    static TrustStrategy insecureTrustAllStrategy() {
        return (chain, authType) -> true;
    }

    /**
     * Create an Elasticsearch client with standard configuration.
     * Uses global JINGRA_INSECURE_TLS setting for TLS verification.
     *
     * @param url Elasticsearch URL (e.g., "https://localhost:9200")
     * @param user Username (null for no authentication)
     * @param password Password (null for no authentication)
     * @return Configured Elasticsearch client wrapper
     * @throws Exception if client creation fails
     */
    public static ElasticsearchClientWrapper createClient(String url, String user, String password) throws Exception {
        return createClient(url, user, password, TlsSettings.insecureTlsEnabled());
    }

    /**
     * Create an Elasticsearch client with standard configuration.
     *
     * @param url Elasticsearch URL (e.g., "https://localhost:9200")
     * @param user Username (null for no authentication)
     * @param password Password (null for no authentication)
     * @param insecureTls If true, disable TLS certificate verification (insecure)
     * @return Configured Elasticsearch client wrapper
     * @throws Exception if client creation fails
     */
    public static ElasticsearchClientWrapper createClient(String url, String user, String password, boolean insecureTls) throws Exception {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch URL cannot be null or empty");
        }

        java.net.URI uri = new java.net.URI(url);
        String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
        String hostname = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (scheme.equals("https") ? 443 : 9200);

        HttpHost host = new HttpHost(scheme, hostname, port);
        co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder builder =
                co.elastic.clients.transport.rest5_client.low_level.Rest5Client.builder(host);

        H2Config h2Config = H2Config.custom()
                .setMaxConcurrentStreams(100)
                .setInitialWindowSize(1024 * 1024)
                .setPushEnabled(false)
                .build();

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
        if (https && insecureTls) {
            logger.warn("Insecure TLS is enabled: TLS verification is disabled (unsafe outside controlled environments)");
            javax.net.ssl.SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(insecureTrustAllStrategy())
                    .build();
            cmBuilder.setTlsStrategy(ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .setHostnameVerifier(org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE)
                    .build());
        }

        PoolingAsyncClientConnectionManager connectionManager = cmBuilder.build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofMinutes(5))
                .build();

        DefaultHttpRequestRetryStrategy retryStrategy = new DefaultHttpRequestRetryStrategy(
                3,
                TimeValue.ofMilliseconds(500)
        );

        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .setRetryStrategy(retryStrategy)
                    .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                    .setH2Config(h2Config);

            if (user != null && password != null) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                AuthScope authScope = new AuthScope(host);
                UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(user, password.toCharArray());
                credentialsProvider.setCredentials(authScope, credentials);
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
        });

        co.elastic.clients.transport.rest5_client.low_level.Rest5Client restClient = builder.build();
        Rest5ClientTransport transport = new Rest5ClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );
        ElasticsearchClient client = new ElasticsearchClient(transport);

        logger.debug("Created Elasticsearch client for: {}", url);

        return new ElasticsearchClientWrapper(client, restClient);
    }

    /**
     * Wrapper class to hold both the high-level client and low-level REST client.
     * The REST client is needed for proper cleanup/closing.
     */
    public static class ElasticsearchClientWrapper {
        private final ElasticsearchClient client;
        private final co.elastic.clients.transport.rest5_client.low_level.Rest5Client restClient;

        public ElasticsearchClientWrapper(
                ElasticsearchClient client,
                co.elastic.clients.transport.rest5_client.low_level.Rest5Client restClient) {
            this.client = client;
            this.restClient = restClient;
        }

        public ElasticsearchClient getClient() {
            return client;
        }

        public co.elastic.clients.transport.rest5_client.low_level.Rest5Client getRestClient() {
            return restClient;
        }

        /**
         * Close the client and release resources.
         */
        public void close() throws Exception {
            if (restClient != null) {
                restClient.close();
            }
        }
    }
}
