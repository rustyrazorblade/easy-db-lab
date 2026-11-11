## MODIFIED Requirements

### Requirement: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Data bucket cleanup MUST use lifecycle expiration rather than individual object deletion.

Before any infrastructure is torn down, the system MUST run an automatic backup that captures VictoriaMetrics and the Grafana annotations as one coupled operation. The two backups MUST always be attempted together — never one without the other. IF the backup fails, the system MUST retry it; IF it still fails, teardown MUST abort with no infrastructure removed, and the failure MUST be reported. The `--force` flag MUST skip the automatic backup and proceed with teardown regardless.

#### Scenario: Teardown removes AWS resources

- **GIVEN** a running cluster
- **WHEN** the user tears it down with confirmation
- **THEN** all AWS resources (EC2, NAT gateways, security groups, route tables, subnets, internet gateways, VPC) are terminated.

#### Scenario: Data bucket expires via lifecycle rule

- **GIVEN** a running cluster with a data bucket
- **WHEN** the user tears it down
- **THEN** a lifecycle expiration rule is set on the data bucket to expire all objects after the retention period.

#### Scenario: Teardown of all clusters

- **GIVEN** multiple clusters
- **WHEN** the user tears down all clusters
- **THEN** every tagged VPC and its resources are removed, and all per-cluster data buckets are deleted.

#### Scenario: Teardown requires confirmation

- **GIVEN** a teardown request without confirmation
- **WHEN** the command runs
- **THEN** the user is prompted for approval before proceeding.

#### Scenario: Automatic backup runs before any infrastructure is torn down

- **GIVEN** a running cluster with an S3 bucket configured
- **WHEN** the user tears it down
- **THEN** the metrics and annotations backup runs before any infrastructure is torn down
- **AND** the metrics backup and the annotations backup are attempted together, never one without the other

#### Scenario: Backup failure aborts teardown with no infrastructure removed

- **GIVEN** a teardown in progress whose automatic backup fails after retry
- **WHEN** the command runs without `--force`
- **THEN** teardown aborts and no infrastructure is torn down
- **AND** the failure is reported to the operator

#### Scenario: --force skips the backup and tears down anyway

- **GIVEN** a teardown request with `--force`
- **WHEN** the command runs
- **THEN** the automatic backup is skipped
- **AND** teardown proceeds regardless
