- This is a command line tool.  The user interacts by reading the output.  Do not suggest replacing print statements with logging, because it breaks the UX.
- Commands should use `outputHandler.handleMessage()` for user-facing output, not logging frameworks.
- Do not add logging frameworks to command classes unless there is a specific internal debugging need separate from user output.
- When logging is needed, use: `import io.github.oshai.kotlinlogging.KotlinLogging` and create a logger with `private val log = KotlinLogging.logger {}`

## Project Organization

### Architecture Overview

The project follows a layered architecture with clear separation of concerns:

```
Commands (PicoCLI) → Services → External Systems (K8s, AWS, Filesystem)
```

### Project Modules

The Gradle project has multiple modules:
- **Root module** (`:`) — the main CLI application
- **`bulk-writer`** — Cassandra bulk writer (requires cassandra-analytics built with JDK 11)
- **`spark-shared`** — shared Spark utilities

### Layer Responsibilities

**Commands (`commands/`)**: Lightweight PicoCLI execution units. Commands are thin wrappers that:
- Parse CLI arguments and options
- Delegate to service layers for actual work
- Handle user-facing output via `outputHandler`
- Should contain minimal business logic

See [`commands/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md) for detailed command patterns.

**Services (`services/`, `providers/`)**: Business logic layer that:
- Interacts with Kubernetes clusters
- Calls cloud provider APIs (AWS EC2, S3, IAM, etc.)
- Manages filesystem operations
- Contains the actual implementation logic

See [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) for AWS/SSH/Docker patterns and retry logic.

### MCP Server & REPL

Two commands run as long-lived processes instead of the typical run-and-exit pattern:
- **`Server`** — starts an MCP server (Ktor + SSE) for AI agent integration. See [`mcp/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/mcp/CLAUDE.md).
- **`Repl`** — starts an interactive REPL to reduce typing for repeated commands.

### Dependency Injection

Use **Koin** for dependency injection throughout the codebase:
- Services are registered in Koin modules (`AWSModule.kt`, `ServicesModule.kt`, `KubernetesModule.kt`, etc.)
- Commands receive dependencies via Koin injection
- Tests use `BaseKoinTest` which provides mocked dependencies

### Design Principles

Follow **SOLID principles**:
- **S**ingle Responsibility: Each class has one reason to change
- **O**pen/Closed: Open for extension, closed for modification
- **L**iskov Substitution: Subtypes must be substitutable for base types
- **I**nterface Segregation: Prefer small, focused interfaces
- **D**ependency Inversion: Depend on abstractions, not concretions

### Testing Approach

Practice **reasonable TDD**:
- Write tests for non-trivial code with meaningful behavior
- Skip trivial tests on simple configs, small wrappers, and data classes
- Review tests after writing to evaluate test quality
- Focus on testing behavior, not implementation details

**No mock-echo tests.** Every test must verify real logic. These patterns are banned:
- Tests that only verify a mock was called with the same values that were set up — this proves nothing
- Tests that assert default field values (e.g., `assertThat(command.field).isNull()`) — the compiler already guarantees defaults
- Tests that verify a method was called but don't assert anything about the outcome or transformation
- Tests where removing the code under test wouldn't cause the test to fail because the mock does all the work

A good test exercises a code path where the system under test makes a decision, transforms data, or could fail in a meaningful way.

See [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) for test patterns, BaseKoinTest usage, and custom assertions.

**Never mock `TemplateService`** — always use the real instance in tests. It only reads classpath resources and does string substitution with no external side effects.

**Quality tools workflow**:
```bash
# Find test coverage gaps
./gradlew koverHtmlReport
# Report at build/reports/kover/html/index.html

# Format code before committing
./gradlew ktlintFormat

# Find potential code issues
./gradlew detekt
```

## Development Setup

### Java Version Management (SDKMAN)

The devcontainer uses SDKMAN to manage Java versions:
- **Java 21** (Temurin) - Default for the main project
- **Java 11** (Temurin) - Required for building cassandra-analytics

SDKMAN is pre-configured in the devcontainer with both versions installed and Java 21 as the default.

**Why two versions?** The `bulk-writer` module depends on `cassandra-analytics` which requires JDK 11 to build. The `bin/build-cassandra-analytics` script automatically switches to JDK 11 for that build.

**Common commands:**
```bash
# Check current Java version
java -version

# List installed versions
sdk list java

# Temporarily use a different version (current shell only)
sdk use java 11.0.25-tem

# Switch default version permanently
sdk default java 21.0.5-tem
```

### Building Cassandra Analytics Dependencies

The `bulk-writer` module depends on cassandra-analytics SNAPSHOT artifacts that aren't published to Maven Central. Run `bin/dev test` and they will be built automatically if missing. To manually build or rebuild:

```bash
# Build cassandra-analytics (auto-skips if already built)
bin/dev build-analytics

# Force rebuild
bin/dev build-analytics --force
```

This clones the cassandra-analytics repo, builds with JDK 11, and publishes artifacts to the local Maven repository (`~/.m2/repository`).

### Pre-commit Hook Installation

Install the ktlint pre-commit hook to automatically check code style before commits:

```bash
./gradlew addKtlintCheckGitPreCommitHook
```

**Important**: Pre-commit hooks are stored in `.git/hooks/` which is local-only and not tracked by Git. Each developer must run this command individually to install the hook on their machine.

The hook automatically runs `ktlintCheck` on staged Kotlin files before each commit, preventing style violations from being committed.

### Configuration Cache

The project uses Gradle configuration cache for faster builds, enabled via `gradle.properties`:
- `org.gradle.configuration-cache=true` - Enables configuration caching
- `org.gradle.caching=true` - Enables build caching

**When to clear the cache**:
- After modifying `.editorconfig` or ktlint rules
- After changing Gradle plugins or build scripts
- When encountering unexpected build behavior

```bash
# Clear configuration cache
rm -rf .gradle/configuration-cache

# Or clean everything
./gradlew clean
```

**Why this matters**: If you modify `.editorconfig` (which configures ktlint rules), the configuration cache may prevent ktlint from seeing the new rules. This can cause local builds to pass while CI fails with style violations.

### Local Validation

Before pushing code, verify it passes all checks:

```bash
# Run all checks (matches CI)
./gradlew check

# Run only ktlint check (verify style compliance)
./gradlew ktlintCheck

# Auto-fix ktlint violations (when possible)
./gradlew ktlintFormat
```

**Note**: `ktlintFormat` auto-fixes many violations but can't fix all issues (e.g., line length). Always run `ktlintCheck` after formatting to catch remaining issues.

### Packer Script Testing

Test packer provisioning scripts locally using Docker (no AWS required):

```bash
# Test base provisioning scripts
./gradlew testPackerBase

# Test Cassandra provisioning scripts
./gradlew testPackerCassandra

# Run all packer tests
./gradlew testPacker

# Test a specific script
./gradlew testPackerScript -Pscript=cassandra/install/install_cassandra_easy_stress.sh
```

For more details, see [packer/README.md](packer/README.md) and [packer/TESTING.md](packer/TESTING.md).

## Development Rules

- All tests should pass before committing.
- Always add tests to new non-trivial code.
- If this document needs to be updated in order to provide more context for future work, do it.
- Do not use remote docker-compose commands, use docker compose, the subcommand version.
- Check if the codebase already has a way of accomplishing something before writing new code.  For example, there's already Docker logic.
- ABSOLUTE RULE: Never try to commit without explicit instruction to do so.
- activate kotlin and java for context7
- activate the serena MCP server
- ABSOLUTE RULE: NEVER attribute commit messages to Claude.
- ABSOLUTE RULE: When posting a plan to github, do not include 'Test plan' section.
- Do not use wildcard imports.
- When making changes, use the detekt plugin to determine if there are any code quality regressions.
- Always ensure files end with a newline
- Tests should extend BaseKoinTest to use Koin DI
- Use resilience4j for retry logic instead of custom retry loops. See [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) for RetryUtil factory methods.
- Use AssertJ assertions, not JUnit assertions
- For serialization, use kotlinx.serialization, not Jackson.  Jackson usage in this codebase is deprecated.
- Constants and magic numbers should be stored in com.rustyrazorblade.easydblab.Constants
- When migrating code, it is not necessary to maintain backwards compatibility
- Fail fast is usually preferred
- Always use @TempDir for temporary directories in tests - JUnit handles lifecycle automatically
- **Never disable functionality as a solution.** If something isn't working, fix the root cause. Adding flags to skip features, making things optional, or suggesting users disable components is not an acceptable solution.
- **Configuration problems require configuration fixes.** If a service can't connect to a dependency, the fix is to provide the correct endpoint/credentials, not to make the dependency optional.
- Include testing when planning.  Integration tests use TestContainers.
- When planning, iterate with me.  Ask questions.  Don't automatically add features I didn't ask for.  Ask if I want them first.
- Include updates to the documentation as part of planning.
- CRITICAL: Tests must pass, in CI, on my local, and in the devcontainer.  It is UNACCEPTABLE to say that tests are only failing in devcontainers and to ignore them.
- When making changes, keep CLAUDE.md and subdirectory CLAUDE.md files up to date if the change affects architecture, file locations, or patterns described in them.
- NEVER build YAML with strings in Kotlin.  If you are building a config in memory to execute with K8, use fabric8.  If it's something that needs to be written to disk, use kotlinx.serialization with data classes.  ALWAYS prefer typed objects over big strings.
- When outputting multiple lines to the console, use a multiline block instead of multiple calls to `outputHandler.handleMessage`
- Write new K8 configuration using fabric8.  If there are configuration files, store them as a resource and load them with the TemplateService.
- If you need to modify a K8 configuration, ask if you should migrate it to the new fabric8 based configs in `src/main/kotlin/coom/rustyrazorblade/easydblab/configuration/`
- When describing directory structure, use normal lists.  Don't draw them, I don't find them useful.

## Specifications

- Keep track of user experience and product information in `docs` under the project root.
- Use these as a reference when making changes to determine if there are conflicts.
- Plan changes to the spec before moving on to implementation.
- The intent is to have a maintainable source of truth for product decisions.

## Testing Guidelines

For comprehensive testing guidelines, including custom assertions and Domain-Driven Design patterns, see [docs/development/testing.md](docs/development/testing.md).

See [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) for BaseKoinTest usage, mocking patterns, and custom assertions.

## Cluster State Management

See [`configuration/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md) for ClusterState, ServerType, ClusterStateManager, TemplateService, and S3 path patterns.

## Observability

The cluster runs a full observability stack on the control node. When modifying any part of this stack, keep the related K8s manifests, Kotlin services, and user docs in sync.

### Data Flow

All observability K8s resources are built programmatically using Fabric8 manifest builders in `configuration/` subpackages. No raw YAML files remain in the core observability stack.

- **MAAC agent** (port 9000) runs on Cassandra nodes (4.0, 4.1, 5.0). Loaded as a JVM agent, exposes Cassandra internal metrics (`mcac_*` prefix) as a Prometheus endpoint. Installed per-version at `/opt/management-api/{version}/datastax-mgmtapi-agent.jar`. Configured in `packer/cassandra/cassandra.in.sh`. Scraped by OTel collector (`cassandra-maac` job).
- **OpenTelemetry Collector** (`configuration/otel/OtelManifestBuilder.kt`) runs on all nodes. Collects host metrics, scrapes Prometheus endpoints (ClickHouse, Vector, Beyla, ebpf_exporter, MAAC), reads file-based logs, and receives OTLP. Exports metrics to VictoriaMetrics, logs to VictoriaLogs, traces to Tempo.
- **Vector** (`configuration/vector/VectorManifestBuilder.kt`) runs as a DaemonSet for system/Cassandra/ClickHouse log collection, plus a separate deployment for S3 log ingestion (EMR/Spark via SQS). Both sink to VictoriaLogs.
- **Grafana Alloy eBPF profiler** (`configuration/pyroscope/PyroscopeManifestBuilder.kt`) runs on all nodes via Grafana Alloy with `pyroscope.ebpf` component. Collects CPU profiles via eBPF for Cassandra, ClickHouse, and stress jobs. Sends profiles to Pyroscope server.
- **Beyla** (`configuration/beyla/BeylaManifestBuilder.kt`) runs on all nodes. Provides L7 network RED metrics (Rate/Errors/Duration) for Cassandra and ClickHouse protocols via eBPF. Exposes Prometheus metrics scraped by OTel collector.
- **ebpf_exporter** (`configuration/ebpfexporter/EbpfExporterManifestBuilder.kt`) runs on all nodes. Provides low-level TCP retransmit, block I/O latency, and VFS latency metrics via eBPF. Exposes Prometheus metrics scraped by OTel collector.
- **Stress job sidecars** (`configuration/cassandra/otel-stress-sidecar-config.yaml`) — long-running stress jobs get an OTel sidecar that scrapes `cassandra-easy-stress:9500` and forwards to the node's DaemonSet collector.

### Storage Backends (control node)

- **VictoriaMetrics** (port 8428, 7-day retention) — Prometheus-compatible metrics store. K8s: `configuration/victoria/VictoriaManifestBuilder.kt`. Services: `VictoriaStreamService`, `VictoriaBackupService`.
- **VictoriaLogs** (port 9428, 7-day retention) — log store with Elasticsearch-compatible sink. K8s: `configuration/victoria/VictoriaManifestBuilder.kt`. Services: `VictoriaLogsService`, `VictoriaStreamService`, `VictoriaBackupService`.
- **Tempo** (port 3200) — trace store. K8s: `configuration/tempo/TempoManifestBuilder.kt`.
- **Pyroscope** (port 4040) — continuous profiling store. K8s: `configuration/pyroscope/PyroscopeManifestBuilder.kt`. Receives profiles from Java agent (Cassandra) and eBPF agent (all nodes). Data directory permissions set via SSH in `GrafanaUpdateConfig`.

### Grafana Dashboards

Grafana runs on port 3000. All Grafana K8s resources (ConfigMaps, Deployment) are built programmatically using Fabric8 in `GrafanaManifestBuilder`. Dashboard JSON lives in standalone resource files under `configuration/grafana/dashboards/` with `__KEY__` template variable substitution (`__CLUSTER_NAME__`, `__BUCKET_NAME__`, `__METRICS_FILTER_ID__`). The `GrafanaDashboard` enum is the single source of truth for dashboard metadata — adding a new dashboard requires only a new enum entry and a JSON file.

Kotlin code: `configuration/grafana/GrafanaDashboard.kt` (registry), `configuration/grafana/GrafanaManifestBuilder.kt` (Fabric8 builder), `configuration/grafana/GrafanaDatasourceConfig.kt`, `commands/grafana/`, `services/GrafanaDashboardService.kt`.

Datasources: VictoriaMetrics (Prometheus), VictoriaLogs, ClickHouse, Tempo, CloudWatch, Pyroscope.

Current dashboards: System Overview, AWS CloudWatch (S3/EBS/EC2), EMR, OpenSearch, Stress, ClickHouse metrics, ClickHouse logs, Profiling, Cassandra Condensed, Cassandra Overview.

### CLI Commands

- `grafana update-config` — build and deploy Grafana dashboards and resources
- `logs query` / `logs backup` / `logs import` / `logs ls` — query, backup, import, list log snapshots
- `metrics backup` / `metrics import` / `metrics ls` — backup, import, list metric snapshots

### OTel Instrumentation in Kotlin

The `observability/` package provides `TelemetryProvider` with `withSpan()`, `recordDuration()`, `incrementCounter()`. `TelemetryFactory` creates `OtelTelemetryProvider` (when `OTEL_EXPORTER_OTLP_ENDPOINT` is set) or `NoOpTelemetryProvider` (zero overhead). Span/metric names are in `TelemetryNames`. See `docs/reference/opentelemetry.md`.

All builder paths above are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/`. Config resources are at corresponding paths under `src/main/resources/com/rustyrazorblade/easydblab/`.

## Documentation

User documentation is in `docs/` (mdbook format).  When making user facing changes, make sure the docs for that feature are up to date.

If I refer to Kubernetes configs or k8 configs, I am referring to these: `src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/` by default.

## Subdirectory Documentation

Detailed patterns live in package-level CLAUDE.md files:
- [`commands/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md) — command patterns, available services
- [`services/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/services/CLAUDE.md) — SystemD service management
- [`services/aws/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/services/aws/CLAUDE.md) — AWS service classes (AMI, EC2, EMR, OpenSearch, S3, SQS)
- [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) — AWS SDK wrappers, SSH/Docker patterns, retry logic
- [`configuration/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md) — cluster state, templates
- [`mcp/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/mcp/CLAUDE.md) — MCP server architecture
- [`kubernetes/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/kubernetes/CLAUDE.md) — K8s client patterns
- [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) — test infrastructure
