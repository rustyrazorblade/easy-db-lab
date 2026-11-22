## MODIFIED Requirements

### Requirement: install.yaml supports runtime section
`install.yaml` SHALL support an optional `runtime:` block providing K8s resource metadata for running-state detection.

The `runtime:` block SHALL have:
- `type` (string) — one of `helm`, `deployment`, `statefulset`, `pods`
- `release` (string, optional) — helm release name; required when `type: helm`
- `selector` (string, optional) — label selector; used when `type: pods`
- `name` (string, optional) — resource name; used when `type: deployment` or `statefulset`
- `namespace` (string, optional) — K8s namespace; when absent, the cluster namespace

#### Scenario: Helm runtime block parsed
- **WHEN** `install.yaml` contains `runtime: {type: helm, release: presto}`
- **THEN** `WorkloadInstallConfig.runtime` is deserialized with type `helm` and release `presto`

#### Scenario: Absent runtime block
- **WHEN** `install.yaml` has no `runtime:` section
- **THEN** `WorkloadInstallConfig.runtime` is null and running state is inferred from phases

#### Scenario: Runtime namespace defaults to the cluster namespace
- **WHEN** a kit's `runtime:` block names no `namespace` and the cluster namespace is `lab-a`
- **THEN** running-state detection looks for the workload in `lab-a`
