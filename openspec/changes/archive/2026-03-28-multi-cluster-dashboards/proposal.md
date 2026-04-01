## Why

Dashboards currently assume a single cluster per VictoriaMetrics instance. As labs push metrics to a central VictoriaMetrics, the same `cluster` label that OTel already applies to every metric can drive a multi-cluster selector — enabling side-by-side comparison and external linking to specific cluster views without maintaining separate dashboard copies.

## What Changes

- **Add `cluster` multi-select variable** to all dashboards, backed by `label_values(up, cluster)`. Defaults to all clusters; single-cluster deployments see exactly one option.
- **Add `adhocfilters` variable** to all metric dashboards (VictoriaMetrics datasource). Surfaces any labels present in the datasource at runtime — external tooling labels are discoverable through the UI without being named in the OSS codebase.
- **Update all panel queries** to include `{cluster=~"$cluster"}` so panels scope correctly when a cluster is selected.
- **Fix `system-overview` cascade**: the `hostname` variable query must be scoped by `cluster` to avoid ambiguity (two clusters can both have a `db0`).
- **Remove dead ClickHouse native datasource** from `GrafanaDatasourceConfig.kt` — confirmed unused by any dashboard panel.

## Capabilities

### New Capabilities

- `multi-cluster-dashboards`: Cluster multi-select variable and ad hoc filter variable present on all dashboards; all panel queries scoped by cluster; external tools can pre-select clusters via URL params (`?var-cluster=name`).

### Modified Capabilities

- `observability`: Dashboard requirements extend to mandate cluster-scoped panel queries and the cluster variable pattern.

## Impact

- `dashboards/*.json` — all 11 dashboard JSON files modified (variable additions, panel query updates)
- `configuration/grafana/GrafanaDatasourceConfig.kt` — remove ClickHouse native datasource entry
- `openspec/specs/observability/spec.md` — new requirements for cluster variable and adhoc filters
