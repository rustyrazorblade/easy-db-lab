## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering.

#### Scenario: Dashboard titles use descriptive names

- **WHEN** the user views the Grafana dashboard list
- **THEN** each dashboard title is a simple descriptive name (e.g., "System Overview", "EMR Overview", "Profiling") without any cluster name prefix

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

#### Scenario: Grafana pod includes image renderer sidecar

- **WHEN** the Grafana deployment is applied
- **THEN** the pod SHALL contain both the Grafana container and a `grafana-image-renderer` sidecar container
