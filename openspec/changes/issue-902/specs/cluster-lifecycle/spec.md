# Cluster Lifecycle Spec

## MODIFIED Requirements

### Requirement: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Data bucket cleanup MUST use lifecycle expiration rather than individual object deletion.

Teardown MUST NOT apply an S3 lifecycle expiration to any prefix that holds metrics, logs, traces or
profile data. In particular, no lifecycle rule is applied to the account bucket's
`clusters/<name>-<clusterId>` prefix, which holds every metrics and logs backup, nor to any sibling
prefix holding profile data. S3 measures `Expiration.Days` from object *creation*, so applying such
a rule at teardown expires anything already older than the window at the next evaluation.

The per-cluster **data** bucket keeps its existing whole-bucket expiry — it is ephemeral by design —
but no observability data may live there.

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

#### Scenario: No expiration is applied to observability data

- **GIVEN** a cluster whose account bucket holds metrics, logs and profile backups
- **WHEN** the user tears the cluster down
- **THEN** no S3 lifecycle expiration is applied to any prefix holding metrics, logs, traces or profile data
- **AND** a backup taken before teardown is still readable an arbitrary time later

#### Scenario: The account bucket carries no cluster-prefix rule after teardown

- **WHEN** the account bucket is inspected after a teardown
- **THEN** it carries no lifecycle rule covering `clusters/<name>-<clusterId>`

#### Scenario: The data bucket's expiry loses no observability data

- **GIVEN** a cluster whose observability data all lives in the account bucket
- **WHEN** the cluster is torn down and its data bucket expires
- **THEN** no observability data is lost, because none of it was stored in that bucket
