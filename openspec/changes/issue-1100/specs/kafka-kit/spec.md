## MODIFIED Requirements

### Requirement: Kafka kit supports configurable args
The kafka kit SHALL accept the following install-time arguments:

- `--version` (string, default: `latest`) — Kafka image version
- `--brokers` (int, default: `1`) — number of broker/controller nodes
- `--storage-size` (string, default: `${KIT_STORAGE_SIZE}`) — persistent volume size per broker: `10Ti` on local volumes, the provisioned-class default otherwise, or `init --kit-storage-size`

The node pool's storage SHALL name the class `__KIT_STORAGE_CLASS__` with no selector.

#### Scenario: Default single broker
- **WHEN** the user runs `easy-db-lab kafka start` with no flags on a cluster with no recorded kit StorageClass
- **THEN** one Kafka broker is started with 10Ti persistent storage on the class `edl-local-kafka`

#### Scenario: Custom broker count
- **WHEN** the user runs `easy-db-lab kafka start --brokers 3`
- **THEN** three Kafka broker pods are started

#### Scenario: Custom version
- **WHEN** the user runs `easy-db-lab kafka start --version 3.8`
- **THEN** the Kafka image with tag `3.8` is used

### Requirement: Kafka kit supports start, stop, and uninstall phases
The kafka kit SHALL implement `start`, `stop`, and `uninstall` lifecycle phases.

- `start`: applies the Kafka and KafkaNodePool resources; waits for broker pods to be Ready
- `stop`: deletes the Kafka and KafkaNodePool resources, keeping the claims and the PVs (`deleteClaim: false`)
- `uninstall`: deletes the claims explicitly, then the kit's storage (`kit-storage-delete`)

#### Scenario: Start waits for broker readiness
- **WHEN** `easy-db-lab kafka start` is run
- **THEN** the command does not return until all broker pods report Ready

#### Scenario: Stop keeps the claims
- **WHEN** `easy-db-lab kafka stop` is run
- **THEN** no broker pods remain running
- **AND** the brokers' PersistentVolumeClaims still exist

#### Scenario: Start after stop rebinds the same volumes
- **WHEN** `easy-db-lab kafka stop` and then `easy-db-lab kafka start` are run
- **THEN** each broker mounts the claim, and so the volume, it had before the stop

#### Scenario: Uninstall removes claims and PVs
- **WHEN** `easy-db-lab kafka uninstall` is run
- **THEN** the brokers' PersistentVolumeClaims are removed
- **AND** on local volumes the kit's PVs, its class and its node directories are removed
