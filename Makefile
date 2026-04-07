# Build and push: make image=<DOCKER_URL>
TAG := ${image}
JAVA_VERSION := $(shell cat .java-version)

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
	@echo "  make test                       Run tests with fail-fast (default)"
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
	@echo "\n::: Validating Java version consistency"
	@./validate-java-version.sh
	@echo "\n::: Validating engine version consistency"
	@./validate-engine-versions.sh
	@echo "\n::: Running tests with fail-fast enabled"
	mvn clean verify -Dsurefire.skipAfterFailureCount=1

# Run all tests regardless of failures (useful for seeing all issues at once)
test-all:
	@echo "\n::: Validating Java version consistency"
	@./validate-java-version.sh
	@echo "\n::: Validating engine version consistency"
	@./validate-engine-versions.sh
	@echo "\n::: Running all tests with coverage enforcement"
	mvn clean verify

# Build Docker image (tests must pass first)
build: test
	@echo "\n::: Building and pushing multi-arch image $(TAG) with Java $(JAVA_VERSION)"
	docker buildx build --push -f Dockerfile --platform linux/amd64,linux/arm64/v8 --build-arg JAVA_VERSION=$(JAVA_VERSION) --tag $(TAG) .

# Use if you see "parent snapshot does not exist" (stale/corrupt cache)
build-no-cache: test
	@echo "\n::: Building and pushing (no cache) $(TAG) with Java $(JAVA_VERSION)"
	docker buildx build --no-cache --push -f Dockerfile --platform linux/amd64,linux/arm64/v8 --build-arg JAVA_VERSION=$(JAVA_VERSION) --tag $(TAG) .

# View coverage report in browser
coverage:
	@echo "\n::: Opening coverage report"
	@mvn jacoco:report
	@open target/site/jacoco/index.html || xdg-open target/site/jacoco/index.html || echo "Coverage report: target/site/jacoco/index.html"

clean:
	@echo "\n::: Cleaning build artifacts and Docker cache"
	mvn clean
	docker buildx prune -f

pull:
	docker pull $(TAG)
