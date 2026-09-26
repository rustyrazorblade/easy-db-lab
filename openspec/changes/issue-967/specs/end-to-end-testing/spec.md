## MODIFIED Requirements

### Requirement: Observability stack validation

The test runner SHALL validate the observability stack including metrics, logs, traces, and dashboards.

#### Scenario: Observability health checks

- **WHEN** the observability test step runs
- **THEN** Mimir and Loki health endpoints are verified, Grafana datasources are validated, and metric ingestion is confirmed

#### Scenario: Dashboard validation

- **WHEN** the dashboard test step runs
- **THEN** all Grafana dashboards load successfully

#### Scenario: Teardown flush

- **WHEN** the teardown test step runs `down`
- **THEN** the pre-teardown flush completes, and Mimir blocks and Loki index files for the cluster exist in S3

#### Scenario: Logs query

- **WHEN** the logs query step runs
- **THEN** the `logs query` command returns results from Loki
