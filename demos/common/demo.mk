.PHONY: help build start load eval analyze stop clean run

DEMO_MK_DIR := $(dir $(lastword $(MAKEFILE_LIST)))

COMPOSE_OVERRIDES ?=
ENGINE_SERVICE ?= elasticsearch
SINK_SERVICE    ?= elasticsearch-sink
DEMO_OUTPUT_DIRS ?= output
POST_START_HOOK ?=

SINK_ENGINE ?= elasticsearch
export SINK_ENGINE
export SINK_VERSION := $(shell cat $(DEMO_MK_DIR)../../engine-versions/.$(SINK_ENGINE) | tr -d '[:space:]')

COMPOSE = docker-compose \
    -f $(DEMO_MK_DIR)docker-compose.yml \
    -f $(DEMO_MK_DIR)docker-compose.sink.yml \
    $(COMPOSE_OVERRIDES) \
    --project-directory . \
    --project-name $(notdir $(CURDIR))

help:
	@echo "$(DEMO_NAME)"
	@echo "=================================="
	@echo ""
	@echo "Available commands:"
	@echo "  make build      - Compile Jingra"
	@echo "  make start      - Start $(ENGINE_SERVICE)"
	@echo "  make load       - Load data into $(ENGINE_SERVICE)"
	@echo "  make eval       - Run benchmark evaluation"
	@echo "  make analyze    - Analyze benchmark results"
	@echo "  make stop       - Stop $(ENGINE_SERVICE)"
	@echo "  make clean      - Stop $(ENGINE_SERVICE) and remove output dir"
	@echo ""
	@echo "Full demo lifecycle:"
	@echo "  make run        - Run the full demo"
	@echo ""

build:
	@echo "Compiling Jingra..."
	cd $(DEMO_MK_DIR)../.. && mvn package -DskipTests -q
	$(COMPOSE) build jingra

start:
	@echo "Starting $(ENGINE_SERVICE)..."
	$(COMPOSE) up -d --build $(SINK_SERVICE) $(ENGINE_SERVICE)
	@if $(COMPOSE) ps $(ENGINE_SERVICE) | grep -q "healthy"; then \
		echo "✓ $(ENGINE_SERVICE) is ready"; \
	else \
		echo "Waiting for $(ENGINE_SERVICE) to be healthy..."; \
		timeout=60; \
		while [ $$timeout -gt 0 ]; do \
			sleep 1; \
			timeout=$$((timeout - 1)); \
			if $(COMPOSE) ps $(ENGINE_SERVICE) | grep -q "healthy"; then \
				echo "✓ $(ENGINE_SERVICE) is ready"; \
				break; \
			fi; \
		done; \
		if [ $$timeout -eq 0 ]; then \
			echo "❌ $(ENGINE_SERVICE) failed to become healthy"; \
			exit 1; \
		fi; \
	fi
	@echo "Waiting for sink to accept requests..."; \
	timeout=30; \
	while [ $$timeout -gt 0 ]; do \
		if curl -sf -X PUT "http://localhost:9200/_index_template/jingra-defaults" \
			-H "Content-Type: application/json" \
			-d '{"index_patterns":["jingra-*"],"template":{"settings":{"number_of_replicas":0}}}' > /dev/null 2>&1; then \
			echo "✓ Sink index template configured (number_of_replicas=0)"; \
			break; \
		fi; \
		sleep 1; \
		timeout=$$((timeout - 1)); \
		if [ $$timeout -eq 0 ]; then \
			echo "❌ Sink failed to accept index template request"; \
			exit 1; \
		fi; \
	done
	$(if $(POST_START_HOOK),@$(POST_START_HOOK))

load:
	@echo "Loading data into $(ENGINE_SERVICE)..."
	$(COMPOSE) run --rm jingra load config.yaml

eval:
	@echo "Running benchmark evaluation..."
	$(COMPOSE) run --rm jingra eval config.yaml

analyze:
	@echo "Analyzing benchmark results..."
	$(COMPOSE) run --rm jingra analyze config.yaml

stop stop-end:
	@echo "Stopping and removing containers..."
	$(COMPOSE) down -v

clean: stop
ifneq ($(strip $(DEMO_OUTPUT_DIRS)),)
	@echo "Removing output directories..."
	rm -rf $(foreach d,$(DEMO_OUTPUT_DIRS),$(CURDIR)/$(d))
endif

run: clean build start load eval analyze stop-end
