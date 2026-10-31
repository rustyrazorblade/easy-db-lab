# Design: per-environment observability storage (#1035)

Epic #1032 (local execution environment), decision Q3: local storage is a plain host directory with no S3, no emulator and no AWS. Every decision below was approved by the owner on 2026-10-31. The architect's alternatives are named with each decision.

## Approach

### Storage selection
- New `ObservabilityStorage` sealed interface in `configuration/`, with two cases:
  - `S3(bucket, region)`
  - `Filesystem(mountPath)`
- `ObservabilityStorage.from(ClusterState)` returns `S3` until #1040 records the environment.
- `ObservabilityStore` (`ObservabilityStore.kt:20-35`) takes the storage and the tenant. Its prefix functions (`metricsPrefix`, `logsPrefix`, and so on) stay the same.

### Config rendering (decision 2)
- Each backend keeps one resource holding both store blocks, and one value selects between them:
  - Mimir `blocks_storage.backend: ${METRICS_BACKEND}`, with a `filesystem.dir`
  - Loki `schema_config.configs[].object_store`, with `storage_config.filesystem`
  - Tempo `storage.trace.backend`, with `local.path`
  - Pyroscope `storage.backend: __PROFILES_BACKEND__`, with `filesystem.dir`
- `ClusterConfigData` (`services/ClusterConfigData.kt:35`) publishes the selector and the filesystem paths into `cluster-config`.
- Task 1 checks that each backend ignores the block it is not using. Loki's `common.storage` is the least certain.
- Alternatives:
  - Separate `*-filesystem.yaml` files. Rejected: they drift from the S3 files.
  - CLI flag overrides. Rejected: they cannot change Loki's schema.

### Data directory layout (decision 1)
- D mirrors the bucket, so #902's local viewer and export can open it directly:
  - `mimir/`, `loki/` (index under `loki/index/`), `tempo/`, `pyroscope/`
  - `grafana/annotations/<tenant>/`
  - `reports/<tenant>/`
  - `state/<backend>/`
- Local state lives under `state/`, so that a new cluster on the same D can still read earlier data. This matters most for Pyroscope, whose metastore is the only index of its blocks (`pyroscope/config.yaml:17-19`).
- D's mount point inside the cluster is `Constants.Observability.LOCAL_DATA_MOUNT`. The hostPath volumes that today hard-code `/mnt/db1/<backend>` point under it in filesystem mode:
  - `MimirManifestBuilder.kt:53`
  - `LokiManifestBuilder.kt:51`
  - `TempoManifestBuilder.kt:35`
  - `PyroscopeManifestBuilder.kt:42`
- Mounting the host directory into the K3s container is #1041's job.
- Alternative: a free-form layout. Rejected: it breaks #902.

### Local compaction (decisions 3 and 4)
- **Mimir:**
  - Adds `compactor` to `target` in filesystem mode.
  - Runs with the account compactor's flags (`CompactorTaskDefinition.kt:83-97`), moved into one shared `MimirCompactorArgs` so the two cannot drift.
  - `blocks-retention-period=0` and `partial-block-deletion-delay=0` mean nothing is ever deleted by age or as a partial block.
  - Its `cleanup-interval=1m` writes the bucket index. Without it, the store-gateway finds no block older than the 15-minute local hold (`mimir.yaml:6`).
- **Loki:** overrides `compaction_interval: 87600h` (`loki.yaml:82`) with `10m`, matching `LOKI_COMPACTOR_ARGS`. Retention stays disabled and deletion `disabled`. Alternative: leave it idle as on clusters. Rejected: it would not split the shared index files for export.
- **Tempo:** compaction stays off, as on every cluster today; reads work without it. Alternative: run `backend-scheduler` and `backend-worker` as extra containers.
- **Pyroscope:** already compacts in its own process with cleanup off. No change.
- **Account compactor:** for `Filesystem`, `CompactorService.ensureRunning` and `stopIfLastCluster` are never called.

### Object store (decision 6)
- New `FilesystemObjectStore(root)`:
  - Maps a `ClusterS3Path`'s key segments onto `root` and ignores the bucket.
  - Each write goes to a temp file, then an atomic rename.
  - A `..` or absolute segment is rejected.
- `ObservabilityObjectStores.forStorage(storage)` gives `TestDocumentService`, `GrafanaAnnotationBackupService` and `TenantDirectory` their store.
- The global `ObjectStore` binding (`AWSModule.kt:233`) stays S3, because Spark and `CassandraBuildCatalog` are AWS-only.
- Alternative: replace the global binding. Rejected: it breaks Spark and the build catalog.

### Documents
- New sealed `DocumentsSource`:
  - `Bucket`: today's sigv4 proxy plus nginx.
  - `Directory`: nginx alone, with `D/reports` mounted read-only. It uses a second resource, `documents-nginx-local.conf`, with the same `GET`-only, `/reports/`-only guard.

### Teardown
- `TeardownFlushService` is unchanged, as epic decision Q5 requires. Flushes and the annotation backup reach D through the same paths. `down` only flushes, waits, and refuses teardown when the flush did not finish. It adds no verify checks.

### Concurrency (decision 5)
- One running stack per D, documented, with no lock (YAGNI). Two stacks on one D would fight over the Pyroscope Raft state and the Loki ingester IDs.
- Alternatives:
  - A lock file.
  - Per-cluster state. Rejected: it breaks the requirement that data survives into a new cluster.

## macOS bind-mount gate (decision 7)

#902's spike S4 (PR #1020 `design.md:171`) found that the Mimir shipper sets an xattr that a macOS bind mount does not support. Local mode on macOS writes through exactly that kind of mount.

**Status: NOT YET RUN.** The check was attempted on 2026-10-31 and could not finish: the Docker Desktop daemon stopped answering, and `docker ps` and `docker info` hung for more than 20s. The prepared harness is in `/tmp/edl-1035-xattr.TR8g`. It runs two `grafana/mimir:3.2.1` containers with a Prometheus remote-writing to each:
- variant A: object root and TSDB/sync dirs on the bind mount;
- variant B: only the object root on the mount.

So task 1 is a gate. It runs before any other task, and its result picks the Mimir state layout:
- **If variant A passes**, `state/mimir/` lives under D, as above.
- **If only B passes**, decision 7's fallback applies. Mimir's TSDB/WAL and sync dirs stay inside the node, on the K3s container's own disk. Only the object root, `mimir/`, sits on D. The 15-minute local hold and `flush_blocks_on_shutdown` already ship every block to D before `down` removes the node, so nothing acknowledged is lost. The "new cluster on the same D" scenario still holds, because only shipped blocks are read from D.
- **If both fail**, implementation stops and the owner decides. Do not disable local metrics.

The decision and its evidence go in this file before task 2 starts.

## Coordination with #902 / PR #1020

- #1020's `ExportStacks` already builds filesystem Loki and Tempo configs, and its `LokiFilesystemLayout` handles Loki's base64 filesystem chunk names.
- Whichever PR merges first owns the shared storage selection, and the other reuses it.
- The mirrored layout (decision 1) is what lets #902's local viewer open D.
- Naming: docs keep `observability local` (#902's viewer of the shared store) separate from local mode (`init --local`, #1040).

## Risks

- **macOS xattr.** Handled by the task-1 gate and the decision-7 fallback.
- **Paths that could delete data.** Each one is pinned by a scenario:
  - `FilesystemObjectStore.deleteFile` exists because the interface requires it. Only the cluster-backup flow calls it, and no observability path does.
  - The Mimir compactor deletes only sources it merged, with retention 0.
  - The Mimir ingester's `retention_period: 15m` removes only local copies that are already shipped. This must be re-checked for the filesystem store.
  - `down`, `clean` and `init --clean` never touch D. #1041 owns that check.
- **Inactive store blocks.** A backend could choke on the store block it is not using. Task 1 checks this.
- **Linux CI.** It cannot reproduce a macOS bind mount, so that case needs a manual macOS check. It is recorded in this file.
