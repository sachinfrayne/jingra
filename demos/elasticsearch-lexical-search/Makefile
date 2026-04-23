.PHONY: help build start load eval analyze clean run

export ES_VERSION := $(shell cat ../../engine-versions/.elasticsearch | tr -d '[:space:]')

help:
	@echo "Elasticsearch Lexical Search Demo"
	@echo "=================================="
	@echo ""
	@echo "Available commands:"
	@echo "  make build      - Compile Jingra"
	@echo "  make start      - Start Elasticsearch"
	@echo "  make load       - Load data into Elasticsearch"
	@echo "  make eval       - Run benchmark evaluation"
	@echo "  make analyze    - Analyze benchmark results"
	@echo "  make clean      - Stop and remove all containers"
	@echo "  make run        - Run the full demo"
	@echo ""

build:
	@echo "Compiling Jingra..."
	cd ../.. && mvn package -DskipTests -q
	docker-compose build jingra

start:
	@echo "Starting Elasticsearch..."
	docker-compose up -d --build elasticsearch
	@if docker-compose ps elasticsearch | grep -q "healthy"; then \
		echo "✓ Elasticsearch is ready"; \
	else \
		echo "Waiting for Elasticsearch to be healthy..."; \
		timeout=60; \
		while [ $$timeout -gt 0 ]; do \
			sleep 1; \
			timeout=$$((timeout - 1)); \
			if docker-compose ps elasticsearch | grep -q "healthy"; then \
				echo "✓ Elasticsearch is ready"; \
				break; \
			fi; \
		done; \
		if [ $$timeout -eq 0 ]; then \
			echo "❌ Elasticsearch failed to become healthy"; \
			exit 1; \
		fi; \
	fi

load:
	@echo "Loading data into Elasticsearch..."
	docker-compose run --rm jingra load config.yaml

eval:
	@echo "Running benchmark evaluation..."
	docker-compose run --rm jingra eval config.yaml

analyze:
	@echo "Analyzing benchmark results..."
	docker-compose run --rm jingra analyze config.yaml

clean:
	@echo "Stopping and removing containers..."
	docker-compose down -v

run: build start load eval analyze clean
