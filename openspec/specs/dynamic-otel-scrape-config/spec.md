# Dynamic OTel Scrape Config

## Purpose

The OTel Collector ConfigMap is generated dynamically by `OtelManifestBuilder`, combining a fixed set of static scrape jobs for host processes with a dynamic set of per-workload scrape jobs read from the K8s metrics registry (`easydblab-metrics-*` ConfigMaps). This allows workloads to register and deregister their metrics endpoints at runtime without manual config changes.

## Requirements

### Requirement: OtelManifestBuilder generates scrape jobs from workload metrics registry
`OtelManifestBuilder` SHALL accept a list of workload scrape configs and include one `prometheus` scrape job per entry in the generated OTel collector ConfigMap, alongside the existing static jobs.

#### Scenario: Scrape jobs generated for registered workloads
- **WHEN** `OtelManifestBuilder.buildConfigMap(scrapeConfigs)` is called with one entry `{jobName: "scylladb", port: 9180, path: "/metrics"}`
- **THEN** the resulting ConfigMap data SHALL contain a prometheus scrape job named `scylladb` targeting `localhost:9180` with path `/metrics`
- **AND** all existing static scrape jobs (beyla, ebpf-exporter, yace) SHALL also be present

#### Scenario: Empty scrape configs produces only static jobs
- **WHEN** `OtelManifestBuilder.buildConfigMap(emptyList())` is called
- **THEN** the ConfigMap data SHALL contain only the static scrape jobs
- **AND** no error or warning is emitted

#### Scenario: Multiple workloads each get their own scrape job
- **WHEN** two workloads are registered (`clickhouse` on port 9363, `scylladb` on port 9180)
- **THEN** the ConfigMap SHALL contain two dynamic scrape jobs in addition to static jobs

### Requirement: Install command reads metrics registry before calling OtelManifestBuilder
The install command SHALL list all ConfigMaps with label `easydblab.com/workload-metrics=true` in the `default` namespace via the Fabric8 K8s client, extract their `job-name`, `port`, and `path` data fields, and pass the resulting list to `OtelManifestBuilder`.

#### Scenario: Registry ConfigMaps discovered by label selector
- **WHEN** two ConfigMaps with label `easydblab.com/workload-metrics=true` exist
- **THEN** both are included in the scrape config list passed to `OtelManifestBuilder`

#### Scenario: ConfigMaps without the workload-metrics label are ignored
- **WHEN** a ConfigMap named `easydblab-metrics-foo` exists without the label
- **THEN** it is NOT included in the scrape config list

### Requirement: Static scrape jobs for host processes remain in the base OTel config
The base `otel-collector-config.yaml` classpath resource SHALL continue to define static scrape jobs for host processes: `beyla` (`:9400`), `ebpf-exporter` (`:9435`), and `yace` (`:5001`). The existing bespoke `clickhouse` static scrape job SHALL be removed — ClickHouse metrics are registered dynamically when `install clickhouse start` runs.

Cassandra is NOT among them. It formerly had a `cassandra-maac` scrape job on `:9000`, serving the k8ssandra management-api agent. That agent emitted degenerate histograms and was replaced by the OpenTelemetry Java agent, which PUSHES over OTLP to `localhost:4318` rather than exposing a Prometheus endpoint to be pulled. Nothing listens on `:9000`, and the collector receives Cassandra metrics through the `otlp` receiver on the `metrics/otlp` pipeline instead of the `prometheus` receiver.

#### Scenario: No Cassandra scrape job exists in the base config
- **WHEN** `OtelManifestBuilder` builds the ConfigMap
- **THEN** the resulting config SHALL NOT contain a `cassandra-maac` scrape job
- **AND** the resulting config SHALL NOT reference port `9000`

#### Scenario: Cassandra metrics arrive over OTLP rather than by scrape
- **WHEN** a Cassandra node is running with the OpenTelemetry Java agent attached
- **THEN** its metrics SHALL reach the collector via the `otlp` receiver
- **AND** they SHALL carry `job="cassandra"`, derived from the agent's `service.name`

#### Scenario: ClickHouse static scrape job removed from base config
- **WHEN** `OtelManifestBuilder` builds the ConfigMap with an empty scrape config list
- **THEN** the resulting config SHALL NOT contain a `clickhouse` scrape job

#### Scenario: ClickHouse metrics scraped when workload is running
- **WHEN** `install clickhouse start` completes and the `easydblab-metrics-clickhouse` ConfigMap exists
- **THEN** the OTel collector ConfigMap SHALL contain a `clickhouse` scrape job targeting `localhost:9363`
