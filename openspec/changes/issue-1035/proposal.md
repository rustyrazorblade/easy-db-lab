## Why

Mimir, Loki, Tempo and Pyroscope store data only in S3. The endpoint is hard-coded (`s3.${AWS_REGION}.amazonaws.com`) and credentials come from the EC2 instance profile. Annotation backups and test documents also go to S3. Local mode (epic #1032) assumes the user has no AWS access, so local observability storage must be a plain host directory, written with each backend's native filesystem store. There is no S3 emulator. AWS behaviour does not change.

## What Changes

- New `ObservabilityStorage` sealed type in `configuration/`: `S3(bucket, region)` or `Filesystem(mountPath)`. `ObservabilityStorage.from(ClusterState)` returns `S3` until #1040 records the environment in state. `ObservabilityStore` takes the storage and the tenant instead of a bucket.
- Each of `mimir.yaml`, `loki.yaml`, `tempo.yaml` and `pyroscope/config.yaml` keeps one resource with both store blocks. A value published in `cluster-config` chooses between them: `METRICS_BACKEND`, Loki's schema `object_store`, Tempo's `storage.trace.backend`, and Pyroscope's `__PROFILES_BACKEND__`. YAML stays a classpath resource and is never built from strings in Kotlin.
- In filesystem mode the data directory D, mounted at `Constants.Observability.LOCAL_DATA_MOUNT`, mirrors the bucket layout: `mimir/`, `loki/`, `tempo/`, `pyroscope/`, `grafana/annotations/<tenant>/` and `reports/<tenant>/`, plus `state/<backend>/` for each backend's local state. That state is the Mimir TSDB/WAL and sync dirs, the Loki WAL/index/cache, the Tempo WAL, and the Pyroscope metastore data and Raft. The task-1 macOS gate may move the Mimir state.
- Local compaction runs in the backends' own processes, with retention off:
  - Mimir adds `compactor` to its target, with the account compactor's flags moved into a shared `MimirCompactorArgs`. It writes the bucket index the store-gateway needs.
  - Loki compacts every 10m, with retention disabled and deletion `disabled`.
  - Tempo compaction stays off.
  - Pyroscope is unchanged.
  - The ECS account compactor is never called for `Filesystem`.
- New `FilesystemObjectStore(root)`, used for observability objects only: annotation backups, test documents, tenant listing. It is built by `ObservabilityObjectStores.forStorage(storage)`. The global `ObjectStore` binding stays S3, because Spark and the build catalog are AWS-only.
- Grafana's documents view gains a `Directory` source: nginx alone serves `D/reports` read-only, `GET` under `/reports/` only, with no sigv4 proxy.
- `TeardownFlushService` is unchanged. Flushes and the annotation backup land in D through the same code.
- Docs: `docs/user-guide/monitoring.md`, `docs/user-guide/compactor.md`, `configuration/CLAUDE.md`, and the root `CLAUDE.md` Observability section.

## Capabilities

### New Capabilities
- `local-observability-compaction`: in-process compaction of the filesystem store, with retention off.

### Modified Capabilities
- `observability-store`: storage selection, the data-directory layout, local state under D, and AWS behaviour unchanged.
- `grafana-annotations`: annotation backups land under D in filesystem mode.

## Impact

- The four backend config resources and manifest builders (volumes, Mimir target and flags); `ClusterConfigData`, `ObservabilityStore`, `GrafanaDocumentsSidecars`, `CompactorTaskDefinition`, `ServicesModule`, the `Up` compactor call site, `Constants`, and the KDoc in `ObjectStore.kt`.
- No change to the AWS path's storage, the IAM deny rules, or the account compactor.
- Selecting `Filesystem` from state, and mounting D into the cluster, belong to #1040 and #1041. This change is reachable from tests until then.
