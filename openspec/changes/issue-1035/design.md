# Design: per-environment observability storage (#1035)

This is a re-spec from 2026-11-14 on top of #1061 (neutral store), #1091 (portable placement), #1096/#1037 (NodeCapability) and #1092 (collector split). It also takes into account PR #1071 (in-cluster compactor with a store lease), which is on `main` but not yet on `local-k8s-edbl`. It replaces the 2026-11-10 design.

Owner decisions that bind this design:
- Q3: local storage is a host directory, never deleted, compacted with retention off.
- K3: on your cluster durable data needs an object store; a retained volume is allowed and labelled; a deleting class is refused.
- K5: two store identities.
- Root rules: no automatic deletion, no S3-outage design, no verify checks in `down`, and no YAML built from strings.

The owner decided D1-D14 on 2026-11-16. See **Owner decisions** below; every decision follows the architect's recommendation.

## What decides storage

Three questions are decided separately.

| Question | Decided by | Type |
|---|---|---|
| What the backends write to | the recorded store | `StoreBackend` (`configuration/store/StoreBackend.kt:24`), plus `FilesystemBackend` |
| Where volumes live (state, plus a filesystem tree) | the `HOST_PATH` capability | `BackendStorage` (`ObservabilityHosting.kt:24-39`) |
| Who the backends authenticate as | store kind and environment | `StoreAccess` → `StoreIdentities(writer, compactor)` → `StoreCredentials` |

| Environment | Store | Hosting | Identities |
|---|---|---|---|
| AWS (no record) | `S3Backend` | `HostPath("/mnt/db1")` | instance role for both identities (D7: separate compactor role in #1159) |
| K3s in Docker (#1041) | `FilesystemBackend` | `HostPath(NODE_DATA_DIR/state)`, tree at `NODE_DATA_DIR` | none |
| Your cluster, object store (#1082) | `S3Backend` | `RetainedClaim(class, size)` for state | writer Secret, compactor Secret |
| Your cluster, retained volume (#1082) | `FilesystemBackend` | `RetainedClaim`, one claim per workload holding `state/` and `store/` | none, labelled cluster-scoped |

## Data model

### `ClusterState.observabilityStorage: ObservabilityStorageRecord?`

```kotlin
sealed interface ObservabilityStorageRecord {
    val claimClass: String?   // StorageClass where HOST_PATH is absent
    val claimSize: String?
    data class ObjectStore(scheme: StoreScheme /* S3 */, bucket, prefix = "", region, endpoint: String? = null,
                           writerSecret: String? = null, compactorSecret: String? = null, claimClass, claimSize)
    data class Filesystem(dataDirectory: String? /* D on the CLI machine; null on your cluster */, claimClass, claimSize)
}
```

- Null means the AWS account bucket, and `StoreBackend.from` keeps today's `checkNotNull(s3Bucket)`.
- The record is serialized with Jackson `@JsonTypeInfo(NAME, "type")` plus `@JsonSubTypes`, and also carries kotlinx `@Serializable`/`@SerialName`, the way `NodeCapability` does (`NodeCapability.kt:21-36`).
- D10: this change adds the type and the resolvers. #1040 and #1082 add the `init` flags that write it.

### Constants (`Constants.Observability`)

- `STORE_MOUNT_PATH = "/store"`
- `STATE_DIR = "state"`
- `NODE_DATA_DIR`: the node path where #1041 bind-mounts D
- `FILESYSTEM_CONTAINER`: the fixed "bucket" name for D (D11)
- `WRITER_SECRET = "edl-store-writer"`, `COMPACTOR_SECRET = "edl-store-compactor"`
- `HOST_STATE_ROOT_AWS = "/mnt/db1"`

### `cluster-config`

- New `store_kind` (`s3` | `filesystem`), read by Loki's `schema_config…object_store`. `StoreBlockRenderer` allows one placeholder per resource (`StoreBlockRenderer.kt:88-91`), and Loki's schema is the second store reference.
- `store_bucket` and `store_prefix` are unchanged. For a filesystem store they carry `FILESYSTEM_CONTAINER`.

### D layout

D mirrors the bucket, under the store prefix when one is set:
- `mimir/`, `loki/` (with `loki/index/`), `tempo/`, `pyroscope/`
- `grafana/annotations/<tenant>/`, `reports/<tenant>/`
- `state/<backend>/` for mimir, loki, tempo, pyroscope and grafana

Compactor scratch is not on D, because #1071's lease agent wipes its scratch directory. D is always a parameter, never a constant.

## Approach

### Filesystem store (`StoreBackend`)

- `FilesystemBackend(bucket = FILESYSTEM_CONTAINER, prefix)` is a new `StoreBackend` case. The exhaustive `when`s make every consumer handle it:
  - `StoreBlockSource.of` returns `named("filesystem")`.
  - `ClusterConfigData.locationOf` and `TemplateService.storeLocation` return no region or endpoint.
- Each backend gets a new fragment:
  - `mimir/store/filesystem.yaml`: `backend: filesystem`, `storage_prefix`, `filesystem.dir: /store`
  - `loki/store/filesystem.yaml`: `filesystem.directory: /store`. `object_prefix` stays in `loki.yaml:35`.
  - `tempo/store/filesystem.yaml`: `backend: local`, the same `wal.path`, `local.path: /store/<prefix>tempo`
  - `pyroscope/store/filesystem.yaml`: `backend: filesystem`, `prefix`, `filesystem.dir: /store`
- These replace the old "both blocks plus a runtime selector" design, and with it the old "inactive store block" gate.

### Hosting (`ObservabilityHosting`)

- `BackendStorage.HostPath` becomes `HostPath(stateRoot)`. `StateVolume.hostPath` becomes a directory name, and the full path is resolved against `stateRoot`. AWS still resolves to `/mnt/db1/<backend>`. `OnNodeStateDirectories` reads the resolved path.
- `credentials: StoreCredentials` is replaced by `store: StoreAccess`:
  - `Object(StoreIdentities(writer, compactor))` or `Directory`.
  - Neither "filesystem with credentials" nor "writer Secret on the compactor" can be written down.
  - `StoreCredentials` keeps `InstanceRole` and `FromSecret(secretName)`. This change does not add `AssumedRole(roleArn)`. #1159 (D7) adds it.
- For `Directory`, `host(...)` adds the store volume:
  - `HostPath`: a hostPath of `NODE_DATA_DIR` (all of D, D3) at `/store`, and state at `stateRoot/<dir>`.
  - `RetainedClaim`: the existing `<workload>-data` claim, mounted with `subPath: state` at the state path and `subPath: store` at `/store`.
- `withBackendIdentity` gives the backends the writer's `envFrom`. The compactor workload gets `identities.compactor`.
- `ObservabilityHosting.from(state)`:
  - `placement = Placement.from(state)`.
  - `storage = HostPath(record root)` when `HOST_PATH` is in `NodeCapabilities.from(state)`, otherwise `RetainedClaim(record.claimClass, record.claimSize)`. A missing class fails fast and names the `init` flag.
  - `store = Directory` for a filesystem record, otherwise `Object(identities)`. With no record that is `InstanceRole` for both identities.
- State directories where `NODE_SSH` is absent (D12): a root init container creates and chowns its own state directories, added only off SSH. AWS keeps `OnNodeStateDirectories` over SSH.

### Checks before apply

- `RetainedClaimCheck` (`services/RetainedClaimCheck.kt`) is unchanged. It already refuses non-`Retain` classes and names the class.
- The new `StoreSecretCheck(k8sService)` runs beside it in `ObservabilityStackService` (`:151-153`). For each `FromSecret` it requires the Secret and its `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` keys. Otherwise it fails before any apply, names the Secret, and prints that identity's policy. This is a pre-apply check on `up` and is not part of `down`.
- K3 label (D14): when a `FilesystemBackend` sits on a `RetainedClaim`, `up` emits `Event.Observability.StoreIsClusterScoped(storageClass)`, and `status` prints a line saying the data lives only as long as the cluster's volumes.

### Objects and documents

- `FilesystemObjectStore(root): ObjectStore`:
  - maps `ClusterStoragePath` keys under root and ignores the bucket;
  - writes to a temp file, then renames atomically;
  - refuses `..` and absolute segments;
  - `listFiles(recursive = false)` returns child directories as S3 common prefixes do, which `TenantDirectory` relies on;
  - `deleteFile` exists because the interface requires it, and no observability path calls it.
- `fun interface ObservabilityObjectStores { fun forState(state): ObjectStore }`:
  - returns the Koin S3 store, resolved lazily so a local run builds no AWS client, or `FilesystemObjectStore(record.dataDirectory)`;
  - on a retained volume (`dataDirectory == null`) it fails with a message that names #1082 (D5).
  - It is injected into `DefaultTestDocumentService`, `DefaultGrafanaAnnotationBackupService` and `TenantDirectory`.
  - The Spark, `CassandraBuildCatalog` and `ClusterBackupService` bindings stay S3.
- `GrafanaFront`'s `DocumentsBucket` becomes a sealed `DocumentsSource`:
  - `Bucket(bucket, region)`: today's nginx plus sigv4 proxy.
  - `Directory`: nginx only. It mounts the store volume read-only at `subPath <prefix>reports` and uses `grafana-front-nginx-directory.conf`, with the same `GET`/`HEAD`-only, `/reports/`-only guard.
  - `GrafanaDeployService` (`:114`) calls `BucketRegion.of` only for `Bucket`.

### Compaction

- Object stores: #1071's `account-compactor` pod, with the K5 compactor identity.
- Filesystem (D4(a), in-process):
  - Mimir's `-target` adds `compactor`. The compactor flags (`MIMIR_COMPACTOR_ARGS` from #1071, without `-target`) move into one shared `MimirCompactorFlags`, so the two cannot drift: `blocks-retention-period=0`, `partial-block-deletion-delay=0`, the 10m/2h/12h/24h ranges, and a 1m cleanup that writes the bucket index.
  - Loki's compactor runs every 10m with retention and deletion `disabled`.
  - Tempo compaction stays off.
  - Pyroscope is unchanged.
  - The account-compactor stage is skipped with `StageSkipped`.
- This amends the `observability-store` requirement "Mimir on a cluster SHALL run with no compactor module" so that it applies to object stores only.

### Compactor policy across prefixes (gap 1, D9(a))

- The compactor allow is built with the prefix-independent `underAnyPrefix(COMPACTED_ROOTS)` (`AWSPolicy.kt:~386`). Every caller writes the same document, so no `up` can narrow another cluster's allow.
- The lease is one per store at `<prefix>leases/compactor.json`. The compactor compacts the roots of its own store, through `ObservabilityStore(prefix)`.
- On AWS, no prefix is ever set. A record carries a prefix only for an object store on your cluster.

### Teardown

`TeardownFlushService` is unchanged. Flushes and the annotation backup reach D through the same code paths. Nothing in `down`, `clean` or `init --clean` touches D, a claim or a PV. #1041 owns that check for the local environment.

### Concurrency

One running stack per D. This is documented, and there is no lock (owner decision 5, 2026-10-31). Two stacks on one D would contend for Pyroscope Raft and the Loki ingester state.

## Owner decisions (decided 2026-11-16)

The owner approved the spec at Seam 1 on 2026-11-16. D1, D2 and D7 were decided explicitly. For every other decision the owner took the architect's recommendation. The rejected options are kept so the reasoning can be followed.

- **D1. Ordering with PR #1071: (a).** `origin/main` (PR #1071) is merged into `local-k8s-edbl` in its own PR, and #1035 builds only after that merge. The merge PR owns the reconciliation:
  - #1071's compactor ConfigMap renders `mimir.yaml`/`loki.yaml` without `StoreBlockRenderer`;
  - it reads the old `s3_bucket` key;
  - it pins `*_S3_PREFIX` to bare roots.
  - Rejected: (b) build without #1071, because any `CompactorIam` fix would be thrown away; (c) fold the merge into this PR, because it mixes two reviews.
- **D2. Local state hosting: (a).** A hostPath into D, chosen by the `HOST_PATH` capability, per the 2026-11-11 amendment. This replaces the 2026-11-10 decision. Rejected: (b) static Retain PVs with `claimRef`, two per backend.
- **D3. Filesystem tree mount: (a).** All of D is mounted at `/store` in each backend, so each backend can see the others' trees. Rejected: (b) a `subPath` per root, which nests `loki/loki/`; (c) a separate host directory per root.
- **D4. Local compaction: (a).** In-process: the Mimir `compactor` target with shared flags, and Loki compacting every 10m. This amends the "no compactor on a cluster" requirement so that it applies to object stores only. Rejected: (b) and (c), #1071's pod with no lease or with a filesystem `ConditionalObjects`.
- **D5. Retained-volume store on your cluster: (a).** Build the backend half now. CLI-side backups and documents fail with a message that names #1082. Rejected: (b) object store only off AWS.
- **D6. Store credentials off AWS: (a).** Secrets the user creates, named at `init` (defaults `edl-store-writer`, `edl-store-compactor`). They are validated before apply, and both policies are printed. Rejected: (b) Secrets built from a named profile. Deferred: (c) workload identity, to #1082.
- **D7. K5 on AWS: (a), in child issue #1159** ("Run the account compactor under its own AWS identity, EasyDBLabCompactorRole", a sub-issue of #1035). A separate `EasyDBLabCompactorRole`, assumed by the lease container and served on `127.0.0.1` as ECS-style container credentials. The instance role's delete deny returns to all roots.
  - #1159 runs the credentials spike first. It must prove that minio-go and Loki's S3 client honour `AWS_CONTAINER_CREDENTIALS_FULL_URI`.
  - #1035 does not build it. #1035's AWS criterion stays "unchanged", so on AWS both identities remain the instance role.
  - Rejected: (b) an IAM user with static keys; (c) a permanent lease-only guard.
- **D8. Pyroscope and the writer identity: (a).** The writer may delete under `pyroscope/` only. Rejected: (b) giving Pyroscope the compactor identity.
- **D9. Compactor allow and store prefixes: (a).** A prefix-independent allow, with the lease and the roots following each store's prefix. Rejected: (b) enumerating the prefixes in use; (c) leaving the gap latent.
- **D10. Who writes the record: (a).** #1035 adds the type and the resolvers; #1040 and #1082 add the `init` flags. Rejected: (b) waiting for #1040.
- **D11. Filesystem "bucket": (a).** A fixed container constant. Rejected: (b) moving `bucket` off `StoreBackend`.
- **D12. State directories without SSH: (a).** A root init container, only where `NODE_SSH` is absent. Rejected: (b) the init container everywhere; (c) directories created with mode 0777.
- **D13. macOS bind-mount xattr gate: (a).** Kept as a manual gate before the storage tasks (task 1). Rejected: (b) dropping it.
- **D14. Retained-volume label: (a).** A typed event at `up` plus a `status` line. Rejected: (b) also publishing `store_durability`; (c) docs only.
- **R5 (known limit) accepted.** On a filesystem store, K5 is enforced by configuration only (retention off, compaction replaces its sources). The store itself cannot enforce it. This is documented in the user guide.
## Risks

- **R1. AWS calls in local mode.** The paths are `BucketRegion.of`, `CompactorStage.prepare`, the sigv4 container, an eagerly built `S3ObjectStore`, and `ObservabilityStore.from` on a null `s3Bucket`. Covered by the no-egress scenario.
- **R2. #1071 merge breakage** (D1): a literal `__OBJECT_STORE_BLOCK__`, the old `s3_bucket` key, a prefix ignored. Covered by the compactor-config scenario.
- **R3. Pyroscope Raft identity across clusters.** The Raft server id and advertise address are pinned to the stable Service DNS name. Covered by the new-cluster-on-the-same-D scenario.
- **R4. WAL replay on reuse.** Loki's ingester id is `${TENANT}.${CLUSTER_NAME}`. The flush on shutdown leaves the WAL nearly empty. Covered by the no-duplicate-lines check in the reuse test.
- **R5. K5 cannot be structural on a filesystem.** A process that writes can unlink. The guard is configuration only (retention off, compaction replaces sources). The owner accepted it as a known limit on 2026-11-16, and it is documented.
- **R6. A deleting StorageClass.** Already refused by `RetainedClaimCheck`. No new deletion path is added.
- **R7. Writer can delete beyond `pyroscope/`.** Pinned by a policy unit test.
- **R8. Uncompacted Tempo on a long-lived D.** Queries slow down. Reads stay correct.
- **R9. Two stacks on one D.** Documented, not locked.
- **R10. Linux host bind mount uid.** uid 10001 cannot write directories owned by the host user. Handled by D12.
- **R11. Linux CI cannot reproduce a macOS bind mount.** Handled by the manual gate (D13).

## Coordination

- #902 / PR #1020: its export and viewer should consume the `store/filesystem.yaml` fragments instead of building their own `ExportStacks` configs. The mirrored layout is what lets #902 open D.
- #1041 owns the D bind mount at `NODE_DATA_DIR` and the check that `down` deletes nothing under D.
- #1082 owns the `init` flags for your cluster and CLI-side objects on a retained volume.
