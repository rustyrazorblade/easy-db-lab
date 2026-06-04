## ADDED Requirements

### Requirement: StarRocks kit installs the operator via Helm

The StarRocks kit SHALL install the StarRocks Kubernetes Operator using the `kube-starrocks` Helm chart from the `starrocks` repository (`https://starrocks.github.io/starrocks-kubernetes-operator`). The operator SHALL be installed into the `starrocks-operator` namespace.

#### Scenario: Operator installed on first install

- **WHEN** the user runs `easy-db-lab starrocks install`
- **THEN** the `starrocks` Helm repository is added/updated
- **AND** the `kube-starrocks` chart is installed as release `starrocks-operator` in namespace `starrocks-operator`

#### Scenario: Install is idempotent

- **WHEN** `easy-db-lab starrocks install` is run a second time
- **THEN** the command succeeds without error (helm upgrade --install semantics)

---

### Requirement: StarRocks start fails fast if no app nodes are provisioned

Before applying any Kubernetes resources, the `start` phase SHALL verify that at least one app node is available. If `APP_NODE_COUNT` is zero, the command SHALL exit immediately with a clear error message directing the user to re-provision with `--app-nodes`.

#### Scenario: No app nodes — start aborts before manifest apply

- **WHEN** `easy-db-lab starrocks start` is run on a cluster with zero app nodes
- **THEN** the command exits with a non-zero code before any K8s resources are created
- **AND** the error message instructs the user to re-run `easy-db-lab init` with `--app-nodes 1` or more

#### Scenario: App nodes present — start proceeds normally

- **WHEN** `easy-db-lab starrocks start` is run on a cluster with one or more app nodes
- **THEN** the start phase continues past the guard

---

### Requirement: StarRocks BE pods run on db nodes, FE pods run on app nodes

The `StarRocksCluster` CR SHALL configure FE pods with `nodeSelector: {type: app}` and BE pods with `nodeSelector: {type: db}`. Replica counts SHALL be set to `APP_NODE_COUNT` for FE and `DB_NODE_COUNT` for BE.

#### Scenario: FE pods scheduled on app nodes

- **WHEN** `easy-db-lab starrocks start` completes
- **THEN** all StarRocks FE pods are scheduled on nodes labeled `type=app`
- **AND** the FE replica count equals `APP_NODE_COUNT`

#### Scenario: BE pods scheduled on db nodes

- **WHEN** `easy-db-lab starrocks start` completes
- **THEN** all StarRocks BE pods are scheduled on nodes labeled `type=db`
- **AND** the BE replica count equals `DB_NODE_COUNT`

---

### Requirement: StarRocks exposes NodePort services for SQL and HTTP access

The `start` phase SHALL apply a NodePort service exposing:
- Port 9030 (MySQL protocol) for SQL client access
- Port 8030 (HTTP) for the StarRocks web UI and REST API

#### Scenario: MySQL NodePort accessible after start

- **WHEN** `easy-db-lab starrocks start` completes
- **THEN** a NodePort service is accessible on port 9030 on each cluster node
- **AND** a MySQL-compatible client can connect using `root` with no password

#### Scenario: HTTP NodePort accessible after start

- **WHEN** `easy-db-lab starrocks start` completes
- **THEN** a NodePort service is accessible on port 8030 on each cluster node

---

### Requirement: StarRocks supports the sql capability via MySQL JDBC

The StarRocks kit SHALL declare a `sql` capability using `com.mysql.cj.jdbc.Driver` and user `root`. The `mysql-connector-j` driver SHALL be on the classpath as a runtime dependency.

#### Scenario: SQL query executes against StarRocks

- **WHEN** the user runs `easy-db-lab starrocks sql "SELECT 1"`
- **THEN** the query is executed via JDBC against the StarRocks FE on port 9030
- **AND** the result is displayed in tabular format

#### Scenario: MySQL driver available at runtime

- **WHEN** the `sql` capability attempts to load `com.mysql.cj.jdbc.Driver`
- **THEN** the class is found on the classpath without error

---

### Requirement: StarRocks metrics are scraped from the FE Prometheus endpoint

The kit SHALL declare a `metrics` block of type `scrape` targeting the FE HTTP port (8030) at path `/metrics`. This registers StarRocks with the OTel dynamic scrape config.

#### Scenario: Metrics registered after start

- **WHEN** `easy-db-lab starrocks start` completes
- **THEN** a Prometheus scrape job for StarRocks is active on port 8030 at `/metrics`

---

### Requirement: StarRocks stop removes the cluster CR and NodePort services

The `stop` phase SHALL delete the `StarRocksCluster` CR and the NodePort services. It SHALL NOT uninstall the operator.

#### Scenario: Stop removes CR and services

- **WHEN** the user runs `easy-db-lab starrocks stop`
- **THEN** the `StarRocksCluster` CR is deleted
- **AND** the NodePort services for ports 9030 and 8030 are deleted
- **AND** the StarRocks Operator remains installed

---

### Requirement: StarRocks uninstall removes the operator and PVs

The `uninstall` phase SHALL delete PersistentVolumes created for BE storage and uninstall the `starrocks-operator` Helm release.

#### Scenario: Uninstall cleans up all resources

- **WHEN** the user runs `easy-db-lab starrocks uninstall`
- **THEN** the platform PVs for the starrocks kit are deleted
- **AND** the `starrocks-operator` Helm release is removed from namespace `starrocks-operator`
