DEMO_NAME := OpenSearch Lexical Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export OS_VERSION := $(shell cat ../../../engine-versions/.opensearch | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.opensearch.yml
ENGINE_SERVICE := opensearch
include ../../common/demo.mk
