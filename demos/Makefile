DEMOS := $(sort $(patsubst %/Makefile,%,$(wildcard */*/Makefile)))

ENGINES := $(sort $(notdir $(DEMOS)))

TOP_LEVEL_DEMOS := $(sort $(patsubst %/Makefile,%,$(wildcard */Makefile)))

# Demos whose only entry point is a top-level Makefile (no engine sub-targets in DEMOS)
DEMO_PARENTS    := $(sort $(patsubst %/,%,$(dir $(DEMOS))))
SOLO_DEMOS      := $(filter-out $(DEMO_PARENTS),$(TOP_LEVEL_DEMOS))

.PHONY: help list-demos all stopall verify $(DEMOS) $(SOLO_DEMOS)

help:
	@echo "Jingra Demo Runner"
	@echo "=================="
	@echo ""
	@$(MAKE) -s list-demos
	@echo "Run all demos:"
	@echo ""
	@echo "  make all"
	@echo ""
	@echo "Utilities:"
	@echo ""
	@echo "  make stopall   - Stop all lingering demo containers"
	@echo "  make verify    - Check all demos produced output CSV files"

list-demos:
	@echo "Single engine runs:"
	@echo ""
	@for d in $(DEMOS); do \
		echo "  make $$d"; \
	done
	@echo ""
	@echo "Races (engine vs engine / profile vs profile):"
	@echo ""
	@for d in $(SOLO_DEMOS); do \
		dims=$$(awk '/^  engines:/ || /^  profiles:/{p=1;next} \
		             p && /^    - /{sub(/.*- /,""); items=items sep $$0; sep=","} \
		             p && /^  [a-z]/{p=0} \
		             END{print items}' $$d/analysis/config.yaml 2>/dev/null \
		        | sed 's/,/, /g'); \
		if [ -n "$$dims" ]; then \
			printf "  make %-28s [%s]\n" "$$d" "$$dims"; \
		else \
			echo "  make $$d"; \
		fi; \
	done
	@echo ""

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

$(SOLO_DEMOS):
	$(MAKE) -C $@ run

%:
	@:
