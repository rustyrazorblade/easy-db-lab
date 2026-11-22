## 1. Profile model and store

- [ ] 1.1 (judgement) Add `configuration/profile/Profile.kt`: `Profile`, sealed `Destination` with
      `Aws`, sealed `AwsCredentials` with `AwsProfile` and `AccessKey`, `Tailscale`, `AxonOps`, the
      enabled predicates.  kotlinx `@Serializable`, `type` discriminator.  Class KDoc on each.
- [ ] 1.2 (judgement) Add `configuration/profile/LegacyProfileMigration.kt` (design D3) and its tests.
- [ ] 1.3 (judgement) Add `configuration/profile/ProfileStore.kt` replacing `UserConfigProvider`:
      kaml read with `ignoreUnknownKeys`, redacted parse errors, legacy detection and silent rewrite,
      atomic 0600 save, `loadForSetup()`.  Tests from the design's test plan.
- [ ] 1.4 (mechanical) Move the file name and the mode to `Constants.ConfigPaths`.

## 2. Wiring

- [ ] 2.1 (mechanical) `di/ContextModule.kt`: bind `ProfileStore`, `Profile`, `Destination.Aws`;
      remove the `User` and `UserConfigProvider` bindings.
- [ ] 2.2 (mechanical) `providers/aws/AwsClientsModule.kt`: region and credentials provider from
      `Destination.Aws`; test the credential-form choice.
- [ ] 2.3 (mechanical) `services/CommandExecutor.kt`: the `@RequireProfileSetup` check calls
      `ProfileStore.isSetup()`.  Behaviour unchanged.

## 3. Callers

- [ ] 3.1 (mechanical) Replace `User` field reads.  Renames: `user.region` to `aws.region`,
      `user.s3Bucket` to `aws.accountBucket`, `user.keyName` to `aws.keyName`, `user.sshTransport` to
      `aws.sshTransport`, `user.awsProfile`/`awsAccessKey`/`awsSecret` to `aws.credentials`,
      `user.email` to `profile.email`, Tailscale and AxonOps fields to their blocks.
- [ ] 3.2 (mechanical) `commands/Init.kt` `InitConfig.fromInit`: region from `Destination.Aws`,
      network mode from `profile.tailscale`.
- [ ] 3.3 (mechanical) `services/aws/AwsS3BucketService.kt`, `services/aws/AwsKeyPairService.kt`:
      save the bucket and key name through `ProfileStore.save(profile.copy(...))`.
- [ ] 3.4 (mechanical) Update the 35 test files that build `User(...)` to a shared test fixture
      `testProfile()`.
- [ ] 3.5 (mechanical) Delete `configuration/User.kt` and `configuration/UserConfigProvider.kt`.

## 4. Setup and show

- [ ] 4.1 (judgement) `commands/profile/SetupProfile.kt`: work on `Profile`; emit
      `Event.Setup.DestinationSelected` for a new profile; no destination prompt for an existing one;
      prefill from `loadForSetup()`.  Add the event per `events/CLAUDE.md`.
- [ ] 4.2 (mechanical) `commands/profile/ProfileShow.kt` and `ProfileCompletenessTest`: the new fields
      (design D5).

## 5. Docs and specs

- [ ] 5.1 (mechanical) `docs/getting-started/setup.md`: the new file format, the destination line,
      the silent migration.  `docs/reference/commands.md`: `profile show` fields.
- [ ] 5.2 (mechanical) `configuration/CLAUDE.md` (profile model and store), `commands/CLAUDE.md`
      ("Require a profile").
- [ ] 5.3 (mechanical) Archive the spec deltas after the merge.

## 6. Verification

- [ ] 6.1 (mechanical) `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21, through a Sonnet
      build-engineer.
- [ ] 6.2 (judgement) Real AWS run from the design's test plan; evidence in the PR.
