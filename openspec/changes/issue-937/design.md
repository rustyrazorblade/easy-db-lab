## Context

Every cluster stands up its own observability stack on the control node: node-level collectors (OTel Collector, Fluent Bit, Grafana Alloy, Beyla, ebpf_exporter, YACE, OTel Java agent) that gather data, and storage backends (VictoriaMetrics, VictoriaLogs, Tempo, Pyroscope) plus Grafana that receive and display it.  The whole stack is applied unconditionally at bring-up via `GrafanaUpdateConfig.execute()`, invoked from `Up.kt`.  Collector export destinations are hardcoded to in-cluster `*.default.svc.cluster.local` services in `otel-collector-config.yaml`; profiling ships to the control-node Pyroscope.  This design adds a life-of-cluster redirect mode that keeps the collectors but points every signal at an external stack and skips the local backends.

## Goals / Non-Goals

**Goals:**

- A single bring-up choice (redirect vs local) that moves all four signals together to an operator-supplied external stack.
- The default (non-redirect) path stays byte-for-byte unchanged (AC4).
- Origin identity on every exported signal, reusing the existing `cluster_name`.
- Fail-fast validation before any provisioning; clean refusal of stack-dependent commands on a redirect cluster.

**Non-Goals:**

- Cross-cluster network reachability (VPC peering, security groups, DNS) — reachable endpoints are input.
- Auto-discovery of another cluster's endpoints — the operator supplies them.
- Dual-write to both local and external — it is redirect-or-local.
- Any change to the receiving stack's tenancy, retention, or auth.
- Per-signal split (metrics external, logs local).

## Decisions

**State model.**  A nullable `TelemetryRedirect` value lives on `InitConfig` (persisted in `state.json`), set at `init`, read by every downstream step.  `null` is local mode (unchanged); non-null is redirect.  Nullable-as-a-whole is what makes "all four move together" and "redirect-only for life" structural rather than enforced by convention.

**Decision A — endpoint model (owner: base host + derived + optional overrides).**  `TelemetryRedirect` stores four fully-resolved endpoints.  A factory derives them from one base host and the known stack ports, honoring optional per-signal overrides.  Derivation reuses `Constants.K8s` ports so local and redirect stay in lockstep: metrics `http://<host>:8428/api/v1/write`, logs `http://<host>:9428/insert/opentelemetry`, traces `<host>:4320` (the OTLP receiver `TEMPO_OTLP_GRPC_PORT`, **not** 3200, Tempo's query port), profiles `http://<host>:4040`.

**Decision B — collector switching (owner: build-time `__KEY__` substitution).**  The three hardcoded exporter endpoints in `otel-collector-config.yaml` become `__VICTORIAMETRICS_ENDPOINT__`, `__VICTORIALOGS_ENDPOINT__`, `__TEMPO_ENDPOINT__`.  `OtelManifestBuilder.buildConfigMap` substitutes the in-cluster svc URLs when redirect is null and the external endpoints when set, mirroring the existing `__KIT_SCRAPE_JOBS__` substitution.  The endpoint choice stays in typed, unit-testable Kotlin, and the ConfigMap content differs visibly per mode.

**Decision C — AC6 reconciliation (owner: extract `ObservabilityStackService`).**  The apply/restart/wait orchestration is extracted out of `GrafanaUpdateConfig.execute()` into a new `ObservabilityStackService`.  `up` calls the service directly (redirect-aware: on redirect it skips `VictoriaManifestBuilder`, `TempoManifestBuilder`, the Pyroscope server resources, Grafana, and dashboard upload, and applies the Pyroscope agent only).  The `grafana update-config` command becomes a thin wrapper that guards on `telemetryRedirect != null` and refuses.  This also pays down the "commands are thin" rule the current ~230-line command violates.

**Command refusal scope (finding 3).**  The same refusal extends to the stack-reading commands that assume a local backend: `metrics query/backup/import/ls` and `logs query/backup/import/ls`.  On a redirect cluster they report that the data lives on the external stack and touch nothing, rather than failing with a bare connection error.

**Origin identity (AC7/AC8).**  The cluster name is already stamped via the `resource/cluster` processor (from `${env:CLUSTER_NAME}`, fed by `cluster-config.cluster_name`) on the `metrics/local`, `metrics/otlp`, `metrics/logs`, and `traces` pipelines, and `ProfilingConfig.clusterName` is already a Pyroscope label.  Two gaps are closed so origin identity is complete on a shared stack: `resource/cluster` is added to the `logs/local` and `logs/otlp` pipelines, and the span-derived `metrics/spanmetrics` and `metrics/servicegraph` pipelines are verified to carry `cluster` through the connectors (adding the processor there if the label is dropped; `spanmetrics`' `resource_metrics_key_attributes` must not strip it).  These changes are additive and harmless in local mode.

**Fail-fast validation (AC5).**  `TelemetryRedirect.validate()` returns the missing/malformed signal(s) by name.  It runs in `Init.validateParameters()` (operator sees the error at `init`) and again in an early `Up` guard (defends a hand-edited `state.json`, before any AWS resource is created).  Well-formedness only; no live-reachability probe.

**Profile producers (finding 1).**  Four producers target Pyroscope, not two: the Cassandra async-profiler (`ProfilingConfig.pyroscopeUrl` via `SetupInstance`), the Alloy eBPF agent (`config.alloy` write URL), the stress-job runner (`StressJobService`, injected into `JAVA_TOOL_OPTIONS`), and the Cassandra sidecar DaemonSet (`SidecarManifestBuilder`).  All four take `controlNodeIp`/`clusterName` build parameters and all four resolve to the external Pyroscope under redirect.

**Structural fold-ins.**  `PyroscopeManifestBuilder.buildAllResources()` is split into `buildServerResources()` / `buildAgentResources()` so redirect can apply the agent without the server.  `restartObservabilityWorkloads`' hardcoded name list is derived from the resources actually applied, so a redirect `up` does not emit spurious rollout-restart warnings for backends that were never deployed.

## Alternatives Considered

**Decision A — endpoint model.**  *Chosen:* base host + derived endpoints + optional per-signal overrides.  *Rejected — four explicit endpoints, no derivation:* four arguments for the common case (another easy-db-lab stack with a fixed layout); friction without value.  *Rejected — base host only, no overrides:* hard-blocks a non-easy-db-lab target whose layout differs, forcing a redesign later; the scope explicitly allows overrides.

**Decision B — collector switching.**  *Chosen:* build-time `__KEY__` template substitution in `OtelManifestBuilder`.  *Rejected — runtime `${env:...}` expansion from `cluster-config`:* keeps the ConfigMap YAML byte-identical across modes, but spreads the redirect decision across ConfigMap keys and DaemonSet env refs and prevents the builder unit test from seeing the resolved endpoint.  (Note: neither option touches the banned `socksProxyHost`/`socksProxyPort` JVM globals.)

**Decision C — AC6 reconciliation.**  *Chosen:* extract `ObservabilityStackService`; thin, redirect-guarded command.  *Rejected — keep the monolithic command with a "called from up" flag to bypass the refusal:* distinguishing caller identity via a flag is a hack and leaves ~230 lines of orchestration in a command class, against the documented thin-commands rule.

## Risks / Trade-offs

- **AC4 regression risk.**  The local path must stay byte-for-byte unchanged.  Mitigation: the build-time substitution keeps local substitution values identical to today's literals; a test asserts the local-mode ConfigMap equals the current output.
- **Silent profile loss.**  Redirecting some but not all four profile producers ships profiles to a dead local endpoint silently.  Mitigation: test all four resolved endpoints together.
- **Tempo port trap.**  Traces must target 4320 (OTLP), not 3200 (query).  Mitigation: derive from `Constants.K8s.TEMPO_OTLP_GRPC_PORT`, never a literal.
- **Span-metrics label survival (finding 2).**  Whether `cluster` survives the `spanmetrics`/`servicegraph` connectors is verified during implementation with the debug exporter before relying on it for AC7/AC8.
- **Kit dashboards on a redirect cluster.**  `KitRunnerCommand` auto-installs kit dashboards into Grafana; on a redirect cluster there is no local Grafana, so this must no-op rather than fail.  Kit dashboards on the external stack are the operator's responsibility; noted for implementation.
- **No cross-DC coupling.**  The design reads only this cluster's own `initConfig` and writes only its own collectors' endpoints; it never enumerates or mutates the external stack, honoring "not aware of the other DC."
