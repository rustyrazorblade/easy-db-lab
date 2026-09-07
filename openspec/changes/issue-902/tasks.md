# Tasks — issue 902

## 1. Stop the automatic deletion of observability data

- [ ] 1.1 Remove `Down.setClusterLifecycleRule()` (`commands/Down.kt:359`, `:408-420`) and its call
      site, so no S3 lifecycle expiration is applied to `clusters/<name>-<clusterId>`. Keep
      `teardownDataBucketIfNeeded` and its whole-bucket expiry on the per-cluster **data** bucket.
- [ ] 1.2 Decide what `--retention-days` (`Down.kt:76-80`) still means and document it: it applies to
      the data bucket's whole-bucket expiry only. Remove any event or message implying it covers the
      cluster prefix (`Event.S3.LifecycleRuleSet`).
- [ ] 1.3 Change `VictoriaManifestBuilder.RETENTION_PERIOD` (`:28`) from `"7d"` to the unbounded
      value, applied at `:87` and `:131`. Add `-futureRetention=100y` to VictoriaLogs. **Always write
      the unit** — a bare number means months in both products.
- [ ] 1.4 Tests: assert `down` applies no lifecycle rule to the account bucket's cluster prefix, and
      that the deployed VictoriaMetrics and VictoriaLogs args carry the unbounded values.

## 2. Move Pyroscope's store to the account bucket

- [ ] 2.1 Resolve the account bucket's region once at bucket-ensure time via `GetBucketLocation` and
      store it on `ClusterState`.
- [ ] 2.2 Expose that region as its own template variable. **Do not change `BUCKET_NAME`** — its
      other consumer is ClickHouse's S3 data disk, which must not move to the account bucket.
- [ ] 2.3 Point `pyroscope/config.yaml`'s `bucket_name`, S3 endpoint and region at the account bucket
      and the new region variable.
- [ ] 2.4 Fix the incorrect KDoc at `services/TemplateService.kt:50` — Pyroscope's storage prefix
      does allow forward slashes — and move the prefix inside `clusters/` so all three tiers share
      one prefix tree. Use `ClusterS3Path.pyroscope()`.
- [ ] 2.5 Handle a `state.json` predating the field — **a live shared cluster is in this position**.
      Resolve the region lazily on first use with the same `GetBucketLocation` call and persist it,
      so the resolution happens once per cluster. Never fall back to the cluster's region. Fail
      naming the bucket and the call when the lazy resolution itself fails.
- [ ] 2.6 Tests: a cluster in a region other than the account bucket's renders a Pyroscope config
      whose endpoint and region come from the bucket, not the cluster; a `state.json` with no stored
      region resolves lazily, persists, and is not re-resolved on the next command; a failed
      resolution fails naming the bucket; `BUCKET_NAME` resolves unchanged.

## 3. Remove the stale top-level ClickHouse dashboards

- [ ] 3.1 Delete `dashboards/clickhouse.json` and `dashboards/clickhouse-logs.json`.
- [ ] 3.2 Remove the `CLICKHOUSE` and `CLICKHOUSE_LOGS` entries from the `GrafanaDashboard` enum
      (`:57`, `:64`).
- [ ] 3.3 Verify `grafana update-config` still succeeds — no manifest references a deleted resource —
      and installs no ClickHouse dashboard.
- [ ] 3.4 Verify the ClickHouse kit's own two dashboards still install via `KitRunnerCommand` and are
      the only ClickHouse dashboards present.

## 4. Cluster name on every dashboard

- [ ] 4.1 Audit every dashboard in the top-level `dashboards/` directory and every kit dashboard for
      a cluster-name field and cluster-scoped panel queries. **No exclusions**,
      `dashboards/profiling.json` included.
- [ ] 4.2 Fix the ones missing it. `dashboards/profiling.json`'s filter goes in the Pyroscope
      `labelSelector`, not an `expr`.
- [ ] 4.3 Extend the enforcement test to walk `labelSelector` fields as well as `expr` fields.
      Confirm the test **fails** on the pre-fix `profiling.json` — a test that walks only `expr`
      passes it while it silently blends.
- [ ] 4.4 Verify these dashboards still behave as before for a single cluster when deployed by
      `grafana update-config`.

## 5. The `local` command group

- [ ] 5.1 Add the `local` group as a `Runnable` parent following the `Kit` shape, with subcommands
      `scaffold`, `import`, `ls`, `rm`, `dashboards`.
- [ ] 5.2 Give every subcommand a required positional destination directory with **no default**.
- [ ] 5.3 Carry **no** `@RequiresProxy` on any of them, and state in each command's KDoc that it
      reaches no cluster — the existing `metrics import` / `logs import` are live-cluster streams and
      the name similarity will mislead.
- [ ] 5.4 Register the group in `CommandLineParser` and in `Repl.kt`'s hand-maintained command tree.
- [ ] 5.5 Bind each command in `di/CommandsModule.kt`. An omission fails silently via
      `KoinCommandFactory`'s fallback.

## 6. `local scaffold`

- [ ] 6.1 Package the scaffold template as classpath resources. Add recursive-directory copy to
      `TemplateService` — it does not exist yet. **Nothing in this path may read a source checkout.**
- [ ] 6.2 Generate `docker-compose.yml`, `.env` (image pins and host ports), `config/victoriametrics/`,
      `config/victorialogs/`, `config/tempo/`, `config/pyroscope/`, `.gitignore`, `README.md`.
- [ ] 6.3 Generate `config/grafana/provisioning/datasources/` from `GrafanaDatasourceConfig.create()`,
      substituting only the URLs (cluster is `http://localhost:{8428,9428,3200,4040}`; compose needs
      service names). Generate the dashboard provider pointing at the scaffold's `dashboards/`.
- [ ] 6.4 Add `GF_INSTALL_PLUGINS` for the VictoriaLogs and Pyroscope datasource plugins and
      anonymous-admin auth. Omit the image renderer.
- [ ] 6.5 Pin every image to the manifest builders' versions: VictoriaMetrics `v1.136.0`,
      VictoriaLogs `v1.47.0`, Grafana `13.0.2`, Tempo `2.10.0`, Pyroscope `1.18.0`.
- [ ] 6.6 Write `scaffold.json` recording the scaffold version, image pins and every tool-owned path.
- [ ] 6.7 Add the separate dashboard extraction step writing core **and** kit dashboards into the
      scaffold's `dashboards/`, one copy each. Keep it out of config generation.
- [ ] 6.8 Update in place on re-run: rewrite only the manifest's paths; leave `data/`, `imports/`,
      `staging/` and `compose.override.yml` byte-identical.
- [ ] 6.9 Refuse a non-empty directory the tool did not generate, naming it, and write nothing.
- [ ] 6.10 Set every generated store's retention to the unbounded value, plus `-futureRetention` on
      VictoriaLogs, and `-compactor.disabled-tenants=anonymous` on Pyroscope.
- [ ] 6.11 Verify the generated names do not collide with the repo's own top-level
      `docker-compose.yml` / `otel-collector-config.yaml` (the valkey/OTel dev harness).

## 7. `local import`

- [ ] 7.1 Read the source from `state.json` in the CWD. No `--cluster` flag. `--timestamp` is an
      override only. Accept an explicit `s3://bucket/prefix/timestamp` as the escape hatch.
- [ ] 7.2 Import all three tiers on a bare run. No per-tier flag; one URI addresses a run because all
      three tiers share one prefix tree.
- [ ] 7.3 Metrics: `vmrestore -skipBackupCompleteCheck` into `staging/`, start a transient container
      over it **carrying the unbounded retention value**, stream `/api/v1/export` → `/api/v1/import`
      with `max_rows_per_line`, assert unit counts at both ends, then clear staging.
- [ ] 7.4 Logs: file-level copy of the per-partition snapshots (`YYYY_MM` partition directories) —
      not an API import.
- [ ] 7.5 Profiles: sync blocks into `data/pyroscope/`, dedup on leaf ULIDs, report how many blocks
      were added, and say so explicitly when it added none.
- [ ] 7.6 Stamp the cluster label as a **fill**, never an overwrite, in the stream transform.
- [ ] 7.7 Resolve the cluster value from the backup's contents over an explicit all-time range,
      cross-check against the S3 key, and accept an explicit flag. Anchor key resolution on the
      literal tier directory name — the segment before `victoriametrics`/`victorialogs`, or the first
      segment with `pyroscope.` stripped — never on position.
- [ ] 7.8 Implement the four refusals: no cluster identity in the key; label disagrees with the key
      (name both); more than one label value in one backup (list them); no resolvable value at all.
- [ ] 7.9 Refuse a second snapshot of a cluster already recorded, keyed on `(clusterLabel, tier)`,
      naming the recorded snapshot and its time range and stating that `--replace` replaces it.
      Refuse the **whole** command; import no tier partially.
- [ ] 7.10 Implement `--replace` as one transaction across all three tiers: whole-store snapshot
      before any mutation, a phased marker, and recovery identical from every phase.
- [ ] 7.11 Refuse against any store whose retention is not the unbounded value, naming the container
      and both values.
- [ ] 7.12 Refuse to write into a live data directory: stop the storage container first, or refuse
      with a clear instruction.
- [ ] 7.13 Error messages: a missing prefix names the S3 URI it looked at; a missing or
      insufficient credential says so rather than presenting as an empty backup. No stack traces, no
      empty successes.
- [ ] 7.14 Write the ledger entry (cluster, timestamp, S3 URI, time range) under `imports/`.

## 8. `local ls`, `local rm`, `local dashboards`

- [ ] 8.1 `local ls <dir>` lists the ledger's imports with cluster, timestamp and time range.
- [ ] 8.2 `local rm <dir> <import-id>` removes that import's series and its ledger entry, leaving the
      remaining imports queryable; an unknown id fails listing what is there.
- [ ] 8.3 `local dashboards <dir>` rewrites the scaffold's `dashboards/` and makes the new versions
      visible without restarting Grafana and without recreating any storage-tier container.
- [ ] 8.4 `--from <dir>` reads from that directory with no rebuild; with no `--from` the packaged
      copy is used and the command **states which copy it deployed**.
- [ ] 8.5 Fail clearly against a stack that is not running, rather than half-updating.

## 9. Tests

- [ ] 9.1 Scaffold: generation from packaged resources with no checkout present; idempotent re-run
      leaving `data/`, `imports/` and `compose.override.yml` byte-identical; refusal on a foreign
      non-empty directory; `scaffold.json` contents; the four datasource UIDs and types; image pins
      matching the manifest builders'.
- [ ] 9.2 Import merge: a second cluster is added and both stay queryable; two clusters are separable
      on a dashboard; a re-import is refused from the ledger; a second snapshot of one cluster is
      refused on `(clusterLabel, tier)`; `--replace` swaps all three tiers and rolls back fully on a
      failure injected at each phase.
- [ ] 9.3 Cluster labelling: fill-not-overwrite on a mixed snapshot; each of the four refusals; key
      resolution by tier name rather than by position.
- [ ] 9.4 Retention: every generated store and the transient staging container carry the unbounded
      value; import refuses a store that does not, naming the container and both values.
- [ ] 9.5 Streaming: a truncated transfer fails on the unit-count mismatch rather than reporting
      success.
- [ ] 9.6 Dashboard enforcement test walks `labelSelector` as well as `expr`, and fails on the
      pre-fix `profiling.json`.
- [ ] 9.7 Teardown applies no lifecycle rule to the account bucket's cluster prefix.
- [ ] 9.8 Offline: with the network down and no AWS credentials, the stack starts and the profiling
      dashboard returns data.

## 10. Docs, and the three repo defects folded in

- [ ] 10.1 Add the local workflow to `docs/user-guide/victoria-metrics.md` (listed at
      `docs/SUMMARY.md:30`), which today documents only cluster-side architecture and has no
      backup/restore section. It must take a reader from "no cluster, a backup in S3" to "a dashboard
      open in local Grafana showing that backup's data" without reading source.
- [ ] 10.2 In the same page, show importing two runs into one directory and selecting between them,
      so the accumulating behaviour is demonstrated rather than asserted.
- [ ] 10.3 Document the Pyroscope compactor consequences: the store-gateway's direct bucket scans,
      and that `GetProfileStats` has no fallback so the UI's data-availability hint sees nothing —
      flame graphs render with an explicit time range.
- [ ] 10.4 Add the local-iteration loop to `dashboards/CLAUDE.md` alongside its existing `installDist`
      warning.
- [ ] 10.5 **Defect:** fix the incorrect "no forward slashes" KDoc at
      `services/TemplateService.kt:50`. Pyroscope's storage prefix does allow them; that comment is
      why the profiles prefix was a sibling of `clusters/`.
- [ ] 10.6 **Defect:** remove the two `dashboards/CLAUDE.md` references to
      `bin/generate-dashboard-links.py` (`:72` and `:107`). The script has never existed — `bin/`
      contains no Python at all. Replace them with what the reader should actually do.
- [ ] 10.7 **Defect:** declare okio in the version catalog, or drop the import at
      `services/VictoriaStreamService.kt:12`. It currently relies on a transitive dependency. New
      code uses `kotlinx-io`.
- [ ] 10.8 Update `CLAUDE.md` and `dashboards/CLAUDE.md` for the new `local` group and the scaffold's
      dashboard extraction step.

## 11. Verification

- [ ] 11.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (detekt 1.23.8 cannot run under
      JDK 25).
- [ ] 11.2 `./gradlew installDist`, then run the five commands end to end against a real backup:
      scaffold, import, ls, dashboards `--from`, rm.
- [ ] 11.3 Import two different clusters into one scaffold and confirm
      `dashboards/cluster-comparison.json` shows both on one panel.
- [ ] 11.4 Disconnect the network, unset AWS credentials, restart the stack, and confirm every
      dashboard still renders.
