## ADDED Requirements

### Requirement: A profile records exactly one destination

Each profile SHALL record exactly one destination: the place its clusters run, with that
destination's settings and credentials.  The only destination available SHALL be AWS.  No command
option SHALL pick or override the destination; a different destination needs a different profile.

#### Scenario: A new profile names its destination

- **GIVEN** a profile name with no profile file
- **WHEN** the user runs `easy-db-lab profile setup` and completes it
- **THEN** setup states the destination (`Destination: AWS`) before it asks for credentials, without asking the user to choose
- **AND** the saved profile records the AWS destination with its region and credentials

#### Scenario: Re-running setup keeps the destination

- **GIVEN** a completed profile
- **WHEN** the user runs `easy-db-lab profile setup` again
- **THEN** setup reviews that profile's destination settings and does not offer another destination

#### Scenario: Every command that needs a profile still requires one

- **GIVEN** a profile name with no profile file
- **WHEN** the user runs any command that requires a profile
- **THEN** the interactive profile setup runs first, as before

### Requirement: Earlier profile files migrate silently

A profile file written in the earlier flat format SHALL be converted to the current format, and
rewritten in place, the first time any command loads it.  The conversion SHALL print nothing and
SHALL keep every setting the earlier file held: email, region, key pair name, account bucket, SSH
transport, the AWS credentials (an AWS CLI profile name, or an access key and secret), and the
Tailscale and AxonOps settings.  When the earlier file names both an AWS CLI profile and an access
key, the AWS CLI profile SHALL be kept.

A file the conversion rewrites SHALL be replaced whole, never left half written, and SHALL be
readable by its owner only.

#### Scenario: Earlier file is converted on first load

- **GIVEN** a profile file in the earlier flat format with email, region, key pair, bucket and an AWS CLI profile name
- **WHEN** the user runs any command that loads the profile
- **THEN** the command runs with those settings and prints nothing about the conversion
- **AND** the file on disk is now in the current format and holds the same settings

#### Scenario: Earlier file with no credentials is not converted

- **GIVEN** a profile file in the earlier format that holds neither an AWS CLI profile name nor an access key and secret
- **WHEN** the user runs a command that requires a profile
- **THEN** the profile reads as not set up and profile setup runs, prefilled with the values the file holds

#### Scenario: A half-configured optional feature stays off

- **GIVEN** an earlier file that sets the Tailscale client id but not its secret
- **WHEN** the profile is converted
- **THEN** the converted profile has Tailscale disabled, as the earlier file did

## MODIFIED Requirements

### Requirement: Profile Setup

The system MUST provide an interactive workflow to create a user profile for one destination.  For
the AWS destination it collects AWS credentials, region, and key pair configuration.

The workflow SHALL be reached as `easy-db-lab profile setup`. The former top-level names
`setup-profile` and `setup` SHALL NOT resolve.

#### Scenario: First-time user runs setup

- **GIVEN** a first-time user
- **WHEN** they run `easy-db-lab profile setup`
- **THEN** setup states the destination, then prompts for the AWS profile or credentials, region, and key pair selection.

#### Scenario: A profile that does not exist yet is created

- **GIVEN** a profile name with no profile directory (`EASY_DB_LAB_PROFILE=<new>`)
- **WHEN** the user runs `easy-db-lab profile setup`
- **THEN** it prompts and completes, without a "User configuration file not found" error.

#### Scenario: Existing profile reviewed on re-run

- **GIVEN** a completed profile: it can authenticate and holds a key pair and an S3 bucket
- **WHEN** the user runs `easy-db-lab profile setup` again
- **THEN** existing settings are available for review and modification.
- **AND** the profile's S3 bucket and key pair gain any marker tag (`easy_db_lab`, `easy_cass_lab`) they lack, keeping their other tags; a key pair without the legacy marker is left alone. This runs before the review prompts and needs working credentials; a failure, or a key pair the profile names that no longer exists, stops setup before any setting changes.

#### Scenario: Incomplete profile finishes setup

- **GIVEN** a profile that can authenticate but has a blank key pair or a blank S3 bucket
- **WHEN** the user runs `easy-db-lab profile setup`
- **THEN** the full setup runs: it keeps the values the profile holds, asks only for what is missing (an AWS profile name the profile already holds is not asked again), and creates the missing key pair, IAM roles, bucket and Packer VPC
- **AND** the saved profile holds the new key pair name and account bucket.

#### Scenario: Former top-level names no longer resolve

- **WHEN** the user runs `easy-db-lab setup-profile` or `easy-db-lab setup`
- **THEN** the CLI does not resolve either as a top-level command.

#### Scenario: Guidance names the current command

- **GIVEN** any CLI message that directs the user to configure their profile
- **WHEN** that message is shown
- **THEN** it names `easy-db-lab profile setup`.
