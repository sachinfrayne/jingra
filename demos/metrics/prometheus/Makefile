DEMO_NAME := Prometheus Metrics Demo
export DATA_PATH := ../data
export BUILD_CONTEXT := ../../..
export PROMETHEUS_VERSION := $(shell cat ../../../engine-versions/.prometheus | tr -d '[:space:]')
export PROMETHEUS_CONFIG_FILE := $(abspath ../../common/prometheus.yml)
COMPOSE_OVERRIDES = -f ../../common/docker-compose.prometheus.yml
ENGINE_SERVICE := prometheus
include ../../common/demo.mk
