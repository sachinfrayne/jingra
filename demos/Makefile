DEMOS := \
	lexical-search/elasticsearch \
	lexical-search/opensearch \
	vector-search/elasticsearch \
	vector-search/opensearch \
	vector-search/qdrant \
	hybrid-search/elasticsearch \
	parquet-file-format/elasticsearch

.PHONY: help all $(DEMOS)

help:
	@echo "Jingra Demo Runner"
	@echo "=================="
	@echo ""
	@echo "Run a single demo:"
	@for d in $(DEMOS); do echo "  make $$d"; done
	@echo ""
	@echo "Run all demos:"
	@echo "  make all"

all:
	@for d in $(DEMOS); do \
		echo ""; \
		echo "========================================"; \
		echo "Running demo: $$d"; \
		echo "========================================"; \
		$(MAKE) $$d || exit 1; \
	done
	@echo ""
	@echo "All demos completed successfully."

$(DEMOS):
	$(MAKE) -C $(dir $@) $(notdir $@)

%:
	@:
