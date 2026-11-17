.DEFAULT_GOAL := help

help:
	@echo "Jingra - Available Commands"
	@echo "============================"
	@echo ""
	@echo "Build:"
	@echo "  make build                      Build the optimized binary (target/release/jingra)"
	@echo "  make docker-build               Build a local Docker image (jingra:local)"
	@echo ""
	@echo "Testing & Coverage:"
	@echo "  make test                       Run tests with fail-fast (default; requires Docker)"
	@echo "  make test-all                   Run all tests regardless of failures"
	@echo "  make coverage                   View coverage report in browser"
	@echo ""
	@echo "Maintenance:"
	@echo "  make clean                      Remove cargo build artifacts"
	@echo ""

# Run tests with fail-fast (default - best for CI/CD and development)
test:
	@echo "\n::: Running clippy (warnings are errors)"
	cargo clippy --all-targets -- -D warnings
	@echo "\n::: Running tests with fail-fast enabled"
	cargo nextest run --fail-fast --retries 1
	cargo test --doc

# Run all tests regardless of failures (useful for seeing all issues at once)
test-all:
	@echo "\n::: Running clippy (warnings are errors)"
	cargo clippy --all-targets -- -D warnings
	@echo "\n::: Running all tests"
	cargo nextest run --retries 1
	cargo test --doc

# Optimized release binary at target/release/jingra.
build:
	cargo build --release

# Local Docker image. Images are not published: build your own from this source.
docker-build:
	docker build -t jingra:local .

# Generate an HTML coverage report and open it in a browser.
# Threshold is a floor on the current codebase's actual coverage (~93% region / ~94% line as of
# the Rust rewrite), not literal 100% -- see AGENTS.md for why, and raise it as coverage improves.
coverage:
	@echo "\n::: Generating coverage report"
	cargo llvm-cov --html --fail-under-lines 94 --fail-under-regions 93
	@open target/llvm-cov/html/index.html || xdg-open target/llvm-cov/html/index.html || echo "Coverage report: target/llvm-cov/html/index.html"

clean:
	@echo "\n::: Cleaning build artifacts"
	cargo clean

.PHONY: help test test-all build docker-build coverage clean
