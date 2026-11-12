- **This is a testing and fast-iteration tool, not a production tool.** easy-db-lab exists to spin up throwaway clusters and iterate quickly on testing Cassandra builds and fixes.  Optimize for iteration speed and simplicity.  Do not gold-plate features with production hardening the tool does not need — credential rotation, key revocation on live hosts, audit trails, and similar — when it adds friction without value; the clusters are ephemeral and single-operator.  This never licenses leaking secrets or disabling functionality (see the rules below); it means not building production concerns this tool does not have.
- **Clusters are ephemeral by design.** easy-db-lab spins up temporary test clusters that are destroyed after use. There is no production data, no long-lived deployments, and no users who need to migrate. **Do NOT warn about backwards compatibility** when reviewing PRs or planning changes — it is never a concern here.
- **Do not design for S3 being unavailable.** S3 outages happen about once every two years, and the owner accepts the risk of data loss when one does. Do not write code, checks, retries, tests, spec requirements, or review findings for S3 outages or other failures that do not happen in practice. This covers teardown too: `down` flushes every backend, waits until all the data is flushed, and does not destroy the environment if the flush did not finish. It does nothing else. Never add verify checks to it (for example, "every local file exists in S3", "the backend is running", or "a counter did not move").
- **Large `storageSize` defaults in kit.yaml (e.g. `10Ti`) are intentional and correct.** Clusters use Local Persistent Volumes via the `local-storage` provisioner, which does not enforce PVC capacity — the declared size is metadata only and does not affect real disk usage. **Never flag large storage defaults as excessive, wasteful, or a bug** during code review or planning.
- This is a **general purpose database test tool**, not a Cassandra-specific tool. It supports Cassandra, ClickHouse, OpenSearch, TiDB, and more. In user-facing text (docs, error messages, comments), use "database" or "db" instead of "Cassandra" unless referring to Cassandra-specific functionality. The `ServerType.Cassandra` / "db" node type is the generic database node — don't assume it's always Cassandra.
- **Never assume the source checkout is available.** Most people install this tool with Homebrew. They have no repo, no working tree, and no `build/` directory. Any feature that scaffolds, generates, or reads files must work entirely from resources packaged in the distribution. Never bind-mount a repo directory, read a path inside the repo at runtime, or design a workflow that only works for someone with a clone.
- **Never encode a personal or machine-specific path.** Any directory outside this repo — where a user keeps a database checkout, a scaffolded workspace, or test output — is a parameter, never a constant. A path named in conversation is an example of usage, not a requirement to hardcode. Take it as an argument and default it to the current directory.
- This is a command line tool.  The user interacts by reading the output.  Do not suggest replacing print statements with logging, because it breaks the UX.
- **Structured user-facing output** uses `eventBus.emit(Event.Domain.Type(...))` with domain-specific typed events. Events are defined as sealed data classes in `events/Event.kt`, one sealed sub-interface per domain (listed in `events/CLAUDE.md`). See [`events/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/events/CLAUDE.md). **Events are for things an external system would need to be aware of** — lifecycle transitions, state changes, failures. If a Redis subscriber would have no reason to care, it is not an event.
- **For pure informational output** with no associated event type (e.g. help text, plain status lines), use Kotlin's `println()`. **Never use `System.out` directly** — `println()` is the idiomatic Kotlin equivalent and writes to stdout.
- **Read-only display commands** (`info`, `list`, `show`, `describe`, and equivalents) produce pure output — no state changes, no domain facts occurred. Use `println()` directly in `execute()`; do **not** model the output as an event. A useful heuristic: if the command's name is a noun or `show`/`info`/`list`/`describe`, it almost certainly belongs in this category. Examples: `kit info`, `kit list`.
- **Do NOT use `Event.Message` or `Event.Error`** — these generic types exist only for test convenience. When emitting events, always use domain-specific typed events with structured data fields.
- Do not add logging frameworks to command classes unless there is a specific internal debugging need separate from user output.
- When logging is needed, use: `import io.github.oshai.kotlinlogging.KotlinLogging` and create a logger with `private val log = KotlinLogging.logger {}`
- **Never use Python for JSON parsing in shell scripts.** Use `jq` instead.
- **ABSOLUTE RULE: NEVER set, clear, or otherwise touch the `socksProxyHost` / `socksProxyPort` JVM system properties.** Not even temporarily with save/restore. These are JVM-global and capture **every** socket in the process — including the AWS SDK — routing it through the SOCKS tunnel. This caused a major incident and was deliberately removed in #725 (see the commit message and the `Socks5ProxySelector` KDoc). The proxy publishes **only** its port under `Constants.Proxy.PORT_PROPERTY`; cluster clients opt into the tunnel **explicitly and per-client** — Fabric8 via `config.httpsProxy = "socks5://127.0.0.1:{port}"` (`KubernetesClientFactory`), cluster HTTP via `Socks5ProxySelector` installed on that client only (`ProxiedHttpClientFactory`), the CQL driver via `SocksProxyNettyOptions`, and the JDBC `sql` command via `SocksTcpBridge` (a per-connection localhost→SOCKS TCP forwarder). Local shell tools — kit shell steps, phase scripts, hooks and `env.sh` shells — go through the workspace tool wrappers: `<workspace>/bin/{kubectl,helm,cilium,curl,skopeo,k9s,ssh,scp,sftp,rsync}`, one packaged POSIX script (`configuration/tool-wrapper.sh`, written by `ToolWrapperInstaller`) that sets that tool's proxy variables for its own call only, read from the proxy env file; the `ssh`, `scp`, `sftp` and `rsync` copies instead add `-F <workspace>/sshConfig` (rsync: `-ave "ssh -F <workspace>/sshConfig"`), leave proxy variables and `KUBECONFIG` alone, and exit 1 when the workspace has no `sshConfig`. Every kit process is launched through `KitProcessEnvironment`, which puts `<workspace>/bin` first on `PATH` and sets the absolute workspace `KUBECONFIG`. To route a **new** client type through the tunnel, give **that connection** an explicit `java.net.Proxy(SOCKS, ...)` / driver-native SOCKS option (or route it through `SocksTcpBridge`) — never a global property. This invariant is also encoded in the `SOCKS Proxy` requirement in `openspec/specs/networking/spec.md`.
- **Standing rule: shell-side state comes from the sourceable env file, never from JSON.** Any value a shell-side tool (the tool wrappers, `env.sh`, a kit script) needs is written by Kotlin to `<workspace>/.socks5-proxy.env` (`proxy/ProxyEnvFile`, the file's only writer: fixed `KEY=value` lines, merge-updated under an exclusive lock on `.socks5-proxy.env.lock` and replaced by an atomic rename). Today it holds `EDL_TAILSCALE_ACTIVE`, `EDL_SOCKS_PORT` and `EDL_SOCKS_PID` (the tunnel script's PID; the last two are written after verification and removed together, as one `RecordedTunnel`). A wrapper, `with-proxy` and `socks5-status` use the port only while `kill -0 "$EDL_SOCKS_PID"` succeeds, and otherwise fail with the `socks start` message. Shell code sources it; it never parses `.socks5-proxy-state` or any other JSON, and never needs `jq` for it. The CLI is the only proxy manager: `socks start` / `socks stop` (and every `@RequiresProxy` command, `up` and `down`) start, reuse, or stop the tunnel; `env.sh` and the wrappers never do.
- **ABSOLUTE RULE: NEVER delete the owner's data automatically.** Test results, observability data (metrics, logs, traces, profiles, annotations), backups, and exports are the owner's work product. No code, default, lifecycle rule, retention setting, compactor setting, TTL, or cleanup step may delete them unless the owner runs an explicit delete. This covers S3 lifecycle and expiry rules, `--retention-days`-style options, backend retention such as Tempo `block_retention` or Mimir and Loki compactor retention, and teardown cleanup. Ephemeral clusters are disposable; the data they produce is not. Never add automatic deletion as a feature, a default, or a "cost" optimization, and flag any existing automatic deletion as a critical bug. Compaction is not deletion. A compactor that writes a merged object holding all of its sources' data, and then removes those sources, loses nothing and is allowed. Retention, expiry, and any removal that is not replaced by a merged copy stay forbidden. Every compactor runs with retention off. The account compactor runs in every cluster with the node's instance role, so that role may delete under `mimir/`, `loki/` and `tempo/`; the IAM deny there was removed on purpose (issue 1048, design D3). This is an owner decision: do not flag it in reviews, and do not propose a separate compactor role, IRSA, session tags or a restored deny. The deny on `grafana/` and `reports/` stays.
- When we are working on a PR, and you find a bug, it should generally be fixed in that patch, NOT deferred. I am not interested in separate patches for bug fixes. If you find a bug, tell me about it, the default behavior should be to fix it.
- During the grilling session, ask one question at a time.
- During reviews, flag any loosening of detekt rules as MAJOR issues.

## Project Organization

### Project Modules

The Gradle project has three modules:
- **Root module** (`:`) — the main CLI application
- **`core`** — shared library used by the CLI
- **`compactor-lease`** — the account compactor's lease agent. It has no dependency on the root module. It holds the conditional-write port (`ConditionalObjects`, with the S3 adapter and an in-memory test fixture), the lease loop and the gate file. It is shadowed into `agents/compactor-lease.jar`, which `up` uploads to the control node; the `lease` container of the `account-compactor` pod runs it. `ConditionalObjects` is the only conditional-write port (`ObjectStore` has none); the lease agent and the root module's `CompactorLeaseReader` use it directly. See [`compactor-lease/CLAUDE.md`](compactor-lease/CLAUDE.md).

The example Spark jobs (bulk writers + connector read/write) live in a separate
repository, **`../spark-examples`**. They were decoupled because they depend on
locally-built Cassandra Analytics SNAPSHOTs and don't belong in the CLI build. The
`spark` CLI commands (EMR provisioning + job submission) remain in this repo.

### Layer Responsibilities

- **Listeners** consume events: `ConsoleEventListener` (stdout/stderr), `RedisEventListener` (pub/sub, optional, best effort after startup). `metrics stream` publishes `Metrics.Sample` straight to Redis, not through the bus.

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

**Kernel (`kernel/`)**: The few types both of the layers above share. It holds `PicoCommand`, the interface `services/CommandExecutor` dispatches on and every command implements. Keeping it here is what stops `commands/` and `services/` from depending on each other. Add a type to `kernel/` only when both layers need it, and keep it free of dependencies on either.

### Server & REPL

Three commands run as long-lived processes instead of the typical run-and-exit pattern (`metrics stream` is the third: it publishes chosen metrics to Redis every 5 seconds until the first Ctrl-C or SIGTERM, which exits 0; see `services/metrics/`):
- **`Server`** — starts the REST status server (Ktor): `/status`, `/stress/status`, `/swagger`, with the background status cache. See [`server/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/server/CLAUDE.md).
- **`Repl`** — starts an interactive REPL to reduce typing for repeated commands.

### Dependency Injection

Use **Koin** for dependency injection throughout the codebase:
- Services are registered in Koin modules (`AWSModule.kt`, `ServicesModule.kt`, `KubernetesModule.kt`, etc.)
- Commands receive dependencies via Koin injection
- Tests use `BaseKoinTest` which provides mocked dependencies

### Design Principles

Follow **SOLID principles**.

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
- Tests that assert static help or usage text (picocli `usageMessage`, command descriptions, `--help` output). This is a standing rule; a review finding asking for such a test is dropped, not fixed.

A good test exercises a code path where the system under test makes a decision, transforms data, or could fail in a meaningful way.

See [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) for test patterns, BaseKoinTest usage, and custom assertions. See also [docs/development/testing.md](docs/development/testing.md) for comprehensive testing guidelines.

**Never mock `TemplateService`** — always use the real instance in tests. It only reads classpath resources and does string substitution with no external side effects.

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
- **Fix detekt findings with code, never with `@Suppress`, a threshold change, or a baseline entry.**
- **Prefer composition when it fits.** When a class grows too many responsibilities or functions, split it into small single-purpose classes and compose them, instead of one large class.
- **Use the builder pattern instead of a long constructor.** When a class needs many constructor fields, give it a builder rather than a long parameter list.

### Architecture

- Use resilience4j for retry logic instead of custom retry loops. See [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) for RetryUtil factory methods. resilience4j's plain `decorateRunnable`/`decorateSupplier` never see a checked exception (`SshException`, OkHttp's `IOException`, `ProcessBuilder.start()`), so a body that can throw one uses the checked decorators with a predicate that names what is transient; never retry an `InterruptedException` (see "Checked Exceptions Need The Checked Decorators" there).
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
- Include testing when planning. The suite is split into two tiers: `./gradlew test` runs the fast unit tier (no Docker, `src/test/`), and `./gradlew integrationTest` runs the slow tier (TestContainers/Docker, `src/integrationTest/`). `./gradlew check` runs both. Any test that uses TestContainers, the Fabric8 kubernetes-server-mock, okhttp MockWebServer, or a fixed-port socket belongs in `src/integrationTest/` — those dependencies are scoped to `integrationTestImplementation` only and will not compile under `src/test/`.
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

### Issue Labels

GitHub issues use the spec-flow label vocabulary.

- **`status:ready`** means the issue is groomed and ready to work on. Pick the next work from the highest-priority `status:ready` issue.
- The other lifecycle labels, in order: `status:spec-review` (spec waits for owner approval), `status:in-progress` (implementation in progress), `status:in-review` (PR open, waits for owner review), `status:addressing` (fixes for owner review comments). An issue carries one lifecycle label at a time.
- Priority: `P0` (drop everything), `P1` (high), `P2` (normal), `P3` (low / someday).
- Do not start an issue that carries `agent:active` (an agent already works on it) or `blocked` (a hard dependency stops it).
- `requires-aws` and `requires-ami-bake` are repo-specific gates. Their rules are in [`spec-flow/WORKFLOWS.md`](spec-flow/WORKFLOWS.md).
- The test tiers, local gates, CI, and merge gate are in [`spec-flow/TESTING.md`](spec-flow/TESTING.md).

### Lab Test Plans

Lab test plans created with `/easy-db-lab:plan` are stored in `test-plans/` in the worktree root. Name them descriptively (e.g. `cassandra-5.0-validation-3node.md`). When the `/easy-db-lab:run` skill writes or references `plan.md`, use `test-plans/<descriptive-name>.md` instead.

Test plans are executed exclusively via `easy-db-lab.*` plugin skills — **never via `bin/test` or the `run-test` skill**:
- `/easy-db-lab:plan` — create a new plan
- `/easy-db-lab:run test-plans/<name>.md` — execute a plan

When using the `/easy-db-lab:plan` skill, use `bin/easy-db-lab` in the project directory.

### Cluster Workspace Directories

**CRITICAL:** Every test run must have its own workspace directory under `clusters/`. All `easy-db-lab` commands — `init`, `up`, `cassandra`, `presto`, `down`, everything — must be run from inside that directory. The tool writes `state.json`, `env.sh`, `sshConfig`, `kubeconfig`, and config files into the current directory. Running from the wrong directory will corrupt state or silently target the wrong cluster.

**The `/easy-db-lab:run` skill creates the workspace** — the directory, an `easy-db-lab` wrapper that
`cd`s into it, and the lab-report scaffold — before it executes any plan step. Test plans must **not**
create the workspace themselves. A plan's first step is provisioning (`init ... --up`); everything
after it uses `$EDB`, which `run` sets:

```bash
$EDB init ...
$EDB cassandra start
$EDB down --auto-approve
```

For multi-DC plans `run` creates one wrapper per DC and sets `$EDB_DC1`, `$EDB_DC2`, and so on.

Plans are executed exclusively through the plugin skills (see **Lab Test Plans** above), so there is
no supported path where a plan scaffolds its own workspace.

## Development Setup

### Java Version Management (SDKMAN)

The project uses **Java 21** (Temurin) as the default — managed via SDKMAN. The build is
JDK-version-flexible: it compiles with whatever JDK is installed (21 or newer, e.g. 25) and
always targets Java 21 bytecode (`jvmTarget`/`release = 21`, no fixed `jvmToolchain`), so
artifacts still run on older JVMs. Java 21 remains the recommended local default.

**Caveat — static analysis needs JDK 21:** `detekt` 1.23.8 (the last stable release) cannot
run under a JDK 25 runtime, so `./gradlew detekt` and the full `./gradlew check` must be run
on JDK 21. Compiling, running, and testing the app all work on JDK 25. CI runs `check` on
JDK 21. Upgrading detekt to the JDK-25-capable 2.0.x line is a separate migration (new
`dev.detekt` coordinate, K2 engine, baseline regeneration).

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

A build that uses it ends with `Configuration cache entry stored.` or `... reused.` A task action
must not reach `project` (e.g. the script-level `copy {}` in a `doLast`) — Gradle rejects the
cache entry. The Jib plugin's `jib`/`jibDockerBuild` tasks declare themselves incompatible, so a
build that runs them discards the entry and runs uncached; every other task caches.

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

**Note**: `ktlintFormat` auto-fixes many violations but can't fix all issues (e.g., line length). Always run `ktlintCheck` after formatting to catch remaining issues.

## Documentation & Specifications

User documentation is in `docs/` (mdbook format). When making user-facing changes, make sure the docs for that feature are up to date.

This project relies on **OpenSpec** to maintain product specifications. Specs live in `openspec/specs/` and are managed via the OpenSpec workflow (skills: `openspec-propose`, `openspec-explore`, `openspec-apply-change`, `openspec-archive-change`). **Specs are the source of truth for product decisions.** Before making any change, check relevant specs to ensure the change does not conflict. If a conflict is found, notify the user before proceeding — do not silently override the spec. If a change requires updating a spec, plan the spec update before moving on to implementation.

After running `openspec-apply-change`, use the `simplify` skill to review and improve the code quality of the changes.

If I refer to Kubernetes configs or k8 configs, I am referring to these: `src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/` by default.

## Observability

The cluster runs a full observability stack on the control node. When modifying any part of this stack, keep the related K8s manifests, Kotlin services, and user docs in sync.

The full reference (CNI scrapes, collectors, backends, the `down` tail flush, the account compactor, tenants, Grafana services, dashboard deploy commands) is the `observability-stack` skill in `.claude/skills/observability-stack/`. Load it before changing or debugging the stack.

**Always use the `dashboard-editor` agent for any dashboard change** — editing a panel query, fixing a wrong-looking graph, changing units, adding a panel or a dashboard. The agent enforces the full edit → deploy → read-back-from-Grafana sequence, and knows the metric traps (notably that CPU queries on `system_cpu_time_seconds_total` must not depend on its opt-in per-core label, whose absence made four dashboards render CPU at -190%). See [`dashboards/CLAUDE.md`](dashboards/CLAUDE.md).

## Subdirectory Documentation

Detailed patterns live in package-level CLAUDE.md files:
- [`events/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/events/CLAUDE.md) — Event bus, event hierarchy, adding new events, serialization
- [`commands/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md) — command patterns, available services
- [`services/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/services/CLAUDE.md) — SystemD service management
- [`services/aws/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/services/aws/CLAUDE.md) — AWS service classes (AMI, EC2, EMR, OpenSearch, S3)
- [`providers/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/providers/CLAUDE.md) — AWS SDK wrappers, SSH/Docker patterns, retry logic
- [`configuration/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/configuration/CLAUDE.md) — cluster state, templates, K8s manifest builders, observability stack details
- [`server/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/server/CLAUDE.md) — The REST status server and status cache
- [`kubernetes/CLAUDE.md`](src/main/kotlin/com/rustyrazorblade/easydblab/kubernetes/CLAUDE.md) — K8s client patterns
- [`src/test/.../CLAUDE.md`](src/test/kotlin/com/rustyrazorblade/easydblab/CLAUDE.md) — test infrastructure
