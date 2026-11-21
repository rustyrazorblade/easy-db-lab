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

#### Scenario: The builder instance and the AMI carry both marker tags
- **GIVEN** an AMI build
- **WHEN** Packer launches the builder instance and registers the AMI
- **THEN** the builder instance, its volumes, the AMI and its snapshots all carry `easy_cass_lab=1` and `easy_db_lab=1`, which easy-db-lab passes to Packer from `Constants.Vpc.MARKER_TAGS`

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

### Requirement: AMI builds honor the SSH transport

AMI builds MUST reach the temporary builder instance using the profile's SSH transport. Under
`direct`, Packer SHALL connect to the builder instance's public IP as before. Under `ssm`, Packer
SHALL connect through an SSM Session Manager session, so the build needs no inbound port-22
reachability from the operator's network.

The Session Manager plugin Packer needs under `ssm` MUST be provided by the tool's own Packer
container image, built locally from a definition packaged with the distribution. The operator
SHALL NOT need to install anything beyond what the `ssm` transport already requires.

#### Scenario: Direct transport builds are unchanged

- **GIVEN** a profile whose SSH transport is `direct`
- **WHEN** the user builds an AMI
- **THEN** Packer runs in the stock Packer image and connects to the builder instance's public IP

#### Scenario: SSM transport builds go through Session Manager

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user builds an AMI
- **THEN** Packer connects to the builder instance through an SSM Session Manager session
- **AND** Packer runs in an image that includes the Session Manager plugin

#### Scenario: The SSM-capable Packer image is built once and reused

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user builds an AMI and the SSM-capable Packer image for the current image definition already exists locally
- **THEN** the existing image is reused without rebuilding it

#### Scenario: A changed image definition produces a new image

- **GIVEN** an SSM-capable Packer image built from an earlier image definition
- **WHEN** the image definition changes and the user builds an AMI under `ssm`
- **THEN** a new image is built for the new definition

#### Scenario: An unusable plugin fails before the build starts

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the SSM-capable Packer image is built and its Session Manager plugin cannot run
- **THEN** the image build fails with the plugin's error before any builder instance is launched

### Requirement: AMI builds survive a lost SSH session

An AMI build MUST NOT hang on an SSH stream to its builder that carries no data. Under every SSH
transport, Packer SHALL send an SSH keepalive every 10 seconds and SHALL fail any SSH read or write
that waits more than 2 minutes. The keepalive replies keep a live but quiet stream inside that
bound.

A build whose SSH session to the builder was lost (the session ended, or the read/write timeout
fired) SHALL be run again on a new builder instance, up to 3 attempts in total. The lost attempt's
builder SHALL be terminated before the next attempt starts, even under `--keep-on-error`; when it
cannot be terminated, the build SHALL fail instead of starting a second builder beside it. A build
that failed for any other reason, including an AWS API call that lost its own connection, SHALL NOT
be retried. Each failed attempt SHALL be reported with
its reason, whether it lost its session, whether another attempt follows, and the path of its
Packer log. Each attempt's Packer log SHALL be kept in the profile directory, readable by its owner
only.

One `packer build` SHALL be bounded at 60 minutes. A Packer container still running at that point
SHALL be stopped and removed, and the attempt SHALL fail without a retry.

#### Scenario: A quiet provisioner on a live session is not cut off

- **GIVEN** an AMI build whose provisioner prints nothing for longer than the read/write timeout
- **WHEN** the SSH session to the builder is live
- **THEN** the keepalive replies keep the session inside the timeout and the build continues

#### Scenario: A silent session fails within the bound

- **GIVEN** an AMI build whose SSH stream to the builder stops carrying data while the session still shows as connected
- **WHEN** a provisioner is running
- **THEN** the attempt fails within the read/write timeout instead of hanging

#### Scenario: A lost session is retried on a new builder

- **GIVEN** an AMI build whose SSH session to the builder ended or timed out while a provisioner ran
- **WHEN** the attempt fails
- **THEN** its builder is terminated and the build runs again on a new builder instance
- **AND** the build fails after the third attempt that loses its session

#### Scenario: Other failures are not retried

- **GIVEN** an AMI build whose provisioner exited non-zero, or that failed with an AWS or template error
- **WHEN** the attempt fails
- **THEN** the build fails at once without another attempt

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

