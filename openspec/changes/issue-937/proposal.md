## Why

Every easy-db-lab cluster stands up its own full observability stack (VictoriaMetrics, VictoriaLogs, Tempo, Pyroscope, Grafana) on its control node.  Running two clusters at once — most acutely a multi-DC test where DC1 and DC2 are tuned differently to see which wins — produces two independent Grafanas and two sets of backends, so comparison means flipping between browser tabs.  Operators need to point one cluster's telemetry at an existing observability stack and skip standing up its own backends, so all data lands in a single Grafana.

## What Changes

- A new cluster bring-up mode, **redirect mode**, selected at `init` with a single `--redirect-telemetry <base-host>` option; the four signal endpoints are derived from the known stack layout, with optional per-signal override options for a non-easy-db-lab target.
- All four telemetry signals move together under one control (no per-signal opt-in): metrics (VictoriaMetrics remote-write), logs (VictoriaLogs), traces (Tempo OTLP), and profiles (Pyroscope).  Redirect is all-external or all-local; there is no dual-write.
- On a redirecting cluster the node-level collectors (OTel Collector, Fluent Bit, Grafana Alloy, Beyla, ebpf_exporter, YACE, OTel Java agent) still deploy and run, but the local storage backends (VictoriaMetrics, VictoriaLogs, Tempo, the Pyroscope server) and local Grafana are never stood up.
- The four collector export destinations resolve to the supplied external endpoints instead of the in-cluster `*.default.svc.cluster.local` services; both profiling paths (Cassandra async-profiler, Alloy eBPF) and both additional profile producers (stress jobs, the Cassandra sidecar) target the external Pyroscope.
- Every exported signal stream carries the origin cluster's name as a label/attribute, so two DCs are distinguishable on one Grafana.  The existing `cluster_name` value drives this; the log and span-derived metrics pipelines that currently omit it are made consistent.
- A redirect cluster is redirect-only for its whole life and never modifies the external stack.  Stack-dependent commands that assume a local backend — `grafana update-config`, `metrics query/backup/import/ls`, `logs query/backup/import/ls` — refuse cleanly on a redirect cluster and touch nothing.
- Redirect bring-up fails fast, before provisioning, if any of the four endpoints is missing or malformed, naming the offending signal, and never stands up a partial or mixed stack.
- **Structural fold-ins** (enabling the above): extract an `ObservabilityStackService` from `GrafanaUpdateConfig`; split `PyroscopeManifestBuilder` into server and agent resources; derive the observability workload-restart list from what was actually applied.

## Capabilities

### New Capabilities

- `telemetry-redirect`: a life-of-cluster bring-up mode that ships all four telemetry signals to an operator-supplied external observability stack instead of standing up local backends, stamps origin identity on every signal, fails fast on incomplete endpoints, and makes stack-dependent commands refuse cleanly.

### Modified Capabilities

<!-- None. Redirect is a new, conditional mode; the default (non-redirect) behavior of observability, profiling, and the query/backup commands is unchanged (AC4). Baselines whose behavior gains a redirect-gated branch are enumerated in overrides.md for the owner's awareness. -->

## Impact

- **Bring-up path**: `commands/Init.kt` (new options + validation), `commands/Up.kt` (early re-validation guard), `commands/SetupInstance.kt` (profiling endpoint selection), `configuration/ClusterState.kt` (`InitConfig.telemetryRedirect`).
- **New types**: `configuration/TelemetryRedirect.kt` (value + factory + validation), `services/ObservabilityStackService.kt` (extracted apply/restart/wait orchestration).
- **Manifest builders**: `configuration/otel/OtelManifestBuilder.kt` + `otel-collector-config.yaml` (exporter endpoint substitution, log/span-metrics cluster label), `configuration/pyroscope/PyroscopeManifestBuilder.kt` + `config.alloy` (server/agent split, agent write URL).
- **Profile producers**: `services/StressJobService.kt`, `configuration/sidecar/SidecarManifestBuilder.kt`, `profiling/ProfilingConfig.kt` (external Pyroscope address).
- **Commands guarded on redirect**: `commands/grafana/GrafanaUpdateConfig.kt`, `metrics` and `logs` command groups (`VictoriaMetricsQueryService`, backup/import/ls services).
- **Constants**: reuse `Constants.K8s` ports for endpoint derivation.
- **Docs**: user docs for the redirect bring-up mode and a two-DC test plan under `test-plans/`.
