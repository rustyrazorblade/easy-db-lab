## Why

Installed workloads expose services on private IPs that are only reachable via Tailscale — port 22 is the only publicly exposed port. Users have no way to discover connection endpoints (URLs, JDBC strings, native ports) after starting a workload without digging through scripts or K8s manifests.

## What Changes

- Every installed workload gains a synthesized `status` subcommand (`easy-db-lab presto status`, `easy-db-lab clickhouse status`, etc.)
- `install.yaml` gains two new optional sections: `endpoints:` (declares connection info by type) and `runtime:` (hints for running-state detection for shell-installed workloads)
- `TemplateVariables` gains `APP_NODE_IPS` — private IPs of app nodes (`ServerType.Stress`), mirroring the existing `DB_NODE_IPS`
- `WorkloadRunnerCommandFactory` synthesizes a `status` subcommand for every installed workload directory, regardless of whether `install.yaml` exists
- A new `WorkloadStatusCommand` reads cluster state, checks running status via K8s, and prints connection endpoints

## Capabilities

### New Capabilities

- `workload-status-command`: Per-workload `status` subcommand that shows running state (inferred from install method) and connection endpoints (declared in `install.yaml`)

### Modified Capabilities

- `workload-install-config`: `install.yaml` schema gains `endpoints:` and `runtime:` sections

## Impact

- `WorkloadInstallConfig` data class — new fields
- `WorkloadRunnerCommandFactory` — always synthesizes `status` subcommand
- `TemplateVariables` — new `APP_NODE_IPS` field
- `install.yaml` templates for presto and clickhouse — add `endpoints:` and `runtime:` declarations
- No breaking changes to existing commands
