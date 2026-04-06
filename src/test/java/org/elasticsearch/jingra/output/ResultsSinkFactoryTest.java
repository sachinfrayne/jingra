package org.elasticsearch.jingra.output;

import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.OutputConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultsSinkFactoryTest {

    @Test
    void alwaysIncludesConsole() {
        JingraConfig c = new JingraConfig();
        List<ResultsSink> sinks = ResultsSinkFactory.create(c);
        assertEquals(1, sinks.size());
        assertInstanceOf(ConsoleResultsSink.class, sinks.get(0));
    }

    @Test
    void skipsSinkWithoutType() {
        JingraConfig c = new JingraConfig();
        OutputConfig out = new OutputConfig();
        OutputConfig.ResultsSinkConfig bad = new OutputConfig.ResultsSinkConfig();
        bad.setType(null);
        out.setSinks(new ArrayList<>(List.of(bad)));
        c.setOutput(out);

        List<ResultsSink> sinks = ResultsSinkFactory.create(c);
        assertEquals(1, sinks.size());
    }

    @Test
    void addsElasticsearchSink() {
        JingraConfig c = new JingraConfig();
        OutputConfig out = new OutputConfig();
        OutputConfig.ResultsSinkConfig esc = new OutputConfig.ResultsSinkConfig();
        esc.setType("elasticsearch");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("url", "http://127.0.0.1:9200");
        cfg.put("index", "metrics");
        esc.setConfig(cfg);
        out.setSinks(List.of(esc));
        c.setOutput(out);

        List<ResultsSink> sinks = ResultsSinkFactory.create(c);
        assertEquals(2, sinks.size());
        assertInstanceOf(ConsoleResultsSink.class, sinks.get(0));
        assertInstanceOf(ElasticsearchResultsSink.class, sinks.get(1));
    }

    @Test
    void unknownSinkTypeIsIgnored() {
        JingraConfig c = new JingraConfig();
        OutputConfig out = new OutputConfig();
        OutputConfig.ResultsSinkConfig esc = new OutputConfig.ResultsSinkConfig();
        esc.setType("unknown-sink");
        esc.setConfig(Map.of());
        out.setSinks(List.of(esc));
        c.setOutput(out);

        List<ResultsSink> sinks = ResultsSinkFactory.create(c);
        assertEquals(1, sinks.size());
    }

    @Test
    void privateCtor() throws Exception {
        var cl = Class.forName("org.elasticsearch.jingra.output.ResultsSinkFactory");
        var ctor = cl.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }
}
