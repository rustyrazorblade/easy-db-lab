# Ignite 3

Manages Apache Ignite 3 deployment on K3s with configurable storage profiles and OTLP metrics integration.

## ADDED Requirements

### Requirement: Kit Lifecycle

The system MUST support installing, starting, stopping, and uninstalling Ignite 3 via the kit mechanism.

#### Scenario: Start deploys Ignite 3 cluster

- **WHEN** the user runs `ignite3 start`
- **THEN** a ConfigMap, headless Service, and StatefulSet are applied to the cluster, pods reach Ready state, a cluster-init Job runs, OTLP metrics are configured, and a NodePort Service is exposed

#### Scenario: Stop removes runtime resources but preserves data

- **WHEN** the user runs `ignite3 stop`
- **THEN** the StatefulSet, NodePort Service, and cluster-init Job are deleted, but PersistentVolumeClaims are retained

#### Scenario: Start after stop resumes data

- **WHEN** data is written to Ignite 3, `ignite3 stop` is run, and `ignite3 start` is run again
- **THEN** the previously written data is accessible without any restore step (for `aipersist` and `rocksdb` profiles)

#### Scenario: Uninstall removes all resources including data

- **WHEN** the user runs `kit uninstall ignite3`
- **THEN** the StatefulSet, Services, ConfigMap, PersistentVolumeClaims, and headless Service are all deleted

### Requirement: Storage Profile Selection

The kit SHALL support three storage profiles selectable at start time via `--storage`.

#### Scenario: Default storage is aipersist

- **WHEN** the user runs `ignite3 start` without a `--storage` flag
- **THEN** the cluster starts with the `aipersist` profile (in-memory data, disk-persisted on checkpoint)

#### Scenario: aimem profile runs with volatile in-memory storage

- **WHEN** the user runs `ignite3 start --storage aimem`
- **THEN** the cluster uses pure in-memory storage; data does not survive pod restarts

#### Scenario: rocksdb profile runs with disk-based LSM storage

- **WHEN** the user runs `ignite3 start --storage rocksdb`
- **THEN** the cluster uses RocksDB-backed disk storage suitable for datasets larger than available RAM

#### Scenario: Invalid storage value is rejected

- **WHEN** the user supplies an unrecognized value for `--storage`
- **THEN** an error is emitted before any resources are applied

### Requirement: Cluster Initialization

The kit MUST run a one-time cluster initialization step after the StatefulSet pods are Ready.

#### Scenario: Init Job bootstraps the cluster

- **WHEN** Ignite 3 pods reach Ready state during `ignite3 start`
- **THEN** a K8s Job runs `ignite3 cluster init --name=ignite --url=http://ignite-svc-headless:10300` and completes successfully before the NodePort Service is applied

#### Scenario: Start fails if init Job fails

- **WHEN** the cluster-init Job exits with a non-zero status
- **THEN** `ignite3 start` fails with an error before proceeding to NodePort Service creation

### Requirement: OTLP Metrics

The kit SHALL configure Ignite 3 to push metrics to the cluster's OTel Collector via OTLP after cluster initialization.

#### Scenario: Metrics exporter is configured at start

- **WHEN** `ignite3 start` completes cluster initialization
- **THEN** the system configures Ignite's OTLP exporter pointing to `http://${CONTROL_HOST_PRIVATE}:4318/v1/metrics` using the `http/protobuf` protocol

#### Scenario: Metrics flow into VictoriaMetrics

- **WHEN** the OTLP exporter is configured and Ignite is running
- **THEN** Ignite metrics appear in VictoriaMetrics and are visible in Grafana

### Requirement: SQL Execution

The `ignite3 sql` command SHALL execute SQL statements against a running Ignite 3 cluster via the thin client JDBC driver.
SQL execution is provided via the `sql` capability declared in `ignite3/kit.yaml` — see REQ-KCAP-002.

#### Scenario: SQL query executes successfully

- **WHEN** the user runs `ignite3 sql "SELECT * FROM my_table"`
- **THEN** the query is submitted via JDBC on port 10800 and results are displayed in tabular format

#### Scenario: No db nodes causes early error

- **WHEN** no db nodes exist in cluster state
- **THEN** an error is emitted before any JDBC connection is attempted

### Requirement: Replica Count

The kit SHALL support configuring the number of Ignite server nodes via `--replicas`.

#### Scenario: Default replica count matches db node count

- **WHEN** the user runs `ignite3 start` without `--replicas`
- **THEN** the StatefulSet replica count equals `DB_NODE_COUNT`

#### Scenario: Custom replica count is applied

- **WHEN** the user runs `ignite3 start --replicas 3`
- **THEN** the StatefulSet is created with 3 replicas
