# Setup

## Purpose

Manages user profile creation, AWS credential configuration, and IAM resource initialization required before cluster provisioning.
## Requirements
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

### Requirement: AWS Resource Initialization

The system MUST initialize required AWS resources (IAM roles, S3 bucket, VPC) as part of setup.

#### Scenario: Resources created on setup completion

- **GIVEN** valid AWS credentials
- **WHEN** the user completes setup
- **THEN** IAM roles, an S3 bucket, and networking resources are created or verified.

#### Scenario: Missing resources created and existing ones reused

- **GIVEN** partially configured AWS resources
- **WHEN** setup runs
- **THEN** missing resources are created and existing ones are reused.

### Requirement: IAM Policy Visibility

The system MUST allow users to view the IAM policies required for operation. The displayed
policies MUST include the permissions needed to open SSM Session Manager sessions to cluster
instances.

#### Scenario: User views required IAM policies

- **GIVEN** a user troubleshooting permissions
- **WHEN** they request IAM policy display
- **THEN** the required policies are shown with account-specific values substituted.

#### Scenario: Displayed policies cover SSM sessions

- **GIVEN** a user requesting IAM policies from an administrator
- **WHEN** they display the required policies
- **THEN** the policies grant starting SSM sessions, using the SSH and port-forwarding session documents, only to the account's instances tagged `easy_cass_lab=1`
- **AND** they grant terminating and resuming only the user's own sessions

### Requirement: AWS Reconfiguration

The system MUST allow reconfiguring AWS resources without full profile re-setup.

#### Scenario: Reconfigure without changing credentials

- **GIVEN** an existing profile
- **WHEN** the user reconfigures AWS resources
- **THEN** IAM roles and infrastructure are updated without modifying credential settings.

### Requirement: AWS SSO credential resolution

When a user profile is configured with the name of an AWS profile backed by an SSO session (IAM Identity Center), the system MUST resolve AWS credentials through that profile. The system MUST NOT require static access keys when a valid SSO-backed profile is configured and an active SSO login exists.

#### Scenario: SSO-backed profile resolves credentials
- **WHEN** a user configures their profile with the name of an AWS profile backed by an SSO session and has an active SSO login
- **THEN** AWS operations authenticate using the SSO-derived credentials without requiring static access keys

#### Scenario: SSO session not logged in
- **WHEN** a user configures an SSO-backed profile but has no active SSO login (no cached token)
- **THEN** the tool surfaces the AWS SDK authentication error directing the user to log in (e.g. `aws sso login --profile <name>`)

### Requirement: AMI building uses resolved credentials

AMI building (which runs Packer in a container with its own credential resolution) MUST authenticate using credentials resolved through the same AWS credential provider as the rest of the tool, not static access keys read directly from saved configuration. When the resolved credentials are temporary (carry a session token), the credentials supplied to the Packer container MUST include that session token.

#### Scenario: Build under SSO supplies session token
- **WHEN** AMI building runs while the active profile resolves to temporary SSO credentials
- **THEN** the credentials file provided to Packer contains the access key, secret, and session token, allowing the build to authenticate

#### Scenario: Build under static keys omits session token
- **WHEN** AMI building runs while the active profile resolves to long-lived static credentials
- **THEN** the credentials file provided to Packer contains the access key and secret with no session token line

### Requirement: Setup tolerates IAM propagation delay

Applying the S3 bucket policy (whose principals are the just-created IAM roles) MUST be resilient to IAM eventual consistency. When S3 rejects the policy because a role is not yet visible ("Invalid principal in policy"), the operation MUST retry with backoff rather than failing immediately. Permission errors (403) MUST NOT be retried. If the operation ultimately fails for the propagation reason, the surfaced error MUST explain the cause and direct the user to re-run setup, not expose a raw SDK exception.

#### Scenario: Transient invalid-principal error is retried
- **WHEN** the S3 bucket policy is applied while the referenced IAM role has not yet propagated
- **THEN** the application retries with backoff and succeeds once the role becomes visible

#### Scenario: Permission error is not retried
- **WHEN** applying the S3 bucket policy fails with a 403 permission error
- **THEN** the operation fails immediately without retrying

#### Scenario: Persistent propagation failure gives clear guidance
- **WHEN** the bucket policy still cannot be applied after retries because the role is not visible
- **THEN** the user sees a message explaining the IAM propagation delay and advising a re-run, not a raw S3 exception

### Requirement: Packer SSH access is scoped to the developer's IP

The Packer AMI-build security group MUST allow SSH only from the developer's own public IP (a `/32`), not from `0.0.0.0/0`. This matches the cluster path and avoids managed-account governance tools (which revoke world-open SSH rules) stripping the rule out from under a build. The developer's public IP MUST be resolved through a single shared service used by both the cluster and Packer paths.

#### Scenario: Packer security group restricts SSH to the developer IP
- **WHEN** AMI-build infrastructure is created or ensured
- **THEN** the security group's SSH ingress rule allows only the developer's public IP `/32`, not `0.0.0.0/0`

### Requirement: Instance role supports SSM Session Manager

The cluster instance role (`EasyDBLabEC2Role`) MUST carry an inline Session Manager policy
granting `ssm:UpdateInstanceInformation`, `ssmmessages:CreateControlChannel`,
`ssmmessages:CreateDataChannel`, `ssmmessages:OpenControlChannel` and
`ssmmessages:OpenDataChannel`, regardless of the profile's SSH transport, so that any cluster node
or AMI builder can be reached over SSM Session Manager. The role MUST NOT be given the managed
`AmazonSSMManagedInstanceCore` policy, which also grants Parameter Store reads on every parameter.

#### Scenario: New role receives the SSM policy

- **GIVEN** a profile whose instance role does not yet exist
- **WHEN** setup creates the role
- **THEN** the Session Manager inline policy is put on it

#### Scenario: Existing role receives the SSM policy on up

- **GIVEN** an instance role created before this policy was required
- **WHEN** the user runs `up`
- **THEN** the Session Manager inline policy is put on the role before instances are launched
- **AND** putting it again when already present succeeds

#### Scenario: Existing role receives the SSM policy before an AMI build

- **GIVEN** an instance role that is otherwise valid but lacks the Session Manager inline policy
- **WHEN** the user runs `build-base` or `build-cassandra`
- **THEN** the Session Manager inline policy is put on the role before the builder instance launches

### Requirement: Profile setup offers the SSH transport

The profile setup workflow MUST let the user choose the SSH transport (`direct` or `ssm`), default
`direct`, and the profile display MUST show the configured transport.

#### Scenario: User selects SSM during setup

- **GIVEN** a user running profile setup
- **WHEN** they answer `ssm` to the SSH transport prompt
- **THEN** the profile's SSH transport is saved as `ssm`

#### Scenario: Unrecognized transport is rejected

- **GIVEN** a user running profile setup
- **WHEN** they answer the SSH transport prompt with a value other than `direct` or `ssm`
- **THEN** the value is rejected and the user is asked again

#### Scenario: Unrecognized saved transport

- **GIVEN** a profile file whose saved SSH transport is neither `direct` nor `ssm`
- **WHEN** any command loads the profile
- **THEN** loading fails with an error naming the bad value and the valid choices
- **AND** profile setup reports the bad value, keeps the profile's other values, and asks for the transport again

#### Scenario: Profile display shows the transport

- **GIVEN** a configured profile
- **WHEN** the user displays the profile
- **THEN** the SSH transport is shown

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

#### Scenario: A file system that cannot keep the profile owner-only stops the save

- **GIVEN** the profile directory is on a file system with no POSIX permissions
- **WHEN** any command saves the profile
- **THEN** the save fails, names the profile file, says the file system cannot make it owner-only, and says to put the profile directory on a file system with POSIX permissions
- **AND** no profile file is written

#### Scenario: Every command that needs a profile still requires one

- **GIVEN** a profile name with no profile file
- **WHEN** the user runs any command that requires a profile
- **THEN** the interactive profile setup runs first, as before

### Requirement: Earlier profile files migrate silently

The first command that loads a profile SHALL convert an earlier flat-format file, when the profile has
no file in the current format, into a new file in the current format.
The conversion SHALL print nothing.  It SHALL keep every setting the earlier file held: email,
region, key pair name, account bucket, SSH transport, the AWS credentials (an AWS CLI profile name,
or an access key and secret), and the Tailscale and AxonOps values.  When the earlier file names both
an AWS CLI profile and an access key, the AWS CLI profile SHALL be kept.

The conversion SHALL NOT write, move or delete the earlier file, so a build from an earlier release
keeps working on the same profile.  The new file SHALL be written whole, never left half written,
and SHALL be readable by its owner only.

#### Scenario: Earlier file is converted on first load

- **GIVEN** a profile with only an earlier-format file holding email, region, key pair, bucket and an AWS CLI profile name
- **WHEN** the user runs any command that loads the profile
- **THEN** the command runs with those settings and prints nothing about the conversion
- **AND** a current-format file now exists and holds the same settings
- **AND** the earlier file is unchanged, byte for byte

#### Scenario: Earlier file with no credentials is not converted

- **GIVEN** a profile with only an earlier-format file that holds neither an AWS CLI profile name nor an access key and secret
- **WHEN** the user runs a command that requires a profile
- **THEN** the profile reads as not set up and profile setup runs, prefilled with the values the file holds

#### Scenario: Earlier file with an unrecognized SSH transport

- **GIVEN** a profile with only an earlier-format file whose saved SSH transport is neither `direct` nor `ssm`
- **WHEN** any command loads the profile
- **THEN** loading fails with an error naming the bad value and the valid choices
- **AND** profile setup reports the bad value, keeps the file's other values, and asks for the transport again

#### Scenario: Current-format file with an unrecognized SSH transport

- **GIVEN** a profile whose current-format file has an SSH transport that is neither `direct` nor `ssm`
- **WHEN** any command loads the profile
- **THEN** loading fails with an error naming the bad value and the valid choices
- **AND** profile setup reports the bad value, keeps the file's other values, and asks for the transport again

#### Scenario: A half-configured optional feature stays off and keeps its value

- **GIVEN** an earlier file that sets the Tailscale client id but not its secret
- **WHEN** the profile is converted
- **THEN** the converted profile keeps the client id and has Tailscale disabled, as the earlier file did

### Requirement: One account bucket per profile

Every operation that needs the profile's account bucket or key pair name SHALL read it from the saved
profile at the time it runs, including operations that started before the value was saved.  When the
profile names no bucket, the first such operation SHALL create one and save it to the profile, and
every later operation in the same or another command SHALL use that bucket.  A save SHALL change only
the setting it names and keep every value another command saved.

#### Scenario: A bucket created during setup is used by the image build in the same run

- **GIVEN** a new profile that names no account bucket and no key pair
- **WHEN** profile setup creates the key pair and the bucket and then builds the machine image
- **THEN** the image build uses that bucket and that key pair

#### Scenario: Two commands saving different settings keep both

- **GIVEN** two commands running at once on one profile
- **WHEN** one saves a new key pair name and the other saves a new account bucket
- **THEN** the saved profile holds both values

#### Scenario: Two operations in one command share one new bucket

- **GIVEN** a profile that names no account bucket
- **WHEN** one command runs two operations that each need the account bucket
- **THEN** exactly one bucket is created
- **AND** the profile names that bucket

