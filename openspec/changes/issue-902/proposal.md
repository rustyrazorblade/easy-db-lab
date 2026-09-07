# Local observability stack: import backups into one merged archive and iterate on dashboards offline

## Why

Dashboard development and backup inspection are both hard-blocked on a live AWS cluster. Grafana,
the datasources and the data all exist only on a running control node, so iterating on a panel query
means keeping EC2 up, and tearing the cluster down destroys the ability to verify the work at all.

The data is already durable in S3, but **no code path anywhere in the repo reads a backup back into
anything**. `VictoriaBackupService.kt:33-66` exposes `backupMetrics` and `backupLogs` and nothing
else. Hundreds of megabytes of metrics and logs are write-only artifacts.

There is a second problem the original framing missed. Test results from different runs are stranded
in separate backups. The local stack must **accumulate** them into one store, so results from many
clusters can be compared in one place. Restoring one backup at a time and refusing on a non-empty
directory does not deliver that.

Three current behaviours also delete observability data on their own: `down` puts a one-day S3
lifecycle expiration on the prefix holding every metrics and logs backup; Pyroscope's live store
sits in the per-cluster data bucket that `down` expires wholesale; and both Victoria stores ship
with `RETENTION_PERIOD = "7d"`, which drops a cluster's oldest data before anything backs it up and
silently discards a restored backup older than the window.

## What Changes

**A new `local` top-level command group**, the counterpart to `aws`. `aws <thing>` operates on AWS,
`local <thing>` operates on your machine. Five subcommands, each taking the scaffold directory as a
required positional argument with no default:

- `local scaffold <dir>` — generate the stack, or update an existing one in place
- `local import <dir> [--timestamp <ts>] [--replace] [s3://…]` — import a cluster's backup
- `local ls <dir>` — list what the archive holds
- `local rm <dir> <import-id>` — remove one recorded import
- `local dashboards <dir> [--from <dir>]` — refresh only the dashboards

None of them carries `@RequiresProxy`, because none reaches a cluster. Only `local import` reads
`state.json`; the other four run from any directory, which is what shortens the dashboard-iteration
loop to no cluster at all.

**The scaffold is generated from packaged resources.** `docker-compose.yml`, `.env` holding image
pins and host ports, service config for VictoriaMetrics, VictoriaLogs, Tempo and Pyroscope, Grafana
datasource and dashboard provisioning, `.gitignore`, `README.md`, and a `scaffold.json` manifest
recording which paths the tool owns. A separate extraction step writes core and kit dashboards into
the scaffold's `dashboards/`. Nothing in the path reads a source checkout — most users install from
a package and have no repo. Re-running rewrites only the manifest's paths and leaves `data/`,
`imports/`, `staging/` and `compose.override.yml` byte-identical; a foreign non-empty directory is
refused rather than overwritten.

**Datasources are generated from the same `GrafanaDatasourceConfig.create()` the cluster uses**,
substituting only the URLs, so the four UIDs (`VictoriaMetrics`, `victorialogs`, `tempo`,
`pyroscope`) cannot drift. Image tags are pinned to the manifest builders' versions, because a
`vmbackup` restored into a mismatched VictoriaMetrics is not supported.

**Import merges many clusters into one store.** `vmrestore` writes a full snapshot into an empty
directory and cannot merge, so import restores into `staging/`, streams `/api/v1/export` →
`/api/v1/import` in Kotlin with exact counts at both ends, records the result, then clears staging.
Import never clears another cluster's data and never requires an empty directory. Two clusters in
one aggregate are separable on `dashboards/cluster-comparison.json`, which already exists and is
what the merged store makes usable.

**A second snapshot of the same cluster is refused, not merged.** `vmbackup` output is a full
snapshot, so importing two snapshots of one cluster double-counts every overlapping sample. The
dedup key is `(clusterLabel, tier)`, independent of timestamp. `--replace` removes that cluster's
metrics, logs **and** profile blocks and writes the new import as one rollback-covered transaction
across all three tiers.

**Import stamps the cluster label as a fill, never an overwrite**, so data from the collector
pipelines that do not emit it is still filterable. It refuses rather than guesses in four cases: no
cluster identity in the S3 key, a label that disagrees with the key, more than one label value in
one backup, and no resolvable value at all.

**Pyroscope profiles are restored locally** into `data/pyroscope/` and served from a filesystem
backend, with `-compactor.disabled-tenants=anonymous` so block ULIDs stay stable and per-import
deletion remains possible. Once data is imported the whole stack works with no network and no AWS
credentials.

**Retention for observability data becomes infinite everywhere.** Every local store and every
transient container carries `-retentionPeriod=100y`, plus `-futureRetention=100y` on VictoriaLogs;
import refuses against a store whose retention is not that value, naming the container and both
values. The cluster's own stores get the same treatment —
`VictoriaManifestBuilder.RETENTION_PERIOD` stops naming a bounded period. Nothing evicts from the
local aggregate on age or size; the only removal paths are `local rm` and `local import --replace`.

**Four cluster-side changes, all approved exceptions to this change's otherwise local scope:**

- `Down.setClusterLifecycleRule()` goes. No S3 lifecycle expiration is applied to any prefix holding
  metrics, logs, traces or profile data. The per-cluster data bucket keeps its whole-bucket expiry.
- Pyroscope's store moves from the ephemeral data bucket to the accumulating account bucket. The
  account bucket's region is resolved once at bucket-ensure time via `GetBucketLocation`, stored on
  `ClusterState`, and exposed as its own template variable, because Pyroscope's config currently
  builds its endpoint and region from the *cluster's* region. `BUCKET_NAME` keeps its current
  meaning; its other consumer is ClickHouse's S3 data disk, which does not move.
- `VictoriaManifestBuilder`'s retention becomes unbounded.
- `dashboards/clickhouse.json` and `dashboards/clickhouse-logs.json` are deleted along with the
  `CLICKHOUSE` and `CLICKHOUSE_LOGS` entries in the `GrafanaDashboard` enum. The ClickHouse kit's
  own copies are authoritative and become the only ones.

**Every dashboard gets a cluster-name field and cluster-scoped queries**, with no exclusions —
`dashboards/profiling.json` included. Its filter lives in the Pyroscope `labelSelector` rather than
an `expr`, so the enforcement test walks `labelSelector` fields too; a test that walks only `expr`
passes that dashboard while it silently blends.

**Docs.** `docs/user-guide/victoria-metrics.md` gains the backup-and-restore and local-workflow
sections it has never had; `dashboards/CLAUDE.md` gains the local-iteration loop alongside its
existing `installDist` warning. Three repo defects found while designing are folded into that edit:
the incorrect "no forward slashes" KDoc at `TemplateService.kt:50`, the two `dashboards/CLAUDE.md`
references to a `bin/generate-dashboard-links.py` that has never existed, and the undeclared okio
import at `VictoriaStreamService.kt:12`.

## Impact

- **New capability `local-observability-stack`.** Modified: `cluster-lifecycle` (teardown applies no
  expiration to observability prefixes), `observability` (unbounded store retention; no top-level
  ClickHouse dashboards), `multi-cluster-dashboards` (no exclusions; `labelSelector` walked),
  `profiling` (Pyroscope's store is the account bucket, with its own resolved region).
- **`down --retention-days` no longer applies to the account bucket's cluster prefix.** The flag
  keeps its meaning for the per-cluster data bucket's whole-bucket expiry.
- **Existing clusters' Pyroscope data stays where it is.** Clusters are ephemeral; there is no
  migration path and none is needed.
- **Storage grows without bound, deliberately.** That is the point of the archive. `local rm` is the
  disk-management mechanism, and it is always explicit and always names what it removes.
- **Tempo is in the scaffold but empty.** The container is included so `dashboards/tempo.json`'s
  datasource resolves; it holds no traces until trace backup and import land in 901.
- **The local Pyroscope UI's data-availability hint sees nothing** because the compactor is
  disabled. Flame graphs render normally with an explicit time range. Documented, not worked around.
- Does **not** add the missing `cluster` label to the four OTel pipelines that lack it
  (`logs/local`, `logs/otlp`, `metrics/spanmetrics`, `metrics/servicegraph`). Filed separately;
  import-time labelling covers the local aggregate without it.
