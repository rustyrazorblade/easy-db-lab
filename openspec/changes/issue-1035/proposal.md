## Why

Mimir, Loki, Tempo and Pyroscope can write only to an S3 object store, reached as the EC2 instance role. Annotation backups and test documents also go only to S3. Epic #1032 (Kubernetes-forward) needs three more cases:

- K3s in Docker (#1041). The user has no AWS, so storage is a plain host directory D that is never deleted, compacted with retention off (decision Q3).
- A user's cluster, object store (#1082). Durable data needs an object store reached with credentials from Secrets. Store access uses two identities: a writer that cannot delete, and a compactor that deletes only what it merged (decision K5).
- A user's cluster, retained volume (#1082). This is allowed, is labelled as data that lives only as long as that cluster, and a deleting StorageClass is refused (decision K3).

Since the 2026-11-10 spec, the types these cases hang on have landed:

- #1061 (neutral store): `StoreBackend`, `StorePrefix` and the per-backend store fragments.
- #1091 (portable placement): `ObservabilityHosting` with `BackendStorage` HostPath | RetainedClaim, `StoreCredentials` InstanceRole | FromSecret, and `RetainedClaimCheck`.
- #1096/#1037: `NodeCapability`.

`ObservabilityHosting.from(state)` is still a stub that always answers AWS, "until #1040 records the environment and #1035/#1061 supply the StorageClass and the Secret". This change fills in that stub. It does not add new parallel types.

## What Changes

- **Recorded store.** `ClusterState.observabilityStorage` holds a sealed `ObservabilityStorageRecord`: `ObjectStore(scheme, bucket, prefix, region, endpoint, writerSecret, compactorSecret, claimClass, claimSize)` or `Filesystem(dataDirectory, claimClass, claimSize)`.
  - Null means the AWS account bucket, exactly as today.
  - This change adds the type and its resolvers. #1040 (`init --local --data-dir`) and #1082 (`init --kubeconfig ...`) add the flags that write it.
- **Filesystem store.** `StoreBackend` gains `FilesystemBackend`.
  - Each backend gets a `store/filesystem.yaml` fragment beside its S3 one: Mimir `filesystem`, Loki `filesystem`, Tempo `local`, Pyroscope `filesystem`. The tree is mounted at `/store`.
  - Loki's schema `object_store` reads a new `store_kind` key in `cluster-config`.
- **Hosting resolves for real.** `ObservabilityHosting.from(state)`:
  - picks `BackendStorage` by the `HOST_PATH` capability, not by the store type;
  - replaces the `credentials` field with `StoreAccess`: `Object(StoreIdentities(writer, compactor))` or `Directory`;
  - mounts the filesystem tree (host directory, or `subPath`s of the existing per-workload claim) and the state directories.
  - A recorded claim class must pass `RetainedClaimCheck`.
- **Store credentials off AWS.** These are Secrets the user creates, named at `init`, with defaults `edl-store-writer` and `edl-store-compactor`. A new `StoreSecretCheck` refuses `up` before anything is applied when a Secret or one of its keys is missing, names the Secret, and prints the policy that identity needs. Backends get the writer Secret, and the compactor gets the compactor Secret.
- **Local compaction, retention off.**
  - Mimir adds the `compactor` target, with flags shared with the account compactor.
  - Loki's compactor interval becomes 10m, with retention and deletion off.
  - Tempo compaction stays off. Pyroscope is unchanged.
  - The account-compactor stage is skipped for a filesystem store.
- **Annotation backups and test documents.**
  - A new `FilesystemObjectStore(root)` gives atomic writes and refuses keys outside root.
  - `ObservabilityObjectStores.forState` gives `TestDocumentService`, `GrafanaAnnotationBackupService` and `TenantDirectory` the store for the cluster. The global `ObjectStore` binding stays S3.
- **Documents view.** `GrafanaFront`'s `DocumentsBucket` becomes a sealed `DocumentsSource`: `Bucket` (today's nginx and sigv4 proxy) or `Directory` (nginx only, serving the tree read-only, `GET`/`HEAD` under `/reports/` only). `BucketRegion` is not called for `Directory`.
- **Compactor policy across store prefixes** (the "known gap left to #1035"). The compactor allow becomes prefix-independent, so every caller puts the same document and the last writer can no longer narrow it. The compactor's roots and lease follow its store's prefix.
- **Retained-volume label (K3).** When a filesystem store sits on a claim, `up` emits a typed event, and `status` says the data lives only as long as the cluster's volumes.
- **D layout** mirrors the bucket, under the store prefix when one is set: `mimir/`, `loki/`, `tempo/`, `pyroscope/`, `grafana/annotations/<tenant>/`, `reports/<tenant>/`, and `state/<backend>/` for each backend's local state.

The owner decided D1-D14 on 2026-11-16 (recorded in `design.md`): the architect's recommendation for every decision. K5 on AWS (D7) is #1159, so on AWS both identities stay the instance role here.

## Capabilities

### New Capabilities
- `local-observability-compaction`: in-process compaction of a filesystem store, with retention off.

### Modified Capabilities
- `observability-store`: the recorded store, the filesystem store, hosting and identities resolved from state, the D layout, objects and documents off S3, and AWS unchanged.
- `account-compactor`: a prefix-independent compactor allow, and roots and lease per store prefix.
- `grafana-annotations`: annotation backups land under D on a filesystem store.

## Impact

- Code:
  - `configuration/store/` (`StoreBackend`, `StoreBlockRenderer`) and the four `store/filesystem.yaml` fragments.
  - `loki.yaml` (`object_store`), `ObservabilityHosting`, `ClusterState`, `ClusterConfigData`, `TemplateService.storeLocation`.
  - `OnNodeStateDirectories`, `ObservabilityStackService`, a new `StoreSecretCheck`, `GrafanaFront`/`GrafanaDeployService`.
  - `TestDocumentService`, `GrafanaAnnotationBackupService`, `TenantDirectory`, `AWSPolicy`, the compactor builders, `Constants`, `ServicesModule`.
- Ordering: this change is built after PR #1071 (in-cluster compactor with a store lease, removes the Fargate account compactor) reaches `local-k8s-edbl` (decision D1).
- AWS: rendered configs are byte-identical except Loki's `object_store` line, which now reads `store_kind` and rolls Loki once. The IAM deny rules and the compactor are unchanged. The separate `EasyDBLabCompactorRole` (D7) is #1159, which begins with a credentials spike.

## Out of scope

- The `init` flags that write the record: #1040 for local, #1082 for your cluster.
- Mounting D into the K3s node container: #1041, at `Constants.Observability.NODE_DATA_DIR`.
- CLI-side annotation backups and documents on a user's retained volume. The CLI cannot reach the volume, so these fail with a message that names #1082.
- Workload identity (IRSA, Pod Identity, GKE/Azure WI) as a credential source (D6(c), deferred).
- Any S3 endpoint emulator, and #902's read-only viewer. #902 should consume the `store/filesystem.yaml` fragments.
