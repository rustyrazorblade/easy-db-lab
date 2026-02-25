<!--
Sync Impact Report
==================
Version change: N/A → 1.0.0 (initial ratification)
Modified principles: N/A (initial creation)
Added sections:
  - Core Principles (5 principles)
  - Quality Gates
  - Tech Stack Constraints
  - Development Workflow
  - Governance
Removed sections: N/A
Templates requiring updates:
  - .specify/templates/plan-template.md ✅ (Constitution Check section exists, compatible)
  - .specify/templates/spec-template.md ✅ (no constitution references, compatible)
  - .specify/templates/tasks-template.md ✅ (no constitution references, compatible)
Follow-up TODOs: None
-->

# easy-db-lab Constitution

## Core Principles

### I. CLI-First User Experience

All user interaction occurs through command-line output. Commands and services MUST use `eventBus.emit()` with domain-specific Event types for user-facing messages, not logging frameworks. Each Event implements `toDisplayString()` which produces the console output — this is intentional UX, do not replace it with logging. Multiline output MUST be a single event whose `toDisplayString()` returns the full multiline block, not multiple separate events.

**Rationale**: Users interact by reading terminal output. Logging frameworks break the UX by mixing debug information with user-facing content.

### II. Layered Architecture

The codebase follows strict separation: Commands (PicoCLI) → Services → External Systems (K8s, AWS, Filesystem). Commands are thin wrappers that parse arguments and delegate to services. Business logic belongs in services and providers, not commands. Each layer depends only on layers below it.

**Rationale**: Clear boundaries make the codebase testable, maintainable, and allow swapping implementations (e.g., different cloud providers).

### III. Reasonable TDD

Write tests for non-trivial code with meaningful behavior. Skip trivial tests on simple configs, small wrappers, and data classes. Every test MUST verify real logic — no mock-echo tests that only verify a mock was called with the same values set up. A good test exercises a code path where the system under test makes a decision, transforms data, or could fail meaningfully.

**Rationale**: Tests exist to catch bugs and validate behavior, not to inflate coverage metrics. Mock-echo tests prove nothing and waste maintenance effort.

### IV. Never Disable, Always Fix

Never disable functionality as a solution. If something is not working, fix the root cause. Adding flags to skip features, making things optional, or suggesting users disable components is unacceptable. Configuration problems require configuration fixes — if a service cannot connect to a dependency, provide the correct endpoint/credentials, do not make the dependency optional.

**Rationale**: Disabled features accumulate technical debt and mask underlying problems. Users deserve working software, not workarounds.

### V. Type Safety Over Strings

Never build YAML with string concatenation in Kotlin. Use Fabric8 for in-memory Kubernetes configurations. Use kotlinx.serialization with data classes for configurations written to disk. Always prefer typed objects over string templates for structured data.

**Rationale**: String-based configuration is error-prone, hard to refactor, and lacks compile-time validation. Typed objects catch errors at compile time.

## Quality Gates

All code changes MUST pass these gates before merging:

1. **Tests pass everywhere**: Tests MUST pass in CI, local development, and devcontainer environments. It is unacceptable to ignore test failures in any environment.

2. **ktlint compliance**: Run `./gradlew ktlintCheck` before committing. Use `./gradlew ktlintFormat` to auto-fix violations.

3. **detekt analysis**: Run `./gradlew detekt` to check for code quality regressions when making changes.

4. **No wildcard imports**: All imports MUST be explicit.

5. **Files end with newline**: Every file MUST end with a newline character.

## Tech Stack Constraints

The following technology choices are mandatory for consistency:

- **Dependency Injection**: Use Koin. Services are registered in Koin modules.
- **Serialization**: Use kotlinx.serialization. Jackson usage is deprecated.
- **Retry Logic**: Use resilience4j via RetryUtil factory methods, not custom retry loops.
- **Testing Framework**: Tests extend BaseKoinTest. Use AssertJ assertions, not JUnit assertions.
- **Kubernetes Configuration**: Use Fabric8 manifest builders for all K8s resources.
- **Temporary Files in Tests**: Use `@TempDir` — JUnit handles lifecycle automatically.
- **Logging**: When internal logging is needed (not user output), use `io.github.oshai.kotlinlogging.KotlinLogging`.
- **Constants**: Store in `com.rustyrazorblade.easydblab.Constants`, not as magic numbers in code.

## Development Workflow

### Planning Iteration

When implementing features, iterate with the user. Ask questions. Do not automatically add features not requested — ask first if they are wanted.

### Documentation Sync

When making user-facing changes, update corresponding documentation in `docs/`. When changes affect architecture, file locations, or patterns, update relevant CLAUDE.md files.

### Commits

Never commit without explicit instruction. Never attribute commit messages to Claude. Never run `git push` without explicit instruction.

### Gradle Tests

Run Gradle tests in a subagent. TestContainers failures are not assumed to be pre-existing — raise them immediately as the user may need to restart Docker.

## Governance

This constitution supersedes all other development practices for easy-db-lab. All changes MUST verify compliance with these principles.

**Amendment Process**:
1. Propose changes with rationale
2. Document migration plan if principles change
3. Update this constitution with new version
4. Propagate changes to dependent templates

**Versioning Policy**: Semantic versioning (MAJOR.MINOR.PATCH)
- MAJOR: Backward-incompatible governance changes or principle removal
- MINOR: New principles added or material guidance expansion
- PATCH: Clarifications, wording improvements, non-semantic refinements

**Compliance Review**: All PRs and code reviews MUST verify adherence to Core Principles. Complexity beyond what is needed for the current task MUST be justified.

**Version**: 1.1.0 | **Ratified**: 2026-02-22 | **Last Amended**: 2026-02-23
