## RENAMED Requirements

- FROM: `### Requirement: Global export script queries VictoriaMetrics for any scrape-type kit`
- TO: `### Requirement: Global export script queries Mimir for any scrape-type kit`

## MODIFIED Requirements

### Requirement: Global export script queries Mimir for any scrape-type kit
A single script `bin/export-workload-metrics` SHALL accept a kit name as its only argument, read `CONTROL_HOST_PRIVATE` from `env.sh` and the cluster's tenant from the workspace in the current directory, query Mimir at `http://$CONTROL_HOST_PRIVATE:9009/prometheus` with the tenant in the `X-Scope-OrgID` header for the kit's series that were live in the last 5 minutes (an instant `last_over_time({job="<kit>"}[5m])` query, so series from pods that are gone are not included), and write `<kit>/metrics-catalog.json` in the current cluster working directory. The catalog SHALL be compact: one entry per distinct metric name, whose `labels` maps each label key to the sorted distinct values seen for it, capped at a small fixed number of values per key. Per-pod identity labels (`k8s_pod_uid`, `k8s_pod_name`, `instance`, `service_instance_id`) SHALL be dropped, because they carry no information for dashboards or `METRICS.md` and make the file grow with the number of pods. Label keys present on every series of the export (the collector's infrastructure labels such as `cluster`, `host_name`, `job`, `k8s_*`, `otel_scope_*`) SHALL be listed once, in a top-level `common_labels` map (key → capped sorted distinct values), and omitted from each entry, so an entry's `labels` holds only the labels specific to that metric.

#### Scenario: Export produces metrics-catalog.json for a running kit
- **WHEN** a scrape-type kit is running and `bin/export-workload-metrics <kit>` is executed from the cluster working directory
- **THEN** a file `<kit>/metrics-catalog.json` SHALL be written with keys `workload`, `exported_at` (ISO-8601 UTC), and `series` (array of objects with `name` and `labels` fields)
- **AND** the `series` array SHALL contain exactly one entry per distinct metric name, with `labels` mapping each label key to its distinct values

#### Scenario: Catalog size does not grow with the number of pods
- **WHEN** the same kit is exported with 1 pod and with 3 pods
- **THEN** both catalogs have the same metric names AND neither contains `k8s_pod_uid`, `k8s_pod_name`, `instance`, or `service_instance_id`

#### Scenario: Labels common to every series are listed once
- **WHEN** a label key appears on every series of the export
- **THEN** it appears once under the top-level `common_labels` AND not in any entry's `labels`

#### Scenario: Export script fails if the kit argument is missing
- **WHEN** `bin/export-workload-metrics` is called with no arguments
- **THEN** the script SHALL print usage and exit non-zero

#### Scenario: Export script fails if env.sh is not present
- **WHEN** `bin/export-workload-metrics <kit>` is called from a directory with no `env.sh`
- **THEN** the script SHALL print an error and exit non-zero

### Requirement: Grafana dashboard committed per kit, built from real catalog data
A scrape-type kit's resource directory SHALL contain `dashboards/<kit>.json` — a Grafana dashboard built from metric names in `metrics-catalog.json`. Dashboards SHALL include a `cluster` multi-select variable and SHALL scope all panel queries with `{cluster=~"$cluster",job="<kit>"}`. The dashboard is authored once and committed; it is not generated at runtime.

#### Scenario: A kit's dashboard is installed when the kit starts
- **WHEN** `easy-db-lab <kit> start` completes successfully
- **THEN** every dashboard under that kit's `dashboards/` directory SHALL be installed into Grafana
- **AND** all panels SHALL query the Mimir datasource
