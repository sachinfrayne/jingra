DEMOS := \
	lexical-search/elasticsearch \
	lexical-search/opensearch \
	vector-search/elasticsearch \
	vector-search/opensearch \
	vector-search/qdrant \
	hybrid-search/elasticsearch \
	parquet-file-format/elasticsearch

ENGINES := $(sort $(notdir $(DEMOS)))

.PHONY: help all stopall verify $(DEMOS)

help:
	@echo "Jingra Demo Runner"
	@echo "=================="
	@echo ""
	@echo "Run a single demo:"
	@for d in $(DEMOS); do echo "  make $$d"; done
	@echo ""
	@echo "Run all demos:"
	@echo "  make all"
	@echo ""
	@echo "Utilities:"
	@echo "  make stopall   - Stop all lingering demo containers"
	@echo "  make verify    - Check all demos produced output CSV files"

stopall:
	@echo "Stopping any lingering demo containers..."
	@for engine in $(ENGINES); do \
		docker-compose --project-name $$engine down -v 2>/dev/null || true; \
	done

all:
	@$(MAKE) stopall
	@touch .run-start
	@for d in $(DEMOS); do \
		echo ""; \
		echo "========================================"; \
		echo "Running demo: $$d"; \
		echo "========================================"; \
		$(MAKE) $$d || exit 1; \
	done
	@$(MAKE) verify

verify:
	@echo ""
	@echo "Verifying demo outputs..."
	@failed=0; \
	for d in $(DEMOS); do \
		if [ -f .run-start ]; then \
			found=$$(find $$d/output -name "*.csv" -newer .run-start 2>/dev/null | head -1); \
		else \
			found=$$(find $$d/output -name "*.csv" 2>/dev/null | head -1); \
		fi; \
		if [ -n "$$found" ]; then \
			echo "  ✓ $$d"; \
		else \
			echo "  ✗ $$d  (no CSV files in $$d/output/)"; \
			failed=$$((failed + 1)); \
		fi; \
	done; \
	echo ""; \
	if [ $$failed -gt 0 ]; then \
		echo "❌ $$failed demo(s) produced no output"; \
		exit 1; \
	else \
		echo "✓ All $(words $(DEMOS)) demos produced output"; \
	fi

$(DEMOS):
	$(MAKE) -C $(dir $@) $(notdir $@)

%:
	@:
