# Commands Package

This package contains all CLI commands for easy-db-lab. Commands are implemented using PicoCLI and serve as a thin orchestration layer between the user and the service layer.

## Architecture Principles

### Commands Are Thin Orchestration Layers

Commands should:
- Parse and validate user input
- Load cluster state and configuration
- Delegate work to services
- Format and display output to the user

Commands should NOT:
- Execute SSH commands directly (use `RemoteOperationsService` or domain services)
- Make K8s API calls directly (use `K8sService` or `StressJobService`)
- Call AWS/cloud provider APIs directly (use `AWS`, `EC2Service`, etc.)
- Contain business logic (belongs in services)

### Example: Good vs Bad

**Bad - Direct SSH in command:**
```kotlin
class MyCommand : PicoBaseCommand(context) {
    override fun execute() {
        remoteOps.executeRemotely(host, "sudo systemctl start cassandra")
    }
}
```

**Good - Delegating to service:**
```kotlin
class MyCommand : PicoBaseCommand(context) {
    private val cassandraService: CassandraService by inject()

    override fun execute() {
        cassandraService.start(host).getOrThrow()
    }
}
```

## Package Organization

```
commands/
├── CLAUDE.md              # This file
├── PicoBaseCommand.kt     # Base class for all commands
├── PicoCommand.kt         # Interface for commands
├── cassandra/             # Cassandra-related commands
│   ├── Cassandra.kt       # Parent command group
│   ├── stress/            # Stress testing subcommands
│   │   ├── Stress.kt      # Parent stress command
│   │   ├── StressStart.kt
│   │   ├── StressStop.kt
│   │   ├── StressStatus.kt
│   │   └── StressLogs.kt
│   └── ...
├── aws/                   # AWS-specific commands (e.g., PruneAMIs)
├── clickhouse/            # ClickHouse commands
├── grafana/               # Grafana commands
├── install/               # Kit install internals (BaseInstallCommand, KitInstallCommand, KitRunnerCommand, KitStatusCommand, factories)
├── kit/                   # Kit management commands (kit install, kit list, kit uninstall)
│   ├── Kit.kt             # Parent command group
│   ├── Install.kt         # kit install — scaffolds kit files (dynamic subcommands added at startup)
│   ├── KitList.kt         # kit list — lists discoverable templates
│   └── Uninstall.kt       # kit uninstall — removes an installed kit
├── logs/                  # Log import/listing commands
├── metrics/               # Metrics import/listing commands
├── opensearch/            # OpenSearch commands
├── platform/              # Platform substrate commands (platform create-pvs, platform info)
├── spark/                 # Spark commands
├── tailscale/             # Tailscale VPN commands
├── mixins/                # Reusable PicoCLI mixins
├── converters/            # Type converters for PicoCLI
├── formatters/            # Output formatters
└── *.kt                   # Top-level commands (Up, Down, Init, Start, Stop, Server, Repl, Cleanup, etc.)
```

## Creating New Commands

### 1. Extend PicoBaseCommand

```kotlin
@Command(
    name = "my-command",
    description = ["Description for help text"],
)
class MyCommand(
    context: Context,
) : PicoBaseCommand(context) {

    override fun execute() {
        // Implementation
    }
}
```

### 2. Inject Services

```kotlin
class MyCommand(context: Context) : PicoBaseCommand(context) {
    private val myService: MyService by inject()

    override fun execute() {
        myService.doSomething().getOrThrow()
    }
}
```

### 3. Use Annotations

- `@McpCommand` - Expose command as an MCP tool in the server
- `@RequireProfileSetup` - Require AWS profile configuration
- `@RequireSSHKey` - Require SSH key to be available

### 4. Register in CommandLineParser

Add to `CommandLineParser.kt`:
```kotlin
commandLine.addSubcommand("my-command", MyCommand(context))
```

For nested commands:
```kotlin
val parentCommand = CommandLine(Parent())
parentCommand.addSubcommand("child", ChildCommand(context))
commandLine.addSubcommand("parent", parentCommand)
```

## Dynamic Subcommands (install and kit runner)

Two groups of subcommands are registered dynamically at startup by `CommandLineParser` — do **not**
hand-code Kotlin classes for these:

### kit install &lt;kit&gt; subcommands

`registerDynamicInstallSubcommands()` scans all available `kit.yaml` files (classpath + profile
dir) and registers a `kit install <kit>` subcommand for each via `KitInstallCommandFactory`.
Flags, defaults, and collision detection are declared in `kit.yaml` — no Kotlin needed.

To add a new installable kit: create `src/main/resources/.../kits/<name>/kit.yaml` and
template files (including `bin/start.sh.template`, `bin/stop.sh.template`). No code changes needed.

### &lt;kit&gt; start/stop subcommands

`registerDynamicKitSubcommands()` scans `context.workingDirectory` for directories that
contain a `bin/` subdirectory with at least one executable script. Each such directory becomes a
top-level subcommand group (`easy-db-lab clickhouse`); each script becomes a subcommand
(`easy-db-lab clickhouse start`).

Scripts are run by `KitRunnerCommand` with cluster state variables injected as environment
variables. Dashboard JSON files in `<kit>/dashboards/` are installed into Grafana
automatically after a successful `start`.

## Annotations

- `@McpCommand` — expose command as an MCP tool in the server (must also add to `McpToolRegistry`)
- `@RequireProfileSetup` — require AWS profile configuration before execution
- `@RequireSSHKey` — require SSH key to be available
- `@RequireDocker` — require a Docker daemon to be reachable before execution
- `@RequiresProxy` — command's execution path reaches the private K8s API (Fabric8, via
  `K8sClientProvider`) or a private cluster HTTP endpoint (via `ProxiedHttpClientFactory`), and
  therefore needs the SOCKS5 tunnel. `DefaultCommandExecutor.checkRequirements()` starts the
  tunnel before the command runs, when the cluster is provisioned, infrastructure is UP, and
  Tailscale is not active; a failure to establish it propagates and aborts the command. See
  `annotations/RequiresProxy.kt` and design decision D7/D9 in
  `openspec/changes/up-fail-fast/design.md`.

  Commands driving remote tooling through `RemoteOperationsService` — helm, kubectl, cilium run
  *on the control node* over SSH — do **not** need this annotation. There is no local client
  making the call, and SSH never traverses the SOCKS tunnel (the tunnel exists only to reach the
  private Kubernetes API from the developer's machine). `Down` is a concrete example: its first
  actions unpublish the proxy port and kill the ssh process, so it must not carry `@RequiresProxy`
  or it would start a tunnel it exists to tear down.

  The annotation takes one parameter, `tolerateFailure: Boolean = false`. When `true`, a proxy
  failure is recorded on `ProxyAvailability` instead of aborting the command, which must then
  query that holder itself to render a degraded result. `Status` is the only command that sets
  this — it is read-only, so reporting a partial view can never leave the cluster in an
  unexpected state, and it is the command a user reaches for when the cluster is already broken.
  **No state-mutating command may set `tolerateFailure = true`.**

  There is deliberately no test enforcing that this annotation is applied to every command that
  reaches the private cluster network. Any such test would have to hand-maintain either a list of
  proxy-reaching service types or a suppression list for bytecode-reachability false positives —
  both are lists masquerading as checks that pass while silently covering less as the codebase
  moves. This is safe specifically because the annotation is opt-in (D7): a missed annotation
  degrades to a confusing Fabric8/HTTP connection error, the same failure mode the codebase had
  before the annotation existed, rather than breaking a previously-working command. Apply it by
  reading the command's execution path, not by a mechanical check.
- `@TriggerBackup` — trigger a cluster state backup after execution
- `@PreExecute` / `@PostExecute` — lifecycle hooks around command execution

## Special Long-Running Commands

- **`Server`** — starts the server (MCP + REST + background services via Ktor). Does not exit until stopped.
- **`Repl`** — starts interactive REPL. Does not exit until user quits.

Both set `context.isInteractive = true` to keep resources (like CQL sessions) alive across invocations.

## Available Services

Commands should delegate to these services:

| Service | Purpose |
|---------|---------|
| `CassandraService` | Cassandra lifecycle (start, stop, restart) |
| `SidecarService` | Cassandra sidecar management |
| `K8sService` | Kubernetes operations |
| `K3sService` | K3s cluster management |
| `StressJobService` | Stress testing jobs on K8s |
| `HostOperationsService` | Parallel operations across hosts |
| `ClusterProvisioningService` | EC2 instance provisioning |
| `ClusterConfigurationService` | Cluster configuration management |
| `AWSResourceSetupService` | IAM roles, security groups, VPC setup (`services.aws`) |
| `AwsS3BucketService` | S3 bucket admin: lifecycle, metrics, policies (`services.aws`) |
| `OpenSearchService` | OpenSearch domain management (`services.aws`) |
| `GrafanaDashboardService` | Grafana dashboard deployment |
| `VictoriaStreamService` | Stream metrics/logs to external Victoria instances |
| `VictoriaBackupService` | Backup/restore VictoriaMetrics and VictoriaLogs |
| `VictoriaLogsService` | VictoriaLogs query and ingestion |
| `TailscaleService` | Tailscale VPN setup on cluster nodes |
| `RegistryService` | Container registry management |
| `ClickHouseConfigService` | ClickHouse configuration |
| `SparkService` | Spark job submission (EMR) |
| `TemplateService` | K8s manifest template substitution |
| `InstallTemplateResolver` | Resolves install templates from profile dir, classpath, or `--from` path |
| `ObjectStore` | S3 file operations |
| `RemoteOperationsService` | SSH execution (use sparingly, prefer domain services) |

## Output

See the root `CLAUDE.md` for output conventions (events vs `println()`).
See [`events/CLAUDE.md`](../events/CLAUDE.md) for the event hierarchy and how to add new events.
