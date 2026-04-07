package org.elasticsearch.jingra.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.security.cert.X509Certificate;
import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client construction only — no live Elasticsearch cluster.
 */
class ElasticsearchClientFactoryTest {

    @Test
    void privateConstructor_isCallableViaReflection() throws Exception {
        Constructor<ElasticsearchClientFactory> ctor = ElasticsearchClientFactory.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    @Test
    void insecureTrustAllStrategy_acceptsEmptyChain() throws Exception {
        TrustStrategy strategy = ElasticsearchClientFactory.insecureTrustAllStrategy();
        assertTrue(strategy.isTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void createClient_nullUrl_throwsWithExactMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchClientFactory.createClient(null, null, null, false));
        assertEquals("Elasticsearch URL cannot be null or empty", ex.getMessage());
    }

    @Test
    void createClient_emptyUrl_throwsWithExactMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchClientFactory.createClient("", null, null, false));
        assertEquals("Elasticsearch URL cannot be null or empty", ex.getMessage());
    }

    @Test
    void createClient_malformedUrl_propagatesUriSyntaxException() {
        assertThrows(URISyntaxException.class,
                () -> ElasticsearchClientFactory.createClient("%", null, null, false));
    }

    @Test
    void createClient_schemeDefaultsToHttpsWhenUriHasNoScheme() throws Exception {
        URI probe = new URI("//127.0.0.1");
        assertNull(probe.getScheme());
        assertEquals("127.0.0.1", probe.getHost());

        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("//127.0.0.1", null, null, true);
        try {
            assertNotNull(w.getClient());
            assertNotNull(w.getRestClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpExplicitPort() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9300", null, null, false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpImplicitPortDefaultsTo9200() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1", null, null, false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpsImplicitPortDefaultsTo443() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("https://127.0.0.1", null, null, false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpsExplicitPort() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("https://127.0.0.1:9200", null, null, false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpsInsecureTlsTrue_setsTrustAllPath() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("https://127.0.0.1", null, null, true);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpsInsecureTlsFalse_usesJvmTrustStore() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("https://127.0.0.1", null, null, false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_authWhenUserAndPasswordProvided() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", "u", "p", false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_noAuthWhenUserNull() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", null, "p", false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_noAuthWhenPasswordNull() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", "u", null, false);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_threeArgOverload_delegatesToTlsSettings() throws Exception {
        System.setProperty("jingra.insecure.tls", "false");
        try {
            ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                    ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", null, null);
            try {
                assertNotNull(w.getClient());
            } finally {
                w.close();
            }
        } finally {
            System.clearProperty("jingra.insecure.tls");
        }
    }

    @Test
    void wrapper_gettersReturnSameInstancesAsFactoryBuilt() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", null, null, false);
        try {
            ElasticsearchClient client = w.getClient();
            Rest5Client rest = w.getRestClient();
            assertSame(client, w.getClient());
            assertSame(rest, w.getRestClient());
        } finally {
            w.close();
        }
    }

    @Test
    void wrapper_gettersReturnExactReferencesPassedToConstructor() {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                new ElasticsearchClientFactory.ElasticsearchClientWrapper(null, null);
        assertNull(w.getClient());
        assertNull(w.getRestClient());
    }

    @Test
    void wrapper_closeClosesRestClientWhenNonNull() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", null, null, false);
        Rest5Client rest = w.getRestClient();
        assertTrue(rest.isRunning());
        w.close();
        assertFalse(rest.isRunning());
    }

    @Test
    void wrapper_closeNoOpWhenRestClientNull() {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                new ElasticsearchClientFactory.ElasticsearchClientWrapper(null, null);
        assertDoesNotThrow(w::close);
    }
}
