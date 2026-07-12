# Kit Metrics Declaration

## Purpose

`config.yaml` supports a top-level `metrics` block that declares how a kit's metrics reach the OTel collector. After a kit starts or stops, a K8s ConfigMap registry entry is created or deleted, and the OTel collector ConfigMap is regenerated automatically to include or exclude the kit's scrape job.

## Requirements

### Requirement: kit.yaml supports a list of metrics declarations

`kit.yaml` SHALL support a `metrics` key that accepts a list of zero or more metric declarations.
Each list entry follows the same type structure as before (`type: scrape`, `type: java-agent`,
`type: helm-native`). An empty list or absent `metrics` key means no metrics registration.
`KitMetrics.Scrape` SHALL accept an optional `job` field that names the Prometheus scrape job
and the ConfigMap; it defaults to the kit name when omitted.

#### Scenario: Single scrape target declared as a list

- **WHEN** `kit.yaml` declares `metrics: [{type: scrape, port: 9180}]`
- **THEN** the kit config is parsed with one `KitMetrics.Scrape` entry with port 9180, path `/metrics`, and job defaulting to the kit name

#### Scenario: Multiple scrape targets declared

- **WHEN** `kit.yaml` declares multiple `metrics` entries each with a distinct `job` field
- **THEN** the kit config is parsed with one `KitMetrics.Scrape` per entry, each with its own port, path, and job name

#### Scenario: Absent metrics key requires no action

- **WHEN** `kit.yaml` has no `metrics` key
- **THEN** the kit config parses with an empty metrics list and no metrics registration occurs

### Requirement: One ConfigMap written per scrape target after `start` completes

After all steps in the `start` phase complete successfully, the install command SHALL write one
ConfigMap per `KitMetrics.Scrape` entry. Each ConfigMap SHALL be named
`easydblab-metrics-<job>`, carry label `easydblab.com/workload-metrics: "true"`, carry label
`easydblab.com/kit: <kitName>`, and contain data keys `job-name`, `port`, and `path`.

#### Scenario: Single-target kit creates one ConfigMap

- **WHEN** a kit with one `KitMetrics.Scrape` entry (job omitted) completes its `start` phase
- **THEN** exactly one ConfigMap `easydblab-metrics-<kitName>` SHALL exist with the correct port and path
- **AND** it SHALL carry label `easydblab.com/kit: <kitName>`

#### Scenario: Multi-target kit creates one ConfigMap per target

- **WHEN** a kit with four `KitMetrics.Scrape` entries (distinct `job` fields) completes its `start` phase
- **THEN** four ConfigMaps SHALL exist, one per job, each named `easydblab-metrics-<job>`
- **AND** each SHALL carry label `easydblab.com/kit: <kitName>`

#### Scenario: Registry ConfigMaps not created after failed start

- **WHEN** any step in the `start` phase fails
- **THEN** no `easydblab-metrics-*` ConfigMaps SHALL be written for this kit

### Requirement: All ConfigMaps for a kit deleted after `stop` completes

After all steps in the `stop` phase complete, the install command SHALL delete all ConfigMaps
carrying label `easydblab.com/kit: <kitName>` and `easydblab.com/workload-metrics: "true"`,
then trigger OTel sync.

#### Scenario: All targets deregistered after stop

- **WHEN** a kit with four scrape targets completes its `stop` phase
- **THEN** all four `easydblab-metrics-*` ConfigMaps for that kit SHALL be deleted
- **AND** the OTel collector ConfigMap SHALL be regenerated without any scrape jobs for that kit

#### Scenario: Missing ConfigMaps on stop is not an error

- **WHEN** `stop` is run for a kit that has no `easydblab-metrics-*` ConfigMaps
- **THEN** the delete is a no-op and the command succeeds
