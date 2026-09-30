## ADDED Requirements

### Requirement: Base AMI JDKs

The base AMI SHALL install OpenJDK 8, 11, 17, 21, and 25, each with its debug-symbol package.  The node default JDK SHALL stay 11.

#### Scenario: Base AMI provides JDK 25

- **GIVEN** the base AMI build configuration
- **WHEN** the user builds the base image
- **THEN** `/usr/lib/jvm/` holds JDK 25 and its debug symbols, next to JDK 8, 11, 17, and 21
- **AND** the `java-1.25.0-openjdk-<arch>` alternatives entry exists

#### Scenario: Node default JDK is unchanged

- **GIVEN** a node built from the base AMI
- **WHEN** no Cassandra version has selected a JDK
- **THEN** the active JDK is 11
