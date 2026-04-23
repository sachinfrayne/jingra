DEMOS := \
	elasticsearch-lexical-search \
	elasticsearch-vector-search \
	elasticsearch-hybrid-search \
	elasticsearch-ndjson-file-format \
	elasticsearch-parquet-file-format

.PHONY: run-demo $(DEMOS) help

help:
	@echo "Jingra Demo Runner"
	@echo "=================="
	@echo ""
	@echo "Run a single demo:"
	@echo "  make run-demo elasticsearch-lexical-search"
	@echo "  make run-demo elasticsearch-vector-search"
	@echo "  make run-demo elasticsearch-hybrid-search"
	@echo "  make run-demo elasticsearch-ndjson-file-format"
	@echo "  make run-demo elasticsearch-parquet-file-format"
	@echo ""
	@echo "Run all demos:"
	@echo "  make run-demo all"
	@echo ""
	@echo "Available demos:"
	@for d in $(DEMOS); do echo "  $$d"; done

run-demo:
	$(eval DEMO := $(filter-out $@,$(MAKECMDGOALS)))
	@if [ -z "$(DEMO)" ]; then \
		echo "Usage: make run-demo <demo-name> | all"; \
		echo "Available: $(DEMOS)"; \
		exit 1; \
	elif [ "$(DEMO)" = "all" ]; then \
		for d in $(DEMOS); do \
			echo ""; \
			echo "========================================"; \
			echo "Running demo: $$d"; \
			echo "========================================"; \
			$(MAKE) -C $$d run || exit 1; \
		done; \
		echo ""; \
		echo "All demos completed successfully."; \
	else \
		$(MAKE) -C $(DEMO) run; \
	fi

%:
	@:
