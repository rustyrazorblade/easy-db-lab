## MODIFIED Requirements

### Requirement: Static scrape jobs for host processes remain in the base OTel config
The base `otel-collector-config.yaml` classpath resource SHALL continue to define static scrape jobs for host processes: `beyla` (`:9400`), `ebpf-exporter` (`:9435`), and `yace` (`:5001`). The existing bespoke `clickhouse` static scrape job SHALL be removed — ClickHouse metrics are registered dynamically when `install clickhouse start` runs. Built-in kits SHALL register pod-discovery scrape jobs (a `pod-selector` on the pod serving the metrics port), not static `localhost:<port>` targets, so only the collector on the pod's node scrapes it.

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
- **THEN** the OTel collector ConfigMap SHALL contain a `clickhouse` scrape job that discovers pods selected by `clickhouse.altinity.com/chi=clickhouse` on container port `9363`

#### Scenario: No built-in kit declares a static scrape target
- **WHEN** any built-in kit's `kit.yaml` declares a `scrape` metrics entry
- **THEN** it carries a `pod-selector` AND `up` for that job has exactly one series per scraped pod
