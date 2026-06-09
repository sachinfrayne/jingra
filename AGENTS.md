---
description: Jingra Java project instructions
alwaysApply: true
---

# AGENTS

* **Source of truth:** Only this file defines the project instructions. `CLAUDE.md` and `.cursor/rules/jingra-project.mdc` must symlink to it. After cloning, run `./scripts/ensure-agent-links.sh` once.
* **Environment:** Use Java 21 from `.envrc`. Run commands with `source .envrc && <command>`, for example `source .envrc && make test`.

# TEST DISCIPLINE

* **TDD first:** For every new behavior or bug fix, write or update the test first.
* **Show the test first:** Show the new or changed test, or the diff, before changing implementation code.
* **Then implement:** Change the code only until the tests pass.
* **Do not fake passing tests:** Do not weaken assertions, remove valid test cases, or loosen mocks to hide bugs.
* **Do not skip flaky tests:** Do not claim a flaky test can simply be skipped.
* **If a test is wrong:** Say so clearly. Fix the test in a separate step and explain why.
* **Green build means correct behavior:** A green build only counts if the behavior is actually correct, not if the test suite was gamed.

# COVERAGE

* **Full coverage required:** Every line and branch of code you write must be covered. Do not commit code that adds new JaCoCo misses.
* **Always run `make test`**, not `mvn test` alone — the JaCoCo gate only fires during `verify`. A task is not complete until `make test` passes.
* **Fix all coverage gaps**, including pre-existing ones — there are no acceptable misses.

# CONCURRENCY

* **No fixed sleeps:** Do not use `Thread.sleep` or fixed delays for coordination, retries, backpressure, or waiting for readiness.
* **Use proper concurrency tools instead:** Prefer `ExecutorService`, `CompletableFuture`, `awaitTermination`, `CountDownLatch`, `Phaser`, bounded blocking queues, or polling with a timeout.
* **For async or eventual tests:** Use Awaitility or a similar tool. Do not use sleeps.

# ENGINE DESIGN

* **No engine-to-engine inheritance:** Every engine extends `AbstractBenchmarkEngine` directly. No engine may extend another engine class.
* **Always use a client library when one exists:** If an engine has an official Java client library, use it. Only fall back to raw `java.net.http.HttpClient` when no client library is available.
* **Each engine is self-contained:** Shared logic between engines belongs in a helper class, not in a parent engine.

# TOOLING

This project is developed using [Cursor](https://cursor.com) and [Claude Code](https://claude.ai/code) as AI-assisted development tools.
