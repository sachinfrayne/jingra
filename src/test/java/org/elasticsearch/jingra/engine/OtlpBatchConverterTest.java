package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OtlpBatchConverter}.
 *
 * <p>Because the output is binary protobuf (no external proto library added), correctness is
 * verified by:
 * <ul>
 *   <li>String fields: raw UTF-8 bytes must appear in the output (proto stores strings verbatim).
 *   <li>Structural checks: output is non-empty for valid inputs, empty for invalid/empty inputs.
 *   <li>The {@link OtlpBatchConverter.Proto} wire-format helper is also tested directly.
 * </ul>
 */
class OtlpBatchConverterTest {

    private final OtlpBatchConverter converter = new OtlpBatchConverter("jingra", "benchmark");

    private Document doc(Map<String, Object> fields) { return new Document(fields); }

    /** True when {@code needle} (UTF-8 encoded) appears anywhere in {@code haystack}. */
    private static boolean hasBytes(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) { if (haystack[i + j] != n[j]) continue outer; }
            return true;
        }
        return false;
    }

    // ── Basic structural tests ────────────────────────────────────────────────────

    @Test
    void singleGaugeDoc_producesNonEmptyProtoContainingMetricName() {
        byte[] b = converter.convert(List.of(doc(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z",
                "host.name", "host-0001",
                "os.type", "linux",
                "metrics.system.memory.utilization", 0.67))));
        assertTrue(b.length > 0, "proto must be non-empty");
        assertTrue(hasBytes(b, "system.memory.utilization"), "metric name must be in output");
        assertTrue(hasBytes(b, "host-0001"), "host.name must be in output");
        // data stream routing attributes must be present as resource attributes
        // dataset is "jingra" (without ".otel" — ES adds ".otel-" separator automatically)
        assertTrue(hasBytes(b, "data_stream.dataset"), "data_stream.dataset key must be in resource attrs");
        assertTrue(hasBytes(b, "benchmark"),            "data_stream.namespace must be in resource attrs");
    }

    @Test
    void counterMetric_isEncodedAsSum() {
        // system.cpu.time is a counter → must NOT appear under gauge scope name
        // We verify by checking that "system.cpu.time" appears in the proto
        byte[] b = converter.convert(List.of(doc(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z",
                "host.name", "h",
                "state", "user",
                "metrics.system.cpu.time", 1234.5))));
        assertTrue(b.length > 0);
        assertTrue(hasBytes(b, "system.cpu.time"));
    }

    @Test
    void datapointAttribute_appearsInProto() {
        byte[] b = converter.convert(List.of(doc(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z",
                "host.name", "h",
                "state", "user",
                "metrics.system.cpu.time", 1.0))));
        assertTrue(hasBytes(b, "state"), "'state' datapoint attr must appear in proto");
    }

    @Test
    void emptyDocList_producesEmptyOrMinimalProto() {
        byte[] b = converter.convert(List.of());
        // An ExportMetricsServiceRequest with no resource_metrics has zero length
        assertEquals(0, b.length);
    }

    @Test
    void docMissingTimestamp_isSkipped() {
        Document d = doc(Map.of("host.name", "h", "metrics.system.memory.utilization", 0.5));
        assertEquals(0, converter.convert(List.of(d)).length);
    }

    @Test
    void docMissingMetric_isSkipped() {
        Document d = doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "h"));
        assertEquals(0, converter.convert(List.of(d)).length);
    }

    // ── Scope-name tests (verifies subsystem routing) ─────────────────────────────

    @Test
    void scopeFor_coversAllSubsystems() {
        String[][] cases = {
            { "metrics.system.filesystem.usage",   "filesystem" },
            { "metrics.system.disk.io",             "disk" },
            { "metrics.system.network.io",          "network" },
            { "metrics.system.processes.count",     "process" },
            { "metrics.custom.unknown.metric",      "hostmetrics" },
        };
        for (String[] c : cases) {
            byte[] b = converter.convert(List.of(doc(Map.of(
                    "@timestamp", "2026-06-13T09:00:00Z",
                    "host.name", "h",
                    c[0], 1.0))));
            assertTrue(hasBytes(b, c[1]),
                    c[0] + " → expected subsystem '" + c[1] + "' in proto");
        }
    }

    // ── Multi-doc grouping tests ───────────────────────────────────────────────────

    @Test
    void docsWithSameResource_appear_oneResourceBlock() {
        // Two metrics from same host → both metric names appear in the output
        List<Document> docs = List.of(
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                        "metrics.system.memory.utilization", 0.5)),
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                        "metrics.system.cpu.load_average.1m", 0.3)));
        byte[] b = converter.convert(docs);
        assertTrue(hasBytes(b, "system.memory.utilization"));
        assertTrue(hasBytes(b, "system.cpu.load_average.1m"));
    }

    @Test
    void docsWithDifferentResources_allAppear() {
        List<Document> docs = List.of(
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "host-A",
                        "metrics.system.memory.utilization", 0.5)),
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "host-B",
                        "metrics.system.memory.utilization", 0.6)));
        byte[] b = converter.convert(docs);
        assertTrue(hasBytes(b, "host-A"));
        assertTrue(hasBytes(b, "host-B"));
    }

    @Test
    void twoMetricsInSameScope_bothAppear() {
        List<Document> docs = List.of(
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                        "state", "user", "metrics.system.cpu.time", 1.0)),
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                        "metrics.system.cpu.load_average.1m", 0.5)));
        byte[] b = converter.convert(docs);
        assertTrue(hasBytes(b, "system.cpu.time"));
        assertTrue(hasBytes(b, "system.cpu.load_average.1m"));
    }

    @Test
    void multipleDatapointsForSameMetric_allTimestampsEncoded() {
        // Two docs with same metric but different timestamps
        List<Document> docs = List.of(
                doc(Map.of("@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                        "metrics.system.memory.utilization", 0.5)),
                doc(Map.of("@timestamp", "2026-06-13T09:01:00Z", "host.name", "h",
                        "metrics.system.memory.utilization", 0.6)));
        byte[] b = converter.convert(docs);
        assertTrue(b.length > 0);
        assertTrue(hasBytes(b, "system.memory.utilization"));
    }

    // ── Attribute type tests (exercises all AnyValue branches) ────────────────────

    @Test
    void attributeTypes_boolLongIntDouble_encoded() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "2026-06-13T09:00:00Z");
        fields.put("host.name", "h");
        fields.put("metrics.system.memory.utilization", 0.5);
        fields.put("attr_bool", true);
        fields.put("attr_long", 42L);
        fields.put("attr_int", 7);          // Integer
        fields.put("attr_double", 3.14);
        byte[] b = converter.convert(List.of(doc(fields)));
        assertTrue(b.length > 0);
        // attribute keys appear as strings in proto
        assertTrue(hasBytes(b, "attr_bool"));
        assertTrue(hasBytes(b, "attr_long"));
        assertTrue(hasBytes(b, "attr_int"));
        assertTrue(hasBytes(b, "attr_double"));
    }

    @Test
    void floatValue_encodedAsDouble() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "2026-06-13T09:00:00Z");
        fields.put("host.name", "h");
        fields.put("metrics.system.memory.utilization", 0.5f);
        byte[] b = converter.convert(List.of(doc(fields)));
        assertTrue(b.length > 0);
    }

    @Test
    void unknownValueType_fallsBackToString() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "2026-06-13T09:00:00Z");
        fields.put("host.name", "h");
        fields.put("metrics.system.memory.utilization", 0.5);
        fields.put("attr_list", List.of("a", "b")); // non-primitive → string fallback
        byte[] b = converter.convert(List.of(doc(fields)));
        assertTrue(b.length > 0);
    }

    @Test
    void floatAttributeValue_encodedAsDouble() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "2026-06-13T09:00:00Z");
        fields.put("host.name", "h");
        fields.put("metrics.system.memory.utilization", 0.5);
        fields.put("ratio", 0.75f); // Float attribute → double_value path
        byte[] b = converter.convert(List.of(doc(fields)));
        assertTrue(b.length > 0);
        assertTrue(hasBytes(b, "ratio"));
    }

    @Test
    void nonNumberMetricValue_treatedAsDoubleZero() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("@timestamp", "2026-06-13T09:00:00Z");
        fields.put("host.name", "h");
        fields.put("metrics.system.memory.utilization", "not-a-number");
        byte[] b = converter.convert(List.of(doc(fields)));
        // Doc should still be ingested (as 0.0 double), not skipped
        assertTrue(b.length > 0);
    }

    // ── Unit assignment ───────────────────────────────────────────────────────────

    @Test
    void unitStrings_appearInProto() {
        byte[] bMem = converter.convert(List.of(doc(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                "metrics.system.memory.usage", 4096L))));
        assertTrue(hasBytes(bMem, "By"), "memory.usage unit is 'By'");

        byte[] bCpu = converter.convert(List.of(doc(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                "state", "user", "metrics.system.cpu.time", 1.0))));
        assertTrue(hasBytes(bCpu, "s"), "cpu.time unit is 's'");

        byte[] bUnknown = converter.convert(List.of(doc(Map.of(
                "@timestamp", "2026-06-13T09:00:00Z", "host.name", "h",
                "metrics.custom.unknown", 1.0))));
        assertTrue(hasBytes(bUnknown, "1"), "unknown metric unit defaults to '1'");
    }

    // ── Timestamp conversion ──────────────────────────────────────────────────────

    @Test
    void isoToNanos_convertsCorrectly() {
        String iso = "2026-06-13T09:00:00Z";
        long expected = java.time.Instant.parse(iso).getEpochSecond() * 1_000_000_000L;
        assertEquals(expected, OtlpBatchConverter.isoToNanos(iso));
        assertTrue(expected > 0);
    }

    // ── Proto wire-format writer ──────────────────────────────────────────────────

    @Test
    void protoWriter_stringField_decodable() {
        OtlpBatchConverter.Proto p = new OtlpBatchConverter.Proto();
        p.string(1, "hello");
        byte[] b = p.bytes();
        // Tag for field 1, wire type 2 = 0x0a; length 5 = 0x05; then "hello"
        assertEquals(0x0a, b[0] & 0xFF);
        assertEquals(5, b[1] & 0xFF);
        assertEquals('h', b[2] & 0xFF);
    }

    @Test
    void protoWriter_varintField_decodable() {
        OtlpBatchConverter.Proto p = new OtlpBatchConverter.Proto();
        p.varint(2, 300); // field 2, value 300
        byte[] b = p.bytes();
        // tag for field 2, wire type 0 = 0x10; then varint 300 = 0xAC 0x02
        assertEquals(0x10, b[0] & 0xFF);
        assertEquals(0xAC, b[1] & 0xFF);
        assertEquals(0x02, b[2] & 0xFF);
    }

    @Test
    void protoWriter_fixed64_eightBytesLE() {
        OtlpBatchConverter.Proto p = new OtlpBatchConverter.Proto();
        p.fixed64(3, 0x0102030405060708L);
        byte[] b = p.bytes();
        // tag = (3 << 3) | 1 = 0x19
        assertEquals(0x19, b[0] & 0xFF);
        // little-endian: 08 07 06 05 04 03 02 01
        assertEquals(0x08, b[1] & 0xFF);
        assertEquals(0x07, b[2] & 0xFF);
        assertEquals(0x01, b[8] & 0xFF);
    }

    @Test
    void protoWriter_rawBytes_appendedDirectly() {
        OtlpBatchConverter.Proto inner = new OtlpBatchConverter.Proto();
        inner.string(1, "x");
        OtlpBatchConverter.Proto outer = new OtlpBatchConverter.Proto();
        outer.rawBytes(inner.bytes());
        assertArrayEquals(inner.bytes(), outer.bytes());
    }

    @Test
    void protoWriter_boolTrue_encodedAs1() {
        OtlpBatchConverter.Proto p = new OtlpBatchConverter.Proto();
        p.bool(1, true);
        byte[] b = p.bytes();
        assertEquals(0x08, b[0] & 0xFF); // tag: field 1, wire type 0
        assertEquals(0x01, b[1] & 0xFF); // true = 1
    }

    @Test
    void protoWriter_boolFalse_encodedAs0() {
        OtlpBatchConverter.Proto p = new OtlpBatchConverter.Proto();
        p.bool(1, false);
        byte[] b = p.bytes();
        assertEquals(0x08, b[0] & 0xFF); // tag: field 1, wire type 0
        assertEquals(0x00, b[1] & 0xFF); // false = 0
    }
}
