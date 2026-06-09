package org.elasticsearch.jingra.engine;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Mimir engine implementation. Extends PrometheusEngine with Mimir-specific API paths and
 * the X-Scope-OrgID header required for Mimir multi-tenancy.
 */
public class MimirEngine extends PrometheusEngine {

    private static final String DEFAULT_ORG_ID = "anonymous";

    public MimirEngine(Map<String, Object> config) {
        super(config);
    }

    protected String orgId() {
        return getConfigString("org_id", DEFAULT_ORG_ID);
    }

    @Override
    public String getEngineName() {
        return "mimir";
    }

    @Override
    public String getShortName() {
        return "mim";
    }

    @Override
    protected String buildInfoOperation(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url + "/prometheus/api/v1/status/buildinfo"))
                .header("X-Scope-OrgID", orgId())
                .GET()
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("buildinfo returned HTTP " + resp.statusCode());
        }
        Map<String, Object> body = objectMapper.readValue(resp.body(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return data != null ? String.valueOf(data.get("version")) : "unknown";
    }

    @Override
    protected int otlpWriteOperation(byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/otlp/v1/metrics"))
                .header("Content-Type", "application/x-protobuf")
                .header("X-Scope-OrgID", orgId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            logger.warn("OTLP write HTTP {}: {}", resp.statusCode(), resp.body());
        }
        return resp.statusCode();
    }

    @Override
    protected String instantQueryOperation(String promql) throws Exception {
        String encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8);
        String bodyStr = "query=" + encoded;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prometheus/api/v1/query"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Scope-OrgID", orgId())
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("instant query returned HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean hasAnySeriesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prometheus/api/v1/label/__name__/values"))
                .header("X-Scope-OrgID", orgId())
                .GET()
                .build();
        HttpResponse<String> resp = httpSendString(req);
        if (resp.statusCode() != 200) {
            throw new IOException("label values returned HTTP " + resp.statusCode());
        }
        Map<String, Object> body = objectMapper.readValue(resp.body(), new TypeReference<>() {});
        List<String> values = (List<String>) body.get("data");
        return values != null && !values.isEmpty();
    }

    @Override
    protected void deleteSeriesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prometheus/api/v1/admin/tsdb/delete_series"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Scope-OrgID", orgId())
                .POST(HttpRequest.BodyPublishers.ofString(DELETE_ALL_SERIES_BODY))
                .build();
        HttpResponse<Void> resp = httpSendVoid(req);
        if (resp.statusCode() != 204) {
            throw new IOException("delete_series returned HTTP " + resp.statusCode()
                    + "; ensure Mimir is configured with appropriate admin settings");
        }
    }

    @Override
    protected void cleanTombstonesOperation() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prometheus/api/v1/admin/tsdb/clean_tombstones"))
                .header("X-Scope-OrgID", orgId())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> resp = httpSendVoid(req);
        if (resp.statusCode() != 204) {
            throw new IOException("clean_tombstones returned HTTP " + resp.statusCode());
        }
    }
}
