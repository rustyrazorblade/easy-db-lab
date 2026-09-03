## Why

Users whose AWS access is provisioned through AWS SSO (IAM Identity Center) cannot use easy-db-lab today. When a named profile backed by an SSO session (`sso_session` / `sso_start_url`) is configured, credential resolution fails at runtime because the AWS SDK v2 `sso` and `ssooidc` modules are not on the classpath. SSO is increasingly the only credential mechanism available in managed AWS organizations, so the tool is unusable for those users without out-of-band workarounds to obtain static keys.

## What Changes

- Add the AWS SDK v2 `sso` and `ssooidc` modules as dependencies and include them in the `awssdk` bundle so `ProfileCredentialsProvider` can resolve SSO sessions from the local token cache.
- No change to credential-selection logic for runtime AWS SDK clients: the existing profile path in `AWSModule.kt` already routes a configured profile name through `ProfileCredentialsProvider`. With the modules present, SSO profiles flow through unchanged.
- **Fix AMI building under SSO.** AMI builds shell out to Packer in a Docker container, which resolves AWS credentials independently of the JVM SDK. Today `AWSCredentialsManager` writes a static-key-only credentials file (`aws_access_key_id` / `aws_secret_access_key`) that is blank under SSO. Change it to resolve credentials through the SDK `AwsCredentialsProvider` (which now handles SSO) and write the resolved `aws_access_key_id`, `aws_secret_access_key`, and — when present — `aws_session_token`, so Packer authenticates with the same credentials as the rest of the tool.
- Document the SSO setup flow (configure an SSO profile, run `aws sso login --profile <name>`, set that profile name in `setup-profile`) in the getting-started docs.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `setup`: adds a requirement that a configured AWS profile backed by an SSO session resolves credentials successfully, not only profiles holding static keys.

## Impact

- **Dependencies**: `gradle/libs.versions.toml` — two new library entries (`awssdk-sso`, `awssdk-ssooidc`) added to the `awssdk` bundle. These pull additional AWS SDK v2 artifacts onto the runtime classpath.
- **Code**: No change to `providers/aws/AWSModule.kt` credential logic. The classpath addition alone enables SSO resolution via the existing `ProfileCredentialsProvider` path for runtime SDK clients. `providers/aws/AWSCredentialsManager.kt` is changed to resolve credentials through the SDK provider (instead of reading static keys off `User`) and emit a session token when one is present; `containers/Packer.kt` passes the provider into it.
- **Docs**: `docs/getting-started/setup.md` gains an AWS SSO section.
- **Out of scope**: No support for env-var/instance-profile credential chains, no `DefaultCredentialsProvider` migration, no `aws sso login` orchestration from within the tool — users authenticate their SSO session externally.
