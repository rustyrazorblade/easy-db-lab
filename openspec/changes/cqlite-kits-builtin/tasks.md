## 1. Port cqlite-flight kit

- [ ] 1.1 Copy `cqlite-flight/` from `pmcfadin/cqlite/easy-db-lab-kits/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-flight/` (kit.yaml, daemonset.yaml.template, bin/*.sh.template, dashboards/cqlite-flight.json)
- [ ] 1.2 Rename `README.md` → `README.md.template` (D2 — it contains `__VAR__` placeholders that must render)
- [ ] 1.3 Verify no source edits to the daemonset's ported logic (#2114 multi-disk detector, #2158 Recreate strategy) — port verbatim
- [ ] 1.4 Confirm `kit.yaml` `type: db`, args (`--tag`, `--flight-port`, `--data-dir`, `--data-root`, `--data-gid`, `--otel-endpoint`), and `nodeSelector: type=db` are intact

## 2. Port cqlite-trino kit

- [ ] 2.1 Copy `trino-cqlite/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-trino/` (D4 — resource dir named `cqlite-trino`)
- [ ] 2.2 Confirm `kit.yaml` keeps `name: cqlite` (D4 — installed kit/catalog stays `cqlite` for clean `cqlite.<ks>.<tbl>` SQL) and `connector.name=cqlite_flight` in the properties template
- [ ] 2.3 Port `gradle-assemble-plugin/{build.gradle.kts.template,settings.gradle.kts}` verbatim (D1 — relies on full-tree materialization at install)
- [ ] 2.4 Confirm `bin/*.sh.template` (install, start, stop, uninstall, verify, reapply-plugin-patch, ensure-catalog-registered) ported verbatim, incl. #2290 add-opens handling and the required `--sidecar-uri` arg; README.md.template kept as-is

## 3. Port trino-loadtest kit

- [ ] 3.1 Copy `trino-loadtest/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/trino-loadtest/` (kit.yaml, driver.py, bin/*.sh.template, README.md)
- [ ] 3.2 Do NOT copy `test_driver.py` (D5 — non-runtime test code)
- [ ] 3.3 Confirm `kit.yaml` `type: app` and `--target` kit-ref with `capability: sql` (satisfied by the built-in trino kit)

## 4. Guard test

- [ ] 4.1 Extend `src/test/kotlin/com/rustyrazorblade/easydblab/services/InstallTemplateResolverTest.kt`: assert `loadInstallConfig` parses for `cqlite-flight`, `cqlite-trino`, and `trino-loadtest` built-in sources
- [ ] 4.2 Assert the `cqlite-trino` source listing includes `gradle-assemble-plugin/build.gradle.kts.template` and `gradle-assemble-plugin/settings.gradle.kts`
- [ ] 4.3 Assert the `trino-loadtest` source listing includes `driver.py` and excludes `test_driver.py`

## 5. Plan migration

- [ ] 5.1 Edit `test-plans/cqlite-flight-production-readiness.md`: remove the P0.6 `kit source add` step and reference the built-in kits (`kit install cqlite-flight` / `cqlite-trino` / `trino-loadtest` directly)
- [ ] 5.2 Delete `test-plans/cqlite-flight-milestone-snapshot-0.15.md` and `test-plans/cqlite-flight-0.16.0-rc1-fixvalidation.md` (superseded checkpoints)
- [ ] 5.3 Absorb the durable, non-version-specific setup guidance from `clusters/HANDOFF-cqlite-lab-testing.md` (tunnel/keepalive, Cassandra step ordering, digest discipline) into the general plan's prelude, so the plan stands alone in-tree after the checkpoints are deleted (no dangling out-of-tree runbook reference)
- [ ] 5.4 Grep-verify: zero matches for `kit source add` / `pmcfadin/cqlite/easy-db-lab-kits` across `test-plans/*cqlite*.md`

## 6. Docs & verification

- [ ] 6.1 Add per-kit user-guide pages following the existing convention (`docs/user-guide/cqlite-flight.md`, `cqlite-trino.md`, `trino-loadtest.md`, matching `sysbench.md`/`tidb.md` style), stating the offline / read-only / flushed-only / eventually-stale semantics (spec R7)
- [ ] 6.2 Register the new pages in `docs/SUMMARY.md` nav and reference them from `docs/user-guide/kits.md` (so the docs are not silently incomplete)
- [ ] 6.3 Update `docs/development/kits.md` to note the three new built-in kits
- [ ] 6.4 Run the fast unit tier (`./gradlew test`) on JDK 21 and confirm the new guard test passes
- [ ] 6.5 Run `ktlintCheck` / `detekt` (JDK 21) — no regressions
