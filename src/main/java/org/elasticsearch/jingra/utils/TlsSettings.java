package org.elasticsearch.jingra.utils;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * TLS behavior for Elasticsearch and OpenSearch HTTP clients.
 * <p>
 * By default the JVM trust store is used (production-safe). For lab clusters with
 * self-signed certificates, set environment variable {@code JINGRA_INSECURE_TLS=true}
 * to disable certificate verification (insecure; MITM possible).
 */
public final class TlsSettings {

    private TlsSettings() {}

    /**
     * When {@code true}, trust all certificates and skip hostname verification for HTTPS.
     * Controlled by system property {@code jingra.insecure.tls} (tests) or env {@code JINGRA_INSECURE_TLS}
     * (default {@code false}).
     */
    public static boolean insecureTlsEnabled() {
        String prop = System.getProperty("jingra.insecure.tls");
        if (prop != null) {
            return prop.equalsIgnoreCase("true") || "1".equals(prop);
        }
        String v = System.getenv("JINGRA_INSECURE_TLS");
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }

    /**
     * Trust manager that accepts any certificate chain (insecure). Used by {@code QdrantEngine}
     * when connecting over TLS with {@link #insecureTlsEnabled()} {@code true}.
     */
    public static X509TrustManager insecureTrustAllX509TrustManager() {
        return new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType) {
            }
        };
    }
}
