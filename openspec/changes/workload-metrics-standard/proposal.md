## Why

Workloads with `metrics.type: scrape` send data to VictoriaMetrics, but there is no standard way to discover what those metrics are, document the useful ones for agents, or build Grafana dashboards from real data. Dashboards built from memory or guesswork are unreliable; we need a catalog-driven approach anchored to live data.

## What Changes

- **New global script** `bin/export-workload-metrics <workload>` — queries VictoriaMetrics after a workload is running, outputs JSON series catalog, saves to `<workload>/metrics-catalog.json` in the cluster working directory
- **Committed resource artifacts** per scrape-type workload (`metrics-catalog.json`, `METRICS.md`, `dashboards/<workload>.json`) installed alongside the workload's other templates; `metrics-catalog.json` is the authoritative source for METRICS.md and the dashboard
- **Test verification step** added to every integration test (`bin/tests/<workload>`) that asserts metrics are flowing to VictoriaMetrics after `start` (≥10 series visible)
- **Workload-creation skill update** (`generate-workload`) — the skill now knows about `metrics-catalog.json`, `METRICS.md`, and when to include them for scrape-type workloads
- **Presto implementation** — `metrics-catalog.json`, `METRICS.md`, `dashboards/presto.json` committed as real artifacts generated from a live cluster; presto integration test gains a metrics verification step
- **ClickHouse implementation** — same pattern applied to ClickHouse

## Capabilities

### New Capabilities
- `workload-metrics-catalog`: Global `bin/export-workload-metrics` script; committed `metrics-catalog.json`, `METRICS.md`, and `dashboards/<workload>.json` artifacts inside workload resource templates; test verification pattern for confirming metrics flow after start

### Modified Capabilities
- `workload-metrics-declaration`: Test verification requirement added — integration tests MUST assert that ≥10 metric series are visible in VictoriaMetrics within 30s of a successful `start`

## Impact

- `bin/export-workload-metrics` (new shell script)
- `bin/tests/presto`, `bin/tests/clickhouse` (new verification steps)
- `src/main/resources/.../install/presto/` (new `metrics-catalog.json`, `METRICS.md`, `dashboards/presto.json`)
- `src/main/resources/.../install/clickhouse/` (same three files)
- `src/main/kotlin/.../install/WorkloadInstallCommand.kt` or `WorkloadRunnerCommand.kt` — may need to install dashboard artifacts on `start`
- Workload-creation skill (`generate-workload`) — updated guidance for metrics artifacts
