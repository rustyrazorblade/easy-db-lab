# Acceptance-criteria coverage — issue-937

Capability: `telemetry-redirect`.  Scenario names are cited from `specs/telemetry-redirect/spec.md`.

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | Redirect bring-up → collectors running, no local backend/Grafana workload | `telemetry-redirect: Redirect bring-up runs collectors without local storage` | ✅ Covered |
| AC2 | Collector exports metrics/logs/traces to external endpoints, not in-cluster svc | `telemetry-redirect: Collector exports metrics, logs, and traces externally` | ✅ Covered |
| AC3 | Profiling agents → external Pyroscope, not local | `telemetry-redirect: All profile producers ship externally` | ✅ Covered |
| AC4 | Normal (non-redirect) bring-up unchanged | `telemetry-redirect: Normal bring-up is unchanged` | ✅ Covered |
| AC5 | Missing/malformed endpoint → fail fast naming which; no partial stack | `telemetry-redirect: Missing or malformed endpoint aborts bring-up` | ✅ Covered |
| AC6 | `grafana update-config` not available on redirect; touches no Grafana | `telemetry-redirect: grafana update-config refuses on a redirect cluster` | ✅ Covered |
| AC7 | Each data point/stream carries origin-cluster identifier | `telemetry-redirect: Two DCs are distinguishable on one Grafana` + `Log and span-derived metrics also carry origin identity` | ✅ Covered |
| AC8 | Two clusters → single Grafana; no second Grafana | `telemetry-redirect: Two DCs are distinguishable on one Grafana` | ✅ Covered |
| QA | Two-DC test plan authored under `test-plans/` via plugin skills | tasks 7.2 (authored plan; a QA artifact, not a spec scenario) | ✅ Covered |
| QA | Plan proves all four DC2 signals land in DC1's stack with origin id, no second Grafana | tasks 7.3 (executed plan proves it end-to-end) | ✅ Covered |
| Finding 3 | Stack-reading `metrics`/`logs` commands refuse cleanly on redirect | `telemetry-redirect: metrics and logs commands refuse on a redirect cluster` | ✅ Covered |
| Risk | Local path must stay byte-for-byte unchanged (AC4 regression) | `telemetry-redirect: Normal bring-up is unchanged` + tasks 4.5 (ConfigMap-equality test) | ✅ Covered |
| Risk | Silent profile loss if only some of four producers redirected | `telemetry-redirect: All profile producers ship externally` + tasks 5.6 | ✅ Covered |
| Risk | Tempo port trap (4320 OTLP, not 3200) | derivation requirement + tasks 1.4 (asserts 4320) | ✅ Covered |
| Risk | Span-metrics `cluster` label may be dropped by connectors | `telemetry-redirect: Log and span-derived metrics also carry origin identity` + tasks 4.4 (debug-exporter verification) | ✅ Covered |
| Risk | Kit-dashboard auto-install must no-op on a redirect cluster (no local Grafana) | tasks 6.4 (implementation guard; verified in 6.5) | ✅ Covered |
| Risk | No cross-DC coupling — never enumerate/mutate the external stack | `telemetry-redirect: Redirect cluster leaves the external stack untouched` | ✅ Covered |
