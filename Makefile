# Build and push: make build image=<DOCKER_URL>
TAG := ${image}

.DEFAULT_GOAL := help

help:
	@echo "Jingra - Available Commands"
	@echo "============================"
	@echo ""
	@echo "Build & Push:"
	@echo "  make build image=<tag>          Build and push multi-arch Docker image"
	@echo "  make build-no-cache image=<tag> Build and push without cache"
	@echo "  make pull image=<tag>           Pull Docker image"
	@echo ""
	@echo "Testing & Coverage:"
	@echo "  make test                       Run tests with fail-fast (default; requires Docker)"
	@echo "  make test-all                   Run all tests regardless of failures"
	@echo "  make coverage                   View coverage report in browser"
	@echo ""
	@echo "Maintenance:"
	@echo "  make clean                      Clean build artifacts and Docker cache"
	@echo ""
	@echo "Example:"
	@echo "  make build image=<DOCKER_URL>"
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

# Build Docker image (tests must pass first)
build: test
	@echo "\n::: Building and pushing multi-arch image $(TAG)"
	docker buildx build --push -f Dockerfile --platform linux/amd64,linux/arm64/v8 --tag $(TAG) .

# Use if you see stale/corrupt buildx cache issues
build-no-cache: test
	@echo "\n::: Building and pushing (no cache) $(TAG)"
	docker buildx build --no-cache --push -f Dockerfile --platform linux/amd64,linux/arm64/v8 --tag $(TAG) .

# Generate an HTML coverage report and open it in a browser.
# Threshold is a floor on the current codebase's actual coverage (~91% region / ~92% line as of
# the Rust rewrite), not literal 100% -- see AGENTS.md for why, and raise it as coverage improves.
coverage:
	@echo "\n::: Generating coverage report"
	cargo llvm-cov --html --fail-under-lines 90 --fail-under-regions 88
	@open target/llvm-cov/html/index.html || xdg-open target/llvm-cov/html/index.html || echo "Coverage report: target/llvm-cov/html/index.html"

clean:
	@echo "\n::: Cleaning build artifacts and Docker cache"
	cargo clean
	docker buildx prune -f

pull:
	docker pull $(TAG)

.PHONY: help test test-all build build-no-cache coverage clean pull
