## 1. KitStorage value and state

- [x] 1.1 Test first: `KitStorage.from(state)` returns `LocalVolumes(10Ti)` with nothing recorded, `Provisioned(class, default)` with a class recorded, and applies `kitStorageSize` to either; `storageClassFor(kit)` is `edl-local-<kit>` under `LocalVolumes`.
- [x] 1.2 Add `configuration/KitStorage` (sealed), `InitConfig.kitStorageClass` / `kitStorageSize`, `Constants.K8s.KIT_LOCAL_CLASS_PREFIX`, `LOCAL_KIT_STORAGE_SIZE`, `DEFAULT_PROVISIONED_KIT_STORAGE_SIZE`.
- [x] 1.3 `Init --kit-storage-class` / `--kit-storage-size` named options, validated in `validateParameters` (quantity parse). Unit test: a bad quantity fails naming the value and flag.

## 2. Template variables

- [x] 2.1 Test first (real `TemplateService`): `KIT_STORAGE_CLASS` / `KIT_STORAGE_SIZE` per mode; blank class when kit name is blank; `${KIT_STORAGE_SIZE}` arg defaults resolve in `KitInstallCommandFactory`, and an explicit `--storage-size` wins.
- [x] 2.2 Publish both in `TemplateVariables`; remove `STORAGE_CLASS_WFC` / `storageClassWfc`.

## 3. Retain check

- [x] 3.1 Test first: `StorageClassRetainCheck.require` refuses `Delete`, no policy and a missing class, each message naming the class and the fix; accepts `Retain`. Existing `RetainedClaimCheck` tests still pass through delegation.
- [x] 3.2 Generalize `RetainedClaimCheck` into `StorageClassRetainCheck(storageClass, holds)`.
- [x] 3.3 `KitStorageResolver` at `init` (kubeconfig path, #1082 hook) and `up` (when `--kit-storage-class` given): flag, else cluster default, else fail naming `--kit-storage-class`; emit `Event.Setup.KitStorageClassRefused` and fail. Add `K8sService.defaultStorageClass()`. (init hook: `KitStorageResolver.resolve` is ready for the #1082 kubeconfig path, which does not exist yet)

## 4. kit-storage step

- [x] 4.1 Test first: `KitStorageProvisioner` under `LocalVolumes` ensures the class, mkdirs `/mnt/db1/<kit>/<vct>` (via `RemoteOperationsService`, verified with `argumentCaptor`) and creates PVs of `edl-local-<kit>`; under `Provisioned` runs the Retain check and creates nothing; delete is a no-op under `Provisioned`.
- [x] 4.2 Add `InstallStep.KitStorage` / `KitStorageDelete` (`kit-storage`, `kit-storage-delete`), remove `PlatformPvs` / `PlatformPvsDelete` and their `storage-class` field; move the logic out of `WorkloadStepExecutor` into `KitStorageProvisioner`; `PlatformCreatePvs` reuses it.
- [x] 4.3 `DefaultK8sStorageOperations`: `ensureKitLocalStorageClass` / `deleteKitLocalStorageClass` (fabric8, labelled per #1094); PV path includes the claim template.
- [x] 4.4 Remove `local-storage` / `local-storage-wfc`, `ensureLocalStorageWfcClass`, the `Up.kt` steps and their events (D9); update `PlatformInfo` to print the resolved kit storage.

## 5. Kits

- [x] 5.1 cassandra-pods, neo4j, postgres, clickhouse: drop selectors; class `__KIT_STORAGE_CLASS__`; size arg default `${KIT_STORAGE_SIZE}`; postgres and clickhouse `--size` → `--storage-size` (D3); `platform-pvs` → `kit-storage`.
- [x] 5.2 kafka: `class: __KIT_STORAGE_CLASS__`, no selector, `deleteClaim: false` (D10); size default.
- [x] 5.3 memcached: drop `volumeName` and literal `10Ti`; add `STORAGE_SIZE` arg.
- [x] 5.4 tidb: `kit-storage` steps (PD on app with `count: 1`, TiKV and TiFlash on db), class placeholder, `--storage-size`, `pvReclaimPolicy: Retain`, uninstall comment.
- [x] 5.5 ignite3: class placeholder, `kit-storage` step, size arg (D8).
- [x] 5.6 Check `fsGroup` / writable data dir for each non-root kit image on a fresh dynamic volume.

## 6. Guard and integration tests

- [x] 6.1 `KitClaimStorageTest` replaces `PlatformPvReservationTest`: renders every kit in both modes, extended to `TidbCluster` and class-less StatefulSets; asserts class, no selector / volumeName, size; raw scan finds no fixed class; every kit with a claim declares `kit-storage`.
- [ ] 6.2 Integration (K3s TestContainers): a retained copy of `local-path` as `Provisioned`; cassandra-pods claims Bound at the default size.
- [ ] 6.3 Integration: two kits' local PVs and classes; each kit's claims bind only its own PVs. Move `CassandraPodsKitIntegrationTest` off `LOCAL_STORAGE_WFC_CLASS`.
- [ ] 6.4 Integration: Retain check against real StorageClasses — `local-path` refused, the retained copy accepted, a missing class reported missing.
- [ ] 6.5 Integration: Kafka stop then start rebinds its Retain local PVs.

## 7. Docs and specs

- [ ] 7.1 `docs/user-guide/platform-substrate.md`, `docs/development/kits.md` (storage section), kit user guides (sizes, TiDB medium change), `docs/reference/commands.md` (`init` flags, `--storage-size`).
- [ ] 7.2 Kit specs naming the class: `kafka-kit`, `tidb`, `memcached-kit`, `ignite3`, `postgres`, `clickhouse`, `neo4j-kit`.
- [ ] 7.3 `configuration/CLAUDE.md`, `create-kit` skill guidance.

## 8. Verification

- [ ] 8.1 `./gradlew test integrationTest detekt ktlintCheck` on JDK 21 (in a subagent).
- [ ] 8.2 AWS lab run: install cassandra-pods, clickhouse, kafka and tidb; claims bind to local PVs; stop/start/uninstall behave as before.
