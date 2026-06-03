# Kit Metrics Declaration

## Overview

`config.yaml` supports a top-level `metrics` block that declares how a kit's metrics reach the OTel collector. After a kit starts or stops, a K8s ConfigMap registry entry is created or deleted, and the OTel collector ConfigMap is regenerated automatically to include or exclude the kit's scrape job.

## Requirements

### Requirement: config.yaml supports a top-level `metrics` block
`config.yaml` SHALL support an optional top-level `metrics` key that declares how the kit's metrics reach the OTel collector. Three modes are supported: `scrape`, `java-agent`, and `helm-native`.

#### Scenario: Scrape mode declares a Prometheus endpoint
- **WHEN** `config.yaml` declares `metrics: {type: scrape, port: 9180, path: /metrics}`
- **THEN** the install command parses this into a `WorkloadMetrics.Scrape` value object with port 9180 and path `/metrics`
- **AND** path defaults to `/metrics` if omitted

#### Scenario: Java-agent mode declares JVM instrumentation
- **WHEN** `config.yaml` declares `metrics: {type: java-agent, service-name: trino}`
- **THEN** the install command parses this into a `WorkloadMetrics.JavaAgent` value object with service name `trino`

#### Scenario: Helm-native mode is a no-op declaration
- **WHEN** `config.yaml` declares `metrics: {type: helm-native}`
- **THEN** the install command parses this as `WorkloadMetrics.HelmNative`
- **AND** no metrics registry ConfigMap is written and no OTel sync is triggered for this kit

#### Scenario: Absent metrics block requires no action
- **WHEN** `config.yaml` has no `metrics` key
- **THEN** the install command skips all metrics registration steps after `start` and `stop`

### Requirement: Metrics registry ConfigMap written after `start` completes
After all steps in the `start` phase complete successfully, the install command SHALL write a ConfigMap named `easydblab-metrics-<kit>` in the `default` namespace with label `easydblab.com/kit-metrics=true` and data keys `job-name`, `port`, and `path`. This only applies to kits with `metrics.type: scrape`.

#### Scenario: Registry ConfigMap created after successful start
- **WHEN** a kit with `metrics: {type: scrape, port: 9180}` completes its `start` phase
- **THEN** a ConfigMap `easydblab-metrics-<kit>` SHALL exist in the `default` namespace
- **AND** it SHALL have label `easydblab.com/kit-metrics: "true"`
- **AND** its data SHALL contain `port: "9180"`, `path: "/metrics"`, `job-name: "<kit>"`

#### Scenario: Registry ConfigMap not created for helm-native kits
- **WHEN** a kit with `metrics: {type: helm-native}` completes its `start` phase
- **THEN** no ConfigMap named `easydblab-metrics-<kit>` SHALL be created

#### Scenario: Registry ConfigMap not created after failed start
- **WHEN** any step in the `start` phase fails
- **THEN** the metrics registry ConfigMap SHALL NOT be written
- **AND** OTel sync SHALL NOT be triggered

### Requirement: Metrics registry ConfigMap deleted after `stop` completes
After all steps in the `stop` phase complete, the install command SHALL delete the ConfigMap `easydblab-metrics-<kit>` if it exists, then trigger OTel sync.

#### Scenario: Registry ConfigMap deleted after stop
- **WHEN** a kit's `stop` phase completes
- **THEN** the ConfigMap `easydblab-metrics-<kit>` SHALL no longer exist in the default namespace

#### Scenario: Missing registry ConfigMap on stop is not an error
- **WHEN** `stop` is run for a kit that has no `easydblab-metrics-<kit>` ConfigMap
- **THEN** the delete is a no-op and the command succeeds

### Requirement: OTel collector ConfigMap regenerated automatically after `start` and `stop`
After metrics registry write (on `start`) or delete (on `stop`), the install command SHALL call `OtelManifestBuilder` to regenerate and apply the OTel collector ConfigMap. No explicit step in `config.yaml` is required.

#### Scenario: OTel sync triggered after successful start
- **WHEN** a kit with a `metrics` block completes its `start` phase
- **THEN** the OTel collector ConfigMap SHALL be updated to include a scrape job for the kit

#### Scenario: OTel sync triggered after stop
- **WHEN** a kit's `stop` phase completes
- **THEN** the OTel collector ConfigMap SHALL be updated to exclude the kit's scrape job

#### Scenario: OTel sync not triggered for helm-native kits
- **WHEN** a kit with `metrics: {type: helm-native}` starts or stops
- **THEN** the OTel collector ConfigMap SHALL NOT be modified by the install command
