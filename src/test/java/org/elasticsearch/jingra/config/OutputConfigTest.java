package org.elasticsearch.jingra.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputConfigTest {

    @Test
    void resultsSinkConfig_writeQueryMetricsDefaultTrue() {
        OutputConfig.ResultsSinkConfig c = new OutputConfig.ResultsSinkConfig();
        assertTrue(c.getWriteQueryMetrics());
    }

    @Test
    void resultsSinkConfig_writeQueryMetricsExplicitFalse() {
        OutputConfig.ResultsSinkConfig c = new OutputConfig.ResultsSinkConfig();
        c.setWriteQueryMetrics(false);
        assertFalse(c.getWriteQueryMetrics());
    }

    @Test
    void resultsSinkConfig_roundTrip() {
        OutputConfig.ResultsSinkConfig c = new OutputConfig.ResultsSinkConfig();
        c.setType("elasticsearch");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://localhost:9200");
        c.setConfig(cfg);
        assertEquals("elasticsearch", c.getType());
        assertEquals(cfg, c.getConfig());
    }

    @Test
    void outputConfig_sinksRoundTrip() {
        OutputConfig o = new OutputConfig();
        OutputConfig.ResultsSinkConfig s = new OutputConfig.ResultsSinkConfig();
        s.setType("console");
        o.setSinks(java.util.List.of(s));
        assertEquals(1, o.getSinks().size());
    }
}
