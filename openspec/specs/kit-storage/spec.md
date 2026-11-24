# kit-storage Specification

## Purpose
Where a kit's claims get their volumes. On owned nodes each kit gets its own local StorageClass and local PVs on the node NVMe; elsewhere kit claims use a recorded StorageClass that retains, so stopping or uninstalling a kit never deletes its data by accident.
## Requirements
### Requirement: Kit claims follow the environment's kit storage

Every claim a built-in kit creates SHALL name the StorageClass `__KIT_STORAGE_CLASS__` and request `__STORAGE_SIZE__`, and SHALL carry neither a `selector` nor a `volumeName`. The class SHALL come from a `KitStorage` value resolved once from cluster state: `LocalVolumes` where `init` recorded no kit StorageClass on nodes easy-db-lab owns (EC2), and `Provisioned(storageClass, defaultSize)` otherwise: the recorded class, or on any other cluster (K3s in Docker, a brought kubeconfig) with none recorded, the cluster's default class. The environment's choice comes from the cluster contract. Under `LocalVolumes` the class SHALL be `edl-local-<kit>`. No kit resource SHALL name `local-path`, `local-storage`, `local-storage-wfc`, `STORAGE_CLASS_WFC` or any other fixed StorageClass. This SHALL apply to cassandra-pods, neo4j, postgres, clickhouse, kafka, memcached, tidb and ignite3.

#### Scenario: A stateful kit binds on a dynamic StorageClass
- **GIVEN** a cluster whose recorded kit StorageClass is a dynamic class that reclaims with `Retain`
- **AND** no `--storage-size` and no `init --kit-storage-size`
- **WHEN** the cassandra-pods kit starts
- **THEN** each of its claims names that class, has no selector and no volume name
- **AND** each claim is Bound at the provisioned-class default size

#### Scenario: A kit starts on AWS on local volumes
- **GIVEN** an AWS cluster with no recorded kit StorageClass
- **WHEN** a stateful kit starts
- **THEN** its claims bind to the local PVs created for that kit on `/mnt/db1`
- **AND** each claim requests `10Ti` unless the kit's `--storage-size` says otherwise

#### Scenario: A local cluster with no recorded class claims on its default class
- **GIVEN** a K3s in Docker or brought-kubeconfig workspace with no recorded kit StorageClass
- **WHEN** a stateful kit's `kit-storage` step runs
- **THEN** the kit storage is `Provisioned` on the cluster's default class, never `LocalVolumes`
- **AND** that class is checked for `Retain` before any claim exists

#### Scenario: No kit names a fixed storage class
- **WHEN** the packaged kit resources are scanned
- **THEN** none contains `local-path`, `local-storage` or `STORAGE_CLASS_WFC`
- **AND** every `storageClassName`, `storageClass` or Strimzi `class` value is `__KIT_STORAGE_CLASS__`
- **AND** no rendered claim, in either `LocalVolumes` or `Provisioned` mode, has a `selector` or a `volumeName`

#### Scenario: TiDB and Kafka take the environment's class
- **GIVEN** a recorded kit StorageClass `retain-x`
- **WHEN** the tidb and kafka kits are rendered
- **THEN** TiDB's PD, TiKV and TiFlash claims and Kafka's node-pool storage name `retain-x`
- **AND** TiDB renders `pvReclaimPolicy: Retain`

### Requirement: Local kit volumes are reserved per kit on owned nodes

Under `LocalVolumes`, the `kit-storage` step SHALL create a no-provisioner StorageClass `edl-local-<kit>` with `WaitForFirstConsumer` binding and `Retain` reclaim, labelled `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<id>`, and SHALL create that kit's local PVs of that class at `/mnt/db1/<kit>/<claim template>`. An existing class of that name SHALL be reused only when its provisioner, binding mode and reclaim policy match; otherwise the step SHALL fail naming each field that differs and create no directory or PV. The kit name and claim template name SHALL each be one directory name (not blank, no `/`, no `..`), and every path sent to a node SHALL be shell-quoted. A kit's claims SHALL bind only that kit's PVs. Under `Provisioned`, the step SHALL create no PV and no StorageClass. The `kit-storage-delete` step SHALL, under `LocalVolumes` only, delete the kit's PVs and class and the node directory as part of an explicit `kit uninstall`; under `Provisioned` it SHALL do nothing and SHALL never delete a claim or a volume.

#### Scenario: Two kits never take each other's volumes
- **GIVEN** local PVs and per-kit classes for two kits on the same nodes
- **WHEN** both kits start
- **THEN** each kit's claims are Bound only to PVs created for that kit

#### Scenario: One kit holds two volumes on one node
- **WHEN** the tidb kit's `kit-storage` steps run for TiKV and TiFlash on the same db node
- **THEN** two PVs are created with distinct paths under `/mnt/db1/tidb/`

#### Scenario: The step creates nothing on a provisioned class
- **GIVEN** a recorded kit StorageClass
- **WHEN** a kit's `kit-storage` and `kit-storage-delete` steps run
- **THEN** no PersistentVolume and no StorageClass is created or deleted

#### Scenario: A leftover class that deletes is not reused
- **GIVEN** a StorageClass `edl-local-kafka` exists with `reclaimPolicy: Delete`
- **WHEN** the kafka kit's `kit-storage` step runs on local volumes
- **THEN** it fails naming `edl-local-kafka` and `reclaimPolicy Delete (expected Retain)`
- **AND** no directory and no PV is created

#### Scenario: A failed delete keeps the data directory
- **WHEN** deleting the kit's PVs or its class fails during `kit-storage-delete`
- **THEN** the step fails and no node directory is removed

### Requirement: Kit claims survive stop

A kit SHALL keep its claims bound across `stop`, so the next `start` mounts the volumes that hold its data and the operator resumes that data rather than bootstrapping over it. An operator that deletes its claims, or the data on them, when its resource is deleted SHALL be stopped without deleting the resource, or told to retain its claims: postgres hibernates its CNPG `Cluster` (`cnpg.io/hibernation=on`), and clickhouse declares `reclaimPolicy: Retain` on its data claim template. Only `uninstall` deletes a kit's claims. A `kit-storage` step MAY still declare `rebind-released: true` for a kit whose claims are deleted on `stop`: under `Provisioned`, after the Retain check passes, that step SHALL clear the claimRef UID of every `Released` PV of the kit StorageClass whose claim was in the kit namespace, keeping the claim name, and SHALL NOT delete or change any other PV. No built-in kit declares it.

#### Scenario: postgres data survives stop and start
- **WHEN** `postgres stop` runs and then `postgres start`
- **THEN** the Cluster and its claims were kept through the stop, the claims stay bound to the same volumes, and CNPG runs no bootstrap job

#### Scenario: clickhouse tables survive stop and start
- **WHEN** `clickhouse stop` deletes the ClickHouseInstallation and then `clickhouse start` runs
- **THEN** the operator dropped no table and deleted no claim, and the new pods mount the same claims

### Requirement: Kit storage size precedence

A kit's claim size SHALL be, from strongest to weakest: the kit's `--storage-size` named option; `init --kit-storage-size`; the environment default, which SHALL be `10Ti` under `LocalVolumes` and `Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE` under `Provisioned`. Every stateful kit SHALL declare its size arg with the default `${KIT_STORAGE_SIZE}`. `init` SHALL reject a `--kit-storage-size` that is not a positive Kubernetes quantity (so `0` and `-1Gi` are refused).

#### Scenario: The kit option wins
- **GIVEN** `init --kit-storage-size 200Gi`
- **WHEN** a kit is installed with `--storage-size 50Gi`
- **THEN** its claims request `50Gi`

#### Scenario: init --local records the kit storage options
- **WHEN** `init --local --kit-storage-class retain-local --kit-storage-size 20Gi` runs
- **THEN** state records `retain-local` and `20Gi` as the kit StorageClass and size

#### Scenario: The init setting overrides the environment default
- **GIVEN** a provisioned kit StorageClass and `init --kit-storage-size 200Gi`
- **WHEN** a kit is installed with no `--storage-size`
- **THEN** its claims request `200Gi`

#### Scenario: A bad size is refused at init
- **WHEN** `init --kit-storage-size lots` runs
- **THEN** it fails naming `lots` and the flag

### Requirement: A kit StorageClass that does not retain is refused

Where a kit StorageClass is recorded, or a cluster whose nodes easy-db-lab does not own takes its default class, the system SHALL refuse it when its `reclaimPolicy` is not `Retain` or it does not exist, naming the class and stating the fix: pass `init --kit-storage-class` naming a class with `reclaimPolicy: Retain`, or create a copy of the named class with `reclaimPolicy: Retain`. The check SHALL run at `up`, the first time the cluster's API is reachable, when `init` recorded a `--kit-storage-class`, before any kit or observability workload is deployed; and in the `kit-storage` step before any kit claim is created. Every refusal, on any of these paths and in `platform create-pvs`, SHALL be emitted as `Setup.KitStorageClassRefused` before the command fails. `init` SHALL only record the class: on AWS it has no cluster API to check against. The resolver that picks and checks the class (`KitStorageResolver`) SHALL use the named class, else the cluster's default class, and SHALL fail naming `--kit-storage-class` when neither exists; running it at `init` against a cluster the user brings is left to the `init --kubeconfig` change (#1082). Under `LocalVolumes` no check is needed.

#### Scenario: A deleting class fails up
- **GIVEN** `init --kit-storage-class gp2` was run and `gp2` reclaims with `Delete`
- **WHEN** `up` reaches the cluster's API
- **THEN** it fails, naming `gp2`, its reclaim policy, and `init --kit-storage-class` with `reclaimPolicy: Retain` as the fix
- **AND** no observability stack or kit is deployed

#### Scenario: A class swapped after init is refused at kit install
- **GIVEN** the recorded kit StorageClass was deleted and recreated with `reclaimPolicy: Delete`
- **WHEN** a stateful kit is installed
- **THEN** the `kit-storage` step fails naming the class before any claim exists

#### Scenario: A missing class is reported as missing
- **WHEN** the recorded kit StorageClass does not exist
- **THEN** the refusal says the class does not exist and gives the same fix

#### Scenario: K3s's local-path default is refused on a local workspace
- **GIVEN** a K3s in Docker workspace with no recorded kit StorageClass, whose default class `local-path` reclaims with `Delete`
- **WHEN** a stateful kit's `kit-storage` step runs
- **THEN** it fails with `Setup.KitStorageClassRefused` naming `local-path`, `init --kit-storage-class` and a Retain copy of `local-path` as the fix
- **AND** no PersistentVolume and no StorageClass is created

#### Scenario: No default class and no class named
- **GIVEN** a cluster with no default StorageClass
- **WHEN** the kit StorageClass is resolved with no class named
- **THEN** it fails naming `--kit-storage-class`

