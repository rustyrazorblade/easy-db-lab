## ADDED Requirements

### Requirement: Each environment declares its node capabilities

Each execution environment SHALL declare which node and account capabilities it provides, from a closed set: a systemd journal on the nodes, CloudWatch, and S3. The capabilities SHALL be decided once from cluster state. AWS SHALL provide all three, and the local Docker environment SHALL provide none. Until the environment is recorded in state, every cluster SHALL be treated as AWS.

#### Scenario: AWS provides every capability

- **GIVEN** a cluster on AWS
- **WHEN** its capabilities are decided
- **THEN** they include the systemd journal, CloudWatch and S3

#### Scenario: Local Docker provides none

- **GIVEN** the local Docker environment
- **WHEN** its capabilities are decided
- **THEN** they include none of the systemd journal, CloudWatch or S3

### Requirement: A workload whose capability is absent is skipped and reported

A workload that needs a capability SHALL be deployed only where the environment provides it. When skipped, the deploy SHALL emit one event per workload naming the workload, the missing capability and the reason, and SHALL NOT wait for that workload's rollout. Fluent Bit's journald reader needs the systemd journal; YACE needs CloudWatch; s3manager needs S3. No other workload SHALL be gated by a capability.

#### Scenario: Local deploy skips the three workloads

- **GIVEN** an environment with no capabilities
- **WHEN** the observability stack deploys
- **THEN** fluent-bit-journald, YACE and s3manager are not applied
- **AND** the output names each one, the missing capability and why it was skipped
- **AND** the rollout wait does not wait for any of them

#### Scenario: AWS deploy is unchanged

- **GIVEN** an AWS environment
- **WHEN** the observability stack deploys
- **THEN** the set of workloads applied equals today's set
- **AND** no skip event is emitted

#### Scenario: Pod logs reach Loki without a journal

- **GIVEN** a K3s node with no systemd journal
- **WHEN** a pod writes a line to stdout
- **THEN** that line reaches Loki through the collector's container-log pipeline

#### Scenario: s3manager is not advertised without S3

- **GIVEN** an environment without S3
- **WHEN** the user runs `status`
- **THEN** the output has no S3 Manager section
