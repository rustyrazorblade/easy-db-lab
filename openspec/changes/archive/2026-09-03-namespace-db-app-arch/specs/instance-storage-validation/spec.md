## MODIFIED Requirements

### Requirement: Instance store detection via AWS API

The system SHALL use the AWS `DescribeInstanceTypes` API to determine whether an instance type has local instance store. The system SHALL NOT use hardcoded lists or pattern-matching on instance type names.

The system SHALL resolve the instance type by an `instance-type` filter carrying the raw instance-type string, and SHALL NOT route the user-supplied instance type through the SDK's fixed `InstanceType` enum. Instance-type support therefore does not depend on the installed SDK version: any valid EC2 instance type is accepted, including a type newer than the SDK.

A single `DescribeInstanceTypes` call SHALL report both whether the instance type has local instance store and the instance type's supported CPU architectures, so both facts are obtained from one query per instance type.

#### Scenario: Querying instance type capabilities

- **WHEN** the system needs to validate an instance type's storage capabilities
- **THEN** the system SHALL call `DescribeInstanceTypes` with the instance type name
- **AND** the system SHALL check the `instanceStorageSupported` field to determine if instance store is available.

#### Scenario: Instance type newer than the SDK enum is accepted

- **WHEN** a user requests an instance type not present in the installed SDK's `InstanceType` enum
- **THEN** instance-store detection and provisioning SHALL both succeed for any valid EC2 type
- **AND** the system SHALL NOT fail with a `[null]` instance-type error.

#### Scenario: One query yields storage and architecture

- **WHEN** the system queries an instance type's capabilities
- **THEN** the same `DescribeInstanceTypes` result SHALL provide both the instance-store presence and the supported CPU architectures for that instance type.
