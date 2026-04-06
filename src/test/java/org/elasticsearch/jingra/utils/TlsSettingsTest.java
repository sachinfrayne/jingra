package org.elasticsearch.jingra.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsSettingsTest {

    @AfterEach
    void clearProp() {
        System.clearProperty("jingra.insecure.tls");
    }

    @Test
    void propertyTrue_enables() {
        System.setProperty("jingra.insecure.tls", "true");
        assertTrue(TlsSettings.insecureTlsEnabled());
    }

    @Test
    void propertyOne_enables() {
        System.setProperty("jingra.insecure.tls", "1");
        assertTrue(TlsSettings.insecureTlsEnabled());
    }

    @Test
    void propertyFalse_disables() {
        System.setProperty("jingra.insecure.tls", "false");
        assertFalse(TlsSettings.insecureTlsEnabled());
    }

    @Test
    @ClearSystemProperty(key = "jingra.insecure.tls")
    @SetEnvironmentVariable(key = "JINGRA_INSECURE_TLS", value = "true")
    void envTrue_whenPropertyUnset() {
        assertTrue(TlsSettings.insecureTlsEnabled());
    }

    @Test
    @ClearSystemProperty(key = "jingra.insecure.tls")
    @SetEnvironmentVariable(key = "JINGRA_INSECURE_TLS", value = "1")
    void envDigitOne_whenPropertyUnset() {
        assertTrue(TlsSettings.insecureTlsEnabled());
    }

    @Test
    @ClearSystemProperty(key = "jingra.insecure.tls")
    @SetEnvironmentVariable(key = "JINGRA_INSECURE_TLS", value = "yes")
    void envYes_whenPropertyUnset() {
        assertTrue(TlsSettings.insecureTlsEnabled());
    }

    @Test
    @ClearSystemProperty(key = "jingra.insecure.tls")
    @SetEnvironmentVariable(key = "JINGRA_INSECURE_TLS", value = "off")
    void envUnrecognized_disables() {
        assertFalse(TlsSettings.insecureTlsEnabled());
    }
}
