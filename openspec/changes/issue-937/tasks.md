## 1. Redirect config model and validation

- [ ] 1.1 Add `TelemetryRedirect` value type in `configuration/` holding four resolved endpoints, with a factory that derives them from one base host and the `Constants.K8s` ports (metrics 8428, logs 9428, traces 4320 OTLP, profiles 4040) and applies optional per-signal overrides.
- [ ] 1.2 Add `TelemetryRedirect.validate()` returning the missing/malformed signal name(s); well-formedness only, no reachability probe.
- [ ] 1.3 Add `telemetryRedirect: TelemetryRedirect? = null` to `InitConfig` in `ClusterState.kt` and wire it through `fromInit`.
- [ ] 1.4 Unit-test derivation (base host → four endpoints), override replacement, and validation (each missing/malformed signal named); assert traces use 4320, not 3200.

## 2. Bring-up wiring (init + up)

- [ ] 2.1 Add `--redirect-telemetry <host>` and the four optional per-signal override `@Option`s to `Init.kt`; build and validate the `TelemetryRedirect` in `validateParameters()`.
- [ ] 2.2 Add an early `Up.kt` guard (peer of `validateControlNodeConfigured`) that re-validates `initConfig.telemetryRedirect` before any AWS resource is created; fail fast naming the offending signal, standing up nothing.
- [ ] 2.3 Emit a typed event (new `Event.*` domain type) carrying the offending signal name on validation failure; no `Event.Message`/`Event.Error`.

## 3. Observability stack service extraction (Decision C)

- [ ] 3.1 Extract the apply/restart/wait orchestration out of `GrafanaUpdateConfig.execute()` into a new `ObservabilityStackService` (Koin-registered), taking `TelemetryRedirect?`.
- [ ] 3.2 On redirect, skip `VictoriaManifestBuilder`, `TempoManifestBuilder`, the Pyroscope server resources, Grafana, and dashboard upload; deploy collectors and the Pyroscope agent.
- [ ] 3.3 Point `Up.kt`'s nested observability apply at the service directly (not the command), so bring-up still deploys.
- [ ] 3.4 Derive `restartObservabilityWorkloads`' target list from the resources actually applied, so redirect emits no spurious rollout-restart warnings.

## 4. Collector export switching + origin identity (Decisions B, findings 2)

- [ ] 4.1 Replace the three hardcoded exporter endpoints in `otel-collector-config.yaml` with `__VICTORIAMETRICS_ENDPOINT__`, `__VICTORIALOGS_ENDPOINT__`, `__TEMPO_ENDPOINT__`.
- [ ] 4.2 Have `OtelManifestBuilder.buildConfigMap`/`buildAllResources` take `TelemetryRedirect?` and substitute in-cluster svc URLs when null, external endpoints when set.
- [ ] 4.3 Add `resource/cluster` to the `logs/local` and `logs/otlp` pipelines so system/OTLP logs carry origin identity.
- [ ] 4.4 Verify (debug exporter) that `cluster` survives the `spanmetrics`/`servicegraph` connectors into remote-write; add `resource/cluster` to those metrics pipelines and confirm `spanmetrics.resource_metrics_key_attributes` does not strip it if needed.
- [ ] 4.5 Test the local-mode ConfigMap is byte-for-byte equal to the current output (AC4 guard), and the redirect-mode ConfigMap carries the external endpoints.

## 5. Profiling redirect — all four producers (finding 1)

- [ ] 5.1 Split `PyroscopeManifestBuilder.buildAllResources()` into `buildServerResources()` and `buildAgentResources()`; update `K8sServiceIntegrationTest.collectAllResources()`.
- [ ] 5.2 Resolve `ProfilingConfig.pyroscopeUrl` (via `SetupInstance`) to the external Pyroscope under redirect.
- [ ] 5.3 Substitute the Alloy eBPF agent write URL (`config.alloy`, `__CONTROL_NODE_IP__:4040`) to the external endpoint under redirect.
- [ ] 5.4 Point `StressJobService`'s `pyroscopeServerAddress` at the external endpoint under redirect.
- [ ] 5.5 Point `SidecarManifestBuilder`'s Pyroscope address at the external endpoint under redirect.
- [ ] 5.6 Test all four resolved profiling endpoints together under redirect (none targets the control node).

## 6. Stack-dependent command guards (AC6 + finding 3)

- [ ] 6.1 Make `grafana update-config` a thin wrapper guarding on `telemetryRedirect != null`, refusing with a clear message and touching no Grafana.
- [ ] 6.2 Make `metrics query/backup/import/ls` refuse cleanly on a redirect cluster (data lives on the external stack), touching no backend.
- [ ] 6.3 Make `logs query/backup/import/ls` refuse cleanly on a redirect cluster.
- [ ] 6.4 Confirm `KitRunnerCommand`'s kit-dashboard auto-install no-ops (does not fail) on a redirect cluster.
- [ ] 6.5 Test each guarded command reports the redirect message and performs no backend/Grafana call.

## 7. Docs and QA test plan

- [ ] 7.1 Document the redirect bring-up mode (the `--redirect-telemetry` option, derived endpoints, overrides, all-four-together, redirect-only-for-life) in `docs/`.
- [ ] 7.2 Author a two-DC test plan under `test-plans/` (DC1 full local stack, DC2 redirect pointed at DC1) runnable via `/easy-db-lab:plan` then `/easy-db-lab:run`.
- [ ] 7.3 The plan proves all four DC2 signals land in DC1's stack (metrics/VictoriaMetrics, logs/VictoriaLogs, traces/Tempo, profiles/Pyroscope), each carrying DC2's origin identifier, with no second Grafana.

## 8. Verification

- [ ] 8.1 `./gradlew ktlintFormat && ./gradlew check` (JDK 21 for detekt) all green.
- [ ] 8.2 Re-read the spec scenarios and confirm each maps to a passing test or the QA plan step.
