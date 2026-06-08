include ../common/generate.mk

.PHONY: elasticsearch prometheus

elasticsearch prometheus: generate_data
	$(MAKE) -C $@ run

%:
	@:
