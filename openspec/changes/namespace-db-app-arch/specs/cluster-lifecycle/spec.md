## MODIFIED Requirements

### REQ-CL-001: Cluster Initialization

The system MUST allow users to initialize a cluster configuration specifying a name, node counts, and per-group instance types.

The database and application node groups MUST each be configurable through a namespaced option scheme — a `db` family and an `app` family — covering that group's instance type and count. Every option name that existed before this scheme MUST continue to work as an alias for the same setting, carrying its established default, so no existing invocation breaks.

When both a namespaced option and its legacy alias are supplied for the same setting, the namespaced option MUST take precedence, and this MUST hold regardless of the order the options appear on the command line. The precedence MUST be documented in the command help.

#### Scenario: Initialize a new cluster

- **GIVEN** a configured AWS profile
- **WHEN** a user initializes a cluster with a name and node count
- **THEN** cluster configuration is created and persisted locally.

#### Scenario: Re-initialize with different parameters

- **GIVEN** an initialized cluster
- **WHEN** the user re-initializes with different parameters
- **THEN** the configuration is updated.

#### Scenario: Namespaced option takes precedence over its legacy alias

- **GIVEN** a user supplies both the namespaced db instance-type option and its legacy alias with different values
- **WHEN** the cluster is initialized
- **THEN** the value from the namespaced option is used
- **AND** this holds regardless of the order the two options were given on the command line.

#### Scenario: Legacy alias still works when the namespaced option is absent

- **GIVEN** a user supplies only a legacy alias (for an instance type or a count)
- **WHEN** the cluster is initialized
- **THEN** the legacy alias sets the corresponding value, exactly as before the namespaced scheme existed.

### REQ-CL-002: Infrastructure Provisioning

The system MUST provision EC2 instances in AWS with configurable instance types and counts. The system MUST create a per-cluster data bucket for high-volume storage and CloudWatch metrics.

Each node group MUST be provisioned with a machine image matching that group's own CPU architecture. When node groups in the same cluster have different architectures, each group MUST receive the image for its architecture; a single image MUST NOT be applied across groups of differing architecture.

#### Scenario: Provision EC2 instances

- **GIVEN** an initialized cluster configuration
- **WHEN** the user provisions the cluster
- **THEN** EC2 instances are launched and accessible via SSH.

#### Scenario: K3s deployed on provisioned instances

- **GIVEN** provisioned instances
- **WHEN** provisioning completes
- **THEN** K3s Kubernetes is deployed on all nodes.

#### Scenario: Mixed-architecture cluster is provisioned per group

- **GIVEN** a cluster whose database group and application group have different CPU architectures
- **WHEN** the user provisions the cluster
- **THEN** the database group is provisioned with the image for its architecture
- **AND** the application group is provisioned with the image for its architecture
- **AND** each distinct architecture's image is resolved once.

#### Scenario: Per-cluster data bucket created

- **GIVEN** cluster provisioning
- **WHEN** infrastructure is created
- **THEN** a per-cluster S3 data bucket named `easy-db-lab-data-<cluster-id>` is created and tagged with cluster metadata.

#### Scenario: CloudWatch metrics on data bucket

- **GIVEN** a per-cluster data bucket
- **WHEN** provisioning completes
- **THEN** CloudWatch S3 request metrics are configured on the data bucket.

## ADDED Requirements

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
