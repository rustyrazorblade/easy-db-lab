## MODIFIED Requirements

### Requirement: Database instance storage validation at init time

The system SHALL validate that the database instance type has adequate storage before provisioning. An instance type MUST either have local instance store (NVMe) or the user MUST specify `--ebs.type` with a value other than `NONE`. If neither condition is met, the system SHALL fail with a clear error message and NOT proceed with instance creation.

This validation applies only to database (db) nodes. Stress and control nodes do not require data disks.

Note: this validation covers EC2 instance-level storage (NVMe instance store or EBS). Per-workload Kubernetes PersistentVolumes are created lazily at workload install time (via `easy-db-lab platform create-pvs`) and are not validated at `up` time.

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
