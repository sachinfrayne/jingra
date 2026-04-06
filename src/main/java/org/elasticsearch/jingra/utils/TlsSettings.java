package org.elasticsearch.jingra.utils;

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
}
