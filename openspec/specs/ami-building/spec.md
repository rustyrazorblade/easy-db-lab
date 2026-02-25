# AMI Building

Manages creation and maintenance of pre-built Amazon Machine Images containing database software and supporting tools.

## Requirements

### REQ-AMI-001: AMI Creation

The system MUST support building AMIs with multiple database versions and supporting tools pre-installed.

**Scenarios:**

- **GIVEN** Packer build configuration, **WHEN** the user builds a base or Cassandra image, **THEN** an AMI is created with the requested software installed.
- **GIVEN** multiple Cassandra versions in the build config, **WHEN** the AMI is built, **THEN** all specified versions are installed and selectable at runtime.

### REQ-AMI-002: AMI Maintenance

The system MUST support pruning old AMIs to manage costs.

**Scenarios:**

- **GIVEN** multiple AMI versions exist, **WHEN** the user prunes AMIs, **THEN** older AMIs are deregistered while retaining the most recent ones.
