include ../common/generate.mk

.PHONY: elasticsearch opensearch qdrant

elasticsearch opensearch qdrant: generate_data
	$(MAKE) -C $@ run

%:
	@:
