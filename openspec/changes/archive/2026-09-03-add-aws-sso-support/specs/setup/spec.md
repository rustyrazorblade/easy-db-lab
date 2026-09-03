## ADDED Requirements

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
