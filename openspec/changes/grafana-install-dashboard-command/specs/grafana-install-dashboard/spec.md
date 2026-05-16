## ADDED Requirements

### Requirement: Grafana data persists across pod restarts
Grafana's data volume SHALL use a `hostPath` mount at `/mnt/db1/grafana` on the control node instead of `emptyDir`. The directory SHALL be created by `grafana update-config` before the Deployment is applied.

#### Scenario: Grafana pod restarts after dashboards are installed
- **WHEN** the Grafana pod is restarted (e.g., after `grafana update-config`)
- **THEN** previously installed dashboards remain visible in Grafana

#### Scenario: Directory is created before Deployment is applied
- **WHEN** `grafana update-config` runs
- **THEN** `/mnt/db1/grafana` is created on the control node via SSH before the Grafana Deployment is applied

### Requirement: grafana install command uploads a dashboard JSON
The `grafana install <path>` command SHALL read a dashboard JSON file and upload it to the running Grafana instance via `POST /api/dashboards/db` with `overwrite: true`.

#### Scenario: Successful dashboard install
- **WHEN** `grafana install ./dashboards/clickhouse.json` is run against a cluster with Grafana running
- **THEN** the dashboard is visible in Grafana and an `Event.Grafana.DashboardInstalled` event is emitted with the dashboard title

#### Scenario: Re-running install is idempotent
- **WHEN** `grafana install <path>` is run twice with the same file
- **THEN** the second run succeeds and replaces the existing dashboard without error

#### Scenario: File not found
- **WHEN** `grafana install /nonexistent/path.json` is run
- **THEN** the command fails with a clear error message indicating the file does not exist

#### Scenario: Grafana returns an error
- **WHEN** the Grafana API returns a non-2xx response
- **THEN** the command fails with the HTTP status and response body in the error message
