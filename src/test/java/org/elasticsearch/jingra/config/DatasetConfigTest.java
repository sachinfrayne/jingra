package org.elasticsearch.jingra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatasetConfigTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void gettersReflectValuesSetOnFullyPopulatedConfig() {
        DatasetConfig.PathConfig path = new DatasetConfig.PathConfig();
        path.setDataPath("/data/docs.parquet");
        path.setQueriesPath("/data/queries.parquet");
        path.setDataUrlEnv("DATA_URL");
        path.setQueriesUrlEnv("QUERIES_URL");

        DatasetConfig.DataMappingConfig dataMapping = new DatasetConfig.DataMappingConfig();
        dataMapping.setIdField("doc_id");
        dataMapping.setVectorField("embedding");

        DatasetConfig.QueriesMappingConfig queriesMapping = new DatasetConfig.QueriesMappingConfig();
        queriesMapping.setQueryVectorField("q_embedding");
        queriesMapping.setGroundTruthField("neighbors");
        queriesMapping.setConditionsField("filters");

        List<Map<String, Object>> recallParams =
                List.of(Map.of("size", (Object) 100, "k", (Object) 1000));
        Map<String, List<Map<String, Object>>> paramGroups = Map.of("recall@100", recallParams);

        DatasetConfig config = new DatasetConfig();
        config.setType("dense_vector");
        config.setIndexName("bench-index");
        config.setVectorSize(768);
        config.setDistance("cosine");
        config.setSchemaName("default_schema");
        config.setQueryName("knn_query");
        config.setPath(path);
        config.setDataMapping(dataMapping);
        config.setQueriesMapping(queriesMapping);
        config.setParamGroups(paramGroups);

        assertEquals("dense_vector", config.getType());
        assertEquals("bench-index", config.getIndexName());
        assertEquals(768, config.getVectorSize());
        assertEquals("cosine", config.getDistance());
        assertEquals("default_schema", config.getSchemaName());
        assertEquals("knn_query", config.getQueryName());
        assertEquals(path, config.getPath());
        assertEquals(dataMapping, config.getDataMapping());
        assertEquals(queriesMapping, config.getQueriesMapping());
        assertEquals(paramGroups, config.getParamGroups());

        assertEquals("/data/docs.parquet", config.getPath().getDataPath());
        assertEquals("/data/queries.parquet", config.getPath().getQueriesPath());
        assertEquals("DATA_URL", config.getPath().getDataUrlEnv());
        assertEquals("QUERIES_URL", config.getPath().getQueriesUrlEnv());

        assertEquals("doc_id", config.getDataMapping().getIdField());
        assertEquals("embedding", config.getDataMapping().getVectorField());

        assertEquals("q_embedding", config.getQueriesMapping().getQueryVectorField());
        assertEquals("neighbors", config.getQueriesMapping().getGroundTruthField());
        assertEquals("filters", config.getQueriesMapping().getConditionsField());

        assertEquals(100, config.getParamGroups().get("recall@100").getFirst().get("size"));
        assertEquals(1000, config.getParamGroups().get("recall@100").getFirst().get("k"));
    }

    @Test
    void deserializesYamlUsingSnakeCaseJsonPropertyNames() throws Exception {
        String yaml =
                """
                type: dense_vector
                index_name: my-index
                vector_size: 512
                distance: l2_norm
                schema_name: s1
                query_name: q1
                path:
                  data_path: /d.parquet
                  queries_path: /q.parquet
                  data_url_env: ENV_DATA
                  queries_url_env: ENV_Q
                data_mapping:
                  id_field: id
                  vector_field: vec
                queries_mapping:
                  query_vector_field: qvec
                  ground_truth_field: gt
                  conditions_field: cond
                param_groups:
                  recall@100:
                    - size: 100
                      k: 1000
                """;

        DatasetConfig config = YAML_MAPPER.readValue(yaml, DatasetConfig.class);

        assertEquals("dense_vector", config.getType());
        assertEquals("my-index", config.getIndexName());
        assertEquals(512, config.getVectorSize());
        assertEquals("l2_norm", config.getDistance());
        assertEquals("s1", config.getSchemaName());
        assertEquals("q1", config.getQueryName());

        assertNotNull(config.getPath());
        assertEquals("/d.parquet", config.getPath().getDataPath());
        assertEquals("/q.parquet", config.getPath().getQueriesPath());
        assertEquals("ENV_DATA", config.getPath().getDataUrlEnv());
        assertEquals("ENV_Q", config.getPath().getQueriesUrlEnv());

        assertNotNull(config.getDataMapping());
        assertEquals("id", config.getDataMapping().getIdField());
        assertEquals("vec", config.getDataMapping().getVectorField());

        assertNotNull(config.getQueriesMapping());
        assertEquals("qvec", config.getQueriesMapping().getQueryVectorField());
        assertEquals("gt", config.getQueriesMapping().getGroundTruthField());
        assertEquals("cond", config.getQueriesMapping().getConditionsField());

        assertNotNull(config.getParamGroups());
        assertEquals(100, config.getParamGroups().get("recall@100").getFirst().get("size"));
        assertEquals(1000, config.getParamGroups().get("recall@100").getFirst().get("k"));
    }

    @Test
    void yamlRoundTripPreservesMappedFields() throws Exception {
        DatasetConfig original = new DatasetConfig();
        original.setType("t");
        original.setIndexName("idx");
        original.setVectorSize(128);
        original.setDistance("dot_product");
        original.setSchemaName("sch");
        original.setQueryName("qn");

        DatasetConfig.PathConfig path = new DatasetConfig.PathConfig();
        path.setDataPath("a");
        path.setQueriesPath("b");
        path.setDataUrlEnv("C");
        path.setQueriesUrlEnv("D");
        original.setPath(path);

        DatasetConfig.DataMappingConfig dm = new DatasetConfig.DataMappingConfig();
        dm.setIdField("i");
        dm.setVectorField("v");
        original.setDataMapping(dm);

        DatasetConfig.QueriesMappingConfig qm = new DatasetConfig.QueriesMappingConfig();
        qm.setQueryVectorField("qv");
        qm.setGroundTruthField("g");
        qm.setConditionsField("c");
        original.setQueriesMapping(qm);

        original.setParamGroups(
                Map.of("g", List.of(Map.of("x", (Object) 1))));

        String yaml = YAML_MAPPER.writeValueAsString(original);
        DatasetConfig roundTripped = YAML_MAPPER.readValue(yaml, DatasetConfig.class);

        assertEquals(original.getType(), roundTripped.getType());
        assertEquals(original.getIndexName(), roundTripped.getIndexName());
        assertEquals(original.getVectorSize(), roundTripped.getVectorSize());
        assertEquals(original.getDistance(), roundTripped.getDistance());
        assertEquals(original.getSchemaName(), roundTripped.getSchemaName());
        assertEquals(original.getQueryName(), roundTripped.getQueryName());
        assertEquals(path.getDataPath(), roundTripped.getPath().getDataPath());
        assertEquals(path.getQueriesPath(), roundTripped.getPath().getQueriesPath());
        assertEquals(path.getDataUrlEnv(), roundTripped.getPath().getDataUrlEnv());
        assertEquals(path.getQueriesUrlEnv(), roundTripped.getPath().getQueriesUrlEnv());
        assertEquals(dm.getIdField(), roundTripped.getDataMapping().getIdField());
        assertEquals(dm.getVectorField(), roundTripped.getDataMapping().getVectorField());
        assertEquals(qm.getQueryVectorField(), roundTripped.getQueriesMapping().getQueryVectorField());
        assertEquals(qm.getGroundTruthField(), roundTripped.getQueriesMapping().getGroundTruthField());
        assertEquals(qm.getConditionsField(), roundTripped.getQueriesMapping().getConditionsField());
        assertEquals(1, roundTripped.getParamGroups().get("g").getFirst().get("x"));
    }
}
