## Why

When running many clusters with the same name, the per-database dashboards show one cluster at a time. There is no way to compare throughput, latency, and health across clusters side by side. A dedicated comparison dashboard consolidates all visualization approaches in one place so users can evaluate which panel types are most useful for their workflows.

## What Changes

- **New dashboard `dashboards/cluster-comparison.json`** — Cassandra cluster comparison dashboard with seven sections, each showcasing a different visualization approach or metric category. All panels use the `cluster=~"$cluster"` variable so the multi-select dropdown controls which clusters are compared.
- **New `GrafanaDashboard.CLUSTER_COMPARISON` enum entry** — registers the dashboard as optional so it is deployed alongside others when Cassandra is in use.

## Capabilities

### New Capabilities

- `cluster-comparison-dashboard`: A Cassandra-specific comparison dashboard providing fleet-level views (Polystat, Table), trend comparisons (Time Series, Bar Chart), activity history (Status History), latency distribution (Heatmap), latency range (fill-between band), and a percentile ladder table. All panels scope to the selected cluster(s) via the shared `cluster` variable.

### Modified Capabilities

- `observability`: Dashboard registry extended with the new optional Cassandra comparison dashboard.

## Impact

- `dashboards/cluster-comparison.json` — new file (~500–800 panels worth of JSON)
- `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/grafana/GrafanaDashboard.kt` — new enum entry
- `openspec/specs/observability/spec.md` — new scenario for cluster comparison dashboard
