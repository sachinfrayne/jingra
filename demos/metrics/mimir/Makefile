DEMO_NAME := Mimir Metrics Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export MIMIR_VERSION := $(shell cat ../../../engine-versions/.mimir | tr -d '[:space:]')
export MIMIR_CONFIG_FILE := $(abspath ../../common/mimir.yaml)
COMPOSE_OVERRIDES = -f ../../common/docker-compose.mimir.yml
ENGINE_SERVICE := mimir
ENGINE_READY_URL := http://localhost:8080/ready
include ../../common/demo.mk
