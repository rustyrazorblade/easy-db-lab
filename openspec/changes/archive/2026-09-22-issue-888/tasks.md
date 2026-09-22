# Tasks

## 1. The shared scanner

- [x] 1.1 Create `src/main/kotlin/com/rustyrazorblade/easydblab/services/WorkspaceKitScanner.kt`
      with `isInstalledKit(dir: File): Boolean` and `discover(): List<File>`. `kit.yaml`
      (`Constants.Kit.CONFIG_FILE`) is the sole marker. Class KDoc states the rule and why a `bin/`
      directory does not qualify.
- [x] 1.2 Register it in `services/ServicesModule.kt` beside the other kit registrations:
      `singleOf(::WorkspaceKitScanner)`.

## 2. Both call sites

- [x] 2.1 `CommandLineParser.registerDynamicKitSubcommands()`: replace the `listFiles`/`filter`/
      `hasBinScripts`/`hasConfigYaml` block with `get<WorkspaceKitScanner>().discover()`. Leave the
      subcommand-name guard and the try/catch around `buildKitGroup` untouched.
- [x] 2.2 Rewrite that method's KDoc (lines 251-259) to state the `kit.yaml`-only rule. It currently
      documents the defect.
- [x] 2.3 Remove the now-unused `java.io.File` import from `CommandLineParser.kt`. Keep `Constants`;
      line 135 still uses it. ktlint fails otherwise.
- [x] 2.4 `Status`: inject `WorkspaceKitScanner`, and reduce `displayKitsSection()`'s first statement
      to `workspaceKitScanner.discover().map { it.name }.sorted()`. Everything below the
      `isEmpty()` guard is unchanged.
- [x] 2.5 Reword `displayKitsSection()`'s KDoc so "same detection as dynamic subcommand
      registration" names the shared type instead of describing a coincidence.

## 3. Enforce the invariant at install time

- [x] 3.1 `services/InstallTemplateResolver.kt`, `resolveAdHoc` (lines 133-137): add a second
      `require` beside the existing directory check, rejecting a template directory with no
      `kit.yaml` (`Constants.Kit.CONFIG_FILE`). The message must name the missing file. This fails
      before anything is written to the workspace.
- [x] 3.2 `readInstallYamlContent`'s KDoc (lines 139-144) says `config.yaml` twice; the file it
      reads is `kit.yaml`. One-line correction in a method being read for this change — folded in
      because it documents the very rule this change is codifying.

## 4. Tests

All fast tier, `src/test/`. Nothing here needs Docker, TestContainers, the Fabric8 mock server, or a
socket.

- [x] 4.1 New `src/test/kotlin/com/rustyrazorblade/easydblab/services/WorkspaceKitScannerTest.kt`,
      extending `BaseKoinTest`. Koin is needed only to obtain the test `Context`, whose
      `workingDirectory` is already an isolated temp dir. No mocks — the class has no side effects
      worth faking. Use AssertJ.
- [x] 4.2 Cover: a `bin/start.sh`-only directory is not discovered; `trunk/bin/cassandra` plus
      `.venv/bin/activate` yields an empty result (the issue's own reproduction — this is the test
      that fails before the fix); a directory with `kit.yaml` is discovered; a directory with both
      markers is discovered exactly once; a `kit.yaml` that is a directory rather than a file does
      not qualify; a plain file at the workspace root is not discovered; an empty workspace returns
      an empty list without throwing.
- [x] 4.3 Add two cases to `commands/StatusTest.kt`, reusing its existing stdout capture: given
      `clickhouse/kit.yaml` and an executable `trunk/bin/cassandra`, the output lists `clickhouse`
      under `=== KITS ===` and does not contain `trunk`; given only a `bin/`-holding directory, no
      `=== KITS ===` header is printed.
- [x] 4.4 Cover `resolveAdHoc` in the existing `InstallTemplateResolver` test (or a new one if none
      exists): a `--from` directory holding `bin/start.sh` and no `kit.yaml` is rejected, and the
      message names the missing file; a directory holding `kit.yaml` resolves. Fast tier.

## 5. Specs and documentation

- [x] 5.1 `openspec/specs/workload-runner/spec.md`: edit the Purpose line (line 5) only. The
      requirement sentence and every scenario under it live in this change's delta
      (`openspec/changes/issue-888/specs/workload-runner/spec.md`) and are applied to the live
      spec by `openspec archive`, in its own batch commit. A feature branch never edits a
      requirement block here; doing so applies the delta twice.
- [x] 5.2 `openspec/specs/kit-install-command/spec.md`: edit the non-requirement narrative only — the
      Purpose clause (lines 5-7) and the "Kit runner subcommands" section (lines 208-210), both of
      which state the old rule, plus the example tree (lines 214-220), which depicts a kit
      directory with no `kit.yaml`. Archive never touches narrative prose, so these must be
      corrected in this branch. The additions to the "Kit descriptor filename" requirement are in
      the delta and are applied by `openspec archive`.
- [x] 5.3 `src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md`, the
      `<kit> start/stop subcommands` section (lines 146-155): replace the `bin/`-with-executable
      sentence with the `kit.yaml` rule, naming `WorkspaceKitScanner` as the source of truth shared
      with `status`.

## 6. Verify

- [x] 6.1 `./gradlew ktlintFormat && ./gradlew ktlintCheck`
- [x] 6.2 `./gradlew test` — the full fast tier, not just the new tests.
- [x] 6.3 `./gradlew detekt` (JDK 21; detekt 1.23.8 cannot run under JDK 25).
- [x] 6.4 `openspec validate issue-888 --type change --strict`
