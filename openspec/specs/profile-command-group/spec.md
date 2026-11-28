# profile-command-group Specification

## Purpose
TBD - created by archiving change issue-892. Update Purpose after archive.
## Requirements
### Requirement: `profile` top-level parent command
The CLI SHALL expose a `profile` top-level parent command that groups profile inspection and setup
operations. When invoked without a subcommand, `profile` SHALL print its help text and exit 0.

#### Scenario: `profile` with no subcommand prints help
- **WHEN** the user runs `easy-db-lab profile`
- **THEN** the CLI prints the help text listing the `show` and `setup` subcommands
- **AND** exits with status 0

### Requirement: `profile show` reports the active profile
The CLI SHALL expose a `profile show` subcommand that reports the active profile's name, its
absolute directory, its `email`, its destination, and the destination's settings.  For the AWS
destination these are the region, the credential form (`aws-profile` with the AWS CLI profile name,
or `access-key`), the key pair name, the account bucket, and the SSH transport.

The report SHALL be written with `println()` from a single multiline string. No `Event` SHALL be
emitted for the report content.

#### Scenario: Configured profile reports its settings
- **GIVEN** an AWS profile that sets the email, region, an AWS CLI profile name, key pair name and account bucket
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output contains the profile name, the absolute profile directory, the destination `aws`, and each of those values
- **AND** no `Event` is emitted for the report content

#### Scenario: A profile in the earlier format is reported after conversion
- **GIVEN** the active profile's file is in the earlier flat format
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the report shows the same values the earlier file held, with the destination `aws`

### Requirement: `profile show` never prints secret values
`profile show` SHALL NOT print the value of the AWS access key id, the AWS secret access key, the
AxonOps key, the Tailscale client id, or the Tailscale client secret. The report builder SHALL NOT
call any masking helper, so that no partial or truncated form of a secret can reach the output.

#### Scenario: Secret values are absent from the report
- **GIVEN** the AWS access key id and secret, the AxonOps key, and the Tailscale client id and secret are each set to a known non-empty value
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output contains none of those five values
- **AND** it reports the credential form as `access-key`

### Requirement: `profile show` reports AxonOps and Tailscale as flags
`profile show` SHALL report AxonOps and Tailscale as `ENABLED` or `DISABLED` rather than printing
their credentials.

Each flag SHALL be determined by the same shared rule that `Up` and `cassandra start` use, rather
than re-deriving it. A flag SHALL report `ENABLED` only where the feature will actually run: AxonOps
requires **both** the organization and the key to be non-blank, and Tailscale requires both the
client id and the client secret to be non-blank. A report that asserted a capability the next
command would silently skip would be worse than no report at all.

#### Scenario: AxonOps enabled when both the org and the key are present
- **GIVEN** the AxonOps organization and key are both non-blank
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports AxonOps as `ENABLED`

#### Scenario: AxonOps disabled when either credential is missing
- **GIVEN** either the AxonOps organization or key is empty or blank
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports AxonOps as `DISABLED`

#### Scenario: Tailscale enabled when both credentials are present
- **GIVEN** the Tailscale client id and client secret are both non-blank
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the output reports Tailscale as `ENABLED`

#### Scenario: Tailscale disabled when either credential is missing
- **GIVEN** either the Tailscale client id or client secret is empty or blank
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
`profile show` SHALL report, when the active profile has neither a current-format profile file nor an
earlier-format `settings.yaml`, that the profile is not configured and name
`easy-db-lab profile setup`. It SHALL NOT launch the interactive setup flow and SHALL NOT throw.

#### Scenario: No profile file reports not-configured
- **GIVEN** the active profile has no profile file in either format
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI prints that the profile is not configured and names `easy-db-lab profile setup`
- **AND** the interactive setup flow does not run and no exception is raised

### Requirement: `profile show` reports a malformed profile
When the active profile's file exists but cannot be used, `profile show` SHALL report the profile as
present but unreadable, naming the failing file path, the reason and one hint. It SHALL NOT surface a
raw deserialization error or any text from the file.
`profile setup` reads the raw values of either file, so it repairs a value that does not decode, but
it parses both files and checks every key first. The hint SHALL name `easy-db-lab profile setup` only
for a value that does not decode; for an unknown key it SHALL say to fix or remove that key in the
file, and for a file that is not valid YAML or cannot be read, written or locked, or that names a
destination type this version does not know, to fix the file. Setup SHALL NOT rebuild a profile whose
destination type this version does not know.
The path and the hint SHALL each appear once.

#### Scenario: Truncated profile file is reported, not thrown
- **GIVEN** the active profile's current-format file exists but omits a field that has no default
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI reports the profile as present but unreadable and names the file path and `easy-db-lab profile setup`
- **AND** no raw deserialization error is shown

#### Scenario: An unknown key is reported without the setup hint
- **GIVEN** the active profile's current-format file holds a key that no field declares
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI names the file path and the reason, and says "Fix or remove that key in the file"
- **AND** it does not name `easy-db-lab profile setup`

#### Scenario: An unknown destination type is reported without the setup hint
- **GIVEN** the active profile's current-format file names a destination type this version does not register
- **WHEN** the user runs `easy-db-lab profile show` or `easy-db-lab profile setup`
- **THEN** the CLI names the file path, the type when it is a short identifier, and the known types, and says "Fix the file"
- **AND** setup changes nothing and creates no resources

#### Scenario: A file that is not valid YAML is reported without the setup hint
- **GIVEN** the active profile's current-format file, or its earlier-format file, is not valid YAML
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI names the file path and the reason, and says "Fix the file"
- **AND** it does not name `easy-db-lab profile setup`

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

