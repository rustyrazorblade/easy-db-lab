## 1. Fixtures (before any code change)

- [x] 1.1 (mechanical) On main, write the earlier-format fixtures with `UserConfigProvider.saveUserConfig`
      from `User` fixtures: AWS profile name, access key, both, neither, half Tailscale, half AxonOps,
      full.  The bad `sshTransport` fixture cannot be written that way (`sshTransport` is an enum):
      generate the full fixture, then edit its transport value by hand.  Check them in under
      `src/test/resources/.../profile/legacy/`.  Fake values only.
- [x] 1.2 (mechanical) After 2.3, generate a `profile.yaml` fixture with the new store and hand-edit
      its transport to a bad value, for the `profile.yaml` bad-transport test.

## 2. Profile model and store

- [x] 2.1 (judgement) `configuration/profile/Profile.kt`: `Profile`, open `Destination`, `Tailscale`,
      `AxonOps`, the enabled rules, `ProfileAccess`, `LegacyProfileReader`.  In the AWS package
      `configuration/profile/aws/`: `AwsDestination`, `AwsLocation`, sealed `AwsCredentials`,
      `awsProfileSerializers` (registers `AwsDestination` under `type`), `AwsDestinationAccess`.
      kotlinx `@Serializable`.  Class KDoc on each.
- [x] 2.2 (judgement) `configuration/profile/aws/LegacyAwsProfileReader.kt` (design D3) and its tests.
- [x] 2.3 (judgement) `configuration/profile/ProfileStore.kt` replacing `UserConfigProvider`
      (design D2): constructor takes the directory, the `SerializersModule` and the
      `LegacyProfileReader`; `isSetup`, `load` with the migration into `profile.yaml`, flattened
      `loadForSetup`, locked read-modify-write `update` (lock file, re-read, atomic 0600 write),
      redacted parse errors.  Tests from the design's test plan.
- [x] 2.4 (mechanical) `Constants.ConfigPaths`: `PROFILE_FILE = "profile.yaml"`, the lock file name,
      keep `PROFILE_SETTINGS_FILE` as the earlier file's name, the file mode.
- [x] 2.5 (mechanical) `PackageBoundaryTest`: the neutral profile classes depend on nothing in the
      AWS profile package (design Test plan), with its control.

## 3. Wiring

- [x] 3.1 (mechanical) `di/ContextModule.kt`: build `ProfileStore` from `awsProfileSerializers` and
      `LegacyAwsProfileReader`; bind `ProfileStore` and `ProfileAccess`; remove `User` and
      `UserConfigProvider`.  Bind no `Profile` value.
- [x] 3.2 (mechanical) `providers/aws/AwsClientsModule.kt`: bind `AwsDestinationAccess` (its cast fails
      naming the type); region and credentials provider read once from it; test the credential-form
      choice.  Bind no `AwsDestination` value.
- [x] 3.3 (judgement) `services/CommandExecutor.kt`: the `@RequireProfileSetup` check calls
      `ProfileStore.isSetup()`, whose meaning changes (design D2).  Add the `CommandExecutorTest`.

## 4. Callers

- [x] 4.1 (mechanical) Replace `User` field reads.  Renames: `user.region` to `aws.location.region`,
      `user.s3Bucket` to `aws.accountBucket`, `user.keyName` to `aws.keyName`, `user.sshTransport` to
      `aws.sshTransport`, `user.awsProfile`/`awsAccessKey`/`awsSecret` to `aws.credentials`,
      `user.email` to `profile.email`, Tailscale and AxonOps fields to their blocks.  `aws` is
      `awsDestinationAccess.current()`, called where the value is used, never stored in a field.
- [x] 4.2 (judgement) Bucket and key readers (design D4): `containers/Packer.kt:86,162`,
      `services/aws/AwsServicesModule.kt:188-189` (`AwsBuildSites` takes the accessor),
      `commands/observability/CompactorStatus.kt:29`, `services/ClusterProvisioningService.kt:84`,
      `commands/spark/SparkInit.kt:105`.  Add `PackerTest`'s late-save case.
- [x] 4.3 (mechanical) `commands/Init.kt` `InitConfig.fromInit`: region from `AwsDestinationAccess`,
      network mode from `profile.tailscale`.  `TemplateService`'s region fallback reads `ProfileAccess`.
- [x] 4.4 (judgement) `services/aws/AwsS3BucketService.kt` (design D4): `ensureAccountBucket()` and
      `ensureMarkedAccountBucket()` read and update through the store; `VersionSources` and
      `CassandraBuildCatalog` stop holding a profile value.  Add the stale-profile test.
- [x] 4.5 (mechanical) `services/aws/AwsKeyPairService.kt`: save `keyName` through `update`; its
      message at `:101` and `AwsKeyPairServiceTest:151` name `profile.yaml`.  `LazySshRoute.kt:9` and
      the `ProfileShow` KDoc name `profile.yaml`.
- [x] 4.6 (mechanical) The 31 test files that build `User(` use a shared `testProfile()` fixture.
- [x] 4.7 (mechanical) Delete `configuration/User.kt` and `configuration/UserConfigProvider.kt`.

## 5. Setup and show

- [x] 5.1 (judgement) `commands/profile/SetupProfile.kt` (design D5): typed profile,
      `Event.Setup.DestinationSelected` (per `events/CLAUDE.md`), no destination prompt for an existing
      profile, prefill and bad-value recovery from the flat `loadForSetup()` map for either file.
- [x] 5.2 (mechanical) `SetupProfileTest`: real `ProfileStore` on `@TempDir`; remove the
      `verify(...).saveUserConfig(userConfig)` assertions (lines 410, 422) in favour of decoding the file.
- [x] 5.3 (judgement) `SetupProfileIntegrationTest`: drop the `UserConfigProvider` mock (lines 51,
      86); assert `accountBucket` and `keyName` land in `profile.yaml`; add the new-profile case where
      the recorded image build sees the bucket and key created in the same run.
- [x] 5.4 (mechanical) `commands/profile/ProfileShow.kt` and `ProfileShowTest`,
      `ProfileCompletenessTest`: design D6.

## 6. Docs and specs

- [x] 6.1 (mechanical) `docs/getting-started/setup.md` (line 69): `profile.yaml`, its format, the
      destination line, the migration that leaves `settings.yaml` in place.
      `docs/reference/commands.md` (line 47): `profile show` fields and files.
- [x] 6.2 (mechanical) `configuration/CLAUDE.md` (profile model, store, accessors, lock),
      `commands/CLAUDE.md` ("Require a profile").
- [ ] 6.3 (mechanical) Archive the spec deltas after the merge.

## 7. Verification

- [ ] 7.1 (mechanical) `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21, through a Sonnet
      build-engineer.
- [ ] 7.2 (judgement) Real AWS run from the design's test plan, including the `cmp` of
      `settings.yaml` and main's `profile show` on the same copy afterwards; evidence in the PR,
      with each control's failing test name and assertion line.
