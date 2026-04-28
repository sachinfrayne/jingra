include ../common/generate.mk

.PHONY: elasticsearch opensearch

elasticsearch opensearch: generate_data
	$(MAKE) -C $@ run

%:
	@:
