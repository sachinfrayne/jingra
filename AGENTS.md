---
description: Jingra Java project instructions
alwaysApply: true
---

# AGENTS

* **Source of truth:** Only this file defines the project instructions. `CLAUDE.md` and `.cursor/rules/jingra-project.mdc` must symlink to it. After cloning, run `./ensure-agent-links.sh` once.
* **Environment:** Use Java 21 from `.envrc`. Run commands with `source .envrc && <command>`, for example `source .envrc && make test`.
* **TDD first:** For every new behavior or bug fix, write or update the test first.
* **Show the test first:** Show the new or changed test, or the diff, before changing implementation code.
* **Then implement:** Change the code only until the tests pass.
* **Do not fake passing tests:** Do not weaken assertions, remove valid test cases, or loosen mocks to hide bugs.
* **Do not skip flaky tests:** Do not claim a flaky test can simply be skipped.
* **If a test is wrong:** Say so clearly. Fix the test in a separate step and explain why.
* **Green build means correct behavior:** A green build only counts if the behavior is actually correct, not if the test suite was gamed.
* **No fixed sleeps:** Do not use `Thread.sleep` or fixed delays for coordination, retries, backpressure, or waiting for readiness.
* **Use proper concurrency tools instead:** Prefer `ExecutorService`, `CompletableFuture`, `awaitTermination`, `CountDownLatch`, `Phaser`, bounded blocking queues, or polling with a timeout.
* **For async or eventual tests:** Use Awaitility or a similar tool. Do not use sleeps.
