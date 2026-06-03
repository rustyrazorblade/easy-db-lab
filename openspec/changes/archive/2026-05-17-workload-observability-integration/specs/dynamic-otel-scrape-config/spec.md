## ADDED Requirements

### Requirement: OtelManifestBuilder generates scrape jobs from workload metrics registry
`OtelManifestBuilder` SHALL accept a list of workload scrape configs and include one `prometheus` scrape job per entry in the generated OTel collector ConfigMap, alongside the existing static jobs.

#### Scenario: Scrape jobs generated for registered workloads
- **WHEN** `OtelManifestBuilder.buildConfigMap(scrapeConfigs)` is called with one entry `{jobName: "scylladb", port: 9180, path: "/metrics"}`
- **THEN** the resulting ConfigMap data SHALL contain a prometheus scrape job named `scylladb` targeting `localhost:9180` with path `/metrics`
- **AND** all existing static scrape jobs (cassandra-maac, clickhouse, beyla, ebpf-exporter, yace) SHALL also be present

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
The base `otel-collector-config.yaml` classpath resource SHALL continue to define static scrape jobs for host processes: `cassandra-maac` (`:9000`), `beyla` (`:9400`), `ebpf-exporter` (`:9435`), and `yace` (`:5001`). The existing bespoke `clickhouse` static scrape job SHALL be removed — ClickHouse metrics are registered dynamically when `install clickhouse start` runs.

#### Scenario: ClickHouse static scrape job removed from base config
- **WHEN** `OtelManifestBuilder` builds the ConfigMap with an empty scrape config list
- **THEN** the resulting config SHALL NOT contain a `clickhouse` scrape job

#### Scenario: ClickHouse metrics scraped when workload is running
- **WHEN** `install clickhouse start` completes and the `easydblab-metrics-clickhouse` ConfigMap exists
- **THEN** the OTel collector ConfigMap SHALL contain a `clickhouse` scrape job targeting `localhost:9363`
