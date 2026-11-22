## Why

A profile exists to hold one destination and its credentials.  Today the profile file
(`profiles/<name>/settings.yaml`) is a flat list of AWS fields, read with Jackson into `User`.  It
cannot say which destination it belongs to, so the planned targets (AWS EKS, GCP, Azure, an existing
Kubernetes cluster) have nowhere to put their settings.  The owner decided (2026-11-22) that each
profile belongs to exactly one destination, that no CLI flag picks or overrides it, and that the
work starts with profile setup and a new file format.  Old profiles migrate silently on first load.

This is Phase 1 of the multi-cloud plan (#1062 (provider-agnostic epic)).  Only AWS is complete, so
only AWS is offered.

## What Changes

- **New profile format.**  `settings.yaml` holds `email`, a `destination` block with a type
  discriminator (`aws` today), and optional `tailscale` and `axonOps` blocks.  AWS credentials are
  one of two forms: an AWS CLI profile name, or an access key and secret.  It is read and written
  with kotlinx.serialization (kaml), not Jackson, and written atomically with owner-only permissions.
- **Silent migration.**  A file in the earlier flat format (no `destination` key) is converted to the
  new format and rewritten in place when it is first loaded.  Nothing is printed.  A file without
  credentials (setup was interrupted) is not converted and reads as not set up.
- **Setup names the destination.**  `profile setup` states the destination before it asks for
  credentials.  With only AWS available it asks nothing and prints `Destination: AWS`.  Re-running
  setup reviews the existing destination and never offers another one.  The AWS prompts and the AWS
  resource steps are unchanged.
- **`profile show`** prints the destination and the destination's fields, and still never prints a
  secret.
- `User` is removed.  Callers take the new profile type; AWS code takes the AWS destination.
- `@RequireProfileSetup` keeps its meaning: every command that carries it requires a set-up profile.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `setup`: the profile records one destination; setup names it; the earlier format migrates.
- `profile-command-group`: `profile show` reports the destination and the new field names.

## Impact

- `configuration/User.kt`, `configuration/UserConfigProvider.kt`: replaced by `configuration/profile/`
  (`Profile`, `Destination`, `AwsCredentials`, `ProfileStore`, `LegacyProfileMigration`).
- `di/ContextModule.kt`: binds `ProfileStore`, `Profile` and `Destination.Aws`.
- `providers/aws/AwsClientsModule.kt`: region and credentials come from `Destination.Aws`.
- `commands/profile/SetupProfile.kt`, `commands/profile/ProfileShow.kt`, `commands/Init.kt` (InitConfig
  reads the region from the destination), and the callers of the `User` fields (main: about 150 sites
  across region, s3Bucket, keyName, sshTransport, awsProfile, Tailscale and AxonOps; 35 test files).
- `services/aws/AwsS3BucketService.kt` and `services/aws/AwsKeyPairService.kt`: save through `ProfileStore`.
- Docs: `docs/getting-started/setup.md`, `docs/reference/commands.md`, `configuration/CLAUDE.md`,
  `commands/CLAUDE.md`.
- No AMI bake.  `requires-aws`: setup and every AWS client read the migrated profile.
