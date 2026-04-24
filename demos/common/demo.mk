.PHONY: help build start load eval analyze stop clean run

DEMO_MK_DIR := $(dir $(lastword $(MAKEFILE_LIST)))

COMPOSE_OVERRIDES ?=
ENGINE_SERVICE ?= elasticsearch
DEMO_OUTPUT_DIRS ?= output

export ES_VERSION := $(shell cat $(DEMO_MK_DIR)../../engine-versions/.elasticsearch | tr -d '[:space:]')

COMPOSE = docker-compose \
    -f $(DEMO_MK_DIR)docker-compose.yml \
    $(COMPOSE_OVERRIDES) \
    --project-directory . \
    --project-name $(notdir $(CURDIR))

help:
	@echo "$(DEMO_NAME)"
	@echo "=================================="
	@echo ""
	@echo "Available commands:"
	@echo "  make build      - Compile Jingra"
	@echo "  make start      - Start Elasticsearch"
	@echo "  make load       - Load data into Elasticsearch"
	@echo "  make eval       - Run benchmark evaluation"
	@echo "  make analyze    - Analyze benchmark results"
	@echo "  make stop       - Stop Elasticsearch"
	@echo "  make clean      - Stop Elasticsearch and remove output dir"
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
	$(COMPOSE) up -d --build elasticsearch $(ENGINE_SERVICE)
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

load:
	@echo "Loading data into Elasticsearch..."
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
