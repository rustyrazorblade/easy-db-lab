# Cluster Lifecycle

Manages the full lifecycle of lab environments: initialization, provisioning, teardown, and status.

## Requirements

### REQ-CL-001: Cluster Initialization

The system MUST allow users to initialize a cluster configuration specifying a name and node counts.

**Scenarios:**

- **GIVEN** a configured AWS profile, **WHEN** a user initializes a cluster with a name and node count, **THEN** cluster configuration is created and persisted locally.
- **GIVEN** an initialized cluster, **WHEN** the user re-initializes with different parameters, **THEN** the configuration is updated.

### REQ-CL-002: Infrastructure Provisioning

The system MUST provision EC2 instances in AWS with configurable instance types and counts. The system MUST create a per-cluster data bucket for high-volume storage and CloudWatch metrics.

**Scenarios:**

- **GIVEN** an initialized cluster configuration, **WHEN** the user provisions the cluster, **THEN** EC2 instances are launched and accessible via SSH.
- **GIVEN** provisioned instances, **WHEN** provisioning completes, **THEN** K3s Kubernetes is deployed on all nodes.
- **GIVEN** cluster provisioning, **WHEN** infrastructure is created, **THEN** a per-cluster S3 data bucket named `easy-db-lab-data-<cluster-id>` is created and tagged with cluster metadata.
- **GIVEN** a per-cluster data bucket, **WHEN** provisioning completes, **THEN** CloudWatch S3 request metrics are configured on the data bucket.

### REQ-CL-003: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Data bucket cleanup MUST use lifecycle expiration rather than individual object deletion.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user tears it down with confirmation, **THEN** all AWS resources (EC2, NAT gateways, security groups, route tables, subnets, internet gateways, VPC) are terminated.
- **GIVEN** a running cluster with a data bucket, **WHEN** the user tears it down, **THEN** a lifecycle expiration rule is set on the data bucket to expire all objects after the retention period.
- **GIVEN** multiple clusters, **WHEN** the user tears down all clusters, **THEN** every tagged VPC and its resources are removed, and all per-cluster data buckets are deleted.
- **GIVEN** a teardown request without confirmation, **WHEN** the command runs, **THEN** the user is prompted for approval before proceeding.

### REQ-CL-004: Cluster Status

The system MUST provide comprehensive status of cluster resources including nodes, networking, Kubernetes pods, and running workloads.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user checks status, **THEN** the state of EC2 instances, VPC networking, K8s pods, stress jobs, and database versions is displayed.

### REQ-CL-005: Local Cleanup

The system MUST allow cleanup of locally generated cluster files.

**Scenarios:**

- **GIVEN** a previously initialized cluster, **WHEN** the user runs local cleanup, **THEN** state files, SSH config, and cached configuration are removed.

### REQ-CL-006: Cluster State Backup

The system MUST back up cluster configuration files to S3 for recovery.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** configuration changes occur, **THEN** changed files are incrementally backed up to S3.
- **GIVEN** a cluster with backed-up state, **WHEN** the user triggers a full backup, **THEN** all configuration files (state, SSH config, kubeconfig, cassandra patches) are persisted to S3.

### REQ-CL-007: Cluster State Restore

The system MUST support restoring cluster configuration from S3 using VPC identification.

**Scenarios:**

- **GIVEN** a VPC ID from a previously provisioned cluster, **WHEN** the user restores state, **THEN** cluster configuration is recovered from S3 and the local environment is rebuilt.
- **GIVEN** no backup exists for a VPC, **WHEN** restore is attempted, **THEN** the user is informed that no configuration was found.

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
