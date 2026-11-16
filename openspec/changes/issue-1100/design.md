## Context

Kit paths are under `src/main/resources/com/rustyrazorblade/easydblab/kits/`; Kotlin paths under `src/main/kotlin/com/rustyrazorblade/easydblab/`.

### Inventory

**Claims bound to local PVs by selector or name**

| Kit | Location | Note |
|---|---|---|
| cassandra-pods | `cassandra-pods/statefulset.yaml.template:193` class, `:195-197` selector, `:200` size | issue cited 186-190 (stale) |
| neo4j | `neo4j/statefulset.yaml.template:113` class, `:115-117` selector, `:120` size | issue cited 117-121 (stale) |
| postgres (CNPG) | `postgres/postgres-cluster.yaml.template:46` class, `:47` size, `:50-52` `pvcTemplate.selector` | |
| clickhouse (Altinity) | `clickhouse/clickhouseinstallation.yaml.template:135` class, `:137-139` selector, `:144` size | |
| kafka (Strimzi) | `kafka/kafka.yaml.template:18` size, `:19` literal `class: local-storage-wfc`, `:21-22` selector, `:23` `deleteClaim: true` | |
| memcached | `memcached/memcached-extstore.yaml.template:16` class, `:17` `volumeName: data-memcached-0`, `:20` literal `10Ti` | issue cited `:36`, which is the Deployment pod selector |

**Hard-coded or implicit classes**

- tidb: `tidb/tidbcluster.yaml.template:21,59,71` `local-path`; `:10` `pvReclaimPolicy: Delete`; `:20,58,70` literal `10Ti`; `tidb/kit.yaml:134-139` uninstall deletes PVCs "so local-path removes the volumes".
- ignite3 (missed by the issue): `ignite3/statefulset.yaml.template:82-93` claim with no `storageClassName` and a fixed `10Gi` (`:92`); uninstall deletes PVCs (`ignite3/kit.yaml:175`).
- Kotlin: `Constants.kt:500-501`; `services/TemplateVariables.kt:48,92` (`STORAGE_CLASS_WFC`); `services/InstallStep.kt:129-130`; `commands/platform/PlatformCreatePvs.kt:66`; `commands/platform/PlatformInfo.kt:45`; `commands/Up.kt:814-816`; `services/DefaultK8sStorageOperations.kt:258-349` (`local-storage-wfc` is created with `reclaimPolicy: Delete`; the PVs at `:170` are `Retain`).
- Resource: `commands/platform/example-helm-values.yaml:4`.

**Size defaults** (10Ti is correct on local volumes): cassandra-pods `kit.yaml:55-59`, kafka `:55-59`, neo4j `:35-39` (`--storage-size`); clickhouse `:59-63`, postgres `:57-61` (`--size`); memcached `kit.yaml:64` and template `:20`; tidb literals; ignite3 `10Gi` literal.

**Local-PV creation**: `type: platform-pvs` / `platform-pvs-delete` (`InstallStep.PlatformPvs`, `InstallStep.kt:114-136`) in cassandra-pods, kafka, neo4j, clickhouse, memcached, postgres. `WorkloadStepExecutor.kt:207-228,309-355`: SSH `sudo mkdir -p /mnt/db1/<kit>` at `:330`, SSH `rm -rf` on delete at `:225`. PVs built with fabric8 at `DefaultK8sStorageOperations.kt:146-202`, one path per kit, so two PVs of one kit on one node would share a directory.

**Stale spec**: `platform-substrate` says PVs are "ClaimRef pre-bound"; the code does not do this.

**Variable flow**: `${VAR}` arg defaults resolve against `TemplateVariables` (`KitInstallCommandFactory.kt:73,112-118`); `STORAGE_SIZE` is persisted in `kit-resolved-args.env` (`BaseInstallCommand.kt:63-71`) and re-read by later phases (`KitRunnerCommand.kt:144-153`); `KitProcessEnvironment.applyTo` exports every variable to kit scripts.

## Decision record

Owner, 2026-11-16: every decision D1-D10 below is decided as its option (a). D10: Kafka `deleteClaim: false`; uninstall deletes the claims explicitly; the restart-after-stop integration test (task 6.5) is the proof.

## Goals / Non-Goals

**Goals:** one kit template that binds on owned local volumes and on any retained dynamic class; a real size on dynamic classes; no kit names a fixed class; a non-`Retain` kit class is refused with its name and the fix.

**Non-goals:** pod placement by role (#1101); backend storage (#1035); `init --kubeconfig` itself (#1082).

## Decisions

### D1. A per-kit local StorageClass replaces the selector (decided (a), owner 2026-11-16)

`LocalVolumes.storageClassFor(kit)` returns `edl-local-<kit>`. The `kit-storage` step creates that no-provisioner, `WaitForFirstConsumer`, `Retain` class (fabric8 `StorageClassBuilder`, labelled `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<id>`) and the kit's PVs of that class. A claim of class `edl-local-cassandra-pods` can only bind a PV of that class, so reservation is structural.

- **(a) Chosen (owner 2026-11-16):** per-kit class. One template, no operator-specific claim names, valid on a dynamic class.
- (b) Pre-bind each PV's `claimRef` to the exact claim name. Needs per-operator name patterns (Strimzi, CNPG, Altinity); fragile.
- (c) Conditional template sections or a Kotlin post-render patch that strips selectors. A mini template language, or hidden rewriting of operator CRs.

### D2. Default size on a provisioned class (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** `100Gi`, overridden by `init --kit-storage-size`.
- (b) `20Gi`: cheaper, kits fill it.
- (c) No default: the flag is required on any provisioned class.

### D3. Per-kit size option (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** keep the kit.yaml named arg, default `${KIT_STORAGE_SIZE}`, and rename postgres's and clickhouse's `--size` to `--storage-size` so every kit uses one name.
- (b) Keep `--size` on those two.
- (c) One global injected `--storage-size`; hides the option from kit.yaml.

### D4. Your cluster with no `--kit-storage-class` (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** use the cluster's default class (annotation `storageclass.kubernetes.io/is-default-class`), then run the Retain check. With no default, fail naming `--kit-storage-class`.
- (b) Always require `--kit-storage-class`.
- (c) Fall back to the observability retained class (#1035). Couples two settings.

### D5. When Retain is checked (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** at `init` (kubeconfig reachable) / `up`, and in the `kit-storage` step. One GET per kit install catches a class deleted and recreated as `Delete`.
- (b) `init` only.
- (c) Also every `start`; redundant with (a).

### D6. The local environment (K3s in Docker, #1041) (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** `Provisioned` on a retained copy of K3s `local-path` that `up` creates. Same code path as your cluster; needs no `NODE_SSH`.
- (b) `LocalVolumes` with `mkdir` through `docker exec` (#1039).
- (c) hostPath PVs with `DirectoryOrCreate`.

### D7. Step name (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** rename to `kit-storage` / `kit-storage-delete`.
- (b) Keep `platform-pvs` and branch inside it.

### D8. ignite3 and memcached in scope (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** yes. ignite3's class-less claim lands on a `Delete` default; memcached's literal `10Ti` would bill 10Ti on EBS.
- (b) Defer ignite3.

### D9. Remove `local-storage-wfc` and `local-storage` (decided (a), owner 2026-11-16)

- **(a) Chosen (owner 2026-11-16):** remove both, their `up` steps and their events; nothing consumes them after this change.
- (b) Keep both for ad-hoc `kit install --from` templates.

### D10. Kafka `deleteClaim` (decided (a), owner 2026-11-16; the restart-after-stop integration test, task 6.5, is the proof)

`stop` deletes the KafkaNodePool; with `deleteClaim: true` Strimzi deletes the claims, and a Retain local PV is left `Released` and cannot rebind on the next `start` (likely an existing bug; to be verified by test).

- **(a) Chosen (owner 2026-11-16):** `deleteClaim: false`; uninstall deletes claims explicitly, as today.
- (b) Leave it if the integration test shows restart works.

Consequence (review finding): `stop` also deletes the `Kafka` resource, whose `.status.clusterId` is the KRaft cluster ID that the kept volumes record in `meta.properties`. A new resource gets a new ID, and a broker refuses a volume of another cluster. So `stop` saves the ID to `kafka-cluster-id` in the kit directory. After a stop, `start` applies the resources with `strimzi.io/pause-reconciliation`, patches the saved ID into the status, and removes the pause. This is Strimzi's procedure for recovering a KRaft cluster from persistent volumes. Keeping the `Kafka` resource across stop instead (pause or scale to 0) was not chosen: it leaves the entity operator and exporter running, and `start` would no longer apply a changed `--brokers` or `--version` the same way.

### Fixed choices (no owner decision needed)

- **State shape:** `InitConfig.kitStorageClass: String?`, `kitStorageSize: String?`; `KitStorage.from(state)` builds the sealed value, like `Placement.from` and `NodeCapabilities.from`. No Jackson polymorphism.
- **Templates stay resources** with value placeholders; no YAML from strings.
- **TiDB `pvReclaimPolicy: Retain`** is a bug fix under the owner-data rule.
- **Composition:** `KitStorageProvisioner` owns the step; `WorkloadStepExecutor` only dispatches. `PlatformCreatePvs` reuses it.

## Interfaces

| Unit | Responsibility |
|---|---|
| `configuration/KitStorage` (sealed: `LocalVolumes`, `Provisioned`) | `storageClassFor(kit)`, `defaultSize: Quantity`, `from(state)` |
| `InitConfig` + `Init --kit-storage-class` / `--kit-storage-size` | record the setting; validate the quantity |
| `TemplateVariables` | publish `KIT_STORAGE_CLASS`, `KIT_STORAGE_SIZE`; remove `STORAGE_CLASS_WFC`; blank class when the kit name is blank |
| `services/KitStorageProvisioner` | `kit-storage`: LocalVolumes → ensure class + mkdir + PVs; Provisioned → Retain check. `kit-storage-delete`: LocalVolumes → delete PVs, class, `rm -rf` (explicit uninstall); Provisioned → no-op |
| `K8sService` | `ensureKitLocalStorageClass`, `deleteKitLocalStorageClass`, `defaultStorageClass(): Result<String?>` |
| `services/StorageClassRetainCheck` | `require(storageClass, holds)`; `RetainedClaimCheck.require(BackendStorage)` delegates |
| `services/KitStorageResolver` | at init / up: resolve the class (flag, else cluster default per D4), check it, emit `Event.Setup.KitStorageClassRefused(storageClass, reclaimPolicy)` and fail |
| `Constants.K8s` | `KIT_LOCAL_CLASS_PREFIX = "edl-local-"`, `LOCAL_KIT_STORAGE_SIZE = "10Ti"`, `DEFAULT_PROVISIONED_KIT_STORAGE_SIZE` |

Refusal message:

> `StorageClass gp2 reclaims with Delete, which would delete kit data when a kit's claim is deleted. Pass init --kit-storage-class naming a StorageClass with reclaimPolicy: Retain, or create one (a copy of gp2 with reclaimPolicy: Retain).`

## Risks / Trade-offs

- **Blast radius:** all eight stateful kits and their specs. Mitigated by the guard test rendering every kit in both modes.
- **Root-owned dynamic volumes:** non-root images (cassandra, neo4j, clickhouse, ignite) may not write to a fresh dynamic volume. Each kit needs `fsGroup` checked; the integration test covers cassandra-pods.
- **TiDB medium change on AWS:** root EBS to NVMe local PVs; PD's PV on an app node assumes `/mnt/db1` there — verify on the instance types.
- **Kafka restart after stop** (D10).
- **Pods still Pending on an unlabelled cluster** until #1101; AC1 is tested on a labelled K3s.
- **`init` has no Kubernetes API on AWS**, so the check runs at `up` there; the `init --kubeconfig` hook depends on #1082.
- **Zonal volumes** pin pods to a zone; acceptable, same as #1091.
- **Per-kit classes are cluster-scoped**; labelled per #1094 and removed by `kit-storage-delete`.
