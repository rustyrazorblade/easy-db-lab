## MODIFIED Requirements

### Requirement: kit.yaml declares kit version and dashboard files
`kit.yaml` SHALL support:
- A top-level `version` field (string) identifying the kit version being installed
- A top-level `dashboards` list of objects with `path` and optional `name` fields

Dashboards SHALL be installed into Grafana automatically after the `start` phase completes
successfully, replacing the hardcoded directory scan in `KitRunnerCommand`.

If a kit dashboard cannot be installed — the dashboard files or the tenant listing cannot be read
or rendered, a declared dashboard file is missing, or Grafana rejects the install — `start` SHALL
emit a typed event that names the kit, the dashboard and the reason, and SHALL exit non-zero. It
SHALL NOT only log a warning. This applies to every kit.

#### Scenario: Dashboards installed after successful start
- **WHEN** `kit.yaml` declares a `dashboards` list and `easy-db-lab <kit> start` exits
  successfully
- **THEN** each listed dashboard JSON file is installed into Grafana via `GrafanaDashboardService`

#### Scenario: Dashboards skipped after failed start
- **WHEN** any step in the `start` phase fails
- **THEN** dashboard installation is skipped and the failure is reported

#### Scenario: Grafana rejects a dashboard
- **WHEN** every `start` step succeeds but Grafana rejects a kit dashboard install
- **THEN** `start` emits a typed dashboard-install-failed event that names the kit and the dashboard
- **AND** `start` exits non-zero

#### Scenario: Dashboards cannot be read
- **WHEN** every `start` step succeeds but the kit's dashboard files or the tenant listing cannot be read or rendered
- **THEN** `start` emits a typed event that names the kit, its dashboards and the reason
- **AND** `start` exits non-zero
