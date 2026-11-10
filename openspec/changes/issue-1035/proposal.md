## Why

Mimir, Loki, Tempo and Pyroscope store data only in S3. The endpoint is hard-coded (`s3.${AWS_REGION}.amazonaws.com`) and credentials come from the EC2 instance profile. Annotation backups and test documents also go to S3. Local mode (epic #1032) assumes the user has no AWS access, so local observability storage must be a directory on the user's machine, reached by the backends through a PersistentVolumeClaim and written with each backend's native filesystem store. There is no S3 emulator. AWS behaviour does not change.

## What Changes

- New `ObservabilityStorage` sealed type in `configuration/`: `S3(bucket, region)` or `Volume(...)`, a PersistentVolumeClaim per backend. `Volume` is the only non-S3 case: the local environment (#1041) backs the claims with static PersistentVolumes over a host directory, and a bring-your-own cluster (#1082) will back them with its StorageClass. The backends never mount a hostPath in this case. `ObservabilityStorage.from(ClusterState)` returns `S3` until #1040 records the environment in state. `ObservabilityStore` takes the storage and the tenant instead of a bucket.
- Each of `mimir.yaml`, `loki.yaml`, `tempo.yaml` and `pyroscope/config.yaml` keeps one resource with both store blocks. A value published in `cluster-config` chooses between them: `METRICS_BACKEND`, Loki's schema `object_store`, Tempo's `storage.trace.backend`, and Pyroscope's `__PROFILES_BACKEND__`. YAML stays a classpath resource and is never built from strings in Kotlin.
- In `Volume` storage each backend mounts two claims (an object claim and a state claim), and the claims' backing storage mirrors the bucket layout under a data directory D: `mimir/`, `loki/`, `tempo/`, `pyroscope/`, `grafana/annotations/<tenant>/` and `reports/<tenant>/`, plus `state/<backend>/` for each backend's local state. That state is the Mimir TSDB/WAL and sync dirs, the Loki WAL/index/cache, the Tempo WAL, and the Pyroscope metastore data and Raft. The claim names and sizes are constants in `Constants`, read by the provisioner. The task-1 macOS gate may move Mimir's state claim off the bind mount.
- Local compaction runs in the backends' own processes, with retention off:
  - Mimir adds `compactor` to its target, with the account compactor's flags moved into a shared `MimirCompactorArgs`. It writes the bucket index the store-gateway needs.
  - Loki compacts every 10m, with retention disabled and deletion `disabled`.
  - Tempo compaction stays off.
  - Pyroscope is unchanged.
  - The ECS account compactor is never called for `Volume`.
- New `FilesystemObjectStore(root)`, used for observability objects only: annotation backups, test documents, tenant listing. It reads and writes D on the machine that runs the CLI. It is built by `ObservabilityObjectStores.forStorage(storage)`. The global `ObjectStore` binding stays S3, because Spark and the build catalog are AWS-only.
- Grafana's documents view gains a `Volume` source: nginx alone serves a read-only reports claim (backed by `D/reports`), `GET` under `/reports/` only, with no sigv4 proxy.
- `TeardownFlushService` is unchanged. Flushes and the annotation backup land in D through the same code.
- New `DataPlacement.forStorage(storage, backend)`: one function that gives the four backend builders their data volumes and paths. For `S3` it answers today's `/mnt/db1/<backend>` hostPath; for `Volume` it answers PVC volume sources and mount paths. They stop hard-coding `/mnt/db1/<backend>`.
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
- Selecting `Volume` from state, and creating the PersistentVolumes over D, belong to #1040 and #1041. Until then the claim path is exercised in a K3s TestContainer where the test creates the Retain PVs over a temp directory.

## Out of scope

- What backs the claims. That is the environment's job:
  - Local K3s-in-docker (#1041): the provisioner pre-creates one static PersistentVolume per claim, `Retain`, at a fixed path under the bind-mounted D, pre-bound by `claimRef`. It never uses K3s's `local-path` provisioner (reclaim `Delete`, random `pvc-<uuid>` directories).
  - Bring-your-own cluster (#1082, later): the cluster's StorageClass provisions the claims. Not built here.
- Documents and annotation backups on a bring-your-own cluster. The CLI cannot reach the volume there, so #1082 must write them through a pod or to S3. Here the CLI writes D on the host, which is the local environment only.
- Any bring-your-own Kubernetes environment.
