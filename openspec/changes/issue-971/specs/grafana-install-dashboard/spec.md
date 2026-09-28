## MODIFIED Requirements

### Requirement: grafana install command uploads a dashboard JSON
The `grafana install <path>` command SHALL read a dashboard JSON file, apply the same install-time pass that `up` and kit `start` apply (datasource picker defaults, `cluster`, `baseline_cluster` and `candidate_cluster` defaults, `doc_tenant` options and default, and the documents host), using the cluster state of the workspace, and upload the result to the running Grafana instance via `POST /api/dashboards/db` with `overwrite: true`.

#### Scenario: Successful dashboard install
- **WHEN** `grafana install ./dashboards/opensearch/opensearch.json` is run against a cluster with Grafana running
- **THEN** the dashboard is visible in Grafana and an `Event.Grafana.DashboardInstalled` event is emitted with the dashboard title

#### Scenario: Install applies the install-time pass
- **WHEN** `grafana install` installs a dashboard with a `cluster` variable and pickers on cluster `lab-<id>`
- **THEN** the installed dashboard's `cluster` defaults to `lab-<id>` and its pickers show the cluster's own tenant
- **AND** the installed dashboard matches what `up` installs for the same file

#### Scenario: Re-running install is idempotent
- **WHEN** `grafana install <path>` is run twice with the same file
- **THEN** the second run succeeds and replaces the existing dashboard without error

#### Scenario: File not found
- **WHEN** `grafana install /nonexistent/path.json` is run
- **THEN** the command fails with a clear error message indicating the file does not exist

#### Scenario: Grafana returns an error
- **WHEN** the Grafana API returns a non-2xx response
- **THEN** the command fails with the HTTP status and response body in the error message
