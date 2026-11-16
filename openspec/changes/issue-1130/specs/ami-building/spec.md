## MODIFIED Requirements

### Requirement: AMI Creation
The system MUST support building AMIs with supporting tools pre-installed. The base AMI SHALL use Ubuntu 26.04 LTS as the host OS. The base AMI SHALL include helm, kubectl, and cilium CLI pre-installed at pinned versions.

The Cassandra AMI bake SHALL download no Cassandra tarball and no per-release AxonOps agent.  It SHALL install the version install and activation scripts, the AxonOps agent install script, the one-time node setup (the `cassandra` user with uid 999 and its directories), cqlsh, the `axon-agent` package with its sudoers file, and the other software it installs today.  `/usr/local/cassandra` SHALL hold no version on a node that boots from the image.  The Cassandra AMI SHALL NOT hold a Cassandra version file.

#### Scenario: Base AMI built on Ubuntu 26.04
- **GIVEN** Packer build configuration targeting Ubuntu 26.04 LTS
- **WHEN** the user builds the base image
- **THEN** an AMI is created running Ubuntu 26.04 LTS with helm, kubectl, and cilium CLI installed

#### Scenario: Cassandra AMI inherits tooling from base
- **GIVEN** the base AMI is built with helm, kubectl, and cilium CLI
- **WHEN** the Cassandra AMI is built on top of the base
- **THEN** all base tooling is present on Cassandra AMI nodes

#### Scenario: The Cassandra AMI bake downloads no Cassandra version
- **WHEN** the Cassandra AMI is baked
- **THEN** the bake downloads no Cassandra tarball and no per-release AxonOps agent package
- **AND** a new node from the image has no version under `/usr/local/cassandra` and no `/etc/cassandra_versions.yaml`
- **AND** the node has the version install script, the activation script, the AxonOps agent install script and the `axon-agent` package.
