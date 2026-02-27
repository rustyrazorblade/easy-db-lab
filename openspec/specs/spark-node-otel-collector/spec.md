## ADDED Requirements

### Requirement: OTel Collector runs as systemd service on EMR nodes

The system SHALL install the OpenTelemetry Collector binary on every EMR node via the bootstrap action and run it as a systemd service for the lifetime of the cluster.

#### Scenario: Collector installed during EMR bootstrap

- **WHEN** the EMR cluster starts and bootstrap actions execute
- **THEN** the OTel Collector binary is downloaded and installed at `/opt/otel/otelcol-contrib`

#### Scenario: Collector starts as systemd service

- **WHEN** the OTel Collector binary is installed
- **THEN** a systemd unit (`otel-collector.service`) is created and enabled, and the service starts automatically

#### Scenario: Collector survives process restarts

- **WHEN** the OTel Collector process crashes
- **THEN** systemd restarts it automatically (Restart=always)

### Requirement: OTel Collector receives OTLP from local Spark JVMs

The system SHALL configure the local OTel Collector to accept OTLP on localhost:4317 (gRPC) and localhost:4318 (HTTP).

#### Scenario: Spark Java agent sends to local collector

- **WHEN** a Spark driver or executor JVM starts with the OTel Java agent (no OTEL_EXPORTER_OTLP_ENDPOINT override)
- **THEN** the Java agent sends metrics, logs, and traces to localhost:4317 where the local collector receives them

### Requirement: OTel Collector collects host metrics from EMR nodes

The system SHALL configure the local OTel Collector to collect host-level metrics from each EMR node.

#### Scenario: Host metrics collected

- **WHEN** the OTel Collector is running on an EMR node
- **THEN** it collects CPU, memory, disk, filesystem, network, load, and process metrics at regular intervals

### Requirement: OTel Collector forwards all telemetry to control node

The system SHALL configure the local OTel Collector to export all received and collected telemetry to the control node's OTel Collector via OTLP gRPC.

#### Scenario: Telemetry forwarded to control node

- **WHEN** the local collector receives OTLP data or collects host metrics
- **THEN** it forwards all data to the control node's OTel Collector at `<control_ip>:4317`

#### Scenario: Control node IP configured at bootstrap time

- **WHEN** the bootstrap script runs
- **THEN** the control node's private IP is available (passed via bootstrap action argument) and written into the collector config
