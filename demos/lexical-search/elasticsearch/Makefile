DEMO_NAME := Elasticsearch Lexical Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export ES_VERSION := $(shell cat ../../../engine-versions/.elasticsearch | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.elasticsearch.yml
ENGINE_SERVICE := elasticsearch
include ../../common/demo.mk
