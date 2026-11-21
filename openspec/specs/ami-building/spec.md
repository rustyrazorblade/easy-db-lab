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
- **THEN** the builder instance, its volumes, the AMI and its snapshots all carry `easy_cass_lab=1` and `easy_db_lab=1`, which easy-db-lab passes to Packer from `Constants.Vpc.MARKER_TAGS`, and the build's own `easy_db_lab_build_id`

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
fired) SHALL be run again on a new builder instance, up to 3 attempts in total. Each build SHALL
have its own build id, and everything it launches (the builder, its volumes, the AMI and its
snapshots) SHALL carry it as the `easy_db_lab_build_id` tag beside the marker tags. A build's
builders SHALL be only the live instances that carry its build id and the `easy_cass_lab=1` tag
in the build region's packer VPC that easy-db-lab created; an instance of another build, or one
outside that VPC, SHALL never be terminated or kept by it. A build's builders SHALL be terminated
before the next attempt starts, even under `--keep-on-error`, which keeps only the last failed
attempt's builders; when they cannot be listed or terminated, the build SHALL fail instead of
starting a second builder beside one that may still run, and the failed attempt's report SHALL say
why and how to find or terminate them. A build that failed for any other reason, including an AWS
API call that lost its own connection or a Packer container that could not run, SHALL NOT be
retried. Diagnostics SHALL be captured from the builders only after the last failed attempt.
Each failed attempt SHALL be reported with its build id and region, its reason, whether it lost its
session, whether another attempt follows, what became of its builders (with their instance ids),
and the path of its Packer log, or that it wrote none. Each attempt's Packer log SHALL be kept in
its own directory in the profile directory, named by the template, the time, the build id and the
attempt; the directory and the log SHALL be readable by their owner only.

The build SHALL run entirely in its build region (`--region`). The packer VPC, subnet and security
group SHALL be ensured in that region by the build itself, and its builders SHALL be found and
terminated there. When the build region is not the profile's region, the build SHALL import the
profile's public key into the build region as a key pair of its own (named
`easy-db-lab-build-<build id>`, carrying the marker tags and the build id), so a builder kept by
`--keep-on-error` accepts the profile's private key, whose path the build prints; that key pair
SHALL be deleted when the build ends. An import that a retry finds already done (a key pair of that
name carrying the build id) SHALL count as done. Under the `ssm` SSH transport, diagnostics SHALL be
read from the builders over a Session Manager route for the build region.

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
- **THEN** its builder is terminated, without capturing diagnostics, and the build runs again on a new builder instance
- **AND** the build fails after the third attempt that loses its session

#### Scenario: Keep-on-error keeps only the last failed attempt's builder

- **GIVEN** an AMI build run with `--keep-on-error`
- **WHEN** an attempt loses its session and another attempt follows
- **THEN** its builder is still terminated, and the report says it was terminated so the build can run again
- **AND** the builder of the attempt that ends the build is left running, with its instance ids, the command to terminate them, and how to SSH in

#### Scenario: Another build's builder survives

- **GIVEN** two AMI builds in the same region, each with a builder in the packer VPC
- **WHEN** one build cleans up after a failed attempt
- **THEN** only the builder carrying its own build id is terminated or kept, and the other build's builder keeps running

#### Scenario: A build outside the profile region runs there

- **GIVEN** an AMI build whose `--region` is not the profile's region
- **WHEN** the build starts
- **THEN** the packer VPC, subnet and security group are ensured in the build region, and the profile's public key is imported there as the build's own key pair
- **AND** a failed attempt's builders are found and terminated in the build region, and under `ssm` their diagnostics are read over a session in the build region
- **AND** the build's key pair is deleted when the build ends, whether it succeeded or failed

#### Scenario: A builder that may still run stops the retry

- **GIVEN** an AMI build whose SSH session to the builder was lost
- **WHEN** the packer VPC's instances cannot be listed, or one of them cannot be terminated
- **THEN** the build fails without another attempt
- **AND** the failed attempt is reported with why its builder may still be running, and how to find (by its build id) or terminate it

#### Scenario: Other failures are not retried

- **GIVEN** an AMI build whose provisioner exited non-zero, that failed with an AWS or template error, or whose Packer container could not run
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

