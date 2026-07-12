# Workload Install Config

## Purpose

Defines the optional `endpoints:` and `runtime:` sections of a workload's `install.yaml`, declaring connection metadata for the workload's services and K8s resource metadata for running-state detection.

## Requirements

### Requirement: install.yaml supports endpoints section
`install.yaml` SHALL support an optional `endpoints:` list declaring connection metadata for the workload's services.

Each endpoint SHALL have:
- `name` (string) — human-readable label
- `node-type` (string) — `app`, `db`, or `control`; determines which node IPs to resolve
- `port` (int) — port number
- `type` (string) — one of `http`, `https`, `jdbc`, `native`, `cql`
- `scheme` (string, optional) — JDBC scheme (e.g. `presto`, `clickhouse`); required when `type: jdbc`
- `path` (string, optional) — URL path suffix

#### Scenario: Valid endpoint declaration parsed
- **WHEN** `install.yaml` contains an `endpoints:` list with valid fields
- **THEN** `WorkloadInstallConfig` deserializes it without error

#### Scenario: Missing optional fields use defaults
- **WHEN** `path` and `scheme` are omitted
- **THEN** URL is constructed without path; `jdbc` type without scheme is an error

### Requirement: install.yaml supports runtime section
`install.yaml` SHALL support an optional `runtime:` block providing K8s resource metadata for running-state detection.

The `runtime:` block SHALL have:
- `type` (string) — one of `helm`, `deployment`, `statefulset`, `pods`
- `release` (string, optional) — helm release name; required when `type: helm`
- `selector` (string, optional) — label selector; used when `type: pods`
- `name` (string, optional) — resource name; used when `type: deployment` or `statefulset`
- `namespace` (string, optional) — K8s namespace; defaults to `default`

#### Scenario: Helm runtime block parsed
- **WHEN** `install.yaml` contains `runtime: {type: helm, release: presto}`
- **THEN** `WorkloadInstallConfig.runtime` is deserialized with type `helm` and release `presto`

#### Scenario: Absent runtime block
- **WHEN** `install.yaml` has no `runtime:` section
- **THEN** `WorkloadInstallConfig.runtime` is null and running state is inferred from phases
