# Acceptance criteria coverage

| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | `bin/start.sh` but no `kit.yaml` → not registered, absent from `-h` | `workload-runner: Dir without kit.yaml is not registered`; `workload-runner: Help omits a bin/-only directory` | ✅ Covered |
| AC | Holds `kit.yaml` → registered under the directory name | `workload-runner: Kit dir with kit.yaml discovered`; `workload-runner: Multiple workloads discovered`; `install-command: An installed kit directory carries its descriptor` | ✅ Covered |
| AC | Holds both `kit.yaml` and `bin/start.sh` → `<kit> start` stays valid | `workload-runner: Kit dir with kit.yaml discovered` | ✅ Covered |
| AC | Holds neither marker → not registered | `workload-runner: Dir with neither marker is not registered` | ✅ Covered |
| AC | `status` lists only the `kit.yaml` directory | `workload-runner: status lists only directories holding kit.yaml` | ✅ Covered |
| AC | Only `bin/`-only dirs → no `=== KITS ===` section | `workload-runner: status omits the KITS section when nothing qualifies` | ✅ Covered |
| AC | Predicate changes → both call sites change; no inline copy remains | `workload-runner: Both listings agree` (requirement: Filesystem kit discovery has a single source of truth) | ✅ Covered |
| Owner | `kit.yaml` invariant: every non-Kotlin kit directory has one, no exceptions | `install-command: Kit descriptor filename` (requirement text) + `An installed kit directory carries its descriptor` | ✅ Covered |
| Risk | `kit install --from <dir>` with no `kit.yaml` installs a kit that no longer registers, silently | `install-command: --from rejects a template without kit.yaml`; `install-command: --from accepts a template with kit.yaml` | ✅ Covered |
| Risk | Kits installed via `kit install <name>` could lose registration | `install-command: An installed kit directory carries its descriptor` | ✅ Covered |
| Risk | `CommandLineParser` registration path is not directly tested end to end | `CommandLineParserTest` — renders `--help` over a workspace holding `clickhouse/kit.yaml` and an executable `trunk/bin/cassandra`, asserting `clickhouse` is listed and `trunk` is not | ✅ Covered — `design.md` records this as excluded on the premise that a test needed three mocked Koin dependencies. That premise was wrong: the test needs five real Koin definitions and no mocks, and costs 3.891s in the parallel run. Added in the review fix round, and mutation-tested — re-inlining a `bin/` predicate turns it red. |
| Risk | `CommandLineParser` loses its last `java.io.File` use; stale import fails ktlint | — | ⚠️ Excluded — build-mechanical, not a behavior contract. Caught by task 5.1 (`ktlintCheck`), and called out explicitly in task 2.3. |
