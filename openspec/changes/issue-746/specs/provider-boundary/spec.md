## ADDED Requirements

### Requirement: Cloud SDK types stay inside the provider adapters

No main class outside the AWS adapter packages (`providers.aws..` and `services.aws..`) SHALL depend on any AWS SDK type (`software.amazon.awssdk..`). This covers imports, signatures, class literals, type checks, and SDK types that appear only in a `catch` clause. The unit test tier SHALL enforce the rule with no allowlist. The rule SHALL scan main classes only.

#### Scenario: The rule passes on the current tree

- **WHEN** the unit test tier runs
- **THEN** the boundary test reports zero violations
- **AND** the test carries no allowlist of excepted classes

#### Scenario: A new SDK import outside the adapters fails the build

- **GIVEN** a main class outside the adapter packages that references an SDK type, for example `S3Client` in a command
- **WHEN** the unit test tier runs
- **THEN** the boundary test fails
- **AND** the failure message names that class and the SDK type

#### Scenario: An SDK exception caught outside the adapters fails the build

- **GIVEN** a main method outside the adapter packages whose only SDK reference is a `catch` of an SDK exception type
- **WHEN** the unit test tier runs
- **THEN** the boundary test fails and names the method, the SDK exception type and its location

#### Scenario: Test classes are not scanned

- **GIVEN** test classes that use SDK types
- **WHEN** the boundary test runs
- **THEN** none of them is reported
- **AND** the scanned set contains the application entry point and no test-support class, so the rule cannot pass by scanning nothing

### Requirement: AWS-boundary package cycles stay broken

No main class in `providers..` SHALL depend on a class in `services..`. No main class in `configuration..` SHALL depend on a class in `providers..`. The unit test tier SHALL enforce both rules.

#### Scenario: providers does not depend on services

- **WHEN** the unit test tier runs
- **THEN** the package test finds no dependency from `providers..` to `services..`

#### Scenario: configuration does not depend on providers

- **WHEN** the unit test tier runs
- **THEN** the package test finds no dependency from `configuration..` to `providers..`

#### Scenario: Dependency wiring still resolves after the split

- **WHEN** the application's dependency modules are loaded
- **THEN** every AWS client, AWS service, and registered provider error handler resolves
- **AND** the error reporter resolves with no user profile present
