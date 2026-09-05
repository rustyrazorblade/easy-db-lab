# Cluster Lifecycle

## Purpose

Manages the full lifecycle of lab environments: initialization, provisioning, teardown, and status.
## Requirements

### Requirement: Cluster Initialization

The system MUST allow users to initialize a cluster configuration specifying a name and node counts.

#### Scenario: Initialize a new cluster

- **GIVEN** a configured AWS profile
- **WHEN** a user initializes a cluster with a name and node count
- **THEN** cluster configuration is created and persisted locally.

#### Scenario: Re-initialize with different parameters

- **GIVEN** an initialized cluster
- **WHEN** the user re-initializes with different parameters
- **THEN** the configuration is updated.

### Requirement: Infrastructure Provisioning

The system MUST provision EC2 instances in AWS with configurable instance types and counts. The system MUST create a per-cluster data bucket for high-volume storage and CloudWatch metrics.

#### Scenario: Provision EC2 instances

- **GIVEN** an initialized cluster configuration
- **WHEN** the user provisions the cluster
- **THEN** EC2 instances are launched and accessible via SSH.

#### Scenario: K3s deployed on provisioned instances

- **GIVEN** provisioned instances
- **WHEN** provisioning completes
- **THEN** K3s Kubernetes is deployed on all nodes.

#### Scenario: Per-cluster data bucket created

- **GIVEN** cluster provisioning
- **WHEN** infrastructure is created
- **THEN** a per-cluster S3 data bucket named `easy-db-lab-data-<cluster-id>` is created and tagged with cluster metadata.

#### Scenario: CloudWatch metrics on data bucket

- **GIVEN** a per-cluster data bucket
- **WHEN** provisioning completes
- **THEN** CloudWatch S3 request metrics are configured on the data bucket.

### Requirement: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Data bucket cleanup MUST use lifecycle expiration rather than individual object deletion.

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

### Requirement: Cluster Status

The system MUST provide comprehensive status of cluster resources including nodes, networking, Kubernetes pods, and running workloads.

#### Scenario: Display cluster status

- **GIVEN** a running cluster
- **WHEN** the user checks status
- **THEN** the state of EC2 instances, VPC networking, K8s pods, stress jobs, and database versions is displayed.

### Requirement: Local Cleanup

The system MUST allow cleanup of locally generated cluster files.

#### Scenario: Remove local cluster files

- **GIVEN** a previously initialized cluster
- **WHEN** the user runs local cleanup
- **THEN** state files, SSH config, and cached configuration are removed.

### Requirement: Cluster State Backup

The system MUST back up cluster configuration files to S3 for recovery.

#### Scenario: Incremental config backup

- **GIVEN** a running cluster
- **WHEN** configuration changes occur
- **THEN** changed files are incrementally backed up to S3.

#### Scenario: Full config backup

- **GIVEN** a cluster with backed-up state
- **WHEN** the user triggers a full backup
- **THEN** all configuration files (state, SSH config, kubeconfig, cassandra patches) are persisted to S3.

### Requirement: Cluster State Restore

The system MUST support restoring cluster configuration from S3 using VPC identification.

#### Scenario: Restore state from VPC ID

- **GIVEN** a VPC ID from a previously provisioned cluster
- **WHEN** the user restores state
- **THEN** cluster configuration is recovered from S3 and the local environment is rebuilt.

#### Scenario: Restore when no backup exists

- **GIVEN** no backup exists for a VPC
- **WHEN** restore is attempted
- **THEN** the user is informed that no configuration was found.

### Requirement: Architecture derived from instance type

The system MUST derive each node group's CPU architecture from that group's resolved instance type at initialization time, and MUST persist the derived architecture per node group in cluster state. The system MUST NOT expose any option to set the architecture directly.

Derivation MUST fail fast at initialization, before any EC2 instance is created, when the architecture cannot be determined. The system MUST NOT fall back to a default architecture.

#### Scenario: Architecture is derived from the instance type

- **GIVEN** a user initializes a cluster and specifies an instance type for a node group but does not — and cannot — specify an architecture
- **WHEN** the cluster is initialized
- **THEN** the architecture for that node group is derived from the instance type
- **AND** the derived architecture is persisted per node group in cluster state.

#### Scenario: Each node group may resolve to a different architecture

- **GIVEN** a user specifies a database instance type of one architecture and an application instance type of another
- **WHEN** the cluster is initialized
- **THEN** each node group's architecture is derived independently from its own instance type.

#### Scenario: Initialization fails fast when the architecture cannot be derived

- **GIVEN** a user specifies an instance type whose architecture cannot be determined (the instance type is unknown in the region, or it maps to no single supported architecture)
- **WHEN** the cluster is initialized
- **THEN** initialization fails with an error naming the instance type
- **AND** no EC2 instances are created
- **AND** the system does not fall back to a default architecture.

### Requirement: Provisioning reports success only when it fully succeeded

The system MUST NOT report successful provisioning unless every provisioning step succeeded. When any provisioning step fails, `up` MUST abort at the point of failure and exit non-zero.

There is no provisioning step whose failure may be logged and stepped over. A step that is skipped because there is nothing for it to do has not failed; a step that was attempted and threw has failed, and MUST abort provisioning.

This applies to every step invoked during provisioning, including nested commands whose exit codes were previously discarded, and to operations whose results were previously reduced to a log line.

#### Scenario: A failing provisioning step aborts `up`
- **WHEN** any provisioning step fails during `up` — writing configuration files, node setup, K3s cluster setup, StorageClass creation, node labeling, Tailscale startup, IAM policy application, or observability deployment
- **THEN** `up` SHALL abort at that step
- **AND** `up` SHALL exit non-zero
- **AND** the reported error SHALL identify the step that failed

#### Scenario: A failing nested command aborts `up`
- **WHEN** `up` invokes a nested command and that command returns a non-zero exit code
- **THEN** `up` SHALL abort
- **AND** `up` SHALL exit non-zero

#### Scenario: Unreachable cluster API aborts `up`
- **GIVEN** the cluster's Kubernetes API cannot be reached
- **WHEN** `up` attempts to apply node labels, StorageClasses, or the observability stack
- **THEN** `up` SHALL abort and exit non-zero
- **AND** `up` SHALL NOT report a successfully provisioned cluster

#### Scenario: Progress events assert only verified outcomes
- **WHEN** a provisioning step emits an event announcing its completion
- **THEN** that event SHALL be emitted only if the step actually succeeded

#### Scenario: Explicit opt-out is not a failure
- **WHEN** the user passes `--no-setup`
- **THEN** node setup SHALL be skipped
- **AND** `up` SHALL exit zero if all remaining steps succeeded

### Requirement: Cluster shape invariants are validated before provisioning

The system MUST validate the cluster's required shape before any AWS resource is provisioned, so that an unsatisfiable configuration fails before EC2 instances are launched rather than being discovered part-way through `up`.

A cluster MUST have a control node. An S3 bucket MUST be configured. Neither may be treated as an optional condition that causes provisioning steps to be silently skipped.

#### Scenario: Missing control node fails before provisioning
- **GIVEN** a cluster configuration that would produce no control node
- **WHEN** the user runs `up`
- **THEN** the command SHALL fail before any EC2 instance is launched
- **AND** the error SHALL state that a control node is required

#### Scenario: Missing S3 bucket fails before provisioning
- **GIVEN** no S3 bucket is configured
- **WHEN** the user runs `up`
- **THEN** the command SHALL fail before any EC2 instance is launched
- **AND** the error SHALL state that an S3 bucket is required

#### Scenario: Provisioning steps do not defend against missing invariants
- **WHEN** provisioning proceeds past validation
- **THEN** no provisioning step SHALL skip its work on the grounds that a control node or S3 bucket is absent

### Requirement: A cluster with zero database nodes is a valid configuration

The system MUST support provisioning a cluster with no database nodes. Workloads such as Trino with OpenSearch require a control node and application nodes but no database nodes.

Work that exists solely to serve database nodes MUST be skipped without warning when there are none. A supported configuration MUST NOT produce warning output.

#### Scenario: Zero database nodes provisions successfully
- **GIVEN** a cluster configuration with a control node, application nodes, and zero database nodes
- **WHEN** the user runs `up`
- **THEN** provisioning SHALL complete successfully and exit zero

#### Scenario: Database node labeling is skipped silently
- **GIVEN** a cluster with zero database nodes
- **WHEN** provisioning reaches database node labeling
- **THEN** the step SHALL be skipped
- **AND** no warning SHALL be emitted to the user

### Requirement: Control node SSH readiness is confirmed before it is used

The system MUST confirm that the control node is accepting SSH connections before any provisioning step depends on connecting to it.

#### Scenario: Provisioning waits for control node SSH
- **WHEN** `up` waits for instances to become reachable
- **THEN** the wait SHALL include the control node, not only database nodes
- **AND** provisioning SHALL NOT proceed to node setup or tunnel establishment until the control node accepts SSH connections

#### Scenario: Control node that never accepts SSH aborts `up`
- **GIVEN** the control node does not accept SSH connections within the retry window
- **WHEN** `up` waits for SSH readiness
- **THEN** `up` SHALL exit non-zero

## Success Criteria

- Users can provision a 3-node cluster in under 10 minutes from command execution.
- Cluster teardown removes all AWS resources within 5 minutes.
- New users can create their first cluster by following documentation in under 30 minutes.
- System supports at least 5 concurrent database nodes per cluster.

## Assumptions

- AWS region defaults to us-west-2 for pre-built AMIs; other regions require building a custom AMI.
- Instance types are selected for cost-effective testing, not production workloads.
- Security groups are configured for lab use with appropriate access controls.
- Lab data is ephemeral by default; backup/restore is user-initiated.
- Users are responsible for monitoring AWS costs; the tool provides teardown commands.
