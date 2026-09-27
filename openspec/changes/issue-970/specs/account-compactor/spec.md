## ADDED Requirements

### Requirement: One compactor service runs per account

The system SHALL run at most one compactor service per AWS account: the ECS Fargate service `easy-db-lab-compactor` in the ECS cluster `easy-db-lab`, in the region of the account bucket, in the account VPC `easy-db-lab-compactor`.  The service SHALL run 1 task, with `desiredCount` 1, `maximumPercent` 100 and `minimumHealthyPercent` 0, so ECS never runs a second task.  The task SHALL run Mimir's compactor (`-target=compactor`), Loki's compactor (`-target=compactor`), and Tempo's backend-scheduler and backend-worker, against the account bucket and every tenant in it.  Pyroscope's compaction SHALL stay in each cluster.  Mimir's compactor SHALL rewrite every tenant's bucket index every minute (`-compactor.cleanup-interval=1m`).  Loki's compactor SHALL compact every table, today's included.  The task SHALL send its logs to the CloudWatch Logs group `/easy-db-lab/compactor`, which SHALL have no retention policy.

#### Scenario: The service runs one task
- **WHEN** the compactor service is started
- **THEN** it runs exactly 1 task, and its deployment settings are `maximumPercent` 100 and `minimumHealthyPercent` 0

#### Scenario: The service follows the bucket's region
- **WHEN** the compactor service is started from a profile whose region differs from the account bucket's region
- **THEN** the service runs in the account bucket's region

#### Scenario: The task runs every compactor
- **WHEN** the compactor task definition is built
- **THEN** it holds a Mimir container with `-target=compactor` and `-compactor.cleanup-interval=1m`, a Loki container with `-target=compactor`, and a Tempo backend-scheduler and a Tempo backend-worker container
- **AND** no container runs Pyroscope

### Requirement: The compactor compacts and never deletes data by retention

The compactor SHALL compact, and SHALL run with retention and every other deletion path off.  It MAY remove source objects only after it has written a merged copy that holds their data.

- Mimir's compactor SHALL run with `-compactor.blocks-retention-period=0` and `-compactor.partial-block-deletion-delay=0`.
- Loki's compactor SHALL run with retention disabled and deletion mode `disabled`.
- Tempo's backend-worker SHALL run with `block_retention` of `876000h`, `max_bytes_per_trace` of 0, and `empty_tenant_deletion_enabled` false, and with no per-tenant retention override.

#### Scenario: Retention is off in every tool
- **WHEN** the compactor task definition and its configuration files are built
- **THEN** Mimir's blocks retention period and partial block deletion delay are 0, Loki's retention is disabled with deletion mode `disabled`, and Tempo's block retention is `876000h` with no per-tenant override
- **AND** Tempo's `max_bytes_per_trace` is 0 and `empty_tenant_deletion_enabled` is false

#### Scenario: Compaction keeps every sample, log line and trace
- **WHEN** the compactor has compacted a tenant's metrics, logs and traces
- **THEN** every sample, log line and trace written before is still returned by queries

### Requirement: Only the compactor's task role can delete observability objects

The compactor's task role `EasyDBLabCompactorTaskRole` SHALL be allowed to list the account bucket and to get, put and delete objects under `mimir/`, `loki/` and `tempo/`, and SHALL have no access to `grafana/` or `pyroscope/`.  The task SHALL use the execution role `EasyDBLabCompactorExecutionRole`.  The user IAM policies SHALL grant what `up`, `down` and the compactor commands need to manage the service, its roles, its log group and its network.

#### Scenario: The task role may delete under the compacted roots
- **WHEN** the compactor task deletes a source object under `mimir/`, `loki/` or `tempo/` after merging it
- **THEN** S3 accepts the delete

#### Scenario: The task role cannot touch other roots
- **WHEN** the compactor task tries to delete an object under `grafana/` or `pyroscope/`
- **THEN** S3 refuses the delete

### Requirement: The compactor is controlled by hand

The CLI SHALL provide `observability compactor start`, `observability compactor stop` and `observability compactor status`.  They SHALL work outside a cluster workspace.

- `start` SHALL start the service as `up` does.
- `stop` SHALL set the service's desired count to 0.
- `status` SHALL print whether the service runs, its desired and running task counts, the current or last task with its state, and the last lines of that task's logs.  It SHALL change nothing.

#### Scenario: Start by hand
- **WHEN** a user runs `observability compactor start` and the service is not running
- **THEN** the service runs 1 task

#### Scenario: Stop by hand
- **WHEN** a user runs `observability compactor stop`
- **THEN** the service's desired count is 0 and its task stops

#### Scenario: Status shows state, task and logs
- **WHEN** a user runs `observability compactor status`
- **THEN** the output shows whether the service runs, its task and that task's state, and its recent log lines
- **AND** nothing is started, stopped or changed

### Requirement: The compactor's network stays out of cluster teardown

The account VPC `easy-db-lab-compactor` SHALL carry the `easy_cass_lab=1` tag and SHALL NOT carry a `bucket` tag.  It SHALL be a public subnet with an internet gateway and a security group with no ingress.  `down --all` SHALL leave it in place, as it leaves the packer VPC.

#### Scenario: Teardown of all clusters keeps the compactor VPC
- **WHEN** a user tears down all tagged clusters
- **THEN** the `easy-db-lab-compactor` VPC and the compactor service are not deleted

#### Scenario: The compactor VPC is not counted as a cluster
- **WHEN** `down` counts the clusters that name the account bucket
- **THEN** the `easy-db-lab-compactor` VPC is not counted
