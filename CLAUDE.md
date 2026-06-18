- **Clusters are ephemeral by design.** easy-db-lab spins up temporary test clusters that are destroyed after use. There is no production data, no long-lived deployments, and no users who need to migrate. **Do NOT warn about backwards compatibility** when reviewing PRs or planning changes — it is never a concern here.
- **Large `storageSize` defaults in kit.yaml (e.g. `10Ti`) are intentional and correct.** Clusters use Local Persistent Volumes via the `local-storage` provisioner, which does not enforce PVC capacity — the declared size is metadata only and does not affect real disk usage. **Never flag large storage defaults as excessive, wasteful, or a bug** during code review or planning.
- This is a **general purpose database test tool**, not a Cassandra-specific tool. It supports Cassandra, ClickHouse, OpenSearch, TiDB, and more. In user-facing text (docs, error messages, comments), use "database" or "db" instead of "Cassandra" unless referring to Cassandra-specific functionality. The `ServerType.Cassandra` / "db" node type is the generic database node — don't assume it's always Cassandra.
- This is a command line tool.  The user interacts by reading the output.  Do not suggest replacing print statements with logging, because it breaks the UX.
- **Structured user-facing output** uses `eventBus.emit(Event.Domain.Type(...))` with domain-specific typed events. Events are defined as sealed data classes in `events/Event.kt` across 28 domain interfaces. See [`events/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/events/CLAUDE.md). **Events are for things an external system would need to be aware of** — lifecycle transitions, state changes, failures. If an MCP client or Redis subscriber would have no reason to care, it is not an event.
- **For pure informational output** with no associated event type (e.g. help text, plain status lines), use Kotlin's `println()`. **Never use `System.out` directly** — `println()` is the idiomatic Kotlin equivalent and writes to stdout.
- **Read-only display commands** (`info`, `list`, `show`, `describe`, and equivalents) produce pure output — no state changes, no domain facts occurred. Use `println()` directly in `execute()`; do **not** model the output as an event. A useful heuristic: if the command's name is a noun or `show`/`info`/`list`/`describe`, it almost certainly belongs in this category. Examples: `kit info`, `kit list`.
- **Do NOT use `Event.Message` or `Event.Error`** — these generic types exist only for test convenience. When emitting events, always use domain-specific typed events with structured data fields.
- Do not add logging frameworks to command classes unless there is a specific internal debugging need separate from user output.
- When logging is needed, use: `import io.github.oshai.kotlinlogging.KotlinLogging` and create a logger with `private val log = KotlinLogging.logger {}`
- **Never use Python for JSON parsing in shell scripts.** Use `jq` instead.

## Project Organization

### Architecture Overview

The project follows a layered architecture:

- **Commands (PicoCLI)** delegate to **Services**, which interact with **External Systems** (K8s, AWS, Filesystem)
- Commands and Services emit events via the **EventBus**
- **Listeners** consume events: `ConsoleEventListener` (stdout/stderr), `McpEventListener` (server MCP status), `RedisEventListener` (pub/sub, optional)

### Project Modules

The Gradle project has two modules:
- **Root module** (`:`) — the main CLI application
- **`core`** — shared library used by the CLI

The example Spark jobs (bulk writers + connector read/write) live in a separate
repository, **`../spark-examples`**. They were decoupled because they depend on
locally-built Cassandra Analytics SNAPSHOTs and don't belong in the CLI build. The
`spark` CLI commands (EMR provisioning + job submission) remain in this repo.

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

### Server & REPL

Two commands run as long-lived processes instead of the typical run-and-exit pattern:
- **`Server`** — starts a hybrid HTTP server with MCP protocol support (Ktor + SSE), REST status endpoints, and background services. See [`mcp/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/mcp/CLAUDE.md).
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

See [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) for test patterns, BaseKoinTest usage, and custom assertions. See also [docs/development/testing.md](docs/development/testing.md) for comprehensive testing guidelines.

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

## Development Rules

### Code Style

- Do not use wildcard imports.
- **Always add class-level KDoc comments.** Every class, object, and interface must have a doc comment explaining what it is and why it exists — not just what it does mechanically.
- **Never use positional CLI parameters (`@CommandLine.Parameters`) for kit install args.** Kit args (declared in `kit.yaml`) must be named options (`@CommandLine.Option`) because they are passed through to kit scripts as environment variables — positional args break that pipeline. Regular commands (e.g. `kit info <name>`, `cassandra use <version>`) may use `@Parameters` where a single positional argument improves UX.
- Always ensure files end with a newline.
- Use AssertJ assertions, not JUnit assertions.
- For serialization, use kotlinx.serialization, not Jackson. Jackson usage in this codebase is deprecated.
- Constants and magic numbers should be stored in `com.rustyrazorblade.easydblab.Constants`.
- When outputting multiple lines to the console via `println()`, use a multiline string block instead of multiple `println()` calls. When using events, a single event with structured fields is always preferred over multiple events.
- When making changes, use the detekt plugin to determine if there are any code quality regressions.

### Architecture

- Use resilience4j for retry logic instead of custom retry loops. See [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) for RetryUtil factory methods.
- NEVER build YAML with strings in Kotlin. If you are building a config in memory to execute with K8s, use fabric8. If it's something that needs to be written to disk, use kotlinx.serialization with data classes. ALWAYS prefer typed objects over big strings.
- Write new K8s configuration using fabric8. If there are configuration files, store them as a resource and load them with the TemplateService.
- If you need to modify a K8s configuration, ask if you should migrate it to the new fabric8 based configs in `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/`.
- Check if the codebase already has a way of accomplishing something before writing new code.
- When migrating code, it is not necessary to maintain backwards compatibility. Clusters are ephemeral — there are no long-lived deployments that need a migration path.
- Fail fast is usually preferred.
- **Never disable functionality as a solution.** If something isn't working, fix the root cause. Adding flags to skip features, making things optional, or suggesting users disable components is not an acceptable solution. When there's a port conflict, assign a different port — don't disable the service. When a feature crashes, fix the crash — don't remove the feature. "Disable it" is never the answer.
- **Never add memory limiters to OTel collectors or other observability components.** The `memory_limiter` processor causes data to be refused and dropped under load. The nodes have enough memory — let them use it.
- **Configuration problems require configuration fixes.** If a service can't connect to a dependency, the fix is to provide the correct endpoint/credentials, not to make the dependency optional.
- **`RemoteOperationsService` is for commands that run on the remote control node** — filesystem operations, service management (systemd), node-level configuration, and all cluster API tooling (helm, kubectl, cilium). These tools are baked into the AMI and run on the control node via SSH. Use `RemoteOperationsService.executeRemotely()` with `KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}` prefixed on each command. Do NOT use `ProcessBuilder` or local CLI invocations for cluster operations — the tools are not installed on the developer's machine.
- **Kit shell scripts that create K8s pods must use unique timestamp-based names** — use `pod-name-$(date +%s)` and label every pod with `easydblab/kit=${KIT_NAME}` and `easydblab/role=<role>`. Fixed pod names cause stale-pod conflicts after natural completion (the completed pod persists in K8s indefinitely). Cleanup in `stop.sh` must delete by label selector (`-l "easydblab/kit=...,easydblab/role=..."`) not by name. See `kits/sysbench/bin/` for the reference implementation.

### Testing

- All tests should pass before committing.
- Always add tests to new non-trivial code.
- Tests should extend BaseKoinTest to use Koin DI.
- Always use @TempDir for temporary directories in tests - JUnit handles lifecycle automatically.
- Include testing when planning. Integration tests use TestContainers.
- CRITICAL: Tests must pass, both in CI and on my local. It is UNACCEPTABLE to ignore failing tests or wave them off as environment-specific.
- When running Gradle tests, run them in a subagent. Never attribute a test containers failure as a pre-existing problem. Raise it to my attention, I may need to restart docker.
- **Minimal mocking.** Only mock what you must:
  - Mock external services with real side effects (AWS API calls that spin up instances, send emails, etc.)
  - Mock dependencies to simulate specific failure modes you need to test
  - **Prefer TestContainers** over mocks for anything that interacts with external state (databases, Redis, K8s). TestContainers give more predictable results and increase real code coverage.
  - Do NOT mock classes that have no side effects (e.g., `TemplateService`, data transformations, pure functions) — use the real implementation.
- **Mocking `RemoteOperationsService` is appropriate for services that execute remote CLI tools** (helm, kubectl, cilium). These tools run on the control node via SSH — there is no local binary to run. Verify the correct command string is passed via `argumentCaptor`. See `HelmServiceTest`, `KubectlServiceTest`, `CiliumServiceTest` for the pattern.
- **Never mock `K8sService` or `RemoteOperationsService` to test K8s manifest application.** Use K3s TestContainers so the resources are actually applied to a real cluster. See `K8sServiceIntegrationTest` for the pattern.

### Workflow & Planning

- ABSOLUTE RULE: Never try to commit without explicit instruction to do so.
- ABSOLUTE RULE: NEVER attribute commit messages to Claude.
- ABSOLUTE RULE: When posting a plan to github, do not include 'Test plan' section.
- When planning, iterate with me. Ask questions. Don't automatically add features I didn't ask for. Ask if I want them first.
- Include updates to the documentation as part of planning.
- When making changes, keep CLAUDE.md and subdirectory CLAUDE.md files up to date if the change affects architecture, file locations, or patterns described in them.
- If this document needs to be updated in order to provide more context for future work, do it.
- When describing directory structure, use normal lists. Don't draw them, I don't find them useful.
- Do not use remote docker-compose commands, use docker compose, the subcommand version.
- Activate kotlin and java for context7.
- Activate the serena MCP server.

### Lab Test Plans

Lab test plans created with `/easy-db-lab:plan` are stored in `test-plans/` in the worktree root. Name them descriptively (e.g. `cassandra-5.0-validation-3node.md`). When the `/easy-db-lab:run` skill writes or references `plan.md`, use `test-plans/<descriptive-name>.md` instead.

Test plans are executed exclusively via `easy-db-lab.*` plugin skills — **never via `bin/test` or the `run-test` skill**:
- `/easy-db-lab:plan` — create a new plan
- `/easy-db-lab:run test-plans/<name>.md` — execute a plan

When using the `/easy-db-lab:plan` skill, use `bin/easy-db-lab` in the project directory.

### Cluster Workspace Directories

**CRITICAL:** Every test run must have its own workspace directory under `clusters/`. All `easy-db-lab` commands — `init`, `up`, `cassandra`, `presto`, `down`, everything — must be run from inside that directory. The tool writes `state.json`, `env.sh`, `sshConfig`, `kubeconfig`, and config files into the current directory. Running from the wrong directory will corrupt state or silently target the wrong cluster.

Each workspace gets an `easy-db-lab` wrapper script generated by `bin/create-easy-db-lab-wrapper`. The wrapper sets the correct `JAVA_HOME` (Java 21 via SDKMAN) and `cd`s into the workspace directory automatically — no manual `cd` or Java ceremony needed.

**Every test plan written to `test-plans/` must use this pattern as its first step:**

```bash
CLUSTER_DIR="clusters/<test-name>-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
```

All subsequent commands use `$EDB` directly — no `cd`, no path prefix:

```bash
$EDB init ...
$EDB cassandra start
$EDB down --auto-approve
```

For multi-DC plans, create a wrapper per DC directory:

```bash
CLUSTER_BASE="clusters/<test-name>-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_BASE/dc1" "$CLUSTER_BASE/dc2"
bin/create-easy-db-lab-wrapper "$CLUSTER_BASE/dc1"
bin/create-easy-db-lab-wrapper "$CLUSTER_BASE/dc2"
DC1="$CLUSTER_BASE/dc1/easy-db-lab"
DC2="$CLUSTER_BASE/dc2/easy-db-lab"
```

## Development Setup

### Java Version Management (SDKMAN)

The project uses **Java 21** (Temurin) — managed via SDKMAN and set as the default.

```bash
# Check current Java version
java -version

# List installed versions
sdk list java
```

### Example Spark Jobs

The example Spark jobs (bulk writers + connector read/write) have moved to their own
repository: **`../spark-examples`**. Build, test, and the Cassandra Analytics dependency
all live there now — this repo no longer builds analytics or requires a second JDK. The
`spark` CLI commands here (EMR provisioning, `spark submit`, status/logs) submit a
pre-built job jar to EMR; build that jar in `../spark-examples` and point `spark submit`
at it.

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

## Documentation & Specifications

User documentation is in `docs/` (mdbook format). When making user-facing changes, make sure the docs for that feature are up to date.

This project relies on **OpenSpec** to maintain product specifications. Specs live in `openspec/specs/` and are managed via the OpenSpec workflow (skills: `openspec-propose`, `openspec-explore`, `openspec-apply-change`, `openspec-archive-change`). **Specs are the source of truth for product decisions.** Before making any change, check relevant specs to ensure the change does not conflict. If a conflict is found, notify the user before proceeding — do not silently override the spec. If a change requires updating a spec, plan the spec update before moving on to implementation.

After running `openspec-apply-change`, use the `simplify` skill to review and improve the code quality of the changes.

If I refer to Kubernetes configs or k8 configs, I am referring to these: `src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/` by default.

## Observability

The cluster runs a full observability stack on the control node. When modifying any part of this stack, keep the related K8s manifests, Kotlin services, and user docs in sync.

All observability K8s resources are built programmatically using Fabric8 manifest builders in `configuration/` subpackages. No raw YAML files remain in the core observability stack. See [`configuration/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md) for detailed builder documentation.

**CNI**: K3s uses Cilium (not Flannel) with Hubble enabled for L7 network visibility and Prometheus metrics at `localhost:9965`.

**Collectors** (run on cluster nodes): OTel Collector, Fluent Bit (journald), Grafana Alloy (eBPF profiling), Beyla (L7 RED metrics), ebpf_exporter (TCP/block I/O/VFS), YACE (CloudWatch), MAAC agent (Cassandra metrics)

**Dynamic OTel config**: `OtelManifestBuilder.buildConfigMap()` accepts `List<WorkloadScrapeConfig>` and injects one Prometheus scrape job per running kit. Kits register by writing `easydblab-metrics-<kit>` ConfigMaps (label: `easydblab.com/workload-metrics=true`). `MetricsRegistryService` creates/deletes these ConfigMaps; `OtelSyncService` reads them all and regenerates the OTel collector ConfigMap. Both are called automatically by `KitRunnerCommand` on successful `start`/`stop`.

**Storage backends** (control node): VictoriaMetrics (metrics, port 8428), VictoriaLogs (logs, port 9428), Tempo (traces, port 3200), Pyroscope (profiles, port 4040)

**Grafana** (port 3000): Two dashboard sources:
- **Kit dashboards (new, correct)**: JSON files in `src/main/resources/.../kits/<name>/dashboards/`. `KitRunnerCommand` auto-installs them from that directory after a successful `start`. No `GrafanaDashboard` enum entry needed — adding a JSON file is all that's required.
- **Core/system dashboards (legacy)**: JSON files in the top-level `dashboards/` directory, registered in the `GrafanaDashboard` enum, loaded by `GrafanaManifestBuilder`. Use this only for non-kit dashboards (system-overview, profiling, etc.). **Do NOT add new kit dashboards here.**

See [`dashboards/CLAUDE.md`](dashboards/CLAUDE.md) for datasource UIDs, label conventions, spanmetrics metric names, and the correct pattern for cross-dashboard dataLinks (panes= URL format, TraceQL query strings).

**CLI Tool Instrumentation**: The CLI tool uses the OpenTelemetry Java Agent for automatic instrumentation of AWS SDK calls, HTTP clients, JDBC operations, and JVM metrics. No manual instrumentation exists in the Kotlin code. See `docs/reference/opentelemetry.md`.

**CLI commands**: `grafana update-config`, `logs query/backup/import/ls`, `metrics backup/import/ls`

All builder paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/`. Config resources are at corresponding paths under `src/main/resources/com/rustyrazorblade/easydblab/`.

## Subdirectory Documentation

Detailed patterns live in package-level CLAUDE.md files:
- [`events/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/events/CLAUDE.md) — Event bus, event hierarchy, adding new events, serialization
- [`commands/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md) — command patterns, available services
- [`services/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/services/CLAUDE.md) — SystemD service management
- [`services/aws/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/services/aws/CLAUDE.md) — AWS service classes (AMI, EC2, EMR, OpenSearch, S3)
- [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) — AWS SDK wrappers, SSH/Docker patterns, retry logic
- [`configuration/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md) — cluster state, templates, K8s manifest builders, observability stack details
- [`mcp/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/mcp/CLAUDE.md) — Server architecture (MCP, REST, background services)
- [`kubernetes/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/kubernetes/CLAUDE.md) — K8s client patterns
- [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) — test infrastructure
