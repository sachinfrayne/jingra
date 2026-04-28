DEMO_NAME := Elasticsearch Hybrid Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export ES_VERSION := $(shell cat ../../../engine-versions/.elasticsearch | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.elasticsearch.yml
ENGINE_SERVICE := elasticsearch
POST_START_HOOK = curl -sf -X POST "http://localhost:9201/_license/start_trial?acknowledge=true" > /dev/null && echo "✓ Trial license activated"
include ../../common/demo.mk
