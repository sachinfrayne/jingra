package org.elasticsearch.jingra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FirstQueryDumpTest {

    private static final class ExposingOpenSearch extends OpenSearchEngine {
        ExposingOpenSearch(Map<String, Object> config) {
            super(config);
        }

        void dumpForTest(String json) {
            writeFirstQueryDumpIfConfigured(getShortName(), json);
        }
    }

    @Test
    void writesPrettyPrintedFirstQueryOnlyOnce(@TempDir Path tempDir) throws Exception {
        ExposingOpenSearch engine = new ExposingOpenSearch(Map.of("query_dump_directory", tempDir.toString()));
        engine.dumpForTest("{\"a\":1}");
        engine.dumpForTest("{\"b\":2}");

        Path f = tempDir.resolve("os-first-query.json");
        assertThat(f).exists();
        String content = Files.readString(f);
        assertThat(content).contains("\"a\"").contains("1").doesNotContain("\"b\"");
    }

    @Test
    void skipsWhenDirectoryNotConfigured(@TempDir Path tempDir) throws Exception {
        ExposingOpenSearch engine = new ExposingOpenSearch(Map.of());
        engine.dumpForTest("{\"x\":1}");
        assertThat(tempDir.resolve("os-first-query.json")).doesNotExist();
    }
}
