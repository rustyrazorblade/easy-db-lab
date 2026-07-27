## Why

The cqlite analytical stack — an Arrow Flight SSTable reader, a Trino connector, and a read-load driver — lives as three kits in the external `pmcfadin/cqlite` repo and is only usable in easy-db-lab via `easy-db-lab kit source add cqlite /path/to/cqlite/easy-db-lab-kits`. That out-of-tree dependency blocks the `cqlite-flight` production-readiness test plan from being committed here, so the cqlite capability cannot ship or be validated as a first-class easy-db-lab feature. Bringing the kits in-tree removes the external dependency and unblocks the plan.

## What Changes

- Port two kits into `src/main/resources/com/rustyrazorblade/easydblab/kits/` as **built-in kits**, discoverable via the classpath resolver with **no `kit source add`**:
  - `cqlite-flight` (`type: db`) — Arrow Flight server DaemonSet, one pod per db node, Cassandra data dir mounted in.
  - `trino-loadtest` (`type: app`) — generic Trino read-load driver with cqlite defaults.
- Fold cqlite Trino integration into the **trino kit as a catalog** (maintainer required change): when delivered it will be `kits/trino/catalogs/cqlite.properties.template` with `connector.name=cqlite_flight`, auto-discovered by the trino kit's `update-catalogs.sh` and applied via its single `helm upgrade` — exactly like cassandra/clickhouse/opensearch/tidb. **There is no standalone `cqlite-trino` kit and no `kit install cqlite-trino` command.** The catalog file itself is **DEFERRED** (blocked on #2869) and not shipped in this change — its placeholders are not yet resolvable, so shipping it would fire a spurious unresolved-variable warning on every `kit install trino`. Today the trino kit is unchanged by cqlite.
- This is a **port, not a rewrite** for the two kits: pure resources; no Kotlin, DI, or build wiring changes. All hard-won pod-side logic (multi-disk `#2114` detector, Arrow `--add-opens` handling) is carried over verbatim.
- **DEFERRED (blocked on pmcfadin/cqlite#2869):** the `cqlite_flight` connector plugin will ship as a single self-contained Shadow fat jar fetched once to a versioned per-node hostPath and mounted into `/usr/lib/trino/plugin/cqlite_flight/`. That artifact does not exist yet. The old pod-start Gradle resolve (`gradle-assemble-plugin/` + `trino-values.yaml` initContainer) is **removed**; `update-catalogs.sh` carries a documented TODO for the fat-jar delivery, and the intended catalog-file content is recorded in design.md. Neither the catalog file nor the plugin mount is shipped until #2869 lands.
- Owner-settled adaptations to built-in conventions:
  - Rename `cqlite-flight/README.md` → `README.md.template` (it contains `__VAR__` placeholders that must be rendered, not shipped literally).
  - Ship `trino-loadtest/driver.py` only; **drop** `test_driver.py` (non-runtime test code, not referenced by any script).
- Add a guard unit test asserting the two new kits' `kit.yaml` parse, that the trino source lists its real catalogs but NO cqlite catalog file (deferred — guarding the install-time UX against a spurious unresolved-variable warning), and that `cqlite-trino` no longer resolves as a standalone kit.
- Plan migration:
  - Update `test-plans/cqlite-flight-production-readiness.md` to drop the `kit source add` prelude step and reference the built-in kits.
  - **Delete** the two version-pinned checkpoint plans (`cqlite-flight-milestone-snapshot-0.15.md`, `cqlite-flight-0.16.0-rc1-fixvalidation.md`) as superseded by the general plan.
- Update user docs (`docs/development/kits.md` / per-kit docs) to reflect the three new built-in kits.

## Capabilities

### New Capabilities
- `cqlite-kits`: the two built-in cqlite kits (Flight SSTable reader, read-load driver) plus the trino kit's `cqlite` catalog, their lifecycle, node placement, catalog registration semantics, and read-only/offline/flushed-only guarantees. Actual connector-plugin delivery is a deferred follow-up (pmcfadin/cqlite#2869).

### Modified Capabilities
<!-- The trino kit gains a `cqlite` catalog property file; the resolver and installer are untouched. The port relies on existing install-command / workload-install-config / template-subdirectory-support behavior without changing their requirements. -->

## Impact

- **New resources**: two kit directories under `src/main/resources/com/rustyrazorblade/easydblab/kits/{cqlite-flight,trino-loadtest}/`. The trino kit's `catalogs/cqlite.properties.template` is DEFERRED (not shipped until #2869); the existing trino kit is otherwise unchanged.
- **New test**: extends `src/test/kotlin/com/rustyrazorblade/easydblab/services/InstallTemplateResolverTest.kt` (parse guards + trino-catalog listing + standalone-kit-absence guard).
- **Docs**: `docs/development/kits.md`, `docs/user-guide/{kits,install-trino,cqlite-flight,trino-loadtest}.md`, `docs/SUMMARY.md`; the standalone `docs/user-guide/cqlite-trino.md` page is deleted; `test-plans/cqlite-flight-production-readiness.md` edited; two checkpoint plans deleted.
- **No code, DI, or build-config changes.** Additive-only blast radius (plus one new catalog file in the trino kit); the classpath resolver is untouched. Clusters are ephemeral — no backward-compat concern.
- **External runtime dependencies**: published image `ghcr.io/pmcfadin/cqlite-flight:<tag>`; the `cqlite_flight` connector fat jar is **deferred, tracked in pmcfadin/cqlite#2869** (not yet published).
