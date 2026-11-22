## Context

- The profile file is `~/.easy-db-lab/profiles/<name>/settings.yaml`; the name comes from
  `EASY_DB_LAB_PROFILE` (`Context.kt:51`), default `default`.
- `User` (`configuration/User.kt:14-33`) is a flat Jackson data class with AWS fields only.
  `UserConfigProvider` (`configuration/UserConfigProvider.kt`) loads it, caches it, redacts parse
  errors, and saves a `Map` without an atomic write.
- `di/ContextModule.kt` binds `User` from `getUserConfig()`.  `AwsClientsModule.kt:54-67` builds the
  region and the credentials provider from it.
- `SetupProfile` (`commands/profile/SetupProfile.kt`, 673 lines) collects and saves a `Map`.
- The owner's plan (`plan.md` of the multi-cloud plan, sections 4 and 5) fixes the destinations:
  `aws`, `aws-eks`, `gcp`, `azure`, `k8s`.  This change ships `aws` only.

## Goals / Non-Goals

Goals:
- One profile, one destination, recorded in the file.
- A file format that the later destinations extend without another migration.
- A silent, one-time migration of every existing profile.
- Remove `User` and the Jackson profile reader.

Non-goals:
- Any destination other than AWS.  The gate variable for unfinished destinations lands with the
  first such destination (Phase 5), not here, because there is nothing to gate yet.
- Recording the target in `state.json` (Phase 4).
- Changing the AWS prompts, validation or resource steps.

## Decisions

### D1. The file format

```yaml
email: someone@example.com
destination:
  type: aws
  region: us-west-2
  credentials:
    type: aws-profile        # or: access-key, with accessKeyId and secretAccessKey
    name: my-sso-profile
  keyName: easy-db-lab-...
  accountBucket: easy-db-lab-...
  sshTransport: direct
tailscale:                   # absent when not configured
  clientId: ...
  clientSecret: ...
  tag: tag:easy-db-lab
axonOps:                     # absent when not configured
  org: ...
  key: ...
```

- kotlinx.serialization with kaml (already a dependency, `build.gradle.kts:236`).  The sealed
  `Destination` and `AwsCredentials` use the `type` property as the class discriminator.
- Unknown keys are ignored, so a field added later does not break an older build's read.
- `tailscale` and `axonOps` are nullable blocks.  "Enabled" is "the block is present with non-blank
  values", replacing `User.isTailscaleEnabled()` and `User.isAxonOpsEnabled()` with the same rules.
- `sshTransport` keeps its values `direct` and `ssm`.  An unknown value fails the load and names the
  bad value and the choices, as the spec requires today.
- Written with `File.writeTextAtomically` (`AtomicFileWrite.kt`), then set to mode 0600.

Alternatives:
- Keep the flat file and add `destination: aws`.  Rejected: each later destination would add its own
  flat fields, and a reader could not tell which fields apply.
- One file per destination type.  Rejected: a profile has one destination; two files invite two.

### D2. The model and its owner

- `configuration/profile/Profile.kt`: `Profile(email, destination, tailscale, axonOps)`,
  `sealed interface Destination` with `Destination.Aws`, `sealed interface AwsCredentials` with
  `AwsProfile(name)` and `AccessKey(accessKeyId, secretAccessKey)`.
- `configuration/profile/ProfileStore.kt` replaces `UserConfigProvider`: `isSetup()`, `load()`
  (cached), `loadForSetup()` (a partial read that setup prefills from), `save(Profile)`, `clearCache()`.
- Koin binds `ProfileStore`, `Profile` (from `load()`), and `Destination.Aws`
  (`get<Profile>().destination as? Destination.Aws ?: error`).  AWS code injects `Destination.Aws`,
  so no AWS class learns about other destinations.
- No new port.  `Destination` is data with one variant now; it is a file-format decision, not a seam.
  `ProfileStore` has one adapter and is a concrete class.

### D3. Migration

- `LegacyProfileMigration.migrate(map): Profile?` is a pure function over the decoded YAML map.
- It runs when the file has no `destination` key.  Mapping:
  - `region`, `keyName`, `sshTransport` copy into `Destination.Aws`; `s3Bucket` becomes `accountBucket`.
  - `awsProfile` non-blank gives `AwsProfile(awsProfile)`; otherwise non-blank `awsAccessKey` and
    `awsSecret` give `AccessKey`.  Neither gives `null`: the file is not migrated and reads as not
    set up, and `profile setup` prefills from it.
  - `tailscaleClientId`/`tailscaleClientSecret`/`tailscaleTag` become `tailscale` when both id and
    secret are non-blank.  `axonOpsOrg`/`axonOpsKey` become `axonOps` when both are non-blank.
    A half-set pair is dropped; today it already reads as disabled.
  - Other keys (for example `sshKeyPath`) are dropped.
- `ProfileStore.load()` writes the migrated profile back atomically, then returns it.  It prints
  nothing and emits no event (a debug log line only).  Two processes migrating at once write the same
  bytes; the atomic rename makes either result whole.
- The earlier format is read once, by a plain kaml `YamlMap` walk.  No Jackson.

### D4. Setup

- `SetupProfile` works on `Profile` and `Destination.Aws` instead of a `Map`.  Its phases and its
  events are kept.  One new event, `Event.Setup.DestinationSelected(destination: String)`, is emitted
  when a new profile's destination is fixed; the console prints `Destination: AWS`.
- A new profile with one available destination does not prompt for it.  With an existing profile,
  setup reviews that destination only.  Choosing a second destination means another profile name.
- `isFullySetUp` reads the typed profile: email, region, credentials, keyName, accountBucket.

### D5. `profile show`

- Prints the profile name and directory, `email`, `destination`, then the destination's fields:
  `region`, `credentials` (`aws-profile <name>` or `access-key`, never the key or secret),
  `keyName`, `accountBucket`, `sshTransport`, then AxonOps and Tailscale as `ENABLED`/`DISABLED`.
- Loading a legacy file from `profile show` migrates it, like every other load.

## Provider neutrality

- The neutral parts are `Profile`, the `destination` key, `ProfileStore` and the migration entry
  point.  The AWS fields are only in `Destination.Aws` and `AwsCredentials`.
- AWS classes depend on `Destination.Aws`, not on `Profile`, so the move of AWS code to its own Gradle
  module (Phase 3) takes `Destination.Aws` with it and leaves `Profile` in `:target-api`.
- `PackageBoundaryTest` is unchanged: the profile model holds no AWS SDK type.

## Risks / Trade-offs

- After the merge, the first new build to load a profile rewrites it, and an older build then fails
  to read that profile.  Accepted: the lead merges when few agents run and tells them to rebase.
- About 150 call sites change in one PR.  The change is mechanical; the field renames are listed in
  tasks.md.

## Test plan

Unit tier (`src/test`, runs in CI).  Each test has a control: break the named check, see the test
fail, revert.

- `ProfileStoreTest.round trip keeps every field` (both credential forms, with and without
  Tailscale and AxonOps).  Control: drop `accountBucket` from the save.
- `ProfileStoreTest.save writes owner-only permissions`.  Control: remove the chmod.
- `ProfileStoreTest.unknown keys are ignored`.  Control: set `ignoreUnknownKeys = false`.
- `ProfileStoreTest.unknown ssh transport fails naming value and choices`.  Control: default the
  transport instead of failing.
- `ProfileStoreTest.parse error message holds no file text` (a secret in a broken file).  Control:
  include the decoder message.
- `LegacyProfileMigrationTest`: `aws profile name maps to AwsProfile`, `access key maps to AccessKey`,
  `profile name wins over keys`, `no credentials returns null`, `half tailscale pair is dropped`,
  `half axonops pair is dropped`, `s3Bucket becomes accountBucket`.  Controls: swap the precedence;
  map a half pair; skip the bucket rename.
- `ProfileStoreTest.legacy file is rewritten in the new format on first load` (fixture: a real
  settings.yaml from main, secrets replaced).  Asserts the file now has `destination` and reloads
  equal.  Control: skip the write-back.
- `ProfileStoreTest.migration prints nothing` (captures stdout and the event bus).  Control: add a
  print.
- `ProfileStoreTest.legacy file without credentials reads as not set up`.  Control: migrate with
  empty credentials.
- `SetupProfileTest.new profile with one destination does not ask for it and emits
  DestinationSelected`.  Control: prompt anyway.
- `SetupProfileTest.existing profile is reviewed without a destination prompt`.
- `SetupProfileTest.interrupted legacy profile prefills setup` (the incomplete-profile scenario).
- `ProfileShowTest` (existing, updated): destination and fields shown; no secret appears, for both
  credential forms; flags follow the block rules.  Control: print the access key id.
- `AwsClientsModuleTest.credentials provider follows the credential form`.  Control: always build
  the static provider.

Integration tier: none.  No container is involved.

Real AWS (before ready, `requires-aws`):
- Copy the default profile's `settings.yaml` aside (the legacy fixture, kept off the repo).  Run the
  branch build's `profile show`: the file migrates, and the output matches the old values.  Then a
  3-node `init`/`up`/`down` with `embark_offhours=off` on the default profile.  No other profile is
  created (owner rule).
- Restore the copied legacy file at the end, so builds from `main` (other agents) keep reading it
  until this change merges.
