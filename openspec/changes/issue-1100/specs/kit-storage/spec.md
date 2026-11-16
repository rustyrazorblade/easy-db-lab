## ADDED Requirements

### Requirement: Kit claims follow the environment's kit storage

Every claim a built-in kit creates SHALL name the StorageClass `__KIT_STORAGE_CLASS__` and request `__STORAGE_SIZE__`, and SHALL carry neither a `selector` nor a `volumeName`. The class SHALL come from a `KitStorage` value resolved once from cluster state: `LocalVolumes` where `init` recorded no kit StorageClass (owned nodes), and `Provisioned(storageClass, defaultSize)` otherwise. Under `LocalVolumes` the class SHALL be `edl-local-<kit>`. No kit resource SHALL name `local-path`, `local-storage`, `local-storage-wfc`, `STORAGE_CLASS_WFC` or any other fixed StorageClass. This SHALL apply to cassandra-pods, neo4j, postgres, clickhouse, kafka, memcached, tidb and ignite3.

#### Scenario: A stateful kit binds on a dynamic StorageClass
- **GIVEN** a cluster whose recorded kit StorageClass is a dynamic class that reclaims with `Retain`
- **AND** no `--storage-size` and no `init --kit-storage-size`
- **WHEN** the cassandra-pods kit starts
- **THEN** each of its claims names that class, has no selector and no volume name
- **AND** each claim is Bound at the provisioned-class default size

#### Scenario: A kit starts on AWS exactly as today
- **GIVEN** an AWS cluster with no recorded kit StorageClass
- **WHEN** a stateful kit starts
- **THEN** its claims bind to the local PVs created for that kit on `/mnt/db1`
- **AND** each claim requests `10Ti` unless the kit's `--storage-size` says otherwise

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

Under `LocalVolumes`, the `kit-storage` step SHALL create a no-provisioner StorageClass `edl-local-<kit>` with `WaitForFirstConsumer` binding and `Retain` reclaim, labelled `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<id>`, and SHALL create that kit's local PVs of that class at `/mnt/db1/<kit>/<claim template>`. A kit's claims SHALL bind only that kit's PVs. Under `Provisioned`, the step SHALL create no PV and no StorageClass. The `kit-storage-delete` step SHALL, under `LocalVolumes` only, delete the kit's PVs and class and the node directory as part of an explicit `kit uninstall`; under `Provisioned` it SHALL do nothing and SHALL never delete a claim or a volume.

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

### Requirement: Kit storage size precedence

A kit's claim size SHALL be, from strongest to weakest: the kit's `--storage-size` named option; `init --kit-storage-size`; the environment default, which SHALL be `10Ti` under `LocalVolumes` and `Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE` under `Provisioned`. Every stateful kit SHALL declare its size arg with the default `${KIT_STORAGE_SIZE}`. `init` SHALL reject a `--kit-storage-size` that is not a Kubernetes quantity.

#### Scenario: The kit option wins
- **GIVEN** `init --kit-storage-size 200Gi`
- **WHEN** a kit is installed with `--storage-size 50Gi`
- **THEN** its claims request `50Gi`

#### Scenario: The init setting overrides the environment default
- **GIVEN** a provisioned kit StorageClass and `init --kit-storage-size 200Gi`
- **WHEN** a kit is installed with no `--storage-size`
- **THEN** its claims request `200Gi`

#### Scenario: A bad size is refused at init
- **WHEN** `init --kit-storage-size lots` runs
- **THEN** it fails naming `lots` and the flag

### Requirement: A kit StorageClass that does not retain is refused

Where a kit StorageClass is recorded, the system SHALL refuse it when its `reclaimPolicy` is not `Retain` or it does not exist, naming the class and stating the fix: pass `init --kit-storage-class` naming a class with `reclaimPolicy: Retain`, or create a copy of the named class with `reclaimPolicy: Retain`. The check SHALL run at `up`, the first time the cluster's API is reachable, when `init` recorded a `--kit-storage-class`, before any kit or observability workload is deployed; and in the `kit-storage` step before any kit claim is created. `init` SHALL only record the class: on AWS it has no cluster API to check against. The resolver that picks and checks the class (`KitStorageResolver`) SHALL use the named class, else the cluster's default class, and SHALL fail naming `--kit-storage-class` when neither exists; running it at `init` against a cluster the user brings is left to the `init --kubeconfig` change (#1082). Under `LocalVolumes` no check is needed.

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

#### Scenario: No default class and no class named
- **GIVEN** a cluster with no default StorageClass
- **WHEN** the kit StorageClass is resolved with no class named
- **THEN** it fails naming `--kit-storage-class`
