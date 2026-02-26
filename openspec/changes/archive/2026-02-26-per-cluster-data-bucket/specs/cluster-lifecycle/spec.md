## MODIFIED Requirements

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
