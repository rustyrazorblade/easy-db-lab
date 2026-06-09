# TiDB

Manages TiDB deployment on K3s using the TiDB Operator, with component placement, MySQL-compatible SQL access, and Prometheus metrics integration.

## Requirements

### REQ-TD-001: TiDB Kit Deploys via TiDB Operator

The TiDB kit SHALL deploy a `TidbCluster` custom resource managed by the TiDB Operator Helm chart. The operator SHALL be installed in the `tidb-admin` namespace. The cluster SHALL include PD, TiDB, TiKV, and TiFlash components.

**Scenarios:**

- **WHEN** the user runs `easy-db-lab tidb install`, **THEN** the TiDB Operator Helm chart is installed in the `tidb-admin` namespace.
- **WHEN** the user runs `easy-db-lab tidb start`, **THEN** a `TidbCluster` CR is applied with PD, TiDB, TiKV, and TiFlash components, and the command waits for all component pods to reach `Ready` state before returning.

### REQ-TD-002: TiDB Kit Requires a Mixed Cluster

The TiDB kit SHALL require at least one db node and at least one app node. If either count is zero, `start` SHALL fail immediately with a descriptive error message before applying any Kubernetes resources.

**Scenarios:**

- **WHEN** `DB_NODE_COUNT >= 1` and `APP_NODE_COUNT >= 1`, **THEN** the start sequence proceeds normally.
- **WHEN** `DB_NODE_COUNT` is `0`, **THEN** `start` exits with a non-zero status and prints an error indicating db nodes are required for TiKV and TiFlash.
- **WHEN** `APP_NODE_COUNT` is `0`, **THEN** `start` exits with a non-zero status and prints an error indicating app nodes are required for TiDB and PD.

### REQ-TD-003: TiDB Kit Component Placement

TiKV and TiFlash SHALL be scheduled on db nodes. TiDB SQL layer and PD SHALL be scheduled on app nodes. Scheduling SHALL be enforced via Kubernetes node selectors or affinity rules in the `TidbCluster` manifest.

**Scenarios:**

- **WHEN** the TidbCluster is applied, **THEN** TiKV pods are scheduled exclusively on nodes labelled as db nodes.
- **WHEN** the TidbCluster is applied, **THEN** TiFlash pods are scheduled exclusively on nodes labelled as db nodes.
- **WHEN** the TidbCluster is applied, **THEN** TiDB SQL layer pods and PD pods are scheduled exclusively on nodes labelled as app nodes.

### REQ-TD-004: TiDB Kit Args

The TiDB kit SHALL expose the following args:

- `--version` (string, default `v8.5.2`): TiDB image version
- `--replicas` (int, default `${DB_NODE_COUNT}`): number of TiKV and TiFlash replicas

PD replicas SHALL be hardcoded to `1`. TiDB SQL replicas SHALL be derived from `${APP_NODE_COUNT}`.

**Scenarios:**

- **WHEN** the user runs `easy-db-lab tidb start` on a 3-db-node cluster without specifying `--replicas`, **THEN** TiKV is deployed with 3 replicas and TiFlash is deployed with 3 replicas.
- **WHEN** the user runs `easy-db-lab tidb start --version v7.5.0`, **THEN** all TiDB components use image tag `v7.5.0`.

### REQ-TD-005: TiDB Kit Exposes MySQL Endpoint on Port 4000

The TiDB kit SHALL expose a NodePort service for the MySQL-compatible protocol on port 4000.

**Scenarios:**

- **WHEN** the TiDB cluster is running, **THEN** a MySQL-compatible client can connect to any db node on NodePort 4000.

### REQ-TD-006: TiDB Kit Exposes Prometheus Metrics

The TiDB kit SHALL configure metrics scraping from the TiDB SQL layer Prometheus endpoint via NodePort `31080` (container port `10080`), with path `/metrics`.

**Scenarios:**

- **WHEN** the TiDB kit is started, **THEN** a metrics scrape job is registered targeting NodePort `31080` (TiDB SQL layer) with path `/metrics`.

### REQ-TD-007: TiDB Kit SQL Capability

The TiDB kit SHALL declare a `sql` capability using `com.mysql.cj.jdbc.Driver` and user `root`, enabling the `easy-db-lab tidb sql` command.

**Scenarios:**

- **WHEN** the user runs `easy-db-lab tidb sql "SELECT tidb_version()"`, **THEN** the query executes against the TiDB MySQL endpoint and results are displayed in tabular format.
- **WHEN** the user runs `easy-db-lab tidb sql "SELECT /*+ read_from_storage(tiflash[t]) */ count(*) FROM t"`, **THEN** the query is routed to TiFlash and results are returned.
