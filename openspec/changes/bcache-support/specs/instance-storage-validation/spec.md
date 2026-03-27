### Requirement: Database instance storage validation at init time

The system SHALL validate that the database instance type has adequate storage before provisioning. An instance type MUST either have local instance store (NVMe) or the user MUST specify `--ebs.type` with a value other than `NONE`. If neither condition is met, the system SHALL fail with a clear error message and NOT proceed with instance creation.

This validation applies only to database (db) nodes. Stress and control nodes do not require data disks.

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

### Requirement: Instance store detection via AWS API

The system SHALL use the AWS `DescribeInstanceTypes` API to determine whether an instance type has local instance store. The system SHALL NOT use hardcoded lists or pattern-matching on instance type names.

#### Scenario: Querying instance type capabilities

- **WHEN** the system needs to validate an instance type's storage capabilities
- **THEN** the system SHALL call `DescribeInstanceTypes` with the instance type name
- **AND** the system SHALL check the `instanceStorageSupported` field to determine if instance store is available

### Requirement: bcache validation requires both EBS and instance store

When the `--bcache` flag is enabled, the system SHALL enforce that BOTH of the following conditions are true at init time. If either condition is not met, the system SHALL fail with a clear error message and NOT proceed.

1. The database instance type MUST have local NVMe instance store (required as the bcache cache device).
2. `--ebs.type` MUST NOT be `NONE` (required as the bcache backing device).

#### Scenario: --bcache with EBS and instance store (valid)

- **WHEN** the user runs init with `--bcache`, an instance type that has local NVMe instance store (e.g., `i3.xlarge`), and `--ebs.type gp3`
- **THEN** the system SHALL proceed normally

#### Scenario: --bcache without EBS

- **WHEN** the user runs init with `--bcache`, an instance type that has instance store, but `--ebs.type` is `NONE`
- **THEN** the system SHALL fail with an error indicating that `--bcache` requires `--ebs.type` to be specified
- **AND** the system SHALL NOT create any EC2 instances

#### Scenario: --bcache without instance store

- **WHEN** the user runs init with `--bcache`, `--ebs.type gp3`, but an instance type that has no local instance store (e.g., `c5.2xlarge`)
- **THEN** the system SHALL fail with an error indicating that `--bcache` requires an instance type with local NVMe instance store
- **AND** the system SHALL NOT create any EC2 instances
