## ADDED Requirements

### Requirement: Cluster comparison dashboard is registered as optional

The `GrafanaDashboard` enum SHALL include a `CLUSTER_COMPARISON` entry with `optional = true`, pointing to `cluster-comparison.json`.

#### Scenario: Cluster comparison dashboard appears in Grafana

- **WHEN** `grafana update-config` is run
- **THEN** a ConfigMap named `grafana-dashboard-cluster-comparison` SHALL be created
- **AND** the dashboard SHALL be mounted at `/var/lib/grafana/dashboards/cluster-comparison`
- **AND** the volume mount SHALL use `optional: true` so absence of the file does not block Grafana startup
