# Setup

## Purpose

Manages user profile creation, AWS credential configuration, and IAM resource initialization required before cluster provisioning.
## Requirements

### REQ-SU-001: Profile Setup

The system MUST provide an interactive workflow to create a user profile with AWS credentials, region, and key pair configuration.

#### Scenario: First-time user runs setup

- **GIVEN** a first-time user
- **WHEN** they run the setup workflow
- **THEN** they are prompted for AWS profile or credentials, region, and key pair selection.

#### Scenario: Existing profile reviewed on re-run

- **GIVEN** a completed profile
- **WHEN** the user runs setup again
- **THEN** existing settings are available for review and modification.

### REQ-SU-002: AWS Resource Initialization

The system MUST initialize required AWS resources (IAM roles, S3 bucket, VPC) as part of setup.

#### Scenario: Resources created on setup completion

- **GIVEN** valid AWS credentials
- **WHEN** the user completes setup
- **THEN** IAM roles, an S3 bucket, and networking resources are created or verified.

#### Scenario: Missing resources created and existing ones reused

- **GIVEN** partially configured AWS resources
- **WHEN** setup runs
- **THEN** missing resources are created and existing ones are reused.

### REQ-SU-003: IAM Policy Visibility

The system MUST allow users to view the IAM policies required for operation.

#### Scenario: User views required IAM policies

- **GIVEN** a user troubleshooting permissions
- **WHEN** they request IAM policy display
- **THEN** the required policies are shown with account-specific values substituted.

### REQ-SU-004: AWS Reconfiguration

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

