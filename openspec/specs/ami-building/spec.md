# AMI Building

## Purpose

Manages creation and maintenance of pre-built Amazon Machine Images containing database software and supporting tools.
## Requirements
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

### Requirement: Clock sync polls Amazon Time Sync every 8 seconds
The base AMI SHALL configure chrony to poll every Amazon Time Sync Service source (`169.254.169.123` and `fd00:ec2::123`) every 8 seconds (`minpoll 3 maxpoll 3`), and SHALL keep every other option on those source lines. The base AMI build SHALL fail if the image's chrony configuration has no Amazon Time Sync source.

#### Scenario: Time Sync source polled every 8 seconds
- **GIVEN** the Ubuntu image's chrony config polls Amazon Time Sync with `minpoll 4 maxpoll 4`
- **WHEN** the user builds the base image
- **THEN** every Amazon Time Sync source line in the image has `minpoll 3 maxpoll 3` and keeps its other options

#### Scenario: No Time Sync source fails the build
- **GIVEN** an image whose chrony config has no Amazon Time Sync source
- **WHEN** the base image is built
- **THEN** the build fails and names the missing source

### Requirement: AMI Maintenance
The system MUST support pruning old AMIs to manage costs.

#### Scenario: Old AMIs pruned while retaining recent ones
- **GIVEN** multiple AMI versions exist
- **WHEN** the user prunes AMIs
- **THEN** older AMIs are deregistered while retaining the most recent ones

