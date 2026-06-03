## Context

Presto's Cassandra catalog is currently hard-coded in `presto/values.yaml.template`. Other databases have no Presto integration at all. The `start.sh.template` has a scan loop for `presto-catalog.properties` files in sibling workload directories — a convention that inverts the dependency and requires each database to know Presto's configuration format.

The tool is expanding beyond Cassandra and ClickHouse to include Jupyter, Hive, Dremio, Drill, JanusGraph, and user-supplied workloads via `--from`. A scalable model needs tools like Presto to own their own connector knowledge, and a general-purpose hook system so workloads can react to each other's lifecycle events without coupling.

## Goals / Non-Goals

**Goals:**
- Presto owns catalog templates for all natively supported databases
- General-purpose `post-workload-start` / `post-workload-stop` hooks in `install.yaml`
- Running workload state tracked in `state.json`
- Hooks fire on both K8s workload lifecycle and EC2/SystemD service lifecycle (Cassandra)
- Hook scripts are idempotent: always render from current state, not from the triggering event
- resilience4j retry on hook failure; emit error and leave state as-is on exhaustion

**Non-Goals:**
- Pre-workload hooks (not needed now)
- Hook ordering / serialization across concurrent starts (rare enough to be acceptable)
- Automatic rollback on hook failure

## Decisions

### 1. Hooks are shell scripts, invoked by `WorkloadRunnerCommand`

Hook scripts live in the workload's `bin/` directory and are declared in `install.yaml`:

```yaml
hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
  post-workload-stop:
    script: bin/update-catalogs.sh
```

After a successful start or stop, `WorkloadRunnerCommand` scans ALL installed workloads' `install.yaml` files, finds any that declare a matching hook, and runs them. This means hooks are declared by the *observer* workload (Presto), not the *trigger* workload (Cassandra, ClickHouse).

**Alternative considered**: trigger workload pushes to observer (e.g. ClickHouse declares "notify presto on start"). Rejected — requires each database to enumerate every tool that cares about it, which is the coupling we're trying to eliminate.

### 2. Hook scripts are idempotent and read state, not arguments

Hook scripts are not told *what* triggered them. They read `state.json` (via template variables or directly) to determine what is currently running, then render the full configuration from scratch. This avoids state accumulation bugs and makes `easy-db-lab presto start` the natural recovery path.

**Alternative considered**: pass `--workload <name> --event started` as arguments. Rejected — adds complexity and tempts scripts to apply incremental updates, which are harder to reason about.

### 3. Running workload state in `state.json`

`ClusterState` gains a `runningWorkloads: Set<String>` field. `WorkloadRunnerCommand` adds/removes the workload name after successful start/stop. Cassandra and OpenSearch command paths do the same.

The set contains workload names as strings (e.g. `"cassandra"`, `"clickhouse"`, `"presto"`). Hook scripts receive this as a template variable `__RUNNING_WORKLOADS__` (comma-separated) and can also read `state.json` directly.

### 4. Hooks fire for ALL workload starts/stops by default; optional scoping

```yaml
hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    # workloads: [cassandra, clickhouse]  # optional — omit to fire on all
```

Unscoped is the default because the scripts are idempotent (re-rendering when Jupyter starts is harmless). Scoping is available for optimization but not required.

### 5. Hook retry via resilience4j, then emit error

`WorkloadStepExecutor` (or a new `WorkloadHookExecutor`) runs each hook script with resilience4j retry (configurable, default 3 attempts with exponential backoff). On exhaustion: emit `Event.Workload.HookFailed` (error event → stderr), continue. The cluster is left in a potentially inconsistent state; the user recovers by re-running the failing workload's start command.

### 6. Presto catalog structure

```
presto/
  catalogs/
    cassandra.properties.template
    clickhouse.properties.template
  bin/
    update-catalogs.sh.template   ← replaces inline catalog assembly in start.sh
    start.sh.template             ← simplified; calls update-catalogs.sh
  values.yaml.template            ← Cassandra catalog entry removed
```

`update-catalogs.sh` reads `state.json`, renders a catalog properties file for each running database that has a matching template in `presto/catalogs/`, and assembles them into a `--values` override for `helm upgrade`. It also scans sibling directories for `presto-catalog.properties` as a fallback for custom `--from` workloads.

### 7. Hook execution scope: installed workloads only

`WorkloadRunnerCommand` scans the working directory for installed workload directories (those with a `bin/` subdirectory or `install.yaml`). Only installed workloads have their hooks evaluated. Presto's hooks don't fire if Presto hasn't been installed.

## Risks / Trade-offs

- **Concurrent starts race**: if Cassandra and ClickHouse start in quick succession, two Presto `helm upgrade` calls may interleave. Helm is not concurrency-safe on the same release. Mitigation: hook scripts can use a lockfile; for now the risk is low given manual CLI usage.
- **Hook discovery cost**: scanning all installed workload `install.yaml` files on every start/stop adds a small overhead. Acceptable given install frequency.
- **`--from` workloads**: custom workloads that Presto doesn't natively support must provide `presto-catalog.properties` themselves. This is documented as the extension point.
- **State divergence**: if `state.json` update and hook execution are not atomic, a crash between them leaves state inconsistent. Mitigation: update state first, then run hooks. Re-running start is idempotent.

## Migration Plan

No migration needed — clusters are ephemeral. Existing installed workload directories without `install.yaml` hooks continue to work unchanged.

## Open Questions

- Should `__RUNNING_WORKLOADS__` be a comma-separated string or a YAML list in the template variable? Scripts likely prefer comma-separated for shell `case` matching.
