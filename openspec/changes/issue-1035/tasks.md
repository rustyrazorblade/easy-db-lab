## 0. Preconditions

- [x] 0.1 The owner decided D1-D14 on 2026-11-16, as recorded in `design.md`. Every decision follows the recommendation, so no task needed revision.
- [ ] 0.2 (D1) The separate merge PR has brought `origin/main` (PR #1071, in-cluster compactor) into `local-k8s-edbl`, with the compactor ConfigMap, the `store_bucket` key and the prefix env reconciled there. Merge `origin/local-k8s-edbl` into this branch. Do not start task 1 before this step.

## 1. Gate: macOS bind mount (D13)

- [ ] 1.1 On macOS Docker Desktop, run Mimir 3.2.1 with variant A (the object root and TSDB/sync dirs both on a bind mount) and variant B (only the object root on the mount). Prometheus remote-writes to each until a block and `bucket-index.json.gz` appear, or 5 minutes pass. Record the Docker file-sharing implementation used.
- [ ] 1.2 Record the result in `design.md`. If only B passes, put Mimir's state on the node container's own disk. If neither passes, stop and ask the owner.

## 2. Store record and store backend

- [ ] 2.1 Test first: `ObservabilityStorageRecord` round-trips through `state.json`, for both cases. A state with no record resolves to `S3Backend` + `HostPath("/mnt/db1")` + instance role for both identities.
- [ ] 2.2 Add `ClusterState.observabilityStorage` and the sealed record (Jackson type info plus kotlinx annotations, with KDoc).
- [ ] 2.3 Add the `Constants.Observability` values: `STORE_MOUNT_PATH`, `STATE_DIR`, `NODE_DATA_DIR`, `FILESYSTEM_CONTAINER`, `WRITER_SECRET`, `COMPACTOR_SECRET`, `HOST_STATE_ROOT_AWS`.
- [ ] 2.4 Test first: `StoreBackend.from` returns `FilesystemBackend` for a filesystem record and keeps the record's prefix. Add `FilesystemBackend` and handle it in every exhaustive `when` (`StoreBlockSource.of`, `ClusterConfigData.locationOf`, `TemplateService.storeLocation`).
- [ ] 2.5 Test first, using the real `TemplateService`: each of the four backends renders its `store/filesystem.yaml` fragment under `/store` and the prefix. The AWS rendering is byte-identical except Loki's `object_store`. Add the fragments, the `store_kind` key and `loki.yaml`'s `object_store: ${STORE_KIND}`.

## 3. Hosting and identities

- [ ] 3.1 Test first: the resolution table for (record, capabilities) gives the expected (`StoreBackend`, `BackendStorage`, `StoreAccess`). A missing claim class fails and names the `init` option.
- [ ] 3.2 Make `HostPath` carry `stateRoot`, turn `StateVolume.hostPath` into a directory name, and make `OnNodeStateDirectories` read the resolved path.
- [ ] 3.3 Replace `credentials` with `StoreAccess`/`StoreIdentities`, and implement `ObservabilityHosting.from(state)`.
- [ ] 3.4 Test first: `host(...)` for `Directory` + `HostPath` mounts `NODE_DATA_DIR` at `/store` and the state under `stateRoot`; `Directory` + `RetainedClaim` mounts the claim's `state` and `store` sub-paths with no hostPath; backends read only the writer Secret, the compactor only the compactor Secret.
- [ ] 3.5 (D12) Add the root init container that creates and chowns the state directories when `NODE_SSH` is absent. Test that it renders only then.
- [ ] 3.6 (R3) Pin the Pyroscope Raft server id and advertise address to the stable Service DNS name.

## 4. Checks and labelling

- [ ] 4.1 Add `StoreSecretCheck` and run it beside `RetainedClaimCheck` before any apply. Integration test against a K3s TestContainer: a missing Secret, a missing key, and a present Secret. It fails naming the Secret and key, and prints the policy.
- [ ] 4.2 (D14) Add `Event.Observability.StoreIsClusterScoped` and a `status` line for a filesystem store on a claim. Test the event at `up`.

## 5. IAM and compactor

- [ ] 5.1 Test first (D9): the compactor allow is identical for prefix `a/`, prefix `b/` and no prefix, and covers every compacted root at the top and under any prefix. Build it with `underAnyPrefix`. Remove the "known gap" note from `services/aws/CLAUDE.md`.
- [ ] 5.2 Test first: the compactor's roots and lease follow the store prefix, and its ConfigMap goes through `StoreBlockRenderer` with no unreplaced placeholder and the `store_bucket` key.
- [ ] 5.3 Test first (D8): the writer policy denies deletes under `mimir/`, `loki/` and `tempo/` at the top and under any prefix, and allows deletes only under `pyroscope/`. Render the writer and compactor policies that `StoreSecretCheck` prints.
- [ ] 5.4 (D7) Do not build K5 on AWS here. The separate `EasyDBLabCompactorRole` is #1159 ("Run the account compactor under its own AWS identity, EasyDBLabCompactorRole"), a sub-issue of #1035, and its credentials spike runs first. Until #1159 lands, both identities on AWS stay the instance role. Check that nothing in this change pre-empts #1159: no `AssumedRole`, and no change to the instance role's deny.

## 6. Local compaction

- [ ] 6.1 Move the Mimir compactor flags into a shared `MimirCompactorFlags`, used by the account compactor and local Mimir. Test that both carry the same values.
- [ ] 6.2 For a filesystem store, add `compactor` to Mimir's target, set Loki's compaction interval to 10m (retention and deletion stay off), keep Tempo's compaction off, and skip the account-compactor stage with `StageSkipped`. Test the rendering and the skip.

## 7. Objects and documents

- [ ] 7.1 Write an `ObjectStoreContract` (round trip, listing order, `startAfter`, non-recursive directory children, atomic write, traversal refused) and run it against `InMemoryObjectStore` and `FilesystemObjectStore` on `@TempDir`.
- [ ] 7.2 Add `FilesystemObjectStore` and `ObservabilityObjectStores.forState`, the latter with a lazy S3 binding and a #1082 error when there is no D. Wire `TestDocumentService`, `GrafanaAnnotationBackupService` and `TenantDirectory` to it. Test `report upload`, `grafana backup` (same-second no-overwrite) and the tenant listing over D. Spark and the build catalog stay on S3.
- [ ] 7.3 Make `GrafanaFront` use a sealed `DocumentsSource` and add `grafana-front-nginx-directory.conf`. `GrafanaDeployService` calls `BucketRegion` only for `Bucket`. Integration test: nginx serves only `GET`/`HEAD` under `/reports/` from a directory.

## 8. Integration

- [ ] 8.1 Integration test: run the real Mimir, Loki, Tempo and Pyroscope images with the rendered filesystem fragments on a `@TempDir`, on an internal Docker network with no egress. Write all four signals, wait for `mimir/<tenant>/bucket-index.json.gz`, and query past a shortened hold window.
- [ ] 8.2 In the same test, after compaction, check that the readable sample, line and profile counts are unchanged. Then destroy the containers, start new ones on the same directory, and query every signal again, profiles included, with no duplicate log lines.
- [ ] 8.3 K3s TestContainers test: bind-mount a temp directory at `NODE_DATA_DIR`, declare `HOST_PATH`, and apply the real builders. Files land in the bucket layout, and a second K3s on the same directory reads them back. This is the rehearsal for #1041.
- [ ] 8.4 (D5) Extend `PortableObservabilityPlacementIntegrationTest` for a filesystem store on `RetainedClaim`: one claim per backend holds `state/` and `store/`, the pods reach Ready, and no hostPath is used.

## 9. Docs and guidance

- [ ] 9.1 Update `docs/user-guide/monitoring.md` and `docs/user-guide/compactor.md`: store kinds per environment, the D layout, store Secrets and their policies, the retained-volume label, the known limit that K5 on a filesystem store is enforced only by configuration (R5), local compaction, and "one stack per D".
- [ ] 9.2 Update `configuration/CLAUDE.md` (`ObservabilityHosting`, the store fragments), `services/aws/CLAUDE.md`, and the root `CLAUDE.md` Observability section.
- [ ] 9.3 Update the KDoc in `ObjectStore.kt` and `StoreBackend.kt`, so credentials are documented in one place.
