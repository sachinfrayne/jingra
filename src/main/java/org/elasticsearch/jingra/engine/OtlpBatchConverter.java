package org.elasticsearch.jingra.engine;

import org.elasticsearch.jingra.model.Document;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Converts flat jingra Documents (one per metric data point, with "metrics." prefix on metric
 * field names) into OTLP {@code ExportMetricsServiceRequest} protobuf bytes suitable for posting
 * to Elasticsearch's {@code /_otlp/v1/metrics} endpoint.
 *
 * <p>No external protobuf dependency required — wire format is written by hand using the OTLP
 * proto field numbers from the OpenTelemetry specification.
 *
 * <p>Field classification:
 * <ul>
 *   <li>{@code "metrics.*"} fields → metric value; strip prefix for metric name.
 *   <li>Fields starting with a known resource-attribute prefix → {@code resource.attributes}.
 *   <li>{@code @timestamp} → {@code time_unix_nano}.
 *   <li>Everything else → datapoint {@code attributes}.
 * </ul>
 */
public class OtlpBatchConverter {

    private static final Set<String> COUNTER_METRICS = Set.of(
            "system.cpu.time",
            "system.disk.io", "system.disk.operations", "system.disk.io_time",
            "system.disk.operation_time", "system.disk.merged",
            "system.disk.pending_operations", "system.disk.weighted_io_time",
            "system.network.io", "system.network.packets",
            "system.network.errors", "system.network.dropped",
            "system.processes.created"
    );

    private static final List<String> RESOURCE_PREFIXES = List.of(
            "host.", "os.", "cloud.", "k8s.", "deployment.", "telemetry."
    );

    private static final Map<String, String> UNITS = Map.of(
            "system.cpu.time", "s",
            "system.cpu.utilization", "1",
            "system.cpu.load_average", "1",
            "system.memory.utilization", "1",
            "system.memory.usage", "By",
            "system.filesystem.usage", "By",
            "system.filesystem.inodes.usage", "1"
    );

    // Datapoint descriptor — keeps all info needed to build one NumberDataPoint.
    private record Dp(long timeNs, long valueBits, boolean isDbl, Map<String, Object> attrs) {}

    private final String dataset;
    private final String namespace;

    public OtlpBatchConverter(String dataset, String namespace) {
        this.dataset   = dataset;
        this.namespace = namespace;
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Convert a list of flat Documents into an OTLP ExportMetricsServiceRequest protobuf byte[].
     * Documents are grouped by resource-attribute fingerprint so all data points from the same
     * host share one {@code ResourceMetrics} entry.
     */
    public byte[] convert(List<Document> docs) {
        // resource key → scope name → metric name → list of Dp
        Map<String, Map<String, Map<String, List<Dp>>>> byResource = new LinkedHashMap<>();
        Map<String, Map<String, Object>> resourceAttrMap = new LinkedHashMap<>();

        for (Document doc : docs) {
            Map<String, Object> fields = doc.getFields();
            Map<String, Object> rAttrs = new LinkedHashMap<>();
            Map<String, Object> dAttrs = new LinkedHashMap<>();
            // A merged doc may contain multiple metrics.* fields (one per metric name).
            Map<String, Object> metricFields = new LinkedHashMap<>();
            String ts = null;

            for (Map.Entry<String, Object> e : fields.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if ("@timestamp".equals(k)) {
                    ts = (String) v;
                } else if (k.startsWith("metrics.")) {
                    metricFields.put(k.substring("metrics.".length()), v);
                } else if (isResourceAttr(k)) {
                    rAttrs.put(k, v);
                } else {
                    dAttrs.put(k, v);
                }
            }
            if (metricFields.isEmpty() || ts == null) continue;

            String rKey = rAttrs.toString();
            resourceAttrMap.putIfAbsent(rKey, rAttrs);

            long timeNs = isoToNanos(ts);

            // Add one Dp per metric in this doc. All metrics for the same
            // (resource + timestamp + datapoint attrs) are placed in the same
            // ResourceMetrics entry, so ES will group them into one document.
            for (Map.Entry<String, Object> me : metricFields.entrySet()) {
                String metricName  = me.getKey();
                Object metricValue = me.getValue();
                boolean isDbl = (metricValue instanceof Double) || (metricValue instanceof Float);
                long bits;
                if (isDbl) {
                    bits = Double.doubleToLongBits(((Number) metricValue).doubleValue());
                } else if (metricValue instanceof Number) {
                    bits = ((Number) metricValue).longValue();
                } else {
                    bits  = 0L;
                    isDbl = true;
                }

                byResource
                    .computeIfAbsent(rKey, x -> new LinkedHashMap<>())
                    .computeIfAbsent(scopeFor(metricName), x -> new LinkedHashMap<>())
                    .computeIfAbsent(metricName, x -> new ArrayList<>())
                    .add(new Dp(timeNs, bits, isDbl, dAttrs));
            }
        }

        return buildExportRequest(byResource, resourceAttrMap);
    }

    // ── Field classification ──────────────────────────────────────────────────────

    private boolean isResourceAttr(String key) {
        for (String p : RESOURCE_PREFIXES) {
            if (key.startsWith(p)) return true;
        }
        return false;
    }

    private String scopeFor(String name) {
        String sub = "hostmetrics";
        if      (name.startsWith("system.cpu."))        sub = "cpu";
        else if (name.startsWith("system.memory."))     sub = "memory";
        else if (name.startsWith("system.filesystem.")) sub = "filesystem";
        else if (name.startsWith("system.disk."))       sub = "disk";
        else if (name.startsWith("system.network."))    sub = "network";
        else if (name.startsWith("system.processes."))  sub = "process";
        return "otelcol/" + dataset + "/" + namespace + "/" + sub;
    }

    private String unitFor(String name) {
        for (Map.Entry<String, String> e : UNITS.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue();
        }
        return "1";
    }

    // ── Protobuf builder ──────────────────────────────────────────────────────────

    private byte[] buildExportRequest(
            Map<String, Map<String, Map<String, List<Dp>>>> byResource,
            Map<String, Map<String, Object>> resourceAttrMap) {

        Proto root = new Proto();
        for (Map.Entry<String, Map<String, Map<String, List<Dp>>>> re : byResource.entrySet()) {
            root.message(1, buildResourceMetrics(re.getKey(), re.getValue(), resourceAttrMap));
        }
        return root.bytes();
    }

    private byte[] buildResourceMetrics(
            String rKey,
            Map<String, Map<String, List<Dp>>> byScope,
            Map<String, Map<String, Object>> resourceAttrMap) {

        Proto rm = new Proto();
        // resource (field 1)
        Proto resource = new Proto();
        for (Map.Entry<String, Object> ae : resourceAttrMap.get(rKey).entrySet()) {
            resource.message(1, buildKeyValue(ae.getKey(), ae.getValue()));
        }
        // data_stream routing attributes: tell /_otlp/v1/metrics which data stream to target.
        // Without these, ES defaults to namespace="default" and the data lands in the wrong stream.
        resource.message(1, buildKeyValue("data_stream.dataset", dataset));
        resource.message(1, buildKeyValue("data_stream.namespace", namespace));
        rm.message(1, resource.bytes());
        // scope_metrics (field 2)
        for (Map.Entry<String, Map<String, List<Dp>>> se : byScope.entrySet()) {
            rm.message(2, buildScopeMetrics(se.getKey(), se.getValue()));
        }
        return rm.bytes();
    }

    private byte[] buildScopeMetrics(String scopeName, Map<String, List<Dp>> byMetric) {
        Proto sm = new Proto();
        Proto scope = new Proto();
        scope.string(1, scopeName);
        sm.message(1, scope.bytes());
        for (Map.Entry<String, List<Dp>> me : byMetric.entrySet()) {
            sm.message(2, buildMetric(me.getKey(), me.getValue()));
        }
        return sm.bytes();
    }

    private byte[] buildMetric(String name, List<Dp> dps) {
        Proto m = new Proto();
        m.string(1, name);
        m.string(3, unitFor(name));

        // Build data points
        Proto dataPoints = new Proto();
        for (Dp dp : dps) {
            Proto dpPb = new Proto();
            dpPb.fixed64(3, dp.timeNs());
            if (dp.isDbl()) dpPb.fixed64(4, dp.valueBits()); // as_double field 4
            else            dpPb.fixed64(6, dp.valueBits()); // as_int (sfixed64) field 6
            for (Map.Entry<String, Object> ae : dp.attrs().entrySet()) {
                dpPb.message(7, buildKeyValue(ae.getKey(), ae.getValue())); // attributes field 7
            }
            dataPoints.message(1, dpPb.bytes());
        }

        if (COUNTER_METRICS.contains(name)) {
            // Sum (field 7): embed data_points, then aggregation_temporality + is_monotonic
            Proto sum = new Proto();
            sum.rawBytes(dataPoints.bytes());
            sum.varint(2, 2);   // aggregation_temporality = CUMULATIVE (2)
            sum.bool(3, true);  // is_monotonic
            m.message(7, sum.bytes());
        } else {
            // Gauge (field 5): embed data_points directly
            m.message(5, dataPoints.bytes());
        }
        return m.bytes();
    }

    private byte[] buildKeyValue(String key, Object value) {
        Proto kv = new Proto();
        kv.string(1, key);
        kv.message(2, buildAnyValue(value));
        return kv.bytes();
    }

    private byte[] buildAnyValue(Object v) {
        Proto a = new Proto();
        if (v instanceof String) {
            a.string(1, (String) v);                                         // string_value
        } else if (v instanceof Boolean) {
            a.bool(2, (Boolean) v);                                          // bool_value
        } else if (v instanceof Long || v instanceof Integer) {
            a.varint(3, ((Number) v).longValue());                           // int_value
        } else if (v instanceof Double || v instanceof Float) {
            a.fixed64(4, Double.doubleToLongBits(((Number) v).doubleValue())); // double_value
        } else {
            a.string(1, String.valueOf(v));                                  // fallback → string
        }
        return a.bytes();
    }

    // ── Timestamp ────────────────────────────────────────────────────────────────

    static long isoToNanos(String iso) {
        java.time.Instant t = java.time.Instant.parse(iso);
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    // ── Minimal protobuf wire-format writer ───────────────────────────────────────

    /**
     * Minimal protobuf encoder. Writes only the field types needed for OTLP metrics:
     * LEN (messages + strings), varint (enums + bools + int64), and 64-bit fixed (double/sfixed64).
     */
    static final class Proto {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        /** Append raw pre-encoded bytes without a wrapping tag (used for merging sub-messages). */
        void rawBytes(byte[] b) { buf.write(b, 0, b.length); }

        /** Embedded message: tag(field, LEN) + length-varint + content bytes. */
        void message(int field, byte[] content) {
            tag(field, 2);
            varint(content.length);
            buf.write(content, 0, content.length);
        }

        /** UTF-8 string: tag(field, LEN) + length-varint + utf8 bytes. */
        void string(int field, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            tag(field, 2);
            varint(b.length);
            buf.write(b, 0, b.length);
        }

        /** Integer / enum as varint: tag(field, VARINT) + varint(value). */
        void varint(int field, long value) { tag(field, 0); varint(value); }

        /** Boolean as varint: tag(field, VARINT) + 0 or 1. */
        void bool(int field, boolean value) { tag(field, 0); buf.write(value ? 1 : 0); }

        /**
         * 64-bit little-endian fixed: tag(field, I64) + 8 bytes LE.
         * Covers {@code double} (as_double), {@code fixed64} (time_unix_nano), and
         * {@code sfixed64} (as_int) — all share wire type 1.
         */
        void fixed64(int field, long value) {
            tag(field, 1);
            for (int i = 0; i < 8; i++) { buf.write((int) (value & 0xFF)); value >>>= 8; }
        }

        byte[] bytes() { return buf.toByteArray(); }

        private void tag(int field, int wireType) { varint((field << 3) | wireType); }

        void varint(long v) {
            while ((v & ~0x7FL) != 0) { buf.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
            buf.write((int) v);
        }
    }
}
