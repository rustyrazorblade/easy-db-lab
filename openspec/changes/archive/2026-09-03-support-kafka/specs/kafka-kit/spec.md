## ADDED Requirements

### Requirement: Kafka kit deploys via Bitnami Helm in KRaft mode
The system SHALL deploy Apache Kafka using the `bitnami/kafka` Helm chart with KRaft mode enabled (`kraft.enabled: true`) and ZooKeeper disabled (`zookeeper.enabled: false`). ZooKeeper SHALL NOT be installed as part of this kit.

#### Scenario: Install adds Bitnami repo and installs chart
- **WHEN** the user runs `easy-db-lab kafka install`
- **THEN** the Bitnami Helm repo is added and the `bitnami/kafka` chart is installed in KRaft mode with no ZooKeeper dependency

#### Scenario: ZooKeeper is absent after install
- **WHEN** the kafka kit is installed
- **THEN** no ZooKeeper pods or services exist in the cluster

### Requirement: Kafka kit targets db nodes
The kafka kit SHALL declare `type: db` in `kit.yaml`, causing it to target the db node pool.

#### Scenario: Kit targets db node pool
- **WHEN** `kit.yaml` contains `type: db`
- **THEN** the kit is validated against db node availability before install

### Requirement: Kafka kit exposes dual bootstrap endpoints
The kafka kit SHALL expose two endpoints:
- `Kafka Internal` — type `kafka`, port `9092`, `node-type: db` — advertised as `kafka.default.svc.cluster.local:9092` for in-cluster consumers
- `Kafka External` — type `kafka`, port `30092`, `node-type: db` — NodePort advertised as the db node's private IP for external consumers

#### Scenario: In-cluster consumer uses internal endpoint
- **WHEN** a pod in the cluster uses `kafka.default.svc.cluster.local:9092` as the bootstrap server
- **THEN** the connection succeeds and Kafka metadata is returned

#### Scenario: External consumer uses NodePort endpoint
- **WHEN** an external client connects to `<db-node-private-ip>:30092`
- **THEN** the connection succeeds and Kafka metadata is returned

### Requirement: Kafka kit supports configurable args
The kafka kit SHALL accept the following install-time arguments:

- `--version` (string, default: `latest`) — Kafka image version
- `--brokers` (int, default: `1`) — number of broker/controller nodes
- `--storage-size` (string, default: `10Ti`) — persistent volume size per broker

#### Scenario: Default single broker
- **WHEN** the user runs `easy-db-lab kafka start` with no flags
- **THEN** one Kafka broker is started with 10Ti persistent storage

#### Scenario: Custom broker count
- **WHEN** the user runs `easy-db-lab kafka start --brokers 3`
- **THEN** three Kafka broker pods are started

#### Scenario: Custom version
- **WHEN** the user runs `easy-db-lab kafka start --version 3.8`
- **THEN** the Kafka image with tag `3.8` is used

### Requirement: Kafka kit exposes Prometheus metrics
The kafka kit SHALL expose Prometheus metrics via the JMX exporter on NodePort `30093`, declared as `type: scrape` in `kit.yaml`.

#### Scenario: Metrics endpoint declared
- **WHEN** the kafka kit is running
- **THEN** the OTel collector can scrape metrics at `<db-node-ip>:30093/metrics`

### Requirement: Kafka kit supports start, stop, and uninstall phases
The kafka kit SHALL implement `start`, `stop`, and `uninstall` lifecycle phases.

- `start`: deploys the Bitnami chart with rendered values; waits for broker pods to be Ready
- `stop`: uninstalls the Helm release, preserving the PVs
- `uninstall`: uninstalls the Helm release and removes PVs

#### Scenario: Start waits for broker readiness
- **WHEN** `easy-db-lab kafka start` is run
- **THEN** the command does not return until all broker pods report Ready

#### Scenario: Stop removes broker pods
- **WHEN** `easy-db-lab kafka stop` is run
- **THEN** the Kafka Helm release is uninstalled and no broker pods remain running

#### Scenario: Uninstall removes release and PVs
- **WHEN** `easy-db-lab kafka uninstall` is run
- **THEN** the Helm release is deleted and associated PersistentVolumeClaims are removed
