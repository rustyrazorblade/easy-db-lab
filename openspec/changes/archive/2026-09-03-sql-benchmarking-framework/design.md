## Context

The kit system lets users install and run database workloads (clickhouse, presto, etc.)
in a cluster. Each kit lives in its own directory (`<workspace>/<kit-name>/`), is
tracked by name in `ClusterState.runningKits: Set<String>`, and exposes CLI subcommands
generated from its directory name by `CommandLineParser`.

Benchmark kits (sysbench, YCSB, pgbench, HammerDB, TPC-H) need to connect to a running
database kit. There are two gaps in the current architecture:

1. **No cross-kit endpoint discovery.** `TemplateVariables` provides cluster-level env
   vars (node IPs, counts, region) but nothing kit-specific. A bench kit script has no
   way to know the JDBC URL or host/port of a running database kit.

2. **No multi-instance support.** `runningKits` is `Set<String>` keyed by kit name.
   Running `sysbench` against both clickhouse and mysql simultaneously would require two
   instances of the same kit — impossible today since `sysbench/` can only exist once.

## Goals / Non-Goals

**Goals:**
- Allow bench kits to declare a `--target <kit>` arg that resolves to `TARGET_*`
  endpoint env vars at start time.
- Support `postgresql` and `mysql` wire-protocol endpoints in `kit.yaml` alongside
  existing `jdbc`, `http`, `native`, `cql`.
- Enable simultaneous multi-database benchmarking by naming bench kit instances
  `<bench>-<target>` (e.g., `sysbench-clickhouse`, `sysbench-mysql`).
- Keep the framework changes minimal — new bench kits should need only `kit.yaml` and
  shell scripts, no Kotlin.

**Non-Goals:**
- Shipping specific bench kits (sysbench, YCSB, etc.) — this framework makes them
  possible; authoring them is separate work.
- Changing how non-bench kits (db kits, app kits) are named or installed.
- Automatic wiring or hook-based auto-discovery — targeting is always explicit.
- Multi-target within a single bench instance (one instance = one target).

## Decisions

### 1. New `kit-ref` arg type drives both naming and env injection

A `kit-ref` arg is declared in `kit.yaml` just like `string` or `int`. The framework
gives it special treatment in two phases:

- **At install time**: `KitInstallCommand` detects a `kit-ref` arg, reads its value
  (the target kit name), and passes `instanceName = "${config.name}-${targetKitName}"`
  to `renderAndWrite()` instead of `config.name`. The `TARGET` variable is written to
  `.resolved-args` as usual.

- **At start time**: `KitRunnerCommand.buildAugmentedEnv()` detects a `kit-ref` arg in
  the installed config, reads `TARGET` from `.resolved-args`, loads the target kit's
  `kit.yaml`, and appends `TARGET_*` vars derived from its endpoints.

**Alternative considered:** a top-level `target:` key in `kit.yaml` (not an arg).
Rejected because args already have variable, description, and required semantics. Reusing
the arg system means no new parsing path and the target appears naturally in `kit info`.

**Alternative considered:** injecting all running kits' endpoints always (e.g.,
`CLICKHOUSE_JDBC_URL` always present). Rejected because it creates env var pollution and
doesn't solve the multi-instance naming problem.

### 2. Instance directory name = `<kit>-<target>`

`BaseInstallCommand.renderAndWrite()` already takes `kitName: String` as the directory
key. `KitInstallCommand` computes `instanceName = "${config.name}-$target"` when a
`kit-ref` arg is present, and passes that as `kitName`. No change to `BaseInstallCommand`
or `CommandLineParser` — the scanner already uses `kitDir.name` as the subcommand name.

`runningKits` naturally stores instance names (`sysbench-clickhouse`, `sysbench-mysql`)
since `ClusterStateManager.markKitRunning(name)` is called with whatever name the running
command knows itself as.

### 3. `TARGET_*` env var naming convention

For each endpoint type declared in the target kit's `kit.yaml`:

| Endpoint type | Vars injected |
|---------------|---------------|
| `jdbc`        | `TARGET_JDBC_URL`, `TARGET_JDBC_USER`, `TARGET_JDBC_DRIVER` |
| `postgresql`  | `TARGET_PG_HOST`, `TARGET_PG_PORT`, `TARGET_PG_USER`, `TARGET_PG_DATABASE` |
| `mysql`       | `TARGET_MYSQL_HOST`, `TARGET_MYSQL_PORT`, `TARGET_MYSQL_USER`, `TARGET_MYSQL_DATABASE` |
| `http`        | `TARGET_HTTP_URL` |

The node IP is resolved from cluster state using the endpoint's `node-type` (same logic
as `KitJdbcSqlService`). The `user` comes from the target kit's `capabilities` block (sql
capability). The `database` for wire-protocol endpoints comes from a new optional
`database` field on `KitEndpoint`.

### 4. New `KitEndpointResolver` service

Target endpoint resolution is extracted into a dedicated `KitEndpointResolver` service
rather than added to `KitRunnerCommand` or `TemplateVariables`. This keeps `TemplateVariables`
as a pure cluster-state view and gives the resolver a single responsibility:
`resolveTargetVars(targetKitDir: File, clusterState: ClusterState): Map<String, String>`.

`KitRunnerCommand` calls this service when it detects a `kit-ref` arg in the installed
config, merging the result into `augmentedEnv`.

### 5. `capability` field on `kit-ref` arg is advisory at this stage

The `capability` field (e.g., `capability: sql`) on a `kit-ref` arg declares the
intended requirement. In this first version the framework logs a warning but does not
fail-fast if the target kit does not declare that capability — endpoint availability is
the actual enforced contract. Hard validation can be added once bench kit authors have
real usage patterns.

## Risks / Trade-offs

- **Instance name collision**: Two installs of `sysbench --target clickhouse` produce the
  same directory. `collision-check: true` on bench kits will catch this with the existing
  collision detection path. Bench kit authors should set `collision-check: true`.

- **Target kit not installed**: If `sysbench-clickhouse start` runs but the `clickhouse/`
  directory is missing or has no readable `kit.yaml`, `KitEndpointResolver` returns an
  empty map and the script gets no `TARGET_*` vars. The script will fail with a clear
  missing-variable error. This is acceptable fail-fast behaviour.

- **wire-protocol endpoint info is partial without `database`**: The new `database` field
  on `KitEndpoint` is optional. If absent, `TARGET_PG_DATABASE` / `TARGET_MYSQL_DATABASE`
  are empty strings. Kit authors must add the field for wire-protocol bench kits to work.

## Migration Plan

No cluster migration required — clusters are ephemeral. New `EndpointType` values
(`POSTGRESQL`, `MYSQL`) are additive; existing `kit.yaml` files continue to parse
correctly. The `kit-ref` `ArgType` is additive — existing kits with no `kit-ref` args
are unaffected.
