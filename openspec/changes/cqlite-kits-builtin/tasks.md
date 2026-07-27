## 1. Port cqlite-flight kit

- [x] 1.1 Copy `cqlite-flight/` from `pmcfadin/cqlite/easy-db-lab-kits/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-flight/` (kit.yaml, daemonset.yaml.template, bin/*.sh.template, dashboards/cqlite-flight.json)
- [x] 1.2 Rename `README.md` → `README.md.template` (D2 — it contains `__VAR__` placeholders that must render)
- [x] 1.3 Verify no source edits to the daemonset's ported logic (#2114 multi-disk detector, #2158 Recreate strategy) — port verbatim
- [x] 1.4 Confirm `kit.yaml` `type: db`, args (`--tag`, `--flight-port`, `--data-dir`, `--data-root`, `--data-gid`, `--otel-endpoint`), and `nodeSelector: type=db` are intact

## 2. Fold cqlite into the trino kit as a catalog (D1 — maintainer required change)

- [x] 2.1 DEFERRED — do NOT ship `kits/trino/catalogs/cqlite.properties.template` yet. Its `__SIDECAR_URI__`/`__FLIGHT_PORT__`/`__READ_MODE__`/`__LOCAL_DATACENTER__` placeholders are neither global `TemplateVariables` nor trino kit args, so shipping it makes `BaseInstallCommand.renderAndWrite` render it on every `kit install trino` and emit a spurious `Event.Install.UnresolvedVariables` warning on the existing trino kit. The intended catalog content (`connector.name=cqlite_flight` + the 4 placeholders + fat-jar hostPath-mount plan) is recorded in design.md D1; add the file when #2869 lands (see 2b.4)
- [x] 2.2 Delete the entire standalone `kits/cqlite-trino/` directory (kit.yaml, all `bin/*.sh.template`, README.md.template, gradle-assemble-plugin/, trino-values.yaml.template, trino-catalog.properties.template) — no `kit install cqlite-trino` command remains
- [x] 2.3 Remove the sibling-`trino-values.yaml` discovery loop from `kits/trino/bin/update-catalogs.sh.template`; leave a documented TODO for the deferred fat-jar hostPath mount (blocked on pmcfadin/cqlite#2869)
- [x] 2.4 Delete the standalone `docs/user-guide/cqlite-trino.md`; document the `cqlite` catalog as part of the trino kit (install-trino.md, trino README.md.template) and fix `docs/SUMMARY.md` nav

## 2b. DEFERRED — connector plugin fat-jar delivery (blocked on pmcfadin/cqlite#2869)

- [ ] 2b.1 (BLOCKED) Once the self-contained Shadow fat jar is published in the cqlite GitHub Releases, wire a download-if-missing initContainer → versioned per-node hostPath cache → volumeMount into `/usr/lib/trino/plugin/cqlite_flight/` (nodeSelector type=app) as an extra `--values` fragment on the trino kit's `helm upgrade`
- [ ] 2b.2 (BLOCKED) Enable the `cqlite` catalog end-to-end and un-block steps 6–9 of the production-readiness plan
- [x] 2b.3 Removed the retired pod-start Gradle resolve (`gradle-assemble-plugin/`, `trino-values.yaml` initContainer) — no fake jar URL, no Gradle resolve shipped; TODO documented in update-catalogs.sh and design.md D1
- [ ] 2b.4 (BLOCKED) Add `kits/trino/catalogs/cqlite.properties.template` when #2869 lands, ensuring its `__SIDECAR_URI__`/`__FLIGHT_PORT__`/`__READ_MODE__`/`__LOCAL_DATACENTER__` placeholders resolve cleanly (declare them as global `TemplateVariables` or as trino kit args) so it renders without the unresolved-variable warning. Also wire `cqlite` explicitly in `update-catalogs.sh` — special-case it like the opensearch infra-catalog check, since no running kit is named `cqlite` (the kit is `cqlite-flight`), so the basename-match loop over `RUNNING_KITS` will not pick it up on its own

## 3. Port trino-loadtest kit

- [x] 3.1 Copy `trino-loadtest/` into `src/main/resources/com/rustyrazorblade/easydblab/kits/trino-loadtest/` (kit.yaml, driver.py, bin/*.sh.template, README.md)
- [x] 3.2 Do NOT copy `test_driver.py` (D5 — non-runtime test code)
- [x] 3.3 Confirm `kit.yaml` `type: app` and `--target` kit-ref with `capability: sql` (satisfied by the built-in trino kit)

## 4. Guard test

- [x] 4.1 Extend `src/test/kotlin/com/rustyrazorblade/easydblab/services/InstallTemplateResolverTest.kt`: assert `loadInstallConfig` parses for `cqlite-flight` and `trino-loadtest` built-in sources
- [x] 4.2 Assert the trino source listing includes its real catalogs (e.g. `catalogs/cassandra.properties.template`) but NO cqlite catalog file (deferred — guards the install-time unresolved-variable regression), and that `cqlite-trino` no longer resolves as a standalone kit
- [x] 4.3 Assert the `trino-loadtest` source listing includes `driver.py` and excludes `test_driver.py`

## 5. Plan migration

- [x] 5.1 Edit `test-plans/cqlite-flight-production-readiness.md`: remove the P0.6 `kit source add` step and reference the built-in kits (`kit install cqlite-flight` / `trino-loadtest`); replace the `kit install cqlite-trino` step with the trino-kit `cqlite` catalog reality and mark the end-to-end query steps blocked-on-#2869
- [x] 5.2 Delete `test-plans/cqlite-flight-milestone-snapshot-0.15.md` and `test-plans/cqlite-flight-0.16.0-rc1-fixvalidation.md` (superseded checkpoints)
- [x] 5.3 Absorb the durable, non-version-specific setup guidance from `clusters/HANDOFF-cqlite-lab-testing.md` (tunnel/keepalive, Cassandra step ordering, digest discipline) into the general plan's prelude, so the plan stands alone in-tree after the checkpoints are deleted (no dangling out-of-tree runbook reference)
- [x] 5.4 Grep-verify: zero matches for `kit source add` / `pmcfadin/cqlite/easy-db-lab-kits` across `test-plans/*cqlite*.md`

## 6. Docs & verification

- [x] 6.1 Add per-kit user-guide pages following the existing convention (`docs/user-guide/cqlite-flight.md`, `trino-loadtest.md`, matching `sysbench.md`/`tidb.md` style) and document the `cqlite` catalog in `docs/user-guide/install-trino.md`, stating the offline / read-only / flushed-only / eventually-stale semantics and the deferred plugin delivery (spec R7)
- [x] 6.2 Register the new pages in `docs/SUMMARY.md` nav and reference them from `docs/user-guide/kits.md` (so the docs are not silently incomplete)
- [x] 6.3 Update `docs/development/kits.md` to note the three new built-in kits
- [x] 6.4 Run the fast unit tier (`./gradlew test`) on JDK 21 and confirm the new guard test passes
- [x] 6.5 Run `ktlintCheck` / `detekt` (JDK 21) — no regressions
