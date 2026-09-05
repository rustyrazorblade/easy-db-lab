# Tasks — issue 892

## 1. Close the `commands↔services` cycle

- [ ] 1.1 Move the `PicoCommand` interface out of `commands/` to a kernel package in the main module
      (not `core` — it imports `annotations.PreExecute`/`PostExecute`). `PicoBaseCommand` stays in
      `commands/`.
- [ ] 1.2 Update the 5 `src/main` importers (`CommandLineParser.kt`, `commands/aws/Region.kt`,
      `commands/aws/S3Bucket.kt`, `mcp/McpToolRegistry.kt`, `services/CommandExecutor.kt`), the 3
      `src/test` importers (`TestModules.kt`, `mcp/McpAnnotationTest.kt`,
      `commands/cassandra/UseCassandraTest.kt`), and the 3 same-package files that gain an import
      (`commands/PicoBaseCommand.kt`, `commands/Commands.kt`, `commands/Ip.kt`).
- [ ] 1.3 Add `services/ProfileSetupCommandProvider.kt` — a `fun interface` returning a
      `PicoCommand` — and bind it. Replace the direct `SetupProfile()` construction at
      `CommandExecutor.kt:231` with `profileSetupProvider.create()`.
- [ ] 1.4 Add the constructor argument at the two direct `DefaultCommandExecutor(...)` construction
      sites in tests (`CommandExecutorTest.kt:86`, `StatusTest.kt:448`).
- [ ] 1.5 Verify no file under `services/` references any type in `commands/`, by import **or**
      fully-qualified name.

## 2. Build the `profile` command group

- [ ] 2.1 Add `commands/profile/Profile.kt` — a `Runnable` group, not a `PicoCommand`, so a bare
      invocation falls through to `CommandLine.RunLast()` and exits 0. Class-level KDoc required.
- [ ] 2.2 Move `SetupProfile.kt` to `commands/profile/`, rename to `@Command(name = "setup")`, and
      remove the `aliases = ["setup"]` entry.
- [ ] 2.3 Register `Profile::class` in `CommandLineParser.kt` and remove `SetupProfile::class` from
      the top-level list.
- [ ] 2.4 Update `Repl.kt:61`'s `ShellCommands` entry — a second, hand-maintained copy of the
      command tree. **No existing test catches this**; `ReplTest` asserts only on `status`,
      `cassandra`, `spark`, `cls`, and `cassandra stress`.
- [ ] 2.5 Update the Koin binding in `di/CommandsModule.kt` for `SetupProfile`'s new package.

## 3. Implement `profile show`

- [ ] 3.1 Add `commands/profile/ProfileShow.kt` — `@Command(name = "show")`, extends
      `PicoBaseCommand`, **no requirement annotations**, never references `clusterState`.
      Class-level KDoc required.
- [ ] 3.2 Implement `buildReport(profileName, profileDir, user: User?)` as a `companion object`
      function returning one multiline string, following `KitList.buildListText` /
      `KitInfo.buildInfoText`. It must **not** call any masking helper.
- [ ] 3.3 Handle all three branches: configured; `settings.yaml` absent (not-configured message
      naming `easy-db-lab profile setup`); `settings.yaml` present but undeserializable (report
      present-but-unreadable with the file path, no raw Jackson error).
- [ ] 3.4 Read profile identity from `context.profile` / `context.profileDir.absolutePath`; use
      `getUserConfig()` guarded by `isSetup()`; call `User.isTailscaleEnabled()` for the Tailscale
      flag and `axonOpsKey.isNotEmpty()` for AxonOps.
- [ ] 3.5 Register `ProfileShow` in `di/CommandsModule.kt` — omission fails **silently** via
      `KoinCommandFactory`'s fallback.

## 4. Tests

- [ ] 4.1 `buildReport` unit tests: all five settings present; each secret absent from output
      (sentinel values); AxonOps `ENABLED`/`DISABLED`; Tailscale `ENABLED`/`DISABLED` including the
      blank-credential case; not-configured message; malformed-profile message.
- [ ] 4.2 Lifecycle-level test class for what `buildReport` cannot observe: bare `profile` exits 0
      and lists `show` and `setup`; `setup` and `setup-profile` no longer resolve at top level;
      `ProfileShow::class.annotations` carries no `RequireProfileSetup`; `ClusterStateManager.load()`
      is never called for `profile show`; `EASY_DB_LAB_PROFILE` resolution through `Context`.
- [ ] 4.3 Confirm the existing `SetupProfileTest` and `SetupProfileIntegrationTest` still pass after
      the move.

## 5. Docs and strings

- [ ] 5.1 Update the 6 user-facing Kotlin strings: `Event.kt:2881`, `Event.kt:3632`
      (`ProfileNotConfigured`), `UserConfigProvider.kt:79`, `providers/aws/AWS.kt:260`,
      `containers/Packer.kt:86`, `commands/tailscale/TailscaleStart.kt:91`.
- [ ] 5.2 Update the remaining 9 Kotlin KDoc/comment references.
- [ ] 5.3 Update the 18 README and `docs/` references.
- [ ] 5.4 Update the two bare-`setup` docs passages that contain no `setup-profile` string:
      `docs/getting-started/setup.md:33` and `docs/reference/commands.md:25`.
- [ ] 5.5 Document `profile` and `profile show` in `docs/reference/commands.md`. Note the existing
      unrelated `cassandra profile` group already documented there.
- [ ] 5.6 Verify no live `setup-profile` or bare-`setup` reference remains outside
      `openspec/archive/` and `openspec/changes/archive/`.

## 6. Folded-in debt

- [ ] 6.1 Delete the dead branch in `SetupProfile.maskValue()` (`SetupProfile.kt:357-362`) — the
      `value.length == 1` case returns exactly what `else` returns.

## 7. Verification

- [ ] 7.1 `./gradlew ktlintFormat` then `./gradlew check` (JDK 21 — detekt 1.23.8 cannot run under
      JDK 25).
- [ ] 7.2 Run the command locally against a real profile: `profile`, `profile show`, a non-default
      `EASY_DB_LAB_PROFILE`, and from a directory with no cluster workspace.
