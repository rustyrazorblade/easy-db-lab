## MODIFIED Requirements

### Requirement: TiDB Kit Args

The TiDB kit SHALL expose the following args:

- `--version` (string, default `v8.5.2`): TiDB image version
- `--replicas` (int, default `${DB_NODE_COUNT}`): number of TiKV and TiFlash replicas
- `--storage-size` (string, default `${KIT_STORAGE_SIZE}`): the claim size of each PD, TiKV and TiFlash node

PD replicas SHALL be hardcoded to `1`. TiDB SQL replicas SHALL be derived from `${APP_NODE_COUNT}`.

#### Scenario: Replicas default to db node count

- **WHEN** the user runs `easy-db-lab tidb start` on a 3-db-node cluster without specifying `--replicas`
- **THEN** TiKV is deployed with 3 replicas and TiFlash is deployed with 3 replicas.

#### Scenario: Version flag applied to all components

- **WHEN** the user runs `easy-db-lab tidb start --version v7.5.0`
- **THEN** all TiDB components use image tag `v7.5.0`.

## ADDED Requirements

### Requirement: TiDB Kit Storage

PD, TiKV and TiFlash SHALL claim from `__KIT_STORAGE_CLASS__` at `__STORAGE_SIZE__`, and the `TidbCluster` SHALL set `pvReclaimPolicy: Retain`. `install` SHALL declare `kit-storage` steps: one PV on one app node for PD, and one PV per db node for each of TiKV and TiFlash. `uninstall` SHALL delete the claims the operator keeps, then run `kit-storage-delete` for the app and db pools. On AWS this moves TiDB's data from `local-path` on the root EBS volume to local PVs on the nodes' `/mnt/db1` NVMe.

#### Scenario: TiDB on local volumes

- **WHEN** the TiDB kit is installed and started on a cluster with no recorded kit StorageClass
- **THEN** PD, TiKV and TiFlash claims bind to PVs of the class `edl-local-tidb` under `/mnt/db1/tidb/`

#### Scenario: TiDB keeps its volumes

- **WHEN** a PD, TiKV or TiFlash claim is deleted
- **THEN** its volume is retained, not deleted
