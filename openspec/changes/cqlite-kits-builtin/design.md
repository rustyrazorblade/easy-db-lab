## Context

The cqlite analytical stack lives as three kits in `pmcfadin/cqlite/easy-db-lab-kits/` and is consumed via `kit source add`. easy-db-lab already supports built-in kits as pure classpath resources under `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/` (clickhouse, presto, sysbench, trino, …), discovered by `InstallTemplateResolver` and installed by `BaseInstallCommand`. The task is to move the three kits into that built-in resource root and adapt them to built-in conventions — a port, not a rewrite.

The load-bearing fact that makes this nearly free: at `kit install` time, `BaseInstallCommand.renderAndWrite` **materializes the entire kit directory tree to disk** (`BaseInstallCommand.kt:33-53`) — every entry `InstallTemplateResolver.listTemplateFiles` returns, preserving nested subdirs (`bin/`, `dashboards/`, `gradle-assemble-plugin/`) via `tempFile.parentFile.mkdirs()`. For built-in kits, `listTemplateFiles` returns entries scanned by ClassGraph (`InstallTemplateResolver.kt:54-68`). So a built-in kit and a `--from` directory kit produce **byte-identical on-disk layouts** after install. Scripts run from that materialized dir (`KitRunnerCommand.kt:200-201`) and read siblings via `SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`.

## Goals / Non-Goals

**Goals:**
- Three cqlite kits become built-in, installable/startable with no `kit source add`.
- Preserve each kit's external working behavior verbatim (a port).
- Keep the SQL-facing catalog name `cqlite` (clean `cqlite.<ks>.<tbl>` addressing) while giving the kit its own resource identity `cqlite-trino`.
- Unblock and update the general production-readiness test plan; retire the version-pinned checkpoint plans.

**Non-Goals:**
- Building the cqlite connector or Arrow Flight image (both are published artifacts).
- Write/DDL support, live-consistency semantics, Presto targeting, SSTable backup/restore.
- Redesigning the kits' arg surface, scripts, or internal architecture beyond the minimal adaptations below.
- Changing the resolver, installer, or any existing kit.

## Decisions

**D1 — Gradle-assemble files: port `gradle-assemble-plugin/` verbatim.** `reapply-plugin-patch.sh` reads `${SCRIPT_DIR}/../gradle-assemble-plugin/{build.gradle.kts,settings.gradle.kts}`. Because `renderAndWrite` materializes every non-`bin` sibling to disk, those files land next to the scripts exactly as they do for a `--from` install. `build.gradle.kts.template` (has `__CONNECTOR_VERSION__`) renders via `TemplateService`; `settings.gradle.kts` (no placeholders) copies verbatim. *Alternatives:* runtime materialize-to-temp (redundant — install *is* materialization); inline the recipe as a heredoc (rewrites structure, diverges from the connector's own documented recipe). Rejected.

**D2 — README suffixing.** `renderAndWrite` only substitutes files ending in `.template` (`BaseInstallCommand.kt:34`). `cqlite-flight/README.md` contains raw `__TAG__`/`__FLIGHT_PORT__` markers, so it must become `README.md.template` or it ships literal placeholders. `cqlite-trino`'s README is already `.template`; `trino-loadtest`'s has no placeholders and stays plain `README.md`.

**D3 — `cqlite-flight` node placement: DaemonSet as-is, all db nodes.** The daemonset hard-codes `nodeSelector: type=db` + `hostNetwork: true` — the same selector the Cassandra sidecar DaemonSet already uses (`SidecarManifestBuilder.kt:118`), so db nodes carry that label and one pod lands per db node. *Alternative:* pursue `#672` granular sub-node assignment — rejected as YAGNI; "one pod per db node" is exactly DaemonSet + `type=db` semantics.

**D4 — Kit identity vs catalog name (decoupled).** The `kit install <name>` subcommand is registered under the **resource directory name** (`CommandLineParser.kt:236,244`), while the installed directory — and thus the Trino catalog — comes from `kit.yaml` `config.name` (`KitInstallCommand.kt:152`). So resource dir `cqlite-trino` gives `kit install cqlite-trino`, and `name: cqlite` (already set in source) gives the clean `cqlite` catalog and `SELECT … FROM cqlite.<ks>.<tbl>`. `connector.name=cqlite_flight` (the Trino factory name) is separate and unchanged. This is the source authors' documented `#2123` naming split, satisfied by construction. *Alternatives:* dir `cqlite` (loses the `-trino` identity) or catalog `cqlite-trino` (hyphen requires SQL quoting, breaks all docs + the plan) — rejected.

**D5 — `trino-loadtest` payload.** Ship `driver.py` (the ConfigMap-staged pod payload) only; **drop** `test_driver.py` (33K of unit tests, referenced by no script). Shipping test code in the app jar and every installed workspace is dead weight; tests stay in the cqlite source repo. Port as a generic Trino read-load driver with cqlite defaults.

**Guard test.** Add a unit test (extending `InstallTemplateResolverTest.kt`, following the existing clickhouse-dashboard and presto-config patterns) asserting each of the three new built-in kits' `loadInstallConfig` parses, and that the `cqlite-trino` source lists `gradle-assemble-plugin/build.gradle.kts.template` + `gradle-assemble-plugin/settings.gradle.kts`. This guards against a future ClassGraph/packaging change silently dropping nested resources or a malformed `kit.yaml` surfacing only at runtime.

**Plan migration.** Edit `test-plans/cqlite-flight-production-readiness.md` to remove the `kit source add` prelude and reference built-in kits. Delete `cqlite-flight-milestone-snapshot-0.15.md` and `cqlite-flight-0.16.0-rc1-fixvalidation.md` (version-pinned checkpoints, superseded).

## Risks / Trade-offs

- **No build-time validation of built-in `kit.yaml`** → a malformed ported descriptor would only fail at runtime discovery. *Mitigation:* the guard unit test parses all three at build time.
- **Preserved pod-side logic looks "optimizable"** (broad hook filters firing on every start/stop, the `#2114` multi-disk detector, `#2290` add-opens handling). *Mitigation:* design and spec explicitly mark these as port-verbatim with issue references; reviewers must not simplify them.
- **`--sidecar-uri` is required with no safe default** — inherited UX sharp edge. *Mitigation:* ported README documents the `easy-db-lab ip db0 --private` recipe; not changed in this port.
- **Large text resources** (`cqlite-flight.json` ~45K, `driver.py` ~29K) → fine; ClassGraph `contentAsString` handles UTF-8 text, and no kit carries binary payloads. *If* a future kit needs a binary asset, the string-based resolver path would need revisiting — noted, not in scope.
- **Blast radius** → additive resources only; resolver/installer/existing kits untouched. Ephemeral clusters, no backward-compat concern.

## Migration Plan

1. Copy the three kit dirs into the built-in resource root with the D2/D4/D5 adaptations.
2. Add the guard test; run the unit tier.
3. Edit the general plan; delete the two checkpoint plans; update kit docs.
4. Rollback is trivial (delete the resource dirs) — no state or schema involved.

## Open Questions

None outstanding — all owner decisions (D1–D5, guard-test scope, plan migration) are settled.
