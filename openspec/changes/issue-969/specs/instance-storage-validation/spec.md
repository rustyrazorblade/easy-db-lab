## MODIFIED Requirements

### Requirement: Database instance storage validation at init time

The system SHALL validate that every node's instance type has adequate storage before provisioning: the db, control, and app (stress) instance types. A db instance type MUST either have local instance store (NVMe) or the user MUST specify `--ebs.type` with a value other than `NONE`. A control or app instance type MUST have local instance store, because `--ebs.type` adds an EBS data volume to db nodes only. If the condition is not met for any node type, the system SHALL fail with a clear error message that names the node type and its instance type, and SHALL NOT proceed with instance creation.

Every node writes data under `/mnt/db1`: databases and kit PVs on db nodes, the observability backends on the control node, and on every node the K3s data directory (pulled images and container layers) and pod logs. The root volume (20 GB) SHALL NOT hold that data.

**Note on workload-scoped PVs**: The instance storage validation ensures a raw data disk is present at `up` time. Per-workload Kubernetes PersistentVolumes at `/mnt/db1/<workload>` are created lazily at install time (via `platform create-pvs`), not at cluster-up time. Both mechanisms use the same underlying disk.

#### Scenario: Instance type with instance store and no EBS

- **WHEN** the user runs init with an instance type that has instance store (e.g., `i3.xlarge`) and `--ebs.type` is `NONE`
- **THEN** the system SHALL proceed normally (instance store provides the data disk)

#### Scenario: Instance type without instance store and EBS specified

- **WHEN** the user runs init with a db instance type that has no instance store (e.g., `c5.2xlarge`) and `--ebs.type` is `gp3`
- **THEN** the system SHALL proceed normally (EBS provides the data disk)

#### Scenario: Instance type without instance store and no EBS

- **WHEN** the user runs init with a db instance type that has no instance store (e.g., `c5.2xlarge`) and `--ebs.type` is `NONE`
- **THEN** the system SHALL fail with an error message indicating that the instance type has no local storage and `--ebs.type` must be specified
- **AND** the system SHALL NOT create any EC2 instances

#### Scenario: Control or app instance type without instance store

- **WHEN** the user runs init with a control or app instance type that has no instance store (e.g., `c5.2xlarge`), whatever `--ebs.type` is
- **THEN** init fails with an error that names the node type and the instance type and says it needs an instance type with instance store
- **AND** the system SHALL NOT create any EC2 instances

#### Scenario: Default instance types pass

- **WHEN** the user runs init with the default db, control and app instance types
- **THEN** the storage validation passes for every node type

## ADDED Requirements

### Requirement: Data disk mounted at up time

During `up`, the instance setup on every node SHALL find the node's data disk (instance store or EBS), format it if it has no file system, and mount it at `/mnt/db1`. After setup, `/mnt/db1` SHALL be a mount point of a device that is not the root volume's device. If no data disk is found, if the mount fails, or if `/mnt/db1` is not such a mount point, setup on that node SHALL exit non-zero, and `up` SHALL fail with an error that names the host and the reason, before K3s starts and before any workload is installed. The setup SHALL NOT create `/mnt/db1` as a plain directory on the root volume as a fallback.

#### Scenario: Data disk mounted on every node

- **WHEN** `up` completes
- **THEN** on every node, `/mnt/db1` is a mount point of a non-root device

#### Scenario: No data disk found

- **WHEN** instance setup on a node finds no unused data disk
- **THEN** setup exits non-zero and `up` fails with an error that names the host and says no data disk was found
- **AND** K3s does not start

#### Scenario: Mount fails

- **WHEN** the data disk exists but mounting it at `/mnt/db1` fails
- **THEN** setup exits non-zero and `up` fails with an error that names the host and the mount failure

#### Scenario: Data disk with a device name outside the first three

- **WHEN** a node's data disk is an NVMe device other than `nvme0n1` or `nvme1n1`, such as `nvme2n1`
- **THEN** setup finds it, mounts it at `/mnt/db1`, and `up` succeeds
