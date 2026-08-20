//! Integration test against a real Qdrant container (Testcontainers) -- no mocking the engine,
//! per AGENTS.md. Exercises the full lifecycle: connect, create collection (REST passthrough),
//! ingest, query, document count, awaitIndexReady, delete.
//!
//! Requires Docker (or a Docker-API-compatible engine, e.g. podman with `DOCKER_HOST` pointed at
//! its API socket).

use jingra::engine::qdrant::QdrantEngine;
use jingra::engine::BenchmarkEngine;
use jingra::model::{Document, QueryParams};
use serde_json::json;
use std::collections::HashMap;
use std::fs;
use testcontainers::core::{IntoContainerPort, WaitFor};
use testcontainers::runners::AsyncRunner;
use testcontainers::GenericImage;

const QDRANT_VERSION: &str = "v1.18.0";

fn write_template(subdir: &str, name: &str, contents: &serde_json::Value) {
    let dir = std::path::Path::new("jingra-config").join(subdir);
    fs::create_dir_all(&dir).expect("create jingra-config dir");
    fs::write(
        dir.join(format!("{name}.json")),
        serde_json::to_string_pretty(contents).unwrap(),
    )
    .expect("write template");
}

#[tokio::test]
async fn full_lifecycle_against_real_qdrant_container() {
    let container = GenericImage::new("qdrant/qdrant", QDRANT_VERSION)
        .with_exposed_port(6333.tcp())
        .with_exposed_port(6334.tcp())
        .with_wait_for(WaitFor::message_on_stdout("Qdrant HTTP listening on 6333"))
        .start()
        .await
        .expect("qdrant container starts");

    let grpc_port = container.get_host_port_ipv4(6334).await.expect("grpc port mapped");
    let rest_port = container.get_host_port_ipv4(6333).await.expect("rest port mapped");

    let index_name = "jingra-it-lifecycle";
    let schema_name = "jingra-it-lifecycle-schema";
    let query_name = "jingra-it-lifecycle-query";

    write_template(
        "schemas",
        schema_name,
        &json!({"template": {"vectors": {"size": 4, "distance": "cosine"}}}),
    );
    write_template(
        "queries",
        query_name,
        &json!({
            "template": {
                "vector": "{{query_vector}}",
                "limit": "{{size}}",
                "with_payload": true,
                "params": {"hnsw_ef": "{{hnsw_ef}}"}
            }
        }),
    );

    let mut config = HashMap::new();
    config.insert("url".to_string(), json!(format!("http://127.0.0.1:{grpc_port}")));
    config.insert("rest_url".to_string(), json!(format!("http://127.0.0.1:{rest_port}")));
    config.insert("vector_field".to_string(), json!("embedding"));

    let engine = QdrantEngine::new(config).with_poll_interval_ms(200);

    engine.connect().await.expect("connect to qdrant");
    assert_eq!(engine.engine_name(), "qdrant");

    let version = engine.version().await.expect("get version");
    assert!(!version.is_empty());

    assert!(!engine.index_exists(index_name).await.expect("index_exists"));
    engine.create_index(index_name, schema_name).await.expect("create_index");
    assert!(engine.index_exists(index_name).await.expect("index_exists after create"));

    let docs = vec![
        Document::new(HashMap::from([
            ("id".to_string(), json!(1)),
            ("embedding".to_string(), json!([1.0, 0.0, 0.0, 0.0])),
            ("label".to_string(), json!("first")),
        ])),
        Document::new(HashMap::from([
            ("id".to_string(), json!(2)),
            ("embedding".to_string(), json!([0.0, 1.0, 0.0, 0.0])),
            ("label".to_string(), json!("second")),
        ])),
    ];
    let ingested = engine
        .ingest(docs, index_name, Some("id"))
        .await
        .expect("ingest");
    assert_eq!(ingested, 2);

    engine.await_index_ready(index_name).await.expect("await_index_ready");

    let count = engine.document_count(index_name).await.expect("document_count");
    assert_eq!(count, 2);

    let mut params = QueryParams::default();
    params.put("query_vector", json!([1.0, 0.0, 0.0, 0.0]));
    params.put("size", 1);
    params.put("hnsw_ef", 64);
    let response = engine
        .query(index_name, query_name, &params)
        .await
        .expect("query");
    assert_eq!(response.document_ids.len(), 1);
    assert_eq!(response.document_ids[0], "1");
    assert!(response.client_latency_ms.unwrap() >= 0.0);
    assert!(response.server_latency_ms.unwrap() >= 0);

    let metadata = engine.index_metadata(index_name).await.expect("index_metadata");
    assert_eq!(metadata.get("points_count"), Some(&"2".to_string()));

    engine.delete_index(index_name).await.expect("delete_index");
    assert!(!engine.index_exists(index_name).await.expect("index_exists after delete"));

    // Deleting an already-absent collection is idempotent (matches Java's NOT_FOUND handling).
    engine.delete_index(index_name).await.expect("delete_index idempotent");
}
