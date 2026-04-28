GENERATE_MK_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
GENERATE_IMAGE  := jingra-data-generator

.PHONY: generate_data

generate_data:
	@if [ -z "$$(ls -A $(CURDIR)/data 2>/dev/null)" ]; then \
		echo "No data found — generating..."; \
		mkdir -p $(CURDIR)/data; \
		docker build -t $(GENERATE_IMAGE) -f $(GENERATE_MK_DIR)Dockerfile.generate $(GENERATE_MK_DIR); \
		docker run --rm \
			-v $(CURDIR)/generate_data.py:/app/generate_data.py:ro \
			-v $(CURDIR)/data:/app/data \
			-v $(HOME)/.cache/huggingface:/root/.cache/huggingface \
			$(GENERATE_IMAGE); \
	else \
		echo "Data already present, skipping generation."; \
	fi
