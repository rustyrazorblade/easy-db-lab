## 1. `kit.yaml` CLI help text

- [x] 1.1 Update the `--rate` flag's `description` in
      `src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/kit.yaml` to warn
      that rates well above cluster capacity cause sysbench's internal event queue to
      overflow and hard-abort within seconds (rather than sustaining an overload window),
      and to point at the user guide for the recommended approach.

## 2. User guide (`docs/user-guide/sysbench.md`)

- [x] 2.1 Add `--rate`, `--skip-trx`, and `--rand-type` rows to the Flags table (currently
      missing despite being real `kit.yaml` `start` args).
- [x] 2.2 Add a "Rate limiting and overload testing" subsection covering: what `--rate`
      does, why over-capacity rates hard-abort (`FATAL: event queue is full`) instead of
      degrading gracefully, and the recommended alternative for overload/latency testing —
      thread-bound runs (`--rate=0` with a chosen `--threads`) or a rate deliberately set
      closer to measured capacity.

## 3. Drift guard

- [x] 3.1 Add `SysbenchFlagsDocumentedTest`
      (`src/test/kotlin/com/rustyrazorblade/easydblab/commands/kit/SysbenchFlagsDocumentedTest.kt`),
      which loads the bundled sysbench `kit.yaml` and asserts every declared flag has a row
      in the user guide's Flags table — presence only, never defaults or wording — so the
      documentation gap this change closes cannot silently reopen.
- [x] 3.2 Declare `docs/user-guide/sysbench.md` as an input of the `test` task in
      `build.gradle.kts`, so an edit dropping a flag row cannot be masked by an up-to-date
      or cached test result.

## 4. Verification

- [x] 4.1 Confirm `docs/user-guide/sysbench.md` still builds cleanly with mdbook (per
      `docs/CLAUDE.md`) and that the new section reads consistently with the rest of the
      page's style.
- [x] 4.2 Confirm `kit.yaml` still parses/validates and that the updated `--rate`
      description renders correctly on its actual user-facing surface,
      `sysbench-<target> start --help`.
      Verified: `kit info sysbench` loads and parses the file (via
      `InstallTemplateResolver.loadInstallConfig`) with no error. `kit info` renders only
      top-level `args`, not per-command args, so the `--rate` description does not appear
      in its output; it reaches the user as picocli help text on the installed kit's start
      command (`sysbench-<target> start --help`) via
      `KitRunnerCommandFactory.argOptionSpec`.
