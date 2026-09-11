## ADDED Requirements

### Requirement: Redirect mode is selected at bring-up with a single base host

The operator SHALL be able to select redirect mode at `init` by supplying one external base host (`--redirect-telemetry <host>`), from which the four signal endpoints are derived using the known stack layout.  The operator MAY override any individual signal endpoint for a non-easy-db-lab target.  Redirect mode SHALL be a life-of-cluster property, persisted with the cluster state, and MUST NOT be a per-signal choice: all four signals move together.

#### Scenario: Base host derives all four endpoints

- **WHEN** a cluster is initialized with `--redirect-telemetry <host>` and no per-signal overrides
- **THEN** the metrics, logs, traces, and profiles endpoints are derived from `<host>` and the known stack ports, and stored on the cluster's init configuration as a single redirect value

#### Scenario: Per-signal override replaces a derived endpoint

- **WHEN** a cluster is initialized with `--redirect-telemetry <host>` and an explicit override for one signal
- **THEN** that signal uses the override endpoint and the other three remain derived from `<host>`

### Requirement: A redirecting cluster deploys collectors but no local backends

WHEN a cluster is brought up in redirect mode, its node-level collectors SHALL be deployed and running, and no VictoriaMetrics, VictoriaLogs, Tempo, Pyroscope server, or Grafana workload SHALL be stood up on that cluster's control node.  The Pyroscope eBPF agent SHALL still be deployed; only the Pyroscope server is skipped.

#### Scenario: Redirect bring-up runs collectors without local storage

- **WHEN** a cluster is brought up in redirect mode with all four endpoints supplied
- **THEN** the OTel Collector, Fluent Bit, Grafana Alloy, Beyla, ebpf_exporter, YACE, and OTel Java agent are deployed and running
- **AND** no VictoriaMetrics, VictoriaLogs, Tempo, Pyroscope server, or Grafana workload exists on the control node

#### Scenario: Normal bring-up is unchanged

- **WHEN** a cluster is brought up without redirect mode
- **THEN** the full local stack including Grafana is stood up and the collectors ship to the in-cluster backends, exactly as before

### Requirement: All four signals export to the external stack under redirect

WHEN a cluster is redirecting, the OTel Collector SHALL export metrics, logs, and traces to the supplied external endpoints rather than to the in-cluster `*.default.svc.cluster.local` backends, and every profile producer SHALL ingest to the supplied external Pyroscope rather than the local control-node Pyroscope.

#### Scenario: Collector exports metrics, logs, and traces externally

- **WHEN** a redirecting cluster's OTel Collector is running
- **THEN** its metrics, logs, and traces are exported to the supplied external endpoints and not to any in-cluster service address

#### Scenario: All profile producers ship externally

- **WHEN** a redirecting cluster's profiling paths run (Cassandra async-profiler, Alloy eBPF agent, stress jobs, and the Cassandra sidecar agent)
- **THEN** each ships profiles to the supplied external Pyroscope endpoint and none targets the local control node

### Requirement: Every exported signal carries the origin cluster identity

WHEN telemetry from a redirecting cluster arrives at the shared external stack, each metric data point, log stream, trace, and profile SHALL carry an identifier for its origin cluster, derived from the existing cluster name, so that two clusters shipping to the same stack are distinguishable on one Grafana.  No new identity scheme is introduced.

#### Scenario: Two DCs are distinguishable on one Grafana

- **WHEN** two clusters ship telemetry to the same external stack
- **THEN** both appear in the single target Grafana, each series/stream labelled with its own origin cluster name, and no second Grafana is stood up

#### Scenario: Log and span-derived metrics also carry origin identity

- **WHEN** a redirecting cluster exports logs (including system and OTLP logs) and span-derived metrics (RED and service-graph)
- **THEN** those streams carry the origin cluster identifier, consistent with the metrics and traces pipelines

### Requirement: Redirect bring-up fails fast on incomplete endpoints

WHEN redirect mode is requested but one or more of the four signal endpoints is missing or malformed, bring-up SHALL fail fast with a message naming which signal is missing or malformed, before any infrastructure is provisioned, and SHALL NOT stand up a partial or mixed local/external stack.  Validation SHALL check well-formedness only and MUST NOT block on live reachability.

#### Scenario: Missing or malformed endpoint aborts bring-up

- **WHEN** redirect mode is requested and at least one of the four signal endpoints is missing or malformed
- **THEN** bring-up fails with a message naming the offending signal
- **AND** no infrastructure and no partial or mixed stack is stood up

#### Scenario: Well-formed but unreachable endpoints do not block

- **WHEN** redirect mode is requested with four well-formed endpoints that are not currently reachable
- **THEN** bring-up proceeds and unreachability surfaces later as collector send failures, not as a bring-up error

### Requirement: Stack-dependent commands refuse cleanly on a redirect cluster

WHEN a command that reads from or writes to the local observability stack is run against a redirect cluster, it SHALL report that it does not apply on a redirect cluster and SHALL touch no Grafana or backend — neither the (absent) local one nor the external stack.  This covers `grafana update-config`, `metrics query`, `metrics backup`, `metrics import`, `metrics ls`, `logs query`, `logs backup`, `logs import`, and `logs ls`.

#### Scenario: grafana update-config refuses on a redirect cluster

- **WHEN** `grafana update-config` is run against a redirect cluster
- **THEN** it reports that it does not apply on a redirect cluster and modifies no Grafana

#### Scenario: metrics and logs commands refuse on a redirect cluster

- **WHEN** a `metrics` or `logs` query/backup/import/ls command is run against a redirect cluster
- **THEN** it reports that the data lives on the external stack and does not apply here, rather than failing with a bare connection error against the absent local backend

### Requirement: A redirect cluster never modifies the external stack

A redirect cluster SHALL be redirect-only for its whole life; it MUST NOT later stand up local backends or a local Grafana, and it MUST NOT modify the external stack's dashboards or configuration.  It is not aware of any other cluster and MUST NOT enumerate or mutate it.

#### Scenario: Redirect cluster leaves the external stack untouched

- **WHEN** a redirect cluster runs its full bring-up and any subsequent commands
- **THEN** it never applies dashboards or configuration to the external stack and never stands up a local backend or Grafana
