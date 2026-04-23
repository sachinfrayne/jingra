.PHONY: help generate start load eval analyze clean rebuild

help:
	@echo "Elasticsearch Lexical Search Demo"
	@echo "=================================="
	@echo ""
	@echo "Available commands:"
	@echo "  make generate   - Generate synthetic dataset and queries"
	@echo "  make start      - Start Elasticsearch"
	@echo "  make load       - Load data into Elasticsearch"
	@echo "  make eval       - Run benchmark evaluation"
	@echo "  make analyze    - Analyze results (optional)"
	@echo "  make clean      - Stop and remove all containers"
	@echo "  make rebuild    - Rebuild Docker images"
	@echo ""
	@echo "Quick start:"
	@echo "  make generate start load eval"
	@echo ""

generate:
	@echo "Generating synthetic dataset..."
	docker-compose run --rm datagen

start:
	@echo "Starting Elasticsearch..."
	docker-compose up -d elasticsearch
	@echo "Waiting for Elasticsearch to be healthy..."
	@timeout=60; \
	while [ $$timeout -gt 0 ]; do \
		if docker-compose ps elasticsearch | grep -q "healthy"; then \
			echo "✓ Elasticsearch is ready"; \
			break; \
		fi; \
		sleep 1; \
		timeout=$$((timeout - 1)); \
	done; \
	if [ $$timeout -eq 0 ]; then \
		echo "❌ Elasticsearch failed to become healthy"; \
		exit 1; \
	fi

load:
	@echo "Loading data into Elasticsearch..."
	docker-compose run --rm jingra load config.yaml

eval:
	@echo "Running benchmark evaluation..."
	docker-compose run --rm jingra eval config.yaml

analyze:
	@echo "Analyzing results..."
	docker-compose run --rm jingra analyze config.yaml

clean:
	@echo "Stopping and removing containers..."
	docker-compose down -v

rebuild:
	@echo "Rebuilding Docker images..."
	docker-compose build --no-cache
