## 0. Baseline (before any refactor, its own commit)

- [x] 0.1 Write the test helper that expands `${VAR}` the way `-config.expand-env=true` does, and the fixed AWS test cluster (bucket `easy-db-lab-test`, region `us-west-2`, tenant `acme`).
- [x] 0.2 Capture the golden files under `src/test/resources/com/rustyrazorblade/easydblab/golden/store/` from the unchanged code: expanded `mimir.yaml`, `loki.yaml` and `tempo.yaml`; the Pyroscope `TemplateService` output; the compactor's `RegisterTaskDefinitionRequest` with each config decoded and expanded; and the instance-role, bucket and compactor task-role policy documents.
- [x] 0.3 Add the golden tests against the current code, so they pass before the refactor starts. Commit 0.1-0.3 alone.

## 1. Neutral path

- [x] 1.1 Test first: `ClusterStoragePath` key rules (empty prefix gives today's key, a prefix leads every key, `toString()` holds no scheme). Rename `ClusterS3Path` to `ClusterStoragePath` with a `prefix` field; update its 15 main and 19 test users.
- [x] 1.2 Test first: `S3Uri.uri`, `S3Uri.parse` (rejects a non-`s3://` URI, as `fromUri` does today). Add `services/aws/S3Uri`; remove `toUri`, `fromUri` and the unused `toEndpointUrl` from the path type.
- [x] 1.3 Add `ObjectStore.uriOf(path)`; implement it in `S3ObjectStore`. Move the display sites in `ClusterBackupService`, `ReportUpload`, `GrafanaAnnotationBackupService`, `CassandraBuild`, `CassandraBuildCatalog` and `StatusCache`/`Status`/`EMRProvisioningService` (EMR log URIs) to `uriOf` or `S3Uri`. Assert the `s3://` string in their existing tests.
- [x] 1.4 `SparkSubmit` parses its jar argument with `S3Uri.parse`.

## 2. In-memory adapter

- [x] 2.1 Test first: the `InMemoryObjectStore` contract test (round trip, `recursive`, `startAfter`, missing-object read throws, directory upload and download, delete, `uriOf` gives `mem://`). Then implement `services/InMemoryObjectStore`.
- [x] 2.2 Sort the 9 test files that mock `ObjectStore` (`TestDocumentServiceTest`, `ReportUploadTest`, `TenantDirectoryTest`, `GrafanaUpdateConfigTest`, `ObservabilityStackServiceTest`, `KitRunnerCommandFactoryTest`, `KitEndToEndTest`, `SparkSubmitTest`, `GrafanaInstallTest`): move stub-only use to the adapter; keep a mock only where it makes a call fail. Record the sort in the PR description.
  - Sort: moved to `InMemoryObjectStore`: `ReportUploadTest`, `TenantDirectoryTest`, `GrafanaUpdateConfigTest`, `ObservabilityStackServiceTest`, `KitRunnerCommandFactoryTest`, `KitEndToEndTest`, `SparkSubmitTest`, `GrafanaInstallTest`. Kept the mock: `TestDocumentServiceTest` (makes `uploadFile` throw).
  - Second pass, outside the 9: moved to `InMemoryObjectStore`: `KitRunnerCommandTestBase` (the shared fixture; only `KitRunnerCommandTypedPhaseTest`'s tenant-listing failure test swaps in a mock that makes `listFiles` throw) and `EMRProvisioningServiceTest` (reads the uploaded bootstrap script back from the store), and `SparkStatusTest` (stores the gzipped stderr, or nothing).  Kept the mock: `EMRSparkServiceTest` (scripts `getFileInfo` answers over successive calls and counts the polling attempts), `S3ObjectStoreTest` (the adapter's own unit test, which mocks the SDK client).
  - Third pass, from review: moved to `InMemoryObjectStore`: `ClusterBackupServiceTest` (a backup is checked by reading the stored object back at its `ClusterStoragePath`, a restore by the local file's content, an unchanged incremental target by its seeded object staying in place; the mock stayed only in two new tests that make `uploadFile` throw, for the single-file backup and for `backupChanged`), `GrafanaAnnotationBackupServiceTest` (integration tier; reads the stored annotations back at the reported path, and asserts the bucket stays empty on the failure paths) and `AwsModuleLogsDirTest` (an unstubbed DI filler). Every remaining `ObjectStore` mock makes a call fail, except `EMRSparkServiceTest`'s scripted `getFileInfo` sequence noted above.

## 3. Store backend and block selection

- [x] 3.1 Add `configuration/store/StoreBackend` (sealed) with `S3Backend` and the `from(clusterState, defaultRegion)` factory. Its KDoc names the volume case and the credential source as #1035's extension points.
- [x] 3.2 Test first: the stub-block rendering test (a test `StoreBlockSource` and a stub fragment in `src/test/resources`; parse each output and read the block at its path; assert no `s3://`, `amazonaws.com` or S3 endpoint). Then add `configuration/store/StoreBlockRenderer` and `StoreBlockSource`.
- [x] 3.3 Move each object-store block into its S3 fragment, verbatim: `mimir/store/s3.yaml`, `loki/store/s3.yaml`, `tempo/store/s3.yaml`, `pyroscope/store/s3.yaml` and the compactor's `tempo-backend` fragment. Replace each block with its placeholder; the Mimir, Loki, Tempo and Pyroscope builders and `CompactorTaskDefinition` splice the fragment in.
- [x] 3.4 The golden tests of task 0 pass unchanged.

## 4. Neutral cluster-config keys

- [x] 4.1 `ClusterConfigData.of` publishes the `store_*` keys of design D5, from `StoreBackend` and `ObservabilityStore`. Update its test.
- [x] 4.2 Rename the `clusterConfigEnv` keys and env names in the Loki, Mimir and Tempo builders, the `${...}` names in the fragments and configs, the compactor task env, and Pyroscope's build-time names. The S3 fragments take the endpoint from `${STORE_ENDPOINT}`.
- [x] 4.3 The golden tests of task 0 pass unchanged. A search of `src/main` and `docs/` finds no `aws_region`, `s3_bucket` or `*_s3_prefix` `cluster-config` key.
  - Every golden file is the task-0 capture. The compactor task's env names that D5 renamed (`S3_BUCKET`, `AWS_REGION` and `*_S3_PREFIX` became `STORE_BUCKET`, `STORE_REGION` and `*_STORE_PREFIX`) are mapped back to their old names in `StoreGoldenTest` before the compare, and the added `STORE_ENDPOINT` is checked for the region's S3 endpoint; the requirement and its scenario say so. The search is `StoreKeyNamesTest`.

## 5. Store prefix and the delete guards

- [x] 5.1 `ObservabilityStore` and `ClusterStoragePath.from`/`root` put every root under the store prefix from `StoreBackend`.
- [x] 5.2 Test first: policy tests with the prefix `team-a` (every old deny resource kept, the prefixed roots added, the compactor allow gains only the prefixed compacted roots, nothing under `grafana/` or `pyroscope/` for the compactor). Then add the prefix-joining helper to `AWSPolicy` and use it in `denyDataDeletes`, `CompactorTaskAccess` and `S3BucketPolicy`; every caller passes the cluster's store prefix.
- [x] 5.3 The empty-prefix golden policy tests of task 0 pass unchanged.

## 6. Docs, specs and checks

- [x] 6.1 Update `docs/` pages that name `aws_region`, `s3_bucket`, the S3 blocks or `ClusterS3Path`.
- [x] 6.2 Update `configuration/CLAUDE.md` (the `cluster-config` key list, the store fragments, and that an intended backend config edit must update its golden file in the same change), `services/CLAUDE.md` and `services/aws/CLAUDE.md`, and the root `CLAUDE.md` Observability section where it names the delete deny.
  - `services/CLAUDE.md` covers SystemD and cluster tooling only and names no store key; the root `CLAUDE.md` in this branch names no delete deny. Neither needed a change. `docs/plans/2026-11-11-kubernetes-forward-architecture.md` keeps `ClusterS3Path`: it records the plan as written.
- [x] 6.3 Fix the `ObjectStore` KDoc, which still says the rename is future work.
- [ ] 6.4 `./gradlew check` on JDK 21 passes with no new `@Suppress`, detekt threshold change or baseline entry; the provider-boundary ArchUnit rules pass with no allowlist.
- [ ] 6.5 Owner-driven, agent-executed (`requires-aws`): on a real AWS cluster, `up`, query metrics, logs, traces and profiles in Grafana, then `down`.
