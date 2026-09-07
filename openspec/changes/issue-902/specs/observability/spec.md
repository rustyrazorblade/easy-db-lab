# Observability Spec

## ADDED Requirements

### Requirement: The cluster's storage tier retains observability data indefinitely

The cluster's VictoriaMetrics and VictoriaLogs SHALL be deployed with `-retentionPeriod=100y`, and
VictoriaLogs SHALL additionally carry `-futureRetention=100y`. `VictoriaManifestBuilder`'s
`RETENTION_PERIOD` SHALL name no bounded period.

The unit SHALL always be written. A bare number means *months* in both products.

A cluster that outlives a bounded window drops its own oldest data before anything backs it up, and
VictoriaMetrics enforces retention as a sliding window against the current clock rather than against
ingest time. Retention for observability data is unbounded on a cluster exactly as it is locally.

#### Scenario: Deployed stores carry the unbounded value

- **WHEN** a cluster's VictoriaMetrics and VictoriaLogs are deployed
- **THEN** each is started with `-retentionPeriod=100y`
- **AND** VictoriaLogs additionally carries `-futureRetention=100y`

#### Scenario: No bounded period remains in the builder

- **WHEN** `VictoriaManifestBuilder`'s `RETENTION_PERIOD` is inspected
- **THEN** it names no bounded period

#### Scenario: A long-running cluster keeps its oldest data

- **GIVEN** a cluster that has been running longer than the former seven-day window
- **WHEN** its oldest metrics and logs are queried
- **THEN** they are still present, so a backup taken later still contains them

### Requirement: Every OTel pipeline stamps the cluster attribute

Every pipeline in the OTel collector's configuration SHALL run the `resource/cluster` processor, so
that no telemetry stream reaches the storage tier without a cluster attribute.

Many clusters' telemetry lands in one store. A record that arrives with no cluster attribute cannot
be told apart from another cluster's afterwards, and no dashboard filter can separate it — the label
it would filter on was never written. Scoping a dashboard whose producer does not stamp the
attribute blanks its panels rather than separating clusters.

This SHALL be enforced as an invariant over all pipelines rather than as a fix to a named list, so a
newly added pipeline cannot reintroduce the gap.

#### Scenario: Every pipeline carries the processor

- **WHEN** the OTel collector configuration is built
- **THEN** every pipeline's `processors` list contains `resource/cluster`

#### Scenario: Span metrics and service graph metrics carry the cluster

- **GIVEN** two clusters exporting to one store
- **WHEN** `traces_spanmetrics_*` or `traces_service_graph_*` series are queried with a cluster
  filter
- **THEN** only the named cluster's series are returned

#### Scenario: System, tool, Cassandra and OTLP log records carry the cluster

- **GIVEN** records emitted through the `logs/local` and `logs/otlp` pipelines
- **WHEN** they reach the storage tier
- **THEN** each carries the cluster attribute, so a log dashboard can filter on it

#### Scenario: A newly added pipeline cannot omit it

- **WHEN** a pipeline is added to the collector configuration without `resource/cluster`
- **THEN** the enforcement test fails, naming that pipeline

## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`.

All dashboards SHALL include a `cluster` multi-select variable and an ad hoc filters variable. All VictoriaMetrics-backed panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

The two stale top-level ClickHouse dashboards SHALL NOT be installed. `dashboards/clickhouse.json`
and `dashboards/clickhouse-logs.json` are removed along with the `CLICKHOUSE` and `CLICKHOUSE_LOGS`
entries in the `GrafanaDashboard` enum, so `grafana update-config` installs no ClickHouse dashboard.
The ClickHouse kit's own two dashboards, installed by the kit runner, are the only ClickHouse
dashboards present. The kit copies are authoritative: `clickhouse.json` was byte-identical to the
kit copy, and `clickhouse-logs.json` had already diverged — the kit copy carries three panel links
and one differing `expr` the top-level copy lacks.

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

#### Scenario: Cluster comparison dashboard appears in Grafana

- **WHEN** `grafana update-config` is run
- **THEN** a ConfigMap named `grafana-dashboard-cluster-comparison` SHALL be created
- **AND** the dashboard SHALL be mounted at `/var/lib/grafana/dashboards/cluster-comparison`
- **AND** the volume mount SHALL use `optional: true` so absence of the file does not block Grafana startup

#### Scenario: The stale top-level ClickHouse dashboards are gone

- **WHEN** the repository's top-level `dashboards/` directory and the `GrafanaDashboard` enum are inspected
- **THEN** `dashboards/clickhouse.json` and `dashboards/clickhouse-logs.json` are absent
- **AND** the enum has no `CLICKHOUSE` or `CLICKHOUSE_LOGS` entry

#### Scenario: `grafana update-config` installs no ClickHouse dashboard

- **WHEN** `grafana update-config` runs against a live cluster
- **THEN** it succeeds, because no manifest references a deleted resource
- **AND** it installs no ClickHouse dashboard

#### Scenario: The kit's copies are the only ones

- **WHEN** the ClickHouse kit is started
- **THEN** its own two dashboards install as before
- **AND** they are the only ClickHouse dashboards present in Grafana
