## Context

Every metric scraped by the OTel collector already carries a `cluster` label set from the `CLUSTER_NAME` environment variable. Dashboards have been written assuming a single cluster per VictoriaMetrics instance. As labs push to a shared central VictoriaMetrics, cluster-scoped filtering becomes necessary to avoid metric bleed between labs.

Dashboard JSON files live in `dashboards/` at the project root and are loaded as classpath resources by `GrafanaManifestBuilder` without template substitution. Variables and queries are pure Grafana JSON.

Current state of cluster variables across dashboards:

| Dashboard | cluster var | multi | includeAll |
|---|---|---|---|
| system-overview | none | — | — |
| cassandra-overview / condensed | yes (`up{job="cassandra-maac"}`) | no | no |
| clickhouse | yes (`ClickHouseAsyncMetrics_Uptime`) | no | no |
| others | none | — | — |

A native ClickHouse datasource is provisioned in `GrafanaDatasourceConfig.kt` but confirmed unused by any dashboard panel (all ClickHouse panels use the prometheus/VictoriaMetrics datasource).

## Goals / Non-Goals

**Goals:**
- All dashboards show a `cluster` variable that works in both single-cluster and multi-cluster VictoriaMetrics deployments
- `system-overview` hostname cascade is scoped by cluster so `db0` in cluster A and `db0` in cluster B are distinguished
- All metric dashboard panel queries filter by `{cluster=~"$cluster"}`
- An ad hoc filters variable on metric dashboards exposes arbitrary runtime labels without naming them in the OSS codebase
- Dead ClickHouse native datasource config is removed
- External tools can pre-select clusters via URL params (`?var-cluster=name`)

**Non-Goals:**
- Ad hoc filters for log dashboards (VictoriaLogs uses a different query language; adhocfilters variable type only applies to the prometheus datasource)
- Introducing new datasources or changing the observability storage backend
- Centralizing VictoriaMetrics federation (separate infrastructure concern)

## Decisions

### Decision: Edit dashboard JSON files directly

**Chosen**: Modify `dashboards/*.json` directly.

**Alternative considered**: Inject variables programmatically in `GrafanaManifestBuilder` by parsing and mutating JSON at build time.

**Rationale**: Programmatic injection adds runtime complexity, makes dashboards harder to inspect/debug, and doesn't reduce the number of files that need to be touched (each dashboard's panel queries still need editing). Direct JSON edits are transparent and match the existing pattern for all other dashboard development.

### Decision: Cluster variable queries `label_values(up, cluster)`

**Chosen**: All dashboards use `label_values(up, cluster)` as the cluster variable source.

**Rationale**: `up` is a universal metric scraped for every job on every node. It always carries the `cluster` label from the OTel relabel config. Database-specific metrics (e.g., `up{job="cassandra-maac"}`) would hide a cluster from the dropdown on dashboards where that database isn't running.

**Exception**: The Cassandra dashboards keep `up{job="cassandra-maac"}` as the source for their existing cluster variable since they already cascade datacenter/rack/node from it.

### Decision: Standardize cluster variable name to `cluster` (lowercase)

The ClickHouse dashboard uses `Cluster` (capitalized). All dashboards will use lowercase `cluster` for consistency. ClickHouse panel queries referencing `$Cluster` will be updated to `$cluster`.

### Decision: Default to "All clusters" (`$__all`)

**Rationale**: A single-cluster deployment sees exactly one option and "All" resolves to it automatically. In multi-cluster mode, "All" shows aggregated metrics which is a valid starting view. Users can narrow down via the URL param mechanism (`?var-cluster=name`) for focused views.

### Decision: Ad hoc filters scoped to metric dashboards only

`adhocfilters` variable type integrates with the prometheus datasource and injects label filters into all PromQL queries. Log dashboards (VictoriaLogs) and trace panels (Tempo) use different query languages where this mechanism doesn't apply. Ad hoc filters will be added only to dashboards that are primarily VictoriaMetrics-backed.

## Risks / Trade-offs

- **Aggregated metrics in "All" mode may be misleading** → Acceptable for now; users comparing clusters should select specific ones. Panels showing counts (e.g., total write throughput) will sum across clusters when "All" is selected, which can be useful.
- **Panel query edits are mechanical but voluminous** → The `clickhouse.json` alone has 291 panels. Each panel's PromQL query needs `cluster=~"$cluster"` added. A scripted approach (Python/jq) should be used to minimize error, followed by manual review of a sample.
- **Cassandra hostname variable regex `db.*` may conflict** → The existing Cassandra `node` variable uses `regex: "db.*"` to filter hostnames. In multi-cluster mode this still works correctly since the cascade already filters by cluster before hostname.

## Migration Plan

1. Remove native ClickHouse datasource from `GrafanaDatasourceConfig.kt`
2. Update each dashboard JSON — add/update cluster variable, add adhocfilters, update panel queries
3. Deploy via `grafana update-config` — no cluster restart required, Grafana hot-reloads ConfigMaps
4. No rollback complexity — dashboard changes are stateless ConfigMap updates
