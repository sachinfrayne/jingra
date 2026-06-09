package org.elasticsearch.jingra.engine;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import org.elasticsearch.jingra.model.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

class OtlpEncoder {

    private OtlpEncoder() {}

    static ExportMetricsServiceRequest buildRequest(List<Document> documents, AtomicLong ingestTimestampOffset) {
        Map<String, List<NumberDataPoint>> pointsByMetric = new LinkedHashMap<>();

        for (Document doc : documents) {
            Map<String, Object> fields = doc.getFields();
            long timeNano = parseTimestamp(fields.get("@timestamp"), ingestTimestampOffset) * 1_000_000L;

            List<KeyValue> attributes = new ArrayList<>();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if ("@timestamp".equals(key) || val == null) continue;
                if (val instanceof String || val instanceof Boolean) {
                    attributes.add(KeyValue.newBuilder()
                            .setKey(key)
                            .setValue(AnyValue.newBuilder().setStringValue(String.valueOf(val)).build())
                            .build());
                }
            }

            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if ("@timestamp".equals(key) || !(val instanceof Number)) continue;
                String metricName = key.replace('.', '_').replace('-', '_');
                NumberDataPoint dp = NumberDataPoint.newBuilder()
                        .addAllAttributes(attributes)
                        .setTimeUnixNano(timeNano)
                        .setAsDouble(((Number) val).doubleValue())
                        .build();
                pointsByMetric.computeIfAbsent(metricName, k -> new ArrayList<>()).add(dp);
            }
        }

        List<Metric> metrics = new ArrayList<>();
        for (Map.Entry<String, List<NumberDataPoint>> entry : pointsByMetric.entrySet()) {
            metrics.add(Metric.newBuilder()
                    .setName(entry.getKey())
                    .setGauge(Gauge.newBuilder().addAllDataPoints(entry.getValue()).build())
                    .build());
        }

        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addAllMetrics(metrics)
                                .build())
                        .build())
                .build();
    }

    private static long parseTimestamp(Object ts, AtomicLong ingestTimestampOffset) {
        if (ts == null) return System.currentTimeMillis();
        try {
            long ms = Instant.parse(ts.toString()).toEpochMilli();
            long offset = ingestTimestampOffset.get();
            if (offset == Long.MIN_VALUE) {
                long nowMs = System.currentTimeMillis();
                long ageMs = nowMs - ms;
                long computed = ageMs > 30 * 60 * 1000L ? nowMs - 180_000L - ms : 0L;
                ingestTimestampOffset.compareAndSet(Long.MIN_VALUE, computed);
                offset = ingestTimestampOffset.get();
            }
            return ms + offset;
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
