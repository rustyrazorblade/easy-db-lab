## Why

A profile exists to hold one destination and its credentials.  Today the profile file
(`profiles/<name>/settings.yaml`) is a flat list of AWS fields, read with Jackson into `User`.  It
cannot say which destination it belongs to, so the planned targets (an existing Kubernetes cluster,
AWS EKS, GCP, Azure) have nowhere to put their settings.  The owner decided (2026-11-22) that each
profile belongs to exactly one destination, that no CLI flag picks or overrides it, and that the
work starts with profile setup and a new file format.  Old profiles migrate silently on first load.

This is Phase 1 of the multi-cloud plan (#1062 (provider-agnostic epic)).  Only AWS is complete, so
only AWS is offered.

## What Changes

- **New profile file.**  `profiles/<name>/profile.yaml` holds `email`, a `destination` block with a
  `type` discriminator (`aws` today) and a `location`, and optional `tailscale` and `axonOps` blocks.
  AWS credentials take one of two forms: an AWS CLI profile name, or an access key and secret.  It is
  read and written with kotlinx.serialization (kaml), written atomically, readable by its owner only.
- **Silent migration, earlier file left alone.**  When `profile.yaml` does not exist and
  `settings.yaml` does, the earlier file is converted into `profile.yaml` on first load.  Nothing is
  printed.  `settings.yaml` is never written or deleted, so builds from earlier releases keep working
  on the same profile.  An earlier file without credentials (setup was interrupted) is not converted
  and reads as not set up.
- **Setup names the destination.**  `profile setup` states the destination before it asks for
  credentials.  With only AWS available it asks nothing and prints `Destination: AWS`.  Re-running
  setup reviews the existing destination and never offers another one.  The AWS prompts and the AWS
  resource steps are unchanged.
- **One account bucket.**  Every caller that ensures the account bucket reads the bucket name from the
  profile store, so a bucket made by one caller is seen by the next and only one is created.
- **`profile show`** prints the destination and its fields, and still never prints a secret.
- `User` and the Jackson profile reader are removed.  Callers take the new profile type; AWS code
  takes the AWS destination.
- `@RequireProfileSetup` keeps its meaning: every command that carries it requires a set-up profile.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `setup`: the profile records one destination; setup names it; the earlier file migrates; one
  account bucket.
- `profile-command-group`: `profile show` reports the destination and the new field names, and
  handles both files.

## Impact

- `configuration/User.kt`, `configuration/UserConfigProvider.kt`: replaced by `configuration/profile/`
  (`Profile`, `Destination`, `AwsDestination`, `AwsLocation`, `AwsCredentials`, `ProfileStore`,
  `LegacyProfileMigration`).
- `di/ContextModule.kt` binds `ProfileStore` and `Profile`; `providers/aws/AwsClientsModule.kt` binds
  `AwsDestination` and builds the region and credentials from it.
- `commands/profile/SetupProfile.kt`, `commands/profile/ProfileShow.kt`, `commands/Init.kt`
  (`InitConfig.fromInit`), `services/CommandExecutor.kt` (setup check), `containers/Packer.kt`,
  `services/ClusterProvisioningService.kt`, and every other reader of a `User` field.
- `services/aws/AwsS3BucketService.kt`, `services/aws/AwsKeyPairService.kt`: read and save through
  `ProfileStore`.  `services/CassandraBuildCatalog.kt`, `services/cassandraversions/VersionSources.kt`
  stop holding a profile value.
- 31 test files that build `User(`; `SetupProfileTest`, `SetupProfileIntegrationTest`, `ProfileShowTest`.
- Docs: `docs/getting-started/setup.md`, `docs/reference/commands.md`, `configuration/CLAUDE.md`,
  `commands/CLAUDE.md`.
- No AMI bake.  `requires-aws`: setup and every AWS client read the migrated profile.
