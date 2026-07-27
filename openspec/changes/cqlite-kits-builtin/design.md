## Context

The cqlite analytical stack lives as three kits in `pmcfadin/cqlite/easy-db-lab-kits/` and is consumed via `kit source add`. easy-db-lab already supports built-in kits as pure classpath resources under `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/` (clickhouse, presto, sysbench, trino, …), discovered by `InstallTemplateResolver` and installed by `BaseInstallCommand`. The task is to move the Flight/load-driver kits into that built-in resource root (and fold the cqlite Trino integration into the trino kit as a catalog file, per D1) and adapt them to built-in conventions — a port, not a rewrite.

The load-bearing fact that makes this nearly free: at `kit install` time, `BaseInstallCommand.renderAndWrite` **materializes the entire kit directory tree to disk** (`BaseInstallCommand.kt:33-53`) — every entry `InstallTemplateResolver.listTemplateFiles` returns, preserving nested subdirs (`bin/`, `dashboards/`, `catalogs/`) via `tempFile.parentFile.mkdirs()`. For built-in kits, `listTemplateFiles` returns entries scanned by ClassGraph (`InstallTemplateResolver.kt:54-68`). So a built-in kit and a `--from` directory kit produce **byte-identical on-disk layouts** after install. Scripts run from that materialized dir (`KitRunnerCommand.kt:200-201`) and read siblings via `SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`.

## Goals / Non-Goals

**Goals:**
- `cqlite-flight` and `trino-loadtest` become built-in kits, installable/startable with no `kit source add`.
- cqlite Trino integration becomes a **catalog property file of the trino kit** (`kits/trino/catalogs/cqlite.properties.template`) alongside cassandra/clickhouse/opensearch/tidb — NOT a standalone kit (D1, maintainer required change).
- Preserve the Flight/load-driver kits' external working behavior verbatim (a port).
- Unblock and update the general production-readiness test plan; retire the version-pinned checkpoint plans.

**Deferred (blocked on pmcfadin/cqlite#2869):**
- Actual `cqlite_flight` connector plugin delivery (a single Shadow fat jar fetched to a versioned per-node hostPath, mounted into `/usr/lib/trino/plugin/cqlite_flight/`). The catalog file ships staged; the plugin mount is not wired until the artifact is published. See D1.

**Non-Goals:**
- Building the cqlite connector or Arrow Flight image (both are published artifacts).
- Write/DDL support, live-consistency semantics, Presto targeting, SSTable backup/restore.
- Redesigning the kits' arg surface, scripts, or internal architecture beyond the minimal adaptations below.
- Changing the resolver, installer, or any existing kit.

## Decisions

**D1 (REVISED AGAIN — maintainer required change, this PR) — cqlite is a trino catalog; the connector plugin ships as a single fat jar fetched to a per-node hostPath. Plugin delivery is DEFERRED (blocked on pmcfadin/cqlite#2869).**

The maintainer requires that cqlite NOT be a standalone Trino-integration kit. cassandra/clickhouse/opensearch/tidb are catalog property files under `kits/trino/catalogs/*.properties.template`, auto-discovered by the trino kit's `update-catalogs.sh` and applied via its single `helm upgrade`; **cqlite gets the same treatment** — a `kits/trino/catalogs/cqlite.properties.template` with `connector.name=cqlite_flight` and the `__SIDECAR_URI__`/`__FLIGHT_PORT__`/`__READ_MODE__`/`__LOCAL_DATACENTER__` placeholders, rendered from the trino kit's install-time template variables. There is no separate `cqlite-trino` resource dir and no `kit install cqlite-trino` command.

The maintainer also requires the connector be shipped as a **single self-contained Shadow fat jar** fetched once to a **versioned per-node hostPath cache** (no pod-start Gradle resolve). That artifact does **not exist yet** — it is tracked in **pmcfadin/cqlite#2869** (a downloadable fat jar to be published in the cqlite GitHub Releases). Therefore:

- **Removed** (retired, not shipped): the entire `cqlite-trino/gradle-assemble-plugin/` pod-start Gradle resolve of `in.mcfad:cqlite-trino` from Maven Central, the `trino-values.yaml.template` initContainer-runs-Gradle fragment, and the sibling-`trino-values.yaml` discovery loop that was added to `kits/trino/bin/update-catalogs.sh.template`. Also gone with the standalone kit: `reapply-plugin-patch.sh`, `ensure-catalog-registered.sh`, `install.sh`, the unfiltered `post-workload-*` hooks, the RFC-6902 uninstall stripping, and the SPI-tag preflight.
- **Placeholder / TODO left in place of the retired machinery**: the `catalogs/cqlite.properties.template` header and a commented block in `kits/trino/bin/update-catalogs.sh.template` document the intended fat-jar delivery shape — a versioned per-node hostPath cache (e.g. `/var/lib/easydblab/cqlite-plugin/<version>/`), a download-if-missing initContainer fetching the release fat jar into that hostPath, `nodeSelector: type=app`, and a volumeMount into `/usr/lib/trino/plugin/cqlite_flight/` (a SUBDIRECTORY of the plugin path, never the plugin path itself). No fake jar URL and no Gradle resolve are introduced.

*Blocked follow-up:* once pmcfadin/cqlite#2869 publishes the fat jar, wire the download-if-missing initContainer + hostPath mount as an extra `--values` fragment on the trino kit's `helm upgrade`. Until then the `cqlite` catalog file ships as a staged template and is intentionally NOT enabled — registering it without the plugin present crashes the coordinator at boot with `No factory for connector 'cqlite_flight'`. *Rejected alternative:* baking a custom Trino image with the plugin pre-installed (needs its own image build+publish pipeline).

**D2 — README suffixing.** `renderAndWrite` only substitutes files ending in `.template` (`BaseInstallCommand.kt:34`). `cqlite-flight/README.md` contains raw `__TAG__`/`__FLIGHT_PORT__` markers, so it must become `README.md.template` or it ships literal placeholders. `trino-loadtest`'s has no placeholders and stays plain `README.md`. (There is no standalone cqlite-trino kit — see D1.)

**D3 — `cqlite-flight` node placement: DaemonSet as-is, all db nodes.** The daemonset hard-codes `nodeSelector: type=db` + `hostNetwork: true` — the same selector the Cassandra sidecar DaemonSet already uses (`SidecarManifestBuilder.kt:118`), so db nodes carry that label and one pod lands per db node. *Alternative:* pursue `#672` granular sub-node assignment — rejected as YAGNI; "one pod per db node" is exactly DaemonSet + `type=db` semantics.

**D4 — REMOVED.** The earlier D4 (kit-identity-vs-catalog-name split for a standalone `cqlite-trino` kit) is moot: per D1, cqlite is a trino catalog like the others, not a separate kit, so there is no `kit install` subcommand name to reconcile with the catalog name. The catalog is `cqlite` (from the `catalogs/cqlite.properties` filename, matching how update-catalogs.sh names catalogs) and `connector.name=cqlite_flight` (the Trino factory name) remains separate and unchanged (#2123).

**D5 — `trino-loadtest` payload.** Ship `driver.py` (the ConfigMap-staged pod payload) only; **drop** `test_driver.py` (33K of unit tests, referenced by no script). Shipping test code in the app jar and every installed workspace is dead weight; tests stay in the cqlite source repo. Port as a generic Trino read-load driver with cqlite defaults.

**Guard test.** A unit test (extending `InstallTemplateResolverTest.kt`, following the existing clickhouse-dashboard and presto-config patterns) asserts the `cqlite-flight` and `trino-loadtest` built-in kits' `loadInstallConfig` parses, that the trino source lists `catalogs/cqlite.properties.template` alongside `catalogs/cassandra.properties.template`, and that `cqlite-trino` no longer resolves as a standalone kit. This guards against a future ClassGraph/packaging change silently dropping the catalog resource or the standalone kit sneaking back in.

**Plan migration.** Edit `test-plans/cqlite-flight-production-readiness.md` to remove the `kit source add` prelude and reference built-in kits. Delete `cqlite-flight-milestone-snapshot-0.15.md` and `cqlite-flight-0.16.0-rc1-fixvalidation.md` (version-pinned checkpoints, superseded).

## Risks / Trade-offs

- **No build-time validation of built-in `kit.yaml`** → a malformed ported descriptor would only fail at runtime discovery. *Mitigation:* the guard unit test parses the built-in kits at build time.
- **cqlite catalog registered without its plugin present crashes the coordinator** (`No factory for connector 'cqlite_flight'`). *Mitigation:* the catalog file ships as a staged template and is not enabled/wired until pmcfadin/cqlite#2869 delivers the fat jar (D1); the file header, the trino README, the trino user-guide page, and the test plan all mark steps 6–9 as blocked-on-#2869.
- **Preserved pod-side logic looks "optimizable"** (the `#2114` multi-disk detector, `#2290` add-opens handling in cqlite-flight). *Mitigation:* design and spec explicitly mark these as port-verbatim with issue references; reviewers must not simplify them.
- **Large text resources** (`cqlite-flight.json` ~45K, `driver.py` ~29K) → fine; ClassGraph `contentAsString` handles UTF-8 text, and no kit carries binary payloads. *If* a future kit needs a binary asset, the string-based resolver path would need revisiting — noted, not in scope.
- **Blast radius** → additive resources only; resolver/installer/existing kits untouched. Ephemeral clusters, no backward-compat concern.

## Migration Plan

1. Copy the `cqlite-flight` and `trino-loadtest` kit dirs into the built-in resource root with the D2/D5 adaptations; add `catalogs/cqlite.properties.template` to the trino kit (D1).
2. Add the guard test; run the unit tier.
3. Edit the general plan; delete the two checkpoint plans; update kit docs.
4. Rollback is trivial (delete the resource dirs / catalog file) — no state or schema involved.

## Open Questions

None outstanding for the shipped scope. The only remaining work — actual `cqlite_flight` plugin-jar delivery (D1) — is a blocked follow-up on pmcfadin/cqlite#2869 and out of scope here.
