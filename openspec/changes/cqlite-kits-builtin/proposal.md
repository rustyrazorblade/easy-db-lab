## Why

The cqlite analytical stack — an Arrow Flight SSTable reader, a Trino connector, and a read-load driver — lives as three kits in the external `pmcfadin/cqlite` repo and is only usable in easy-db-lab via `easy-db-lab kit source add cqlite /path/to/cqlite/easy-db-lab-kits`. That out-of-tree dependency blocks the `cqlite-flight` production-readiness test plan from being committed here, so the cqlite capability cannot ship or be validated as a first-class easy-db-lab feature. Bringing the kits in-tree removes the external dependency and unblocks the plan.

## What Changes

- Port three kits into `src/main/resources/com/rustyrazorblade/easydblab/kits/` as **built-in kits**, discoverable via the classpath resolver with **no `kit source add`**:
  - `cqlite-flight` (`type: db`) — Arrow Flight server DaemonSet, one pod per db node, Cassandra data dir mounted in.
  - `cqlite-trino` (resource dir; installs as kit/catalog name `cqlite` per its `kit.yaml`) — loads the published `in.mcfad:cqlite-trino` connector and registers the `cqlite` Trino catalog additively.
  - `trino-loadtest` (`type: app`) — generic Trino read-load driver with cqlite defaults.
- This is a **port, not a rewrite**: the kits are pure resources; no Kotlin, DI, or build wiring changes. All hard-won pod-side logic (multi-disk `#2114` detector, Arrow `--add-opens` handling, Recreate-strategy fix) is carried over verbatim.
- Owner-settled adaptations to built-in conventions:
  - Resource dir `cqlite-trino` (→ `kit install cqlite-trino`) with `kit.yaml` `name: cqlite` (→ clean SQL `cqlite.<ks>.<tbl>` catalog).
  - Rename `cqlite-flight/README.md` → `README.md.template` (it contains `__VAR__` placeholders that must be rendered, not shipped literally).
  - Ship `trino-loadtest/driver.py` only; **drop** `test_driver.py` (non-runtime test code, not referenced by any script).
  - Port `cqlite-trino/gradle-assemble-plugin/` verbatim (works via full-tree materialization at install time).
- Add a guard unit test asserting the three new kits' `kit.yaml` parse and that `cqlite-trino`'s `gradle-assemble-plugin/{build.gradle.kts.template,settings.gradle.kts}` are listed by the resolver.
- Plan migration:
  - Update `test-plans/cqlite-flight-production-readiness.md` to drop the `kit source add` prelude step and reference the built-in kits.
  - **Delete** the two version-pinned checkpoint plans (`cqlite-flight-milestone-snapshot-0.15.md`, `cqlite-flight-0.16.0-rc1-fixvalidation.md`) as superseded by the general plan.
- Update user docs (`docs/development/kits.md` / per-kit docs) to reflect the three new built-in kits.

## Capabilities

### New Capabilities
- `cqlite-kits`: the three built-in cqlite kits (Flight SSTable reader, Trino connector/catalog, read-load driver), their lifecycle, dependency/fail-fast rules, node placement, catalog registration semantics, and read-only/offline/flushed-only guarantees.

### Modified Capabilities
<!-- None: the resolver, installer, and existing kits are untouched. The port relies on existing install-command / workload-install-config / template-subdirectory-support behavior without changing their requirements. -->

## Impact

- **New resources**: three kit directories under `src/main/resources/com/rustyrazorblade/easydblab/kits/{cqlite-flight,cqlite-trino,trino-loadtest}/`.
- **New test**: extends `src/test/kotlin/com/rustyrazorblade/easydblab/services/InstallTemplateResolverTest.kt` (parse + gradle-assemble file-listing guards).
- **Docs**: `docs/development/kits.md` and/or per-kit docs; `test-plans/cqlite-flight-production-readiness.md` edited; two checkpoint plans deleted.
- **No code, DI, or build-config changes.** Additive-only blast radius; existing kits and the classpath resolver are untouched. Clusters are ephemeral — no backward-compat concern.
- **External runtime dependencies (unchanged, not built here)**: published image `ghcr.io/pmcfadin/cqlite-flight:<tag>` and Maven artifact `in.mcfad:cqlite-trino:<version>`.
