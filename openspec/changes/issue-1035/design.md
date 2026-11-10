# Design: per-environment observability storage (#1035)

Epic #1032 (local execution environment), decision Q3: local storage is a directory on the user's machine with no S3, no emulator and no AWS. Every decision below was approved by the owner on 2026-10-31, except the PVC change, which the owner decided on 2026-11-10: off S3, backend pods mount a PersistentVolumeClaim, never a hostPath. The architect's alternatives are named with each decision.

## Approach

### Storage selection
- New `ObservabilityStorage` sealed interface in `configuration/`, with two cases:
  - `S3(bucket, region)`
  - `Volume(...)`: the backends mount PersistentVolumeClaims and use their native filesystem store on paths inside them. What backs a claim is the environment's choice, not part of the type: static `Retain` PVs over a host directory locally (#1041), the cluster's StorageClass on a bring-your-own cluster (#1082, not built here).
- The sealed hierarchy with an exhaustive `when` is the extension point: a future storage makes every consumer fail to compile until it handles the case, and no consumer falls back silently to S3.
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
- The four builders take their volumes and paths from one function, `DataPlacement.forStorage(storage, backend)`, instead of each hard-coding `/mnt/db1/<backend>` (`MimirManifestBuilder.kt:53`, `LokiManifestBuilder.kt:51`, `TempoManifestBuilder.kt:35`, `PyroscopeManifestBuilder.kt:42`). It returns the volume source and the mount paths: for `S3` the existing `/mnt/db1/<backend>` hostPath, unchanged; for `Volume` PVC volume sources.

### Claims and volumes (owner decision 2026-11-10)
- **No hostPath in `Volume` storage.** Hardened clusters forbid it, and a bring-your-own cluster has no node to pin to.
- **Two claims per backend**, named by constants in `Constants.Observability` (name and size), which the provisioner reads:
  - an object claim, whose volume is `D/<backend>/`, mounted at the backend's object root (Mimir `mimir/`, Loki `loki/`, Tempo `tempo/`, Pyroscope `pyroscope/`);
  - a state claim, whose volume is `D/state/<backend>/`, mounted at its WAL, index cache, TSDB, metastore and Raft paths.
  - One claim for the documents server, read-only, over `D/reports/`.
  - Rationale: a PV pins exactly one directory. The mirrored layout has the object root and the state in different directories, so one claim cannot carry both without a `subPath` over a shared D, which would also give every backend D's other trees. Per-backend claims mean each pod sees only its own data, and the state claim can move independently (see the gate below). Claim sizes are metadata only (see the repo's storage rule); the constant is a nominal size.
  - Grafana annotation backups under `D/grafana/` are written by the CLI, not a pod, and need no claim.
- **Binding.** Every claim names an explicit StorageClass constant (`Constants.Observability.STORAGE_CLASS`, e.g. `edl-observability`) that has no provisioner, so no default class adopts it. The local provisioner pre-creates each PV with that class and a `claimRef` to the claim's constant name and namespace. `claimRef` was picked over a selector: it binds exactly one PV to one claim by name, with no label bookkeeping, and the claim names are constants anyway. A claim stays `Pending` until its PV exists; the stack's readiness wait covers that.
- **Never `local-path`.** K3s's dynamic `local-path` provisioner has reclaim policy `Delete` (it would violate the never-delete-owner-data rule) and names directories `pvc-<uuid>`, so a new cluster on the same D could not find the earlier data. Observability claims never name it.
- **What `down` does with claims.** `down` flushes and refuses teardown if the flush did not finish, as today, and removes the cluster. It does not delete a PV or anything under D. With the Retain PVs in D, deleting the claims would leave the data intact, but this change makes `down` and `clean` delete no claim either: they do not touch PVs, claims or D. The environment (#1041) removes the whole cluster, and a Released Retain PV and its directory remain; a new cluster's provisioner re-creates PVs over the same fixed paths.
- **Testing before #1041.** A K3s TestContainer applies the real builders, and the test itself creates the Retain PVs over a temp directory with the same constants, then checks that claims bind, a file written through a pod appears under the temp directory, and a second cluster over the same directory binds to the same paths and sees the data. The provisioner in #1041 uses the same constants, so the rehearsal and the real thing cannot drift.
- Mounting D into the K3s container and creating the PVs is #1041's job.
- Alternative: a free-form layout. Rejected: it breaks #902.
- Alternative: one claim and PV over all of D, with `subPath` per backend. Rejected: every backend could see every other's data, and the state could not move off the bind mount.

### Local compaction (decisions 3 and 4)
- **Mimir:**
  - Adds `compactor` to `target` in filesystem mode.
  - Runs with the account compactor's flags (`CompactorTaskDefinition.kt:83-97`), moved into one shared `MimirCompactorArgs` so the two cannot drift.
  - `blocks-retention-period=0` and `partial-block-deletion-delay=0` mean nothing is ever deleted by age or as a partial block.
  - Its `cleanup-interval=1m` writes the bucket index. Without it, the store-gateway finds no block older than the 15-minute local hold (`mimir.yaml:6`).
- **Loki:** overrides `compaction_interval: 87600h` (`loki.yaml:82`) with `10m`, matching `LOKI_COMPACTOR_ARGS`. Retention stays disabled and deletion `disabled`. Alternative: leave it idle as on clusters. Rejected: it would not split the shared index files for export.
- **Tempo:** compaction stays off, as on every cluster today; reads work without it. Alternative: run `backend-scheduler` and `backend-worker` as extra containers.
- **Pyroscope:** already compacts in its own process with cleanup off. No change.
- **Account compactor:** for `Volume`, `CompactorService.ensureRunning` and `stopIfLastCluster` are never called.

### Object store (decision 6)
- New `FilesystemObjectStore(root)`:
  - Maps a `ClusterS3Path`'s key segments onto `root` and ignores the bucket. `root` is D on the machine that runs the CLI, which is the local environment: the CLI writes D directly, while the backends reach the same files through the PVs. On a bring-your-own cluster the CLI cannot reach the volume, so #1082 must write documents and annotation backups through a pod or to S3.
  - Each write goes to a temp file, then an atomic rename.
  - A `..` or absolute segment is rejected.
- `ObservabilityObjectStores.forStorage(storage)` gives `TestDocumentService`, `GrafanaAnnotationBackupService` and `TenantDirectory` their store.
- The global `ObjectStore` binding (`AWSModule.kt:233`) stays S3, because Spark and `CassandraBuildCatalog` are AWS-only.
- Alternative: replace the global binding. Rejected: it breaks Spark and the build catalog.

### Documents
- New sealed `DocumentsSource`:
  - `Bucket`: today's sigv4 proxy plus nginx.
  - `Volume`: nginx alone, with the read-only reports claim mounted. It uses a second resource, `documents-nginx-local.conf`, with the same `GET`-only, `/reports/`-only guard.

### Teardown
- `TeardownFlushService` is unchanged, as epic decision Q5 requires. Flushes and the annotation backup reach D through the same paths. `down` only flushes, waits, and refuses teardown when the flush did not finish. It adds no verify checks.

### Concurrency (decision 5)
- One running stack per D, documented, with no lock (YAGNI). Two stacks on one D would fight over the Pyroscope Raft state and the Loki ingester IDs.
- Alternatives:
  - A lock file.
  - Per-cluster state. Rejected: it breaks the requirement that data survives into a new cluster.

## macOS bind-mount gate (decision 7)

#902's spike S4 (PR #1020 `design.md:171`) found that the Mimir shipper sets an xattr that a macOS bind mount does not support. Local mode on macOS writes through exactly that kind of mount, and the static PVs sit on the Docker Desktop bind mount, so the claim does not change the problem.

**Status: NOT YET RUN.** The check was attempted on 2026-10-31 and could not finish: the Docker Desktop daemon stopped answering, and `docker ps` and `docker info` hung for more than 20s. The prepared harness is in `/tmp/edl-1035-xattr.TR8g`. It runs two `grafana/mimir:3.2.1` containers with a Prometheus remote-writing to each:
- variant A: object root and TSDB/sync dirs on the bind mount;
- variant B: only the object root on the mount.

So task 1 is a gate. It runs before any other task, and its result picks the Mimir state layout:
- **If variant A passes**, Mimir's state claim is a PV over `D/state/mimir/`, like the other backends.
- **If only B passes**, Mimir's state claim is backed by a node-local volume that is not on the bind mount: the provisioner makes its PV a `local`-type PV on the K3s container's own disk (the claim is still a PVC, so the pod never mounts a hostPath), instead of a second claim or an `emptyDir`. Picked over `emptyDir` because the TSDB/WAL must survive a Mimir pod restart within the cluster; picked over a second claim on a different mount because the claim already exists (the state claim) and only its PV changes, so the manifests are the same in both outcomes. Only the object claim, `mimir/`, sits on D. The 15-minute local hold and `flush_blocks_on_shutdown` already ship every block to D before `down` removes the node, so nothing acknowledged is lost. The "new cluster on the same D" scenario still holds, because only shipped blocks are read from D.
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
  - `down`, `clean` and `init --clean` never touch D, a PV or a claim. #1041 owns that check.
  - Retain PVs: the provisioner must not set `Delete`, and no observability claim names `local-path`. Pinned by the claim-rendering scenarios.
- **Inactive store blocks.** A backend could choke on the store block it is not using. Task 1 checks this.
- **Linux CI.** It cannot reproduce a macOS bind mount, so that case needs a manual macOS check. It is recorded in this file.
