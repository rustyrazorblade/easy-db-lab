## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`.

All dashboards SHALL include a `cluster` multi-select variable and an ad hoc filters variable. All VictoriaMetrics-backed panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

#### Scenario: Dashboard JSON is not processed by TemplateService

- **WHEN** `GrafanaManifestBuilder` builds a dashboard ConfigMap
- **THEN** the dashboard JSON SHALL be loaded directly from the classpath without passing through `TemplateService.substitute()`
- **AND** all Grafana built-in variables (e.g., `$__rate_interval`, `$__interval`) SHALL be preserved verbatim in the deployed JSON

#### Scenario: Dashboard titles use descriptive names

- **WHEN** the user views the Grafana dashboard list
- **THEN** each dashboard title is a simple descriptive name (e.g., "System Overview", "EMR Overview", "Profiling") without any cluster name prefix

#### Scenario: Renderer container runs alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain a `grafana-image-renderer` container using the `grafana/grafana-image-renderer:latest` image
- **AND** the renderer SHALL listen on port 8081

#### Scenario: Grafana is configured to use the renderer

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the `GF_RENDERING_SERVER_URL` env var SHALL point to `http://localhost:8081/render`
- **AND** the `GF_RENDERING_CALLBACK_URL` env var SHALL point to `http://localhost:3000/`

#### Scenario: All dashboards have a cluster variable

- **WHEN** any dashboard is deployed via `grafana update-config`
- **THEN** the dashboard JSON SHALL contain a `cluster` template variable with `multi: true` and `includeAll: true`
- **AND** the variable SHALL query `label_values(up, cluster)` against the VictoriaMetrics datasource

#### Scenario: All metric panels are cluster-scoped

- **WHEN** a VictoriaMetrics-backed panel renders its query
- **THEN** the PromQL expression SHALL include a `cluster=~"$cluster"` label selector

#### Scenario: No native ClickHouse datasource is provisioned

- **WHEN** Grafana loads its datasource configuration
- **THEN** no datasource of type `grafana-clickhouse-datasource` SHALL be present
