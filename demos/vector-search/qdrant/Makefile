DEMO_NAME := Qdrant Vector Search Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export QD_VERSION := $(shell cat ../../../engine-versions/.qdrant | tr -d '[:space:]')
COMPOSE_OVERRIDES = -f ../../common/docker-compose.qdrant.yml
ENGINE_SERVICE := qdrant
include ../../common/demo.mk
