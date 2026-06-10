## Why

easy-db-lab is adding support for many SQL databases. There is currently no way to run
SQL benchmarking tools (sysbench, YCSB, pgbench, HammerDB, etc.) against those databases,
and no mechanism for one kit to discover and connect to another running kit's endpoints.
Without this, comparing database performance in the same cluster is not possible.

## What Changes

- `kit.yaml` gains a new arg type `kit-ref` that declares a cross-kit dependency on a
  running kit's SQL endpoints. Installing a bench kit with `--target <kit>` creates a
  named instance directory `<bench>-<target>/`, enabling multiple bench kits to run
  simultaneously against different databases.
- `KitEndpoint.EndpointType` gains two new values: `postgresql` (PostgreSQL wire protocol)
  and `mysql` (MySQL wire protocol), alongside the existing `jdbc`, `http`, and `native`.
- When a bench kit with a `kit-ref` arg starts, the framework resolves the named target
  kit's endpoints from its `kit.yaml` and injects `TARGET_*` environment variables into
  the start script (e.g., `TARGET_JDBC_URL`, `TARGET_PG_HOST`, `TARGET_MYSQL_HOST`).
- Kit install creates a `<bench>-<target>/` directory and registers the instance as a
  separate CLI subcommand, allowing `sysbench-clickhouse` and `sysbench-mysql` to run
  simultaneously in the same cluster.
- `runningKits` in `ClusterState` naturally tracks instance names (e.g.,
  `sysbench-clickhouse`, `sysbench-mysql`) — no structural change required.

## Capabilities

### New Capabilities

- `kit-cross-targeting`: A `kit-ref` arg type for bench kits that resolves a named
  running kit's SQL endpoints into `TARGET_*` environment variables at start time,
  and controls instance directory naming at install time.
- `wire-protocol-endpoints`: `postgresql` and `mysql` endpoint types in `kit.yaml`,
  producing `TARGET_PG_*` and `TARGET_MYSQL_*` env vars respectively.

### Modified Capabilities

- `kit-capabilities`: The arg type system gains a new `kit-ref` type. Validation at
  install time checks that the named target kit is installed and declares the required
  capability. This is a spec-level behaviour change — new requirement on the `args:`
  block.

## Impact

- `services/KitConfig.kt` — add `KIT_REF` to `KitArgSpec.ArgType`; add `POSTGRESQL`
  and `MYSQL` to `KitEndpoint.EndpointType`
- `services/TemplateVariables.kt` — new `buildTargetEndpointVars()` that reads a target
  kit's `kit.yaml` endpoints and produces `TARGET_*` vars
- `commands/install/KitInstallCommand.kt` — detect `kit-ref` arg; resolve instance
  directory name as `<kit>-<target>`; inject target endpoint vars at start time
- `commands/install/KitRunnerCommand.kt` — inject `TARGET_*` vars from target kit
  endpoints when a `kit-ref` arg is present in the installed config
- `openspec/specs/kit-capabilities/spec.md` — delta: new `kit-ref` arg type requirements
- `openspec/specs/kit-cross-targeting/spec.md` — new spec
- `openspec/specs/wire-protocol-endpoints/spec.md` — new spec
