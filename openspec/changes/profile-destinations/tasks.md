## 1. Fixtures (before any code change)

- [ ] 1.1 (mechanical) On main, write the earlier-format fixtures with `UserConfigProvider.saveUserConfig`
      from `User` fixtures: AWS profile name, access key, both, neither, half Tailscale, half AxonOps,
      bad `sshTransport`, full.  Check them in under `src/test/resources/.../profile/legacy/`.  Fake
      values only.

## 2. Profile model and store

- [ ] 2.1 (judgement) `configuration/profile/Profile.kt`: `Profile`, open `Destination`,
      `AwsDestination`, `AwsLocation`, sealed `AwsCredentials`, `Tailscale`, `AxonOps`, the enabled
      rules.  kotlinx `@Serializable`, `type` discriminator, a `SerializersModule` registering
      `AwsDestination`.  Class KDoc on each.
- [ ] 2.2 (judgement) `configuration/profile/LegacyProfileMigration.kt` (design D3) and its tests.
- [ ] 2.3 (judgement) `configuration/profile/ProfileStore.kt` replacing `UserConfigProvider`
      (design D2): `isSetup`, `load` with the migration into `profile.yaml`, `loadForSetup`, atomic
      0600 `save`, redacted parse errors.  Tests from the design's test plan.
- [ ] 2.4 (mechanical) `Constants.ConfigPaths`: `PROFILE_FILE = "profile.yaml"`, keep
      `PROFILE_SETTINGS_FILE` as the earlier file's name, the file mode.

## 3. Wiring

- [ ] 3.1 (mechanical) `di/ContextModule.kt`: bind `ProfileStore` and `Profile`; remove `User` and
      `UserConfigProvider`.
- [ ] 3.2 (mechanical) `providers/aws/AwsClientsModule.kt`: bind `AwsDestination` (cast that fails
      naming the type); region and credentials provider from it; test the credential-form choice.
- [ ] 3.3 (judgement) `services/CommandExecutor.kt`: the `@RequireProfileSetup` check calls
      `ProfileStore.isSetup()`, whose meaning changes (design D2).  Add the `CommandExecutorTest`.

## 4. Callers

- [ ] 4.1 (mechanical) Replace `User` field reads.  Renames: `user.region` to `aws.location.region`,
      `user.s3Bucket` to `aws.accountBucket`, `user.keyName` to `aws.keyName`, `user.sshTransport` to
      `aws.sshTransport`, `user.awsProfile`/`awsAccessKey`/`awsSecret` to `aws.credentials`,
      `user.email` to `profile.email`, Tailscale and AxonOps fields to their blocks.  Includes
      `containers/Packer.kt` and `services/ClusterProvisioningService.kt`.
- [ ] 4.2 (mechanical) `commands/Init.kt` `InitConfig.fromInit`: region from `AwsDestination`,
      network mode from `profile.tailscale`.
- [ ] 4.3 (judgement) `services/aws/AwsS3BucketService.kt` (design D4): `ensureAccountBucket()` and
      `ensureMarkedAccountBucket()` read and save through `ProfileStore`; `VersionSources` and
      `CassandraBuildCatalog` stop holding a profile value.  Add the stale-profile test.
- [ ] 4.4 (mechanical) `services/aws/AwsKeyPairService.kt`: save `keyName` through `ProfileStore`.
- [ ] 4.5 (mechanical) The 31 test files that build `User(` use a shared `testProfile()` fixture.
- [ ] 4.6 (mechanical) Delete `configuration/User.kt` and `configuration/UserConfigProvider.kt`.

## 5. Setup and show

- [ ] 5.1 (judgement) `commands/profile/SetupProfile.kt` (design D5): typed profile,
      `Event.Setup.DestinationSelected` (per `events/CLAUDE.md`), no destination prompt for an existing
      profile, prefill and bad-value recovery from `loadForSetup()`.
- [ ] 5.2 (mechanical) `SetupProfileTest`: real `ProfileStore` on `@TempDir`; remove the
      `verify(...).saveUserConfig(userConfig)` assertions (lines 410, 422) in favour of decoding the file.
- [ ] 5.3 (mechanical) `SetupProfileIntegrationTest`: drop the `UserConfigProvider` mock (lines 51,
      86); assert `accountBucket` lands in `profile.yaml`.
- [ ] 5.4 (mechanical) `commands/profile/ProfileShow.kt` and `ProfileShowTest`,
      `ProfileCompletenessTest`: design D6.

## 6. Docs and specs

- [ ] 6.1 (mechanical) `docs/getting-started/setup.md` (line 69): `profile.yaml`, its format, the
      destination line, the migration that leaves `settings.yaml` in place.
      `docs/reference/commands.md` (line 47): `profile show` fields and files.
- [ ] 6.2 (mechanical) `configuration/CLAUDE.md` (profile model and store), `commands/CLAUDE.md`
      ("Require a profile").
- [ ] 6.3 (mechanical) Archive the spec deltas after the merge.

## 7. Verification

- [ ] 7.1 (mechanical) `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21, through a Sonnet
      build-engineer.
- [ ] 7.2 (judgement) Real AWS run from the design's test plan, including the `cmp` of
      `settings.yaml`; evidence in the PR.
