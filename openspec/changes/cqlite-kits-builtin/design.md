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

**D1 (REVISED after maintainer review on PR #859) — Wire the connector through the Trino Helm chart's native values, not out-of-band `kubectl patch`.** The connector jar tree cannot be a single mounted jar: `cqlite_flight` is a third-party connector (unlike the cassandra/clickhouse/tidb catalogs, which are baked into the stock `trinodb/trino` image), and Trino loads each plugin from an isolated classloader dir needing the jar **plus its full pinned runtime tree** — Arrow `flight-core:19.0.0`, 11 explicitly-pinned `io.netty` modules (a naive resolve floats netty to 4.2.x and crashes on first read), jackson-databind — tens of MB across dozens of jars, exceeding the ~1 MB ConfigMap ceiling. So the Gradle `Sync` assembly step is **load-bearing and stays** (as an initContainer).

The *problem* the maintainer correctly identified is that the ported kit wires the plugin via an **out-of-band `kubectl patch --type=strategic` on the live Trino Deployments** (`reapply-plugin-patch.sh.template`), which every `helm upgrade` wipes — and ~4 scripts of resiliency scar tissue exist only to fight that self-inflicted problem (re-patch-on-every-hook, `Recreate`-strategy force #2158, ~90 lines of `JAVA_TOOL_OPTIONS`-clobber avoidance #2290, catalog self-heal #2159, RFC-6902 patch reversal on uninstall).

**Refactor:** express the whole wiring as a **Helm values fragment** applied through the trino kit's existing `helm upgrade` seam:
- The catalog half already matches the standard pattern (sibling `trino-catalog.properties` auto-discovered by `kits/trino/bin/update-catalogs.sh.template:41-59`) — unchanged.
- **Extend `kits/trino/bin/update-catalogs.sh.template`** to also glob siblings for a `trino-values.yaml` fragment and add each as another `--values` to its single `helm upgrade` — symmetric to the catalog discovery it already does. (Editing the trino kit is now clean because PR #859 brings both kits into one repo, removing the external-repo "never edit trino" constraint that forced the patch approach.)
- **`cqlite-trino` ships a `trino-values.yaml.template`** declaring, under `coordinator:`/`worker:`: the assemble-plugin `initContainers`, an `emptyDir` `additionalVolumes` (+ the gradle-recipe ConfigMap), `additionalVolumeMounts` at `/usr/lib/trino/plugin/cqlite_flight`, and the `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED` via `additionalJVMConfig` (additive by construction — deletes the JAVA_TOOL_OPTIONS-clobber logic).
- **Delete:** `reapply-plugin-patch.sh.template`, `ensure-catalog-registered.sh.template`, `install.sh.template`, the unfiltered `post-workload-*` hooks in `kit.yaml`, and the RFC-6902 stripping in `uninstall.sh.template`. **Keep:** the SPI-tag preflight in `start.sh`, `trino-catalog.properties.template`, `verify.sh`, and the `gradle-assemble-plugin/` recipe (now consumed by the initContainer).

*Behavioral change to note:* `cqlite start` no longer hot-patches a live pod — it re-runs `helm upgrade` (same as the trino kit's own catalog flow), so the wiring becomes part of the rendered pod template and survives upgrades natively. *Alternatives:* single mounted jar (Option C — not viable, transitive deps); bake a custom Trino image with the plugin pre-installed (Option B — cleanest runtime but needs an image build+publish pipeline; deferred as a possible follow-up). *Rejected/deferred respectively.*

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
