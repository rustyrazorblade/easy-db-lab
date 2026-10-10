## MODIFIED Requirements

### Requirement: One ConfigMap written per scrape target after `start` completes

After all steps in the `start` phase complete successfully, the install command SHALL write one
ConfigMap per `KitMetrics.Scrape` entry. Each ConfigMap SHALL be named
`easydblab-metrics-<kitName>-<job>` (with `<job>` defaulting to the kit name), carry label
`easydblab.com/workload-metrics: "true"`, carry label `easydblab.com/kit: <kitName>`, and contain
data keys `job-name`, `port`, and `path`. If registering any ConfigMap fails, `start` SHALL emit a
typed event that names the kit and the failure, and SHALL exit non-zero; it SHALL NOT only log a
warning. This applies to every kit.

#### Scenario: Single-target kit creates one ConfigMap

- **WHEN** a kit with one `KitMetrics.Scrape` entry (job omitted) completes its `start` phase
- **THEN** exactly one ConfigMap `easydblab-metrics-<kitName>-<kitName>` SHALL exist with the correct port and path
- **AND** it SHALL carry label `easydblab.com/kit: <kitName>`

#### Scenario: Multi-target kit creates one ConfigMap per target

- **WHEN** a kit with four `KitMetrics.Scrape` entries (distinct `job` fields) completes its `start` phase
- **THEN** four ConfigMaps SHALL exist, one per job, each named `easydblab-metrics-<kitName>-<job>`
- **AND** each SHALL carry label `easydblab.com/kit: <kitName>`

#### Scenario: Registry ConfigMaps not created after failed start

- **WHEN** any step in the `start` phase fails
- **THEN** no `easydblab-metrics-*` ConfigMaps SHALL be written for this kit

#### Scenario: Failed metrics registration fails start

- **WHEN** every `start` step succeeds but writing a metrics ConfigMap fails
- **THEN** `start` SHALL emit a typed metrics-registration-failed event that names the kit and the failure
- **AND** `start` SHALL exit non-zero
