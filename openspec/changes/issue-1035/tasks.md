## 1. Gate: macOS bind mount and inactive store blocks

- [ ] 1.1 Run the Mimir 3.2.1 bind-mount check on macOS Docker Desktop. Variant A puts the object root and the TSDB/sync directories on the mount; variant B puts only the object root there. The PV for each claim sits on the same bind mount, so the result carries over. Prometheus remote-writes to each until a block and `bucket-index.json.gz` appear, or 5 minutes pass. The harness is in `/tmp/edl-1035-xattr.TR8g`; it was not run because the Docker daemon was hung on 2026-10-31.
- [ ] 1.2 Record the result in `design.md`. If only B passes, apply the decision-7 fallback: Mimir's state claim is backed by a node-local `local`-type PV instead of one on the bind mount. If neither passes, stop and ask the owner.
- [ ] 1.3 Check that each backend starts with its unused store block present, Loki's `common.storage` included.

## 2. Storage selection

- [ ] 2.1 Write tests: `ObservabilityStorage.from` returns `S3` for a state with no environment, and `ObservabilityStore` builds the same prefixes for both storages.
- [ ] 2.2 Add `ObservabilityStorage` (sealed, with KDoc) and make `ObservabilityStore` take the storage and the tenant.
- [ ] 2.3 Add the `Constants.Observability` values the provisioner reads: the `STORAGE_CLASS` name, each claim's name and nominal size (object and state claim per backend, plus the reports claim), and the `state/<backend>` directory names.
- [ ] 2.4 Test first: `DataPlacement.forStorage` returns the existing `/mnt/db1/<backend>` hostPath placement for `S3` and PVC volume sources and mount paths for `Volume`, for each of the four backends. The `Volume` answer never contains a hostPath. Add `DataPlacement` and make the Mimir, Loki, Tempo and Pyroscope builders call it.
- [ ] 2.5 Test first: rendering for `Volume` names no `local-path` StorageClass, each claim names `Constants.Observability.STORAGE_CLASS`, and no two workloads mount one claim. Add the claim manifests (fabric8).

## 3. Backend configs

- [ ] 3.1 Write rendering tests: for each backend and each storage, the rendered config selects the matching store with the claim mount paths, and the S3 rendering is unchanged.
- [ ] 3.2 Add the filesystem blocks and the selector value to `mimir.yaml`, `loki.yaml`, `tempo.yaml` and `pyroscope/config.yaml`, and publish the selector and paths through `ClusterConfigData`.
- [ ] 3.3 Make the filesystem placement in `DataPlacement` point the four backends at their claim mount paths (the builders already call it, task 2.4).

## 4. Local compaction

- [ ] 4.1 Extract `MimirCompactorArgs`, shared by `CompactorTaskDefinition` and local Mimir, and add a test that both carry the same values.
- [ ] 4.2 In `Volume` storage, add `compactor` to Mimir's target, set Loki's compaction interval to 10m, and keep Tempo's compaction off.
- [ ] 4.3 Skip `CompactorService.ensureRunning` and `stopIfLastCluster` for `Volume`, with a test that makes no ECS, IAM or CloudWatch call.

## 5. Object store and documents

- [ ] 5.1 Write `FilesystemObjectStore` tests on `@TempDir`: round trip, listing order, `startAfter`, atomic write, traversal refused.
- [ ] 5.2 Add `FilesystemObjectStore` and `ObservabilityObjectStores.forStorage`, and wire `TestDocumentService`, `GrafanaAnnotationBackupService` and `TenantDirectory` to them. The global binding stays S3. The filesystem store's root is D on the CLI's machine.
- [ ] 5.3 Add `DocumentsSource` and `documents-nginx-local.conf` (read-only, `GET` under `/reports/` only), with an nginx integration test serving from a directory that stands in for the reports claim.

## 6. Integration

- [ ] 6.1 Write an integration test that runs the real backend images with the rendered filesystem configs on a `@TempDir` standing in for a claim's volume, on a Docker network with no egress. It writes all four signals, waits for the Mimir bucket index, and queries past the hold window.
- [ ] 6.2 In the same test, destroy the containers and start new ones on the same directory, then query every signal again, profiles included.
- [ ] 6.3 In the same test, after compaction, check that the readable sample, line and profile counts are unchanged.
- [ ] 6.4 Write a K3s TestContainers test that applies the stack's real builders for `Volume` storage. The test creates the static `Retain` PVs over a temp directory (with `claimRef` to the constant claim names and the constant StorageClass), then checks that every claim binds, that data written by a backend pod is under the temp directory, and that no hostPath and no `local-path` is used. It is the rehearsal for #1041.
- [ ] 6.5 In the same test, delete the cluster's claims and the namespace, create a second K3s over the same temp directory with fresh PVs and claims, and check that each claim binds to its backend's directory and the earlier data is readable. Check that the temp directory is untouched by the removal.

## 7. Docs and project guidance

- [ ] 7.1 Update `docs/user-guide/monitoring.md` and `docs/user-guide/compactor.md` with local storage (PVC-backed, with the static Retain PV contract for provisioners), the data directory layout, and "one stack per directory".
- [ ] 7.2 Update `configuration/CLAUDE.md` and the root `CLAUDE.md` Observability section: storage selection, the claims and constants, the D layout, and local compaction with retention off.
- [ ] 7.3 Update the KDoc in `ObjectStore.kt`, which names the filesystem implementation.
