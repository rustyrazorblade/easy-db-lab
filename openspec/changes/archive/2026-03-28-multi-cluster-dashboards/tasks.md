## 1. Cleanup

- [x] 1.1 Remove the `grafana-clickhouse-datasource` entry from `GrafanaDatasourceConfig.kt`

## 2. Cluster variable — dashboards that have none

- [x] 2.1 Add `cluster` variable to `system-overview.json`: query `label_values(up, cluster)`, `multi: true`, `includeAll: true`, `allValue: ".*"`, default `$__all`
- [x] 2.2 Add `cluster` variable to `stress.json`
- [x] 2.3 Add `cluster` variable to `opensearch.json`
- [x] 2.4 Add `cluster` variable to `emr.json`
- [x] 2.5 Add `cluster` variable to `s3-cloudwatch.json`
- [x] 2.6 Add `cluster` variable to `profiling.json`
- [x] 2.7 Add `cluster` variable to `log-investigation.json`

## 3. Cluster variable — dashboards that have single-select

- [x] 3.1 Update `cassandra-overview.json` cluster variable: set `multi: true`, `includeAll: true`, `allValue: ".*"`
- [x] 3.2 Update `cassandra-condensed.json` cluster variable: same as 3.1
- [x] 3.3 Update `clickhouse.json` `Cluster` variable: rename to `cluster` (lowercase), set `multi: true`, `includeAll: true`, `allValue: ".*"`, update source query to `label_values(up, cluster)`

## 4. Panel query updates — add `{cluster=~"$cluster"}`

- [x] 4.1 Update all panel queries in `system-overview.json` to include `{cluster=~"$cluster"}` — also scope the `hostname` variable query by cluster: `label_values(system_cpu_time_seconds_total{node_role=~"$service", cluster=~"$cluster"}, host_name)`
- [x] 4.2 Update all panel queries in `stress.json`
- [x] 4.3 Update all panel queries in `opensearch.json`
- [x] 4.4 Update all panel queries in `emr.json`
- [x] 4.5 Update all panel queries in `s3-cloudwatch.json`
- [x] 4.6 Update all panel queries in `clickhouse.json` — update any `$Cluster` references to `$cluster`
- [x] 4.7 Verify `cassandra-overview.json` panel queries already use `$cluster` (cascade chain means they do); fix any that don't
- [x] 4.8 Verify `cassandra-condensed.json` panel queries similarly

## 5. Ad hoc filters variable

- [x] 5.1 Add `adhocfilters` variable to `system-overview.json` (datasource: VictoriaMetrics)
- [x] 5.2 Add `adhocfilters` variable to `cassandra-overview.json`
- [x] 5.3 Add `adhocfilters` variable to `cassandra-condensed.json`
- [x] 5.4 Add `adhocfilters` variable to `clickhouse.json`
- [x] 5.5 Add `adhocfilters` variable to `stress.json`
- [x] 5.6 Add `adhocfilters` variable to `opensearch.json`
- [x] 5.7 Add `adhocfilters` variable to `emr.json`
- [x] 5.8 Add `adhocfilters` variable to `s3-cloudwatch.json`

## 6. Spec consolidation

- [x] 6.1 Update `openspec/specs/observability/spec.md` with the modified Grafana Dashboards requirement from this change
- [x] 6.2 Create `openspec/specs/multi-cluster-dashboards/spec.md` from this change's new capability spec
