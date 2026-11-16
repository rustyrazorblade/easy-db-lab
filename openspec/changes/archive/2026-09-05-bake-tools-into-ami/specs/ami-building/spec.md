## MODIFIED Requirements

### Requirement: AMI Creation
The system MUST support building AMIs with multiple database versions and supporting tools pre-installed. The base AMI SHALL use Ubuntu 26.04 LTS as the host OS. The base AMI SHALL include helm, kubectl, and cilium CLI pre-installed at pinned versions.

#### Scenario: Base AMI built on Ubuntu 26.04
- **GIVEN** Packer build configuration targeting Ubuntu 26.04 LTS
- **WHEN** the user builds the base image
- **THEN** an AMI is created running Ubuntu 26.04 LTS with helm, kubectl, and cilium CLI installed

#### Scenario: Cassandra AMI inherits tooling from base
- **GIVEN** the base AMI is built with helm, kubectl, and cilium CLI
- **WHEN** the Cassandra AMI is built on top of the base
- **THEN** all base tooling is present on Cassandra AMI nodes

#### Scenario: Multiple Cassandra versions still supported
- **GIVEN** multiple Cassandra versions in the build config
- **WHEN** the AMI is built
- **THEN** all specified versions are installed and selectable at runtime

### Requirement: AMI Maintenance
The system MUST support pruning old AMIs to manage costs.

#### Scenario: Old AMIs pruned while retaining recent ones
- **GIVEN** multiple AMI versions exist
- **WHEN** the user prunes AMIs
- **THEN** older AMIs are deregistered while retaining the most recent ones
