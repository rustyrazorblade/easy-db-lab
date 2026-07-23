## 1. Port cqlite-flight kit

- [x] 1.1 Copy `cqlite-flight/` from `pmcfadin/cqlite/easy-db-lab-kits/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-flight/` (kit.yaml, daemonset.yaml.template, bin/*.sh.template, dashboards/cqlite-flight.json)
- [x] 1.2 Rename `README.md` → `README.md.template` (D2 — it contains `__VAR__` placeholders that must render)
- [x] 1.3 Verify no source edits to the daemonset's ported logic (#2114 multi-disk detector, #2158 Recreate strategy) — port verbatim
- [x] 1.4 Confirm `kit.yaml` `type: db`, args (`--tag`, `--flight-port`, `--data-dir`, `--data-root`, `--data-gid`, `--otel-endpoint`), and `nodeSelector: type=db` are intact

## 2. Port cqlite-trino kit

- [x] 2.1 Copy `trino-cqlite/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-trino/` (D4 — resource dir named `cqlite-trino`)
- [x] 2.2 Confirm `kit.yaml` keeps `name: cqlite` (D4 — installed kit/catalog stays `cqlite` for clean `cqlite.<ks>.<tbl>` SQL) and `connector.name=cqlite_flight` in the properties template
- [x] 2.3 Port `gradle-assemble-plugin/{build.gradle.kts.template,settings.gradle.kts}` verbatim (D1 — relies on full-tree materialization at install)
- [x] 2.4 Confirm `bin/*.sh.template` (install, start, stop, uninstall, verify, reapply-plugin-patch, ensure-catalog-registered) ported verbatim, incl. #2290 add-opens handling and the required `--sidecar-uri` arg; README.md.template kept as-is

## 2b. Refactor cqlite-trino wiring to Helm values (D1 revised — maintainer feedback)

- [x] 2b.1 Extend `kits/trino/bin/update-catalogs.sh.template` to also glob sibling kit dirs for a `trino-values.yaml` fragment and pass each as an additional `--values` to the existing `helm upgrade` (symmetric to the current `trino-catalog.properties` discovery)
- [x] 2b.2 Add `cqlite-trino/trino-values.yaml.template` declaring, under `coordinator:`/`worker:`: the assemble-plugin `initContainers`, an `emptyDir` in `additionalVolumes` (+ the gradle-recipe ConfigMap), `additionalVolumeMounts` at `/usr/lib/trino/plugin/cqlite_flight`, and the `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED` flag via `additionalJVMConfig`
- [x] 2b.3 Delete the out-of-band machinery: `bin/reapply-plugin-patch.sh.template`, `bin/ensure-catalog-registered.sh.template`, `bin/install.sh.template`, the unfiltered `post-workload-*` hooks in `kit.yaml`, and the RFC-6902 patch-stripping in `bin/uninstall.sh.template` (uninstall becomes: remove the fragment + re-run update-catalogs)
- [x] 2b.4 Keep: `bin/start.sh` SPI-tag preflight, `trino-catalog.properties.template`, `bin/verify.sh`, and `gradle-assemble-plugin/` (now consumed by the initContainer)
- [x] 2b.5 Update the guard test (4.x) and cqlite-trino README to reflect the values-fragment wiring; confirm no `kubectl patch` / re-patch-hook references remain

## 3. Port trino-loadtest kit

- [x] 3.1 Copy `trino-loadtest/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/trino-loadtest/` (kit.yaml, driver.py, bin/*.sh.template, README.md)
- [x] 3.2 Do NOT copy `test_driver.py` (D5 — non-runtime test code)
- [x] 3.3 Confirm `kit.yaml` `type: app` and `--target` kit-ref with `capability: sql` (satisfied by the built-in trino kit)

## 4. Guard test

- [x] 4.1 Extend `src/test/kotlin/com/rustyrazorblade/easydblab/services/InstallTemplateResolverTest.kt`: assert `loadInstallConfig` parses for `cqlite-flight`, `cqlite-trino`, and `trino-loadtest` built-in sources
- [x] 4.2 Assert the `cqlite-trino` source listing includes `gradle-assemble-plugin/build.gradle.kts.template` and `gradle-assemble-plugin/settings.gradle.kts`
- [x] 4.3 Assert the `trino-loadtest` source listing includes `driver.py` and excludes `test_driver.py`

## 5. Plan migration

- [x] 5.1 Edit `test-plans/cqlite-flight-production-readiness.md`: remove the P0.6 `kit source add` step and reference the built-in kits (`kit install cqlite-flight` / `cqlite-trino` / `trino-loadtest` directly)
- [x] 5.2 Delete `test-plans/cqlite-flight-milestone-snapshot-0.15.md` and `test-plans/cqlite-flight-0.16.0-rc1-fixvalidation.md` (superseded checkpoints)
- [x] 5.3 Absorb the durable, non-version-specific setup guidance from `clusters/HANDOFF-cqlite-lab-testing.md` (tunnel/keepalive, Cassandra step ordering, digest discipline) into the general plan's prelude, so the plan stands alone in-tree after the checkpoints are deleted (no dangling out-of-tree runbook reference)
- [x] 5.4 Grep-verify: zero matches for `kit source add` / `pmcfadin/cqlite/easy-db-lab-kits` across `test-plans/*cqlite*.md`

## 6. Docs & verification

- [x] 6.1 Add per-kit user-guide pages following the existing convention (`docs/user-guide/cqlite-flight.md`, `cqlite-trino.md`, `trino-loadtest.md`, matching `sysbench.md`/`tidb.md` style), stating the offline / read-only / flushed-only / eventually-stale semantics (spec R7)
- [x] 6.2 Register the new pages in `docs/SUMMARY.md` nav and reference them from `docs/user-guide/kits.md` (so the docs are not silently incomplete)
- [x] 6.3 Update `docs/development/kits.md` to note the three new built-in kits
- [x] 6.4 Run the fast unit tier (`./gradlew test`) on JDK 21 and confirm the new guard test passes
- [x] 6.5 Run `ktlintCheck` / `detekt` (JDK 21) — no regressions
