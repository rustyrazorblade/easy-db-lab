# Require kit.yaml as the sole marker of an installed kit

## Why

The CLI registers any working-directory subdirectory as a top-level kit command when that
directory holds a `bin/` directory containing an executable file or a `*.sh` file. A `bin/`
directory does not prove a kit is installed. Running `easy-db-lab -h` in a directory of Cassandra
checkouts prints 24 false top-level commands, including `trunk`, `5.0`, `4.0`,
`21554-cursor-flush`, and `.venv` — none of which holds a `kit.yaml`. Help output is unusable, and
a directory name can shadow a core command.

The rule is copied inline at two call sites, so `easy-db-lab status` reports the same false kits
under `=== KITS ===`.

`kit.yaml` is the correct marker and the rule is an invariant, not a heuristic: **every non-Kotlin
kit directory has a `kit.yaml`, with no exceptions.** `kit install` writes it
(`BaseInstallCommand.kt:58`), and `KitInstallCommand.kt:124` already treats its absence as "not
installed". The specs currently mandate the `bin/` rule instead; that text was a careless mistake
when written, not a design decision, so this change corrects an error rather than reversing intent.

## What Changes

- **BREAKING** (intentionally): a workspace subdirectory holding only a `bin/` directory is no
  longer registered as a kit subcommand and no longer appears under `=== KITS ===` in
  `easy-db-lab status`. Only a directory holding `kit.yaml` qualifies. This is the defect being
  fixed; every kit installed through `kit install` carries the descriptor and is unaffected.
- A new `WorkspaceKitScanner` service becomes the single source of truth for filesystem kit
  discovery, exposing `isInstalledKit(dir)` and `discover(): List<File>`.
- `CommandLineParser.registerDynamicKitSubcommands()` and `Status.displayKitsSection()` both
  consume that service. Neither retains an inline copy of the rule, so the two listings cannot
  disagree.
- The `kit.yaml` invariant is stated explicitly in the specs, so the rule is written down rather
  than implied by an implementation detail.
- `kit install --from <dir>` rejects a template directory with no `kit.yaml`, with a clear message.
  That path is the one way to produce a descriptor-less kit directory today; without the check the
  invariant would be stated but not enforced, and such an install would silently register nothing.
- Which phases a qualified kit exposes is unchanged. This alters which directories qualify, not
  what a qualified kit does.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `workload-runner` — the discovery requirement and its scenarios currently specify `bin/` as the
  marker. The requirement sentence, three scenarios under it, and one scenario under the `--help`
  requirement all change to `kit.yaml`. A new requirement states the invariant.
- `kit-install-command` — the narrative describing how an installed kit is detected currently states
  the `bin/` rule, and its example directory tree depicts a kit directory with no `kit.yaml`, which
  would not register under the corrected rule.

## Impact

- `src/main/kotlin/com/rustyrazorblade/easydblab/services/WorkspaceKitScanner.kt` — new.
- `src/main/kotlin/com/rustyrazorblade/easydblab/services/ServicesModule.kt` — one Koin
  registration.
- `src/main/kotlin/com/rustyrazorblade/easydblab/CommandLineParser.kt` — predicate removed, KDoc
  corrected, now-unused `java.io.File` import removed.
- `src/main/kotlin/com/rustyrazorblade/easydblab/commands/Status.kt` — inline predicate removed,
  scanner injected.
- `src/main/kotlin/com/rustyrazorblade/easydblab/services/InstallTemplateResolver.kt` — one added
  `require` in `resolveAdHoc`, beside the existing directory check.
- `src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md` — the
  `<kit> start/stop subcommands` section states the old rule.
- Tests: one new `WorkspaceKitScannerTest`, two added cases in `StatusTest`. All fast tier
  (`src/test/`) — no Docker, TestContainers, mock server, or socket.
- No behavior change for any kit installed via `kit install <name>`: that path only registers an
  install subcommand when the template's `kit.yaml` parses, and then writes that descriptor into
  the installed directory.
