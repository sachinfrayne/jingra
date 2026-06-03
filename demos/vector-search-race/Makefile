DEMO_NAME   := Vector Search Engine Race (Elasticsearch vs OpenSearch vs Qdrant)
DEMO_MK_DIR := $(dir $(abspath $(firstword $(MAKEFILE_LIST))))

include ../common/generate.mk

export BUILD_CONTEXT := $(abspath $(DEMO_MK_DIR)../..)
export COMMON_DIR    := $(abspath $(DEMO_MK_DIR)../common)
export DATA_PATH     := $(DEMO_MK_DIR)data
export SINK_VERSION  := $(shell cat $(DEMO_MK_DIR)../../engine-versions/.elasticsearch | tr -d '[:space:]')
export ES_VERSION    := $(shell cat $(DEMO_MK_DIR)../../engine-versions/.elasticsearch | tr -d '[:space:]')
export OS_VERSION    := $(shell cat $(DEMO_MK_DIR)../../engine-versions/.opensearch | tr -d '[:space:]')
export QD_VERSION    := $(shell cat $(DEMO_MK_DIR)../../engine-versions/.qdrant | tr -d '[:space:]')

COMPOSE = docker-compose \
	-f $(DEMO_MK_DIR)docker-compose.yml \
	--project-directory $(DEMO_MK_DIR) \
	--project-name vector-search-race

.PHONY: help build start-sink \
	es-start es-load es-eval es-stop es-run \
	os-start os-load os-eval os-stop os-run \
	qdrant-start qdrant-load qdrant-eval qdrant-stop qdrant-run \
	analyze stop-all clean run

help:
	@echo "$(DEMO_NAME)"
	@echo "======================================================="
	@echo ""
	@echo "Full race:"
	@echo "  make run            - Run all three engines sequentially then plot combined results"
	@echo ""
	@echo "Individual steps:"
	@echo "  make build          - Compile Jingra and build Docker images"
	@echo "  make start-sink     - Start the Elasticsearch sink"
	@echo "  make es-run         - Load + eval Elasticsearch, then stop it"
	@echo "  make os-run         - Load + eval OpenSearch, then stop it"
	@echo "  make qdrant-run     - Load + eval Qdrant, then stop it"
	@echo "  make analyze        - Generate combined plots from all engines"
	@echo "  make stop-all       - Stop and remove all containers"
	@echo "  make clean          - Stop containers and remove output directories"
	@echo "  make regenerate_data - Force regenerate benchmark data"
	@echo ""
	@echo "Plots land in: analysis/output/"
	@echo ""

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

build:
	@echo "Compiling Jingra..."
	cd $(BUILD_CONTEXT) && mvn package -DskipTests -q
	$(COMPOSE) build jingra qdrant

# ---------------------------------------------------------------------------
# Sink
# ---------------------------------------------------------------------------

start-sink:
	@echo "Starting Elasticsearch sink..."
	$(COMPOSE) up -d elasticsearch-sink
	@echo "Waiting for sink to be healthy..."
	@timeout=120; \
	while [ $$timeout -gt 0 ]; do \
		if $(COMPOSE) ps elasticsearch-sink | grep -q "healthy"; then \
			echo "✓ Sink is ready"; \
			break; \
		fi; \
		sleep 2; \
		timeout=$$((timeout - 2)); \
		if [ $$timeout -le 0 ]; then \
			echo "❌ Sink failed to become healthy"; \
			exit 1; \
		fi; \
	done
	@timeout=30; \
	while [ $$timeout -gt 0 ]; do \
		if curl -sf -X PUT "http://localhost:9200/_index_template/jingra-defaults" \
			-H "Content-Type: application/json" \
			-d '{"index_patterns":["jingra-*"],"template":{"settings":{"number_of_replicas":0}}}' > /dev/null 2>&1; then \
			echo "✓ Sink index template configured"; \
			break; \
		fi; \
		sleep 1; \
		timeout=$$((timeout - 1)); \
		if [ $$timeout -le 0 ]; then \
			echo "❌ Sink failed to accept index template request"; \
			exit 1; \
		fi; \
	done

# ---------------------------------------------------------------------------
# Elasticsearch
# ---------------------------------------------------------------------------

es-start:
	@echo "Starting Elasticsearch..."
	$(COMPOSE) up -d elasticsearch
	@timeout=120; \
	while [ $$timeout -gt 0 ]; do \
		if $(COMPOSE) ps elasticsearch | grep -q "healthy"; then \
			echo "✓ Elasticsearch is ready"; \
			break; \
		fi; \
		sleep 2; \
		timeout=$$((timeout - 2)); \
		if [ $$timeout -le 0 ]; then \
			echo "❌ Elasticsearch failed to become healthy"; \
			exit 1; \
		fi; \
	done

es-load:
	@echo "Loading data into Elasticsearch..."
	@mkdir -p $(DEMO_MK_DIR)elasticsearch/output
	CONFIG_FILE=$(DEMO_MK_DIR)elasticsearch/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)elasticsearch/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)elasticsearch/output \
	$(COMPOSE) run --rm jingra load config.yaml

es-eval:
	@echo "Running Elasticsearch benchmark evaluation..."
	CONFIG_FILE=$(DEMO_MK_DIR)elasticsearch/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)elasticsearch/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)elasticsearch/output \
	$(COMPOSE) run --rm jingra eval config.yaml

es-stop:
	@echo "Stopping Elasticsearch..."
	$(COMPOSE) stop elasticsearch
	$(COMPOSE) rm -f elasticsearch

es-run: es-start es-load es-eval es-stop
	@echo "✓ Elasticsearch race leg complete"

# ---------------------------------------------------------------------------
# OpenSearch
# ---------------------------------------------------------------------------

os-start:
	@echo "Starting OpenSearch..."
	$(COMPOSE) up -d opensearch
	@timeout=120; \
	while [ $$timeout -gt 0 ]; do \
		if $(COMPOSE) ps opensearch | grep -q "healthy"; then \
			echo "✓ OpenSearch is ready"; \
			break; \
		fi; \
		sleep 2; \
		timeout=$$((timeout - 2)); \
		if [ $$timeout -le 0 ]; then \
			echo "❌ OpenSearch failed to become healthy"; \
			exit 1; \
		fi; \
	done

os-load:
	@echo "Loading data into OpenSearch..."
	@mkdir -p $(DEMO_MK_DIR)opensearch/output
	CONFIG_FILE=$(DEMO_MK_DIR)opensearch/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)opensearch/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)opensearch/output \
	$(COMPOSE) run --rm jingra load config.yaml

os-eval:
	@echo "Running OpenSearch benchmark evaluation..."
	CONFIG_FILE=$(DEMO_MK_DIR)opensearch/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)opensearch/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)opensearch/output \
	$(COMPOSE) run --rm jingra eval config.yaml

os-stop:
	@echo "Stopping OpenSearch..."
	$(COMPOSE) stop opensearch
	$(COMPOSE) rm -f opensearch

os-run: os-start os-load os-eval os-stop
	@echo "✓ OpenSearch race leg complete"

# ---------------------------------------------------------------------------
# Qdrant
# ---------------------------------------------------------------------------

qdrant-start:
	@echo "Starting Qdrant..."
	$(COMPOSE) up -d qdrant
	@timeout=120; \
	while [ $$timeout -gt 0 ]; do \
		if $(COMPOSE) ps qdrant | grep -q "healthy"; then \
			echo "✓ Qdrant is ready"; \
			break; \
		fi; \
		sleep 2; \
		timeout=$$((timeout - 2)); \
		if [ $$timeout -le 0 ]; then \
			echo "❌ Qdrant failed to become healthy"; \
			exit 1; \
		fi; \
	done

qdrant-load:
	@echo "Loading data into Qdrant..."
	@mkdir -p $(DEMO_MK_DIR)qdrant/output
	CONFIG_FILE=$(DEMO_MK_DIR)qdrant/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)qdrant/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)qdrant/output \
	$(COMPOSE) run --rm jingra load config.yaml

qdrant-eval:
	@echo "Running Qdrant benchmark evaluation..."
	CONFIG_FILE=$(DEMO_MK_DIR)qdrant/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)qdrant/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)qdrant/output \
	$(COMPOSE) run --rm jingra eval config.yaml

qdrant-stop:
	@echo "Stopping Qdrant..."
	$(COMPOSE) stop qdrant
	$(COMPOSE) rm -f qdrant

qdrant-run: qdrant-start qdrant-load qdrant-eval qdrant-stop
	@echo "✓ Qdrant race leg complete"

# ---------------------------------------------------------------------------
# Combined analysis
# ---------------------------------------------------------------------------

analyze:
	@echo "Generating combined plots for all three engines..."
	@mkdir -p $(DEMO_MK_DIR)analysis/output
	CONFIG_FILE=$(DEMO_MK_DIR)analysis/config.yaml \
	CONFIG_DIR=$(DEMO_MK_DIR)analysis/config \
	OUTPUT_DIR=$(DEMO_MK_DIR)analysis/output \
	$(COMPOSE) run --rm jingra analyze config.yaml

# ---------------------------------------------------------------------------
# Lifecycle
# ---------------------------------------------------------------------------

stop-all:
	@echo "Stopping and removing all containers..."
	$(COMPOSE) down -v

clean: stop-all
	@echo "Removing output directories..."
	rm -rf $(DEMO_MK_DIR)elasticsearch/output \
	       $(DEMO_MK_DIR)opensearch/output \
	       $(DEMO_MK_DIR)qdrant/output \
	       $(DEMO_MK_DIR)analysis/output

run: clean build generate_data start-sink es-run os-run qdrant-run analyze stop-all
	@echo ""
	@echo "Race complete! Combined plots are in analysis/output/"
