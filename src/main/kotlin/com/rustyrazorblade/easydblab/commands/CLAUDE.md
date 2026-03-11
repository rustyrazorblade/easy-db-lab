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
├── logs/                  # Log import/listing commands
├── metrics/               # Metrics import/listing commands
├── opensearch/            # OpenSearch commands
├── spark/                 # Spark commands
├── tailscale/             # Tailscale VPN commands
├── mixins/                # Reusable PicoCLI mixins
├── converters/            # Type converters for PicoCLI
├── formatters/            # Output formatters
└── *.kt                   # Top-level commands (Up, Down, Init, Start, Stop, Server, Repl, etc.)
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

## Annotations

- `@McpCommand` — expose command as an MCP tool in the server (must also add to `McpToolRegistry`)
- `@RequireProfileSetup` — require AWS profile configuration before execution
- `@RequireSSHKey` — require SSH key to be available
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
| `ObjectStore` | S3 file operations |
| `RemoteOperationsService` | SSH execution (use sparingly, prefer domain services) |

## Output

Commands have two output mechanisms available via `PicoBaseCommand`:

- **`eventBus`** — preferred for structured output. Services are fully migrated to `eventBus.emit()`.
- **`outputHandler`** — legacy pattern, still used in many command files during the migration.

Both coexist during the transition. New code should use `eventBus.emit()` with domain-specific events:

```kotlin
// Preferred — structured event
eventBus.emit(Event.Cassandra.Starting(host.alias))

// Legacy — still works, will be migrated
outputHandler.handleMessage("Starting Cassandra on ${host.alias}...")
```

Do not use logging frameworks for user output - this breaks the CLI UX.

See [`events/CLAUDE.md`](../events/CLAUDE.md) for the event hierarchy and how to add new events.
