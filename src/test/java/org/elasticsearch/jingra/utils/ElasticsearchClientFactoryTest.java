package org.elasticsearch.jingra.utils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchClientFactoryTest {

    @Test
    void createClient_noAuth() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", null, null);
        try {
            assertNotNull(w.getClient());
            assertNotNull(w.getRestClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_withAuth() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1:9200", "u", "p");
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpImplicitPort9200() throws Exception {
        ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                ElasticsearchClientFactory.createClient("http://127.0.0.1", null, null);
        try {
            assertNotNull(w.getClient());
        } finally {
            w.close();
        }
    }

    @Test
    void createClient_httpsImplicitPort() throws Exception {
        System.setProperty("jingra.insecure.tls", "true");
        try {
            ElasticsearchClientFactory.ElasticsearchClientWrapper w =
                    ElasticsearchClientFactory.createClient("https://127.0.0.1", "u", "p");
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
    void createClient_nullUrlThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchClientFactory.createClient(null, null, null));
    }

    @Test
    void createClient_emptyUrlThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchClientFactory.createClient("", null, null));
    }

    @Test
    void wrapper_closeInvokesRestClient() throws Exception {
        ElasticsearchClient mockEs = org.mockito.Mockito.mock(ElasticsearchClient.class);
        var rest = org.mockito.Mockito.mock(co.elastic.clients.transport.rest5_client.low_level.Rest5Client.class);
        var w = new ElasticsearchClientFactory.ElasticsearchClientWrapper(mockEs, rest);
        w.close();
        org.mockito.Mockito.verify(rest).close();
    }

    @Test
    void wrapper_closeNoOpWhenRestClientNull() throws Exception {
        ElasticsearchClient mockEs = org.mockito.Mockito.mock(ElasticsearchClient.class);
        var w = new ElasticsearchClientFactory.ElasticsearchClientWrapper(mockEs, null);
        assertDoesNotThrow(w::close);
    }
}
