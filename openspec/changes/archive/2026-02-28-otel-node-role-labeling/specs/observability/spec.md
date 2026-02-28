## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure.

#### Scenario: EMR dashboard shows OTel host metrics

- **WHEN** the user views the EMR dashboard in Grafana
- **THEN** the dashboard displays CPU, memory, disk, and network metrics from OTel host metrics collected on Spark/EMR nodes

#### Scenario: EMR dashboard shows Spark JVM metrics

- **WHEN** the user views the EMR dashboard in Grafana
- **THEN** the dashboard displays JVM heap usage, GC activity, and thread metrics from the OTel Java agent on Spark driver/executors

#### Scenario: System Overview dashboard hostname filter includes all node types

- **WHEN** the user views the System Overview dashboard in Grafana
- **THEN** the hostname filter SHALL list hosts from all node types: db, app, control, and spark
- **AND** the service filter SHALL list all node_role values present in metrics
