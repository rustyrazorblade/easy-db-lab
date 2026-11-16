## MODIFIED Requirements

### Requirement: Database instance storage validation at init time

The system SHALL validate that every node's instance type has a data disk before provisioning, so that `/mnt/db1` is never on the root volume.  A database (db) instance type MUST either have local instance store (NVMe) or the user MUST specify `--ebs.type` with a value other than `NONE`.  A control or app (stress) instance type MUST have local instance store, because `--ebs.type` attaches a volume to db nodes only.  If the condition for a node type is not met, the system SHALL fail with a clear error message that names the node type and instance type, and NOT proceed with instance creation.

Every node writes to its data disk: databases and kit PVs on db nodes; the observability backends and the K3s server's etcd datastore and snapshots on the control node; and on every node the K3s data directory and pod logs.

**Note on workload-scoped PVs**: The instance storage validation ensures a raw data disk is present at `up` time. Per-workload Kubernetes PersistentVolumes at `/mnt/db1/<workload>` are created lazily at install time (via `platform create-pvs`), not at cluster-up time. Both mechanisms use the same underlying disk.

#### Scenario: Instance type with instance store and no EBS

- **WHEN** the user runs init with an instance type that has instance store (e.g., `i3.xlarge`) and `--ebs.type` is `NONE`
- **THEN** the system SHALL proceed normally (instance store provides the data disk)

#### Scenario: Instance type without instance store and EBS specified

- **WHEN** the user runs init with an instance type that has no instance store (e.g., `c5.2xlarge`) and `--ebs.type` is `gp3`
- **THEN** the system SHALL proceed normally (EBS provides the data disk)

#### Scenario: Instance type without instance store and no EBS

- **WHEN** the user runs init with an instance type that has no instance store (e.g., `c5.2xlarge`) and `--ebs.type` is `NONE`
- **THEN** the system SHALL fail with an error message indicating that the instance type has no local storage and `--ebs.type` must be specified
- **AND** the system SHALL NOT create any EC2 instances

#### Scenario: Control instance type without instance store

- **WHEN** the user runs init with a control instance type that has no instance store (e.g., `m5.xlarge`)
- **THEN** the system SHALL fail with an error message that names the control node type and the instance type and says it needs instance store for its data disk
- **AND** the system SHALL NOT create any EC2 instances
