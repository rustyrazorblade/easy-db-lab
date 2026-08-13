## Context

The cqlite analytical stack lives as three kits in `pmcfadin/cqlite/easy-db-lab-kits/` and is consumed via `kit source add`. easy-db-lab already supports built-in kits as pure classpath resources under `src/main/resources/com/rustyrazorblade/easydblab/kits/<name>/` (clickhouse, presto, sysbench, trino, …), discovered by `InstallTemplateResolver` and installed by `BaseInstallCommand`. The task is to move the Flight/load-driver kits into that built-in resource root (and fold the cqlite Trino integration into the trino kit as a catalog file, per D1) and adapt them to built-in conventions — a port, not a rewrite.

The load-bearing fact that makes this nearly free: at `kit install` time, `BaseInstallCommand.renderAndWrite` **materializes the entire kit directory tree to disk** (`BaseInstallCommand.kt:33-53`) — every entry `InstallTemplateResolver.listTemplateFiles` returns, preserving nested subdirs (`bin/`, `dashboards/`, `catalogs/`) via `tempFile.parentFile.mkdirs()`. For built-in kits, `listTemplateFiles` returns entries scanned by ClassGraph (`InstallTemplateResolver.kt:54-68`). So a built-in kit and a `--from` directory kit produce **byte-identical on-disk layouts** after install. Scripts run from that materialized dir (`KitRunnerCommand.kt:200-201`) and read siblings via `SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`.

## Goals / Non-Goals

**Goals:**
- `cqlite-flight` and `trino-loadtest` become built-in kits, installable/startable with no `kit source add`.
- cqlite Trino integration will become a **catalog property file of the trino kit** (`kits/trino/catalogs/cqlite.properties.template`) alongside cassandra/clickhouse/opensearch/tidb — NOT a standalone kit (D1, maintainer required change). The catalog file itself is DEFERRED (blocked on #2869) and not shipped in this change; today the trino kit is unchanged by cqlite.
- Preserve the Flight/load-driver kits' external working behavior verbatim (a port).
- Unblock and update the general production-readiness test plan; retire the version-pinned checkpoint plans.

**Deferred (blocked on pmcfadin/cqlite#2869):**
- Actual `cqlite_flight` connector plugin delivery (a single Shadow fat jar fetched to a versioned per-node hostPath, mounted into `/usr/lib/trino/plugin/cqlite_flight/`). The catalog file and the plugin mount are both deferred until the artifact is published. See D1.

**Non-Goals:**
- Building the cqlite connector or Arrow Flight image (both are published artifacts).
- Write/DDL support, live-consistency semantics, Presto targeting, SSTable backup/restore.
- Redesigning the kits' arg surface, scripts, or internal architecture beyond the minimal adaptations below.
- Changing the resolver, installer, or any existing kit.

## Decisions

**D1 (REVISED AGAIN — maintainer required change, this PR) — cqlite is a trino catalog; the connector plugin ships as a single fat jar fetched to a per-node hostPath. Plugin delivery is DEFERRED (blocked on pmcfadin/cqlite#2869).**

The maintainer requires that cqlite NOT be a standalone Trino-integration kit. cassandra/clickhouse/opensearch/tidb are catalog property files under `kits/trino/catalogs/*.properties.template`, auto-discovered by the trino kit's `update-catalogs.sh` and applied via its single `helm upgrade`; **cqlite will get the same treatment when it lands** — a `kits/trino/catalogs/cqlite.properties.template` with `connector.name=cqlite_flight` and the `__SIDECAR_URI__`/`__FLIGHT_PORT__`/`__READ_MODE__`/`__LOCAL_DATACENTER__` placeholders. There is no separate `cqlite-trino` resource dir and no `kit install cqlite-trino` command.

The maintainer also requires the connector be shipped as a **single self-contained Shadow fat jar** fetched once to a **versioned per-node hostPath cache** (no pod-start Gradle resolve). That artifact does **not exist yet** — it is tracked in **pmcfadin/cqlite#2869** (a downloadable fat jar to be published in the cqlite GitHub Releases). Therefore:

- **Removed** (retired, not shipped): the entire `cqlite-trino/gradle-assemble-plugin/` pod-start Gradle resolve of `in.mcfad:cqlite-trino` from Maven Central, the `trino-values.yaml.template` initContainer-runs-Gradle fragment, and the sibling-`trino-values.yaml` discovery loop that was added to `kits/trino/bin/update-catalogs.sh.template`. Also gone with the standalone kit: `reapply-plugin-patch.sh`, `ensure-catalog-registered.sh`, `install.sh`, the unfiltered `post-workload-*` hooks, the RFC-6902 uninstall stripping, and the SPI-tag preflight.
- **The catalog file itself is DEFERRED and intentionally NOT shipped yet** (see below). A commented block in `kits/trino/bin/update-catalogs.sh.template` documents the intended fat-jar delivery shape — a versioned per-node hostPath cache (e.g. `/var/lib/easydblab/cqlite-plugin/<version>/`), a download-if-missing initContainer fetching the release fat jar into that hostPath, `nodeSelector: type=app`, and a volumeMount into `/usr/lib/trino/plugin/cqlite_flight/` (a SUBDIRECTORY of the plugin path, never the plugin path itself). No fake jar URL and no Gradle resolve are introduced.

**Why the catalog file is not shipped yet (regression avoidance).** An earlier pass added `kits/trino/catalogs/cqlite.properties.template`. Unlike the other catalog files (e.g. `cassandra.properties.template` uses `__DB_NODE_IPS__`/`__REGION__`, which ARE global `TemplateVariables`), its four placeholders `__SIDECAR_URI__`/`__FLIGHT_PORT__`/`__LOCAL_DATACENTER__`/`__READ_MODE__` are neither global `TemplateVariables` nor trino kit args (they were args on the now-deleted standalone `cqlite-trino` kit). Because `BaseInstallCommand.renderAndWrite` renders EVERY file `listTemplateFiles` returns, shipping the file made it render on every `kit install trino` and emit a spurious `Event.Install.UnresolvedVariables` warning ("unresolved template variables in 'trino': SIDECAR_URI, FLIGHT_PORT, …") — a UX regression on the existing, widely-used trino kit unrelated to cqlite. It was also inert: `update-catalogs.sh` only wires a catalog whose basename matches a running kit, and there is no kit named `cqlite` (it is `cqlite-flight`), so the file would never have been picked up anyway. So the file is removed until the plugin lands.

**Intended `catalogs/cqlite.properties.template` content, to add when #2869 lands** (record only — NOT shipped today). When added, its placeholders MUST be made resolvable — declared either as global `TemplateVariables` or as trino kit args — so it renders cleanly with no unresolved-variable warning:

```properties
# cqlite catalog — reads Cassandra SSTables offline via the cqlite_flight Trino
# connector + the cqlite-flight Arrow Flight data plane (one pod per db node).
# connector.name MUST equal the registered Trino connector factory name,
# `cqlite_flight` (CqliteFlightConnectorFactory.getName()); setting it to the
# catalog name `cqlite` crashes the coordinator at boot (issue #2123).
connector.name=cqlite_flight
cqlite.sidecar-uri=__SIDECAR_URI__
cqlite.flight-port=__FLIGHT_PORT__
# Preferred datacenter for split placement. Renders BLANK when not supplied,
# which the connector treats as "no DC preference".
cqlite.local-datacenter=__LOCAL_DATACENTER__
# How each scan resolves the SSTable file set: snapshot (consistent per-query
# Sidecar snapshot) | live (current data dir, races compaction).
cqlite.read-mode=__READ_MODE__
```

*Blocked follow-up:* once pmcfadin/cqlite#2869 publishes the fat jar, (a) add the catalog file above with resolvable placeholders, (b) wire the download-if-missing initContainer + hostPath mount as an extra `--values` fragment on the trino kit's `helm upgrade`, and (c) **explicitly wire `cqlite` into `update-catalogs.sh`** — special-case it like the opensearch infra-catalog check, since no running kit is named `cqlite` so the basename-match loop over `RUNNING_KITS` will not pick it up on its own. Registering the catalog without the plugin present crashes the coordinator at boot with `No factory for connector 'cqlite_flight'`, which is the other reason it is not enabled early. *Rejected alternative:* baking a custom Trino image with the plugin pre-installed (needs its own image build+publish pipeline).

**D2 — README suffixing.** `renderAndWrite` only substitutes files ending in `.template` (`BaseInstallCommand.kt:34`). `cqlite-flight/README.md` contains raw `__TAG__`/`__FLIGHT_PORT__` markers, so it must become `README.md.template` or it ships literal placeholders. `trino-loadtest`'s has no placeholders and stays plain `README.md`. (There is no standalone cqlite-trino kit — see D1.)

**D3 — `cqlite-flight` node placement: DaemonSet as-is, all db nodes.** The daemonset hard-codes `nodeSelector: type=db` + `hostNetwork: true` — the same selector the Cassandra sidecar DaemonSet already uses (`SidecarManifestBuilder.kt:118`), so db nodes carry that label and one pod lands per db node. *Alternative:* pursue `#672` granular sub-node assignment — rejected as YAGNI; "one pod per db node" is exactly DaemonSet + `type=db` semantics.

**D4 — REMOVED.** The earlier D4 (kit-identity-vs-catalog-name split for a standalone `cqlite-trino` kit) is moot: per D1, cqlite is a trino catalog like the others, not a separate kit, so there is no `kit install` subcommand name to reconcile with the catalog name. The catalog is `cqlite` (from the `catalogs/cqlite.properties` filename, matching how update-catalogs.sh names catalogs) and `connector.name=cqlite_flight` (the Trino factory name) remains separate and unchanged (#2123).

**D5 — `trino-loadtest` payload.** Ship `driver.py` (the ConfigMap-staged pod payload) only; **drop** `test_driver.py` (33K of unit tests, referenced by no script). Shipping test code in the app jar and every installed workspace is dead weight; tests stay in the cqlite source repo. Port as a generic Trino read-load driver with cqlite defaults.

**Guard test.** A unit test (extending `InstallTemplateResolverTest.kt`, following the existing clickhouse-dashboard and presto-config patterns) asserts the `cqlite-flight` and `trino-loadtest` built-in kits' `loadInstallConfig` parses, that the trino source lists its real catalogs (e.g. `catalogs/cassandra.properties.template`) but NO cqlite catalog file (deferred — so the `kit install trino` render path collects no unresolvable cqlite placeholders and emits no unresolved-variable warning), and that `cqlite-trino` no longer resolves as a standalone kit. This guards against the deferred catalog file (or the standalone kit) sneaking back in and regressing the trino kit's install-time UX.

**Plan migration.** Edit `test-plans/cqlite-flight-production-readiness.md` to remove the `kit source add` prelude and reference built-in kits. Delete `cqlite-flight-milestone-snapshot-0.15.md` and `cqlite-flight-0.16.0-rc1-fixvalidation.md` (version-pinned checkpoints, superseded).

## Risks / Trade-offs

- **No build-time validation of built-in `kit.yaml`** → a malformed ported descriptor would only fail at runtime discovery. *Mitigation:* the guard unit test parses the built-in kits at build time.
- **cqlite catalog registered without its plugin present crashes the coordinator** (`No factory for connector 'cqlite_flight'`). *Mitigation:* the catalog file is not shipped until pmcfadin/cqlite#2869 delivers the fat jar (D1); the `update-catalogs.sh` TODO, the trino README, the trino user-guide page, and the test plan all mark steps 6–9 as blocked-on-#2869.
- **Shipping the catalog template early regresses the trino kit's install UX** (spurious unresolved-variable warning; see D1). *Mitigation:* the file is deferred and a guard test asserts the trino source lists no cqlite catalog file.
- **Preserved pod-side logic looks "optimizable"** (the `#2114` multi-disk detector, `#2290` add-opens handling in cqlite-flight). *Mitigation:* design and spec explicitly mark these as port-verbatim with issue references; reviewers must not simplify them.
- **Large text resources** (`cqlite-flight.json` ~45K, `driver.py` ~29K) → fine; ClassGraph `contentAsString` handles UTF-8 text, and no kit carries binary payloads. *If* a future kit needs a binary asset, the string-based resolver path would need revisiting — noted, not in scope.
- **Blast radius** → additive resources only; resolver/installer/existing kits untouched. Ephemeral clusters, no backward-compat concern.

## Migration Plan

1. Copy the `cqlite-flight` and `trino-loadtest` kit dirs into the built-in resource root with the D2/D5 adaptations. The trino kit's `catalogs/cqlite.properties.template` is DEFERRED — not shipped until #2869 (D1).
2. Add the guard test; run the unit tier.
3. Edit the general plan; delete the two checkpoint plans; update kit docs.
4. Rollback is trivial (delete the resource dirs / catalog file) — no state or schema involved.

## Open Questions

None outstanding for the shipped scope. The only remaining work — actual `cqlite_flight` plugin-jar delivery (D1) — is a blocked follow-up on pmcfadin/cqlite#2869 and out of scope here.
