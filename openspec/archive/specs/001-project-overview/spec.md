# Feature Specification: easy-db-lab Project Overview

**Feature Branch**: `001-project-overview`
**Created**: 2026-02-22
**Status**: Draft
**Input**: User description: "Document existing easy-db-lab capabilities as a baseline specification"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Provision Database Cluster (Priority: P1)

A database engineer wants to quickly spin up a multi-node database cluster in AWS for testing, benchmarking, or learning. They need isolated infrastructure that can be created and destroyed on demand without affecting production systems.

**Why this priority**: Core value proposition — without cluster provisioning, the tool has no purpose.

**Independent Test**: Can be fully tested by running `easy-db-lab init` followed by `easy-db-lab up` and verifying EC2 instances are running with K3s deployed.

**Acceptance Scenarios**:

1. **Given** a configured AWS profile, **When** user runs `easy-db-lab init -c 3 mycluster`, **Then** cluster configuration is created with 3 database nodes
2. **Given** initialized cluster configuration, **When** user runs `easy-db-lab up`, **Then** EC2 instances are provisioned and accessible via SSH
3. **Given** running cluster, **When** user runs `easy-db-lab down --yes`, **Then** all AWS resources are terminated

---

### User Story 2 - Deploy Apache Cassandra (Priority: P1)

A Cassandra developer wants to test different Cassandra versions, configurations, and upgrade scenarios. They need ability to switch versions, modify configurations, and start/stop clusters quickly.

**Why this priority**: Primary supported database with deep feature integration.

**Independent Test**: Can be fully tested by running `easy-db-lab use 5.0` followed by `easy-db-lab start` and connecting via cqlsh.

**Acceptance Scenarios**:

1. **Given** running cluster, **When** user runs `easy-db-lab use 4.1`, **Then** Cassandra 4.1 is selected with appropriate Java and Python versions
2. **Given** selected version, **When** user runs `easy-db-lab start`, **Then** Cassandra cluster starts and nodes join the ring
3. **Given** local cassandra.patch.yaml changes, **When** user runs `easy-db-lab update-config`, **Then** configuration is pushed to all nodes

---

### User Story 3 - Deploy ClickHouse (Priority: P2)

A data engineer wants to test ClickHouse for analytics workloads. They need sharded clusters with configurable replication and S3 storage integration.

**Why this priority**: Secondary database with full K3s-based deployment support.

**Independent Test**: Can be fully tested by running ClickHouse deployment command and connecting via clickhouse-client.

**Acceptance Scenarios**:

1. **Given** running cluster, **When** user deploys ClickHouse via K8s, **Then** sharded cluster starts with distributed tables
2. **Given** ClickHouse cluster, **When** S3 storage is configured, **Then** data can be stored in and read from S3

---

### User Story 4 - Monitor Cluster Performance (Priority: P2)

An engineer wants to observe database performance with metrics, logs, and profiling. They need Grafana dashboards, distributed tracing, and flame graph support.

**Why this priority**: Observability is essential for meaningful benchmarking and troubleshooting.

**Independent Test**: Can be fully tested by accessing Grafana at port 3000 and viewing pre-configured dashboards.

**Acceptance Scenarios**:

1. **Given** running database cluster, **When** user accesses Grafana, **Then** pre-configured dashboards show metrics for the running databases
2. **Given** Cassandra cluster, **When** user runs `c-flame db0`, **Then** CPU flame graph is generated and downloaded
3. **Given** running cluster, **When** logs are generated, **Then** they are queryable via VictoriaLogs

---

### User Story 5 - Integrate with AI Assistants (Priority: P3)

A developer wants to manage clusters through AI assistants like Claude Code. They need an MCP server that exposes cluster management capabilities.

**Why this priority**: Enhances developer experience but not required for core functionality.

**Independent Test**: Can be fully tested by starting MCP server and connecting via Claude Code.

**Acceptance Scenarios**:

1. **Given** running cluster, **When** user runs `easy-db-lab server --port 8888`, **Then** MCP server starts accepting connections
2. **Given** running MCP server, **When** Claude Code connects via SSE, **Then** cluster management tools are available

---

### User Story 6 - Run Spark Analytics Jobs (Priority: P3)

A data engineer wants to run Spark jobs against their database cluster using EMR. They need ability to provision EMR clusters and submit jobs.

**Why this priority**: Advanced analytics capability for data pipeline testing.

**Independent Test**: Can be fully tested by provisioning EMR cluster and submitting a test job.

**Acceptance Scenarios**:

1. **Given** S3 bucket configured, **When** user provisions EMR cluster, **Then** Spark cluster is available for job submission
2. **Given** running EMR cluster, **When** user submits Spark job, **Then** job executes and results are accessible

---

### Edge Cases

- What happens when AWS credentials are missing or invalid? Tool prompts for `setup-profile` workflow.
- What happens when cluster provisioning fails mid-way? Terraform state allows recovery or clean teardown.
- What happens when Cassandra version is not installed on AMI? Tool reports error with available versions.
- What happens when network connectivity fails during operation? Operations should fail fast with clear error messages.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provision EC2 instances in AWS with configurable instance types and counts
- **FR-002**: System MUST deploy K3s Kubernetes on all provisioned nodes
- **FR-003**: System MUST support multiple Cassandra versions (2.2 through trunk) on the same AMI
- **FR-004**: System MUST allow configuration of Cassandra via YAML patch files
- **FR-005**: System MUST support ClickHouse deployment via K3s with sharding and replication
- **FR-006**: System MUST provide Grafana dashboards for all supported databases
- **FR-007**: System MUST collect metrics via OpenTelemetry and store in VictoriaMetrics
- **FR-008**: System MUST collect logs via Vector and store in VictoriaLogs
- **FR-009**: System MUST support flame graph profiling for Cassandra via async-profiler
- **FR-010**: System MUST provide SSH access to all nodes with convenient aliases
- **FR-011**: System MUST expose MCP server for AI assistant integration
- **FR-012**: System MUST support EMR cluster provisioning for Spark jobs
- **FR-013**: System MUST support OpenSearch deployment
- **FR-014**: System MUST clean up all AWS resources on cluster teardown
- **FR-015**: System MUST support mixed-version Cassandra clusters for upgrade testing

### Key Entities

- **Cluster**: Represents a lab environment with name, node count, instance types, and configuration state
- **Node**: Individual EC2 instance with role (database or application/stress), IP addresses, and installed software
- **Database Configuration**: Version-specific settings including Cassandra YAML patches, JVM options, and Java version
- **Observability Stack**: Collection of monitoring components (Grafana, VictoriaMetrics, VictoriaLogs, OTel collectors)
- **AMI**: Pre-built Amazon Machine Image containing multiple database versions and supporting tools

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can provision a 3-node Cassandra cluster in under 10 minutes from command execution
- **SC-002**: Users can switch Cassandra versions in under 1 minute without reprovisioning infrastructure
- **SC-003**: Grafana dashboards display meaningful metrics within 2 minutes of cluster start
- **SC-004**: Cluster teardown removes all AWS resources within 5 minutes
- **SC-005**: New users can successfully create their first cluster by following documentation in under 30 minutes
- **SC-006**: System supports at least 5 concurrent database nodes per cluster
- **SC-007**: All observability data (metrics, logs, traces) is available for at least 7 days by default
- **SC-008**: Flame graph generation completes in under 60 seconds for standard profiling duration

## Assumptions

The following assumptions were made where the specification could have multiple interpretations:

- **AWS Region**: Tool defaults to us-west-2 for pre-built AMIs; other regions require building a custom AMI
- **Instance Types**: Default instance types are selected for cost-effective testing, not production workloads
- **Network Security**: Security groups are configured for lab use with appropriate access controls
- **Data Persistence**: Lab data is ephemeral by default; backup/restore is user-initiated
- **Cost Management**: Users are responsible for monitoring AWS costs; the tool provides teardown commands
