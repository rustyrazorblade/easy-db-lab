## Why

Presto connector configuration is currently fragmented: the Cassandra catalog is hard-coded into `presto/values.yaml.template`, and other databases (ClickHouse, etc.) have no Presto connector at all. This means adding a new database requires modifying Presto's templates — the dependency runs the wrong direction. As the tool grows to support Jupyter, Hive, Dremio, Drill, JanusGraph, and user-supplied workloads via `--from`, each database cannot be expected to know Presto's (or any other tool's) connector format.

## What Changes

- **Presto owns catalog templates** for each known database (`presto/catalogs/cassandra.properties.template`, `clickhouse.properties.template`, etc.). The hard-coded Cassandra catalog in `values.yaml.template` is removed.
- **Workload lifecycle hooks** added to `install.yaml`: `post-workload-start` and `post-workload-stop` hook scripts that fire whenever any other workload starts or stops (optional scoping to specific workload names).
- **Running workload state** tracked in `state.json` so hook scripts can query what is currently running.
- **Hooks fire on both K8s workload lifecycle** (via `WorkloadRunnerCommand`) **and EC2/SystemD service lifecycle** (e.g. `cassandra start`/`stop`).
- **Hook retry via resilience4j**: failed hooks retry; on exhaustion an error is emitted and the cluster is left as-is. Recovery is re-running the workload's start command (idempotent).
- **Convention extension point**: custom `--from` workloads may provide a `presto-catalog.properties` file; Presto's `update-catalogs.sh` picks it up as a fallback for databases Presto doesn't natively know about.

## Capabilities

### New Capabilities

- `workload-lifecycle-hooks`: Post-start and post-stop hooks in `install.yaml` that fire when any (or specific) workloads start or stop. Enables cross-workload integration without coupling database workloads to tool-specific configuration.
- `workload-running-state`: Tracking of which workloads are currently started in `state.json`, readable by hook scripts via template variables or direct file access.

### Modified Capabilities

- `install-command`: `install.yaml` schema gains a `hooks` section.
- `platform-substrate`: `state.json` gains a running-workloads registry updated by `WorkloadRunnerCommand` and Cassandra/OpenSearch service commands.

## Impact

- `WorkloadInstallConfig` (Kotlin): add `hooks` field
- `WorkloadRunnerCommand` (Kotlin): fire post-start/post-stop hooks after successful execution; update running-workload state
- `ClusterState` / `ClusterStateManager` (Kotlin): add `runningWorkloads: Set<String>` field
- Cassandra and OpenSearch command paths: fire hooks on start/stop
- `presto/values.yaml.template`: remove hard-coded Cassandra catalog
- `presto/catalogs/`: new directory with per-database `.properties.template` files
- `presto/bin/update-catalogs.sh`: new script, replaces the inline catalog assembly in `start.sh`
- `presto/bin/start.sh`: simplified — delegates catalog assembly to `update-catalogs.sh`
