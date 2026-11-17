.PHONY: help build start load bench report stop clean run

DEMO_MK_DIR := $(dir $(lastword $(MAKEFILE_LIST)))

COMPOSE_OVERRIDES ?=
ENGINE_SERVICE ?= qdrant
DEMO_OUTPUT_DIRS ?= output results

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
	@echo "  make start      - Start $(ENGINE_SERVICE)"
	@echo "  make load       - Load data into $(ENGINE_SERVICE)"
	@echo "  make bench      - Run the benchmark sweep"
	@echo "  make report     - Generate recall/latency CSV + plots"
	@echo "  make stop       - Stop $(ENGINE_SERVICE)"
	@echo "  make clean      - Stop $(ENGINE_SERVICE) and remove output/results dirs"
	@echo ""
	@echo "Full demo lifecycle:"
	@echo "  make run        - Run the full demo"
	@echo ""

# Note: results now land in ./results (a local file:// mount, see output.results_uri in
# config.yaml) instead of a separate results-storage cluster -- there's no longer a sink service
# to build/wait for here.
#
# The jingra image compiles from source inside its own multi-stage build (see
# demos/common/Dockerfile) rather than copying a host-built binary in: a natively-compiled Rust
# binary is tied to the host's OS/architecture, unlike a Java .jar, so "build on host, COPY into
# a Linux image" breaks for anyone not already developing on Linux.
build:
	@echo "Building Jingra image..."
	$(COMPOSE) build jingra

start:
	@echo "Starting $(ENGINE_SERVICE)..."
	$(COMPOSE) up -d --build $(ENGINE_SERVICE)
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
	@echo "Loading data into $(ENGINE_SERVICE)..."
	$(COMPOSE) run --rm jingra load config.yaml

bench:
	@echo "Running benchmark..."
	$(COMPOSE) run --rm jingra bench config.yaml

report:
	@echo "Generating report..."
	$(COMPOSE) run --rm jingra report config.yaml

stop stop-end:
	@echo "Stopping and removing containers..."
	$(COMPOSE) down -v

clean: stop
ifneq ($(strip $(DEMO_OUTPUT_DIRS)),)
	@echo "Removing output/results directories..."
	rm -rf $(foreach d,$(DEMO_OUTPUT_DIRS),$(CURDIR)/$(d))
endif

run: clean build start load bench report stop-end
