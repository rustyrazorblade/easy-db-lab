# Profile Command Group Spec

## ADDED Requirements

### Requirement: `profile` top-level parent command
The CLI SHALL expose a `profile` top-level parent command that groups profile inspection and setup
operations. When invoked without a subcommand, `profile` SHALL print its help text and exit 0.

#### Scenario: `profile` with no subcommand prints help
- **WHEN** the user runs `easy-db-lab profile`
- **THEN** the CLI prints the help text listing the `show` and `setup` subcommands
- **AND** exits with status 0

### Requirement: `profile show` reports the active profile
The CLI SHALL expose a `profile show` subcommand that reports the active profile's name, its
absolute directory, and the `email`, `region`, `keyName`, `awsProfile`, and `s3Bucket` settings.

The report SHALL be written with `println()` from a single multiline string. No `Event` SHALL be
emitted for the report content.

#### Scenario: Configured profile reports its settings
- **GIVEN** a profile whose `settings.yaml` sets `email`, `region`, `keyName`, `awsProfile`, and `s3Bucket`
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output contains the profile name, the absolute profile directory, and all five values
- **AND** no `Event` is emitted for the report content

### Requirement: `profile show` never prints secret values
`profile show` SHALL NOT print the value of `awsAccessKey`, `awsSecret`, `axonOpsKey`,
`tailscaleClientId`, or `tailscaleClientSecret`. The report builder SHALL NOT call any masking
helper, so that no partial or truncated form of a secret can reach the output.

#### Scenario: Secret values are absent from the report
- **GIVEN** `awsAccessKey`, `awsSecret`, `axonOpsKey`, `tailscaleClientId`, and `tailscaleClientSecret` are each set to a known non-empty value
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output contains none of those five values

### Requirement: `profile show` reports AxonOps and Tailscale as flags
`profile show` SHALL report AxonOps and Tailscale as `ENABLED` or `DISABLED` rather than printing
their credentials.

Each flag SHALL be determined by calling the shared predicate on `User` rather than re-deriving the
rule — `User.isAxonOpsEnabled()` and `User.isTailscaleEnabled()` respectively. A flag SHALL report
`ENABLED` only where the feature will actually run: AxonOps requires **both** `axonOpsOrg` and
`axonOpsKey` to be non-blank, matching what `Up` and `cassandra start` require before they enable
it. A report that asserted a capability the next command would silently skip would be worse than no
report at all.

#### Scenario: AxonOps enabled when both the org and the key are present
- **GIVEN** `axonOpsOrg` and `axonOpsKey` are both non-blank
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports AxonOps as `ENABLED`

#### Scenario: AxonOps disabled when either credential is missing
- **GIVEN** either `axonOpsOrg` or `axonOpsKey` is empty or blank
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports AxonOps as `DISABLED`

#### Scenario: Tailscale enabled when both credentials are present
- **GIVEN** `tailscaleClientId` and `tailscaleClientSecret` are both non-empty
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports Tailscale as `ENABLED`

#### Scenario: Tailscale disabled when either credential is missing
- **GIVEN** either `tailscaleClientId` or `tailscaleClientSecret` is empty or blank
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports Tailscale as `DISABLED`

### Requirement: `profile show` requires no cluster workspace
`profile show` SHALL run in any working directory. It SHALL NOT load cluster state and SHALL NOT
carry any requirement annotation that would trigger interactive setup.

#### Scenario: Runs outside a cluster workspace
- **GIVEN** the working directory holds no `state.json`, `env.sh`, or `sshConfig`
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the command exits 0 and prints the full report
- **AND** no cluster state is loaded and no cluster-state error is raised

### Requirement: `profile show` reports an unconfigured profile
When the active profile has no `settings.yaml`, `profile show` SHALL report that the profile is not
configured and name `easy-db-lab profile setup`. It SHALL NOT launch the interactive setup flow and
SHALL NOT throw.

#### Scenario: Missing settings.yaml reports not-configured
- **GIVEN** the active profile's `settings.yaml` does not exist
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI prints that the profile is not configured and names `easy-db-lab profile setup`
- **AND** the interactive setup flow does not run and no exception is raised

### Requirement: `profile show` reports a malformed profile
When the active profile's `settings.yaml` exists but cannot be deserialized, `profile show` SHALL
report the profile as present but unreadable, naming the file path and `easy-db-lab profile setup`.
It SHALL NOT surface a raw deserialization error.

#### Scenario: Truncated settings.yaml is reported, not thrown
- **GIVEN** the active profile's `settings.yaml` exists but omits a field that has no default
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI reports the profile as present but unreadable and names the file path and `easy-db-lab profile setup`
- **AND** no raw deserialization error is shown

### Requirement: `profile show` honors the active profile selection
`profile show` SHALL report the profile named by `EASY_DB_LAB_PROFILE`, reading its values from that
profile's own directory.

#### Scenario: Non-default profile is reported
- **GIVEN** `EASY_DB_LAB_PROFILE` is set to `staging` and that profile's `settings.yaml` exists
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the report names `staging` and prints values read from the `staging` profile directory

### Requirement: `profile setup` subcommand
The CLI SHALL expose `profile setup`, running the same interactive profile setup workflow
previously reached through `setup-profile`.

#### Scenario: `profile setup` runs the setup workflow
- **WHEN** the user runs `easy-db-lab profile setup`
- **THEN** the interactive profile setup workflow runs as it did under the previous command name

### Requirement: A workspace directory named `profile` does not register as a kit
Dynamic kit registration SHALL skip a workspace directory whose name collides with an existing
top-level command, so a directory named `profile` holding a `kit.yaml` does not shadow the profile
command group.

#### Scenario: A `profile` kit directory is skipped
- **GIVEN** the working directory contains a subdirectory named `profile` holding a `kit.yaml`
- **WHEN** the user runs `easy-db-lab profile`
- **THEN** the profile command group runs and the directory is not registered as a kit command
