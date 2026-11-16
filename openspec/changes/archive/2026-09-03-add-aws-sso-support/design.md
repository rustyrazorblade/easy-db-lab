## Context

easy-db-lab authenticates to AWS using AWS SDK v2. The credential provider is constructed in `providers/aws/AWSModule.kt:73-84`:

- If `User.awsProfile` is non-empty → `ProfileCredentialsProvider.create(profile)`
- Otherwise → `StaticCredentialsProvider` from `awsAccessKey` / `awsSecret`

`ProfileCredentialsProvider` in SDK v2 is fully capable of resolving an SSO-backed profile (`sso_session` / `sso_start_url` in `~/.aws/config`, with a token cached by `aws sso login`). The only thing missing is the runtime machinery: the `sso` and `ssooidc` SDK modules. The `awssdk` bundle in `gradle/libs.versions.toml` currently lists only ec2, iam, emr, s3, sts, opensearch, and ecr. Without `sso`/`ssooidc` on the classpath, resolving an SSO profile throws a hard error at runtime (the SDK reports the `sso` module must be on the class path); it does not fall back.

The fix is therefore a dependency/classpath change, not a credential-logic change.

## Goals / Non-Goals

**Goals:**
- An AWS profile backed by an SSO session resolves credentials successfully through the existing profile path.
- No change to credential-selection logic in `AWSModule.kt`.
- Document the SSO setup flow for users.

**Non-Goals:**
- Migrating to `DefaultCredentialsProvider` or adding env-var / instance-profile credential chains for the CLI itself.
- Orchestrating `aws sso login` from inside the tool — users establish the SSO session externally.
- Any change to how credentials are delivered to provisioned EC2 nodes (instance profiles are unaffected).

## Decisions

**Decision: Add `awssdk-sso` and `awssdk-ssooidc` to the `awssdk` bundle.**
Both modules share the existing `awssdk` version ref (2.42.33), so no version pinning is introduced. Adding them to the bundle means every consumer of the bundle (the root module) gets them on the runtime classpath, which is exactly where SSO resolution needs them.
- *Alternative considered: add only `sso`.* Rejected — SSO token resolution requires `ssooidc` for the OIDC token exchange; `sso` alone is insufficient and would still fail.
- *Alternative considered: switch to `DefaultCredentialsProvider`.* Rejected as out of scope — it broadens behavior (env vars, IMDS) beyond what was requested and changes the explicit, predictable credential selection the tool relies on. The existing `ProfileCredentialsProvider` path already handles SSO once the modules are present.

**Decision: No code change to credential selection.**
The profile branch already routes SSO profiles correctly. Keeping logic untouched minimizes risk and keeps the change to a dependency edit plus docs.

## Risks / Trade-offs

- **Risk: larger dependency footprint / transitive conflicts.** → Both modules are first-party AWS SDK v2 artifacts on the same version as the rest of the bundle; conflict risk is minimal. `./gradlew build` and the existing test suite verify the classpath resolves.
- **Risk: SSO token expiry produces confusing runtime errors mid-run.** → Out of scope to auto-refresh; documented in setup docs that users must have an active `aws sso login` session. The SDK error message names the remedy.
- **Trade-off: no automated test can fully exercise live SSO** (requires a real IdP + browser login). → Coverage is limited to verifying the modules are present/resolvable and that the profile path is selected; the SSO round-trip is validated manually.

## Migration Plan

Not applicable — clusters are ephemeral and there is no persisted state tied to credentials. The change is additive (new classpath entries); rollback is reverting the `libs.versions.toml` edit.
