## MODIFIED Requirements

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
file, and for a file that is not valid YAML or cannot be read, written or locked, to fix the file.
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

#### Scenario: A file that is not valid YAML is reported without the setup hint
- **GIVEN** the active profile's current-format file, or its earlier-format file, is not valid YAML
- **WHEN** the user runs `easy-db-lab profile show`
- **THEN** the CLI names the file path and the reason, and says "Fix the file"
- **AND** it does not name `easy-db-lab profile setup`
