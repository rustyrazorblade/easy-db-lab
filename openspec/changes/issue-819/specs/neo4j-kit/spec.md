## ADDED Requirements

### Requirement: Neo4j kit runs Neo4j Community on a db node
The system SHALL provide a `neo4j` kit that deploys Neo4j Community Edition as a StatefulSet with one replica, scheduled onto a db node, with its data on a volume provisioned by `platform-pvs`. `kit.yaml` SHALL declare `type: db`.

#### Scenario: Install succeeds
- **WHEN** the user runs `easy-db-lab kit install neo4j`
- **THEN** the install succeeds and the `neo4j` kit directory holds its `kit.yaml`

#### Scenario: Start yields a Ready pod on a db node
- **WHEN** the user runs `easy-db-lab neo4j start`
- **THEN** one Neo4j pod labelled `easydblab/kit=neo4j` is Ready on a db node AND its data directory is on a platform PV

### Requirement: Neo4j version is selectable within 5.x and 2025.x
The kit SHALL accept a `--version` argument. Supported versions SHALL be 5.x and 2025.x only. The container image SHALL be `neo4j:<version>-community`, and the default SHALL be pinned to the current Community release.

#### Scenario: Default version
- **WHEN** the kit is installed with no `--version`
- **THEN** the pod runs `neo4j:<pinned default>-community`

#### Scenario: Explicit supported version
- **WHEN** the kit is installed with `--version 5.26.0`
- **THEN** the pod runs `neo4j:5.26.0-community`

### Requirement: Neo4j runs without authentication
The kit SHALL set `NEO4J_AUTH=none`, so clients connect without credentials.

#### Scenario: Unauthenticated connection
- **WHEN** a client connects over Bolt with no credentials
- **THEN** the connection is accepted

### Requirement: Neo4j exposes Bolt and HTTP through NodePorts
The kit SHALL expose Bolt on NodePort 30687 and HTTP on NodePort 30474. `kit.yaml` SHALL declare both under `endpoints` with `node-type: db`: Bolt as type `native`, HTTP as type `http`. `start` and `kit info neo4j` SHALL report both endpoints. The Bolt advertised address SHALL be `<first db node private IP>:30687`, read by a shell step at start because no template variable carries a single db node IP.

#### Scenario: Start reports both endpoints
- **WHEN** `easy-db-lab neo4j start` completes
- **THEN** its output, and the output of `easy-db-lab kit info neo4j`, list `<db node private IP>:30687` (Bolt) and `http://<db node private IP>:30474` (HTTP)

#### Scenario: Cypher over the Bolt NodePort returns a result
- **WHEN** a client connects to `bolt://<db node private IP>:30687` and runs `RETURN 1`
- **THEN** the query returns `1`

#### Scenario: Advertised address is the node address
- **WHEN** a client connects with `neo4j://<db node private IP>:30687`, which fetches the server's routing table
- **THEN** the query succeeds AND the advertised Bolt address in the routing table is `<first db node private IP>:30687`, not a pod IP or `localhost`

### Requirement: Neo4j metrics are pushed by the OpenTelemetry Java agent
The Neo4j container SHALL load the OpenTelemetry Java agent baked into the base AMI, mounted read-only by `hostPath` from `/usr/local/otel` and injected through `NEO4J_server_jvm_additional`. The container SHALL set `OTEL_SERVICE_NAME=neo4j`, `HOST_IP` from `status.hostIP`, an OTLP endpoint of `http://$(HOST_IP):4318`, and a 5 s metric export interval. `kit.yaml` SHALL declare `metrics: [{type: java-agent, service-name: neo4j}]`. The kit SHALL ship a `metrics-catalog.json` exported from a live pod and a `METRICS.md`. JVM and HTTP metrics SHALL be present; database-level metrics SHALL be included only if Neo4j Community exposes them.

#### Scenario: Metrics arrive under job neo4j
- **WHEN** Neo4j is started and one export interval has passed
- **THEN** VictoriaMetrics holds JVM series (e.g. `jvm_memory_used_bytes`) with `job="neo4j"`

#### Scenario: Catalog reflects exported metrics
- **WHEN** `bin/export-workload-metrics neo4j` runs against a live cluster
- **THEN** `metrics-catalog.json` lists the `job="neo4j"` metric names it found

### Requirement: Neo4j has a Grafana dashboard with live data
The kit SHALL ship at least one dashboard installed into a `Neo4j` Grafana folder on `start`. Its panels SHALL be chosen after the exported metrics are reviewed with the owner on a live pod; JVM and HTTP panels are required, database-level panels only if Community exposes those metrics.

#### Scenario: Dashboard shows live data
- **WHEN** Neo4j is started and receiving queries
- **THEN** Grafana has a `Neo4j` folder whose dashboard panels show non-empty data

### Requirement: Neo4j kit refuses a conflicting install
`kit.yaml` SHALL set `collision-check: true`, so installing Neo4j into a cluster where it is already present fails with a clear `CollisionDetected` error before any manifest is applied.

#### Scenario: Second install fails clearly
- **WHEN** Neo4j is already installed and the user installs it again
- **THEN** the install fails with a `CollisionDetected` error naming the kit AND the running Neo4j objects are unchanged

### Requirement: Neo4j stop and uninstall clean up by label
`stop` SHALL delete the StatefulSet, Services, and pods labelled `easydblab/kit=neo4j`. `uninstall` SHALL additionally delete the kit's PVCs (the StatefulSet's `volumeClaimTemplates` metadata carries the kit label) and run `platform-pvs-delete`.

#### Scenario: Stop removes pod and NodePort services
- **WHEN** the user runs `easy-db-lab neo4j stop`
- **THEN** no StatefulSet, pod, or Service labelled `easydblab/kit=neo4j` remains

#### Scenario: Uninstall removes PVCs and PVs
- **WHEN** the user runs `easy-db-lab neo4j uninstall`
- **THEN** no PVC labelled `easydblab/kit=neo4j` remains AND the kit's platform PVs are deleted

### Requirement: Neo4j kit is listed and documented
`kit list` SHALL show `neo4j`. `docs/user-guide/neo4j.md`, linked from `docs/SUMMARY.md`, SHALL cover install, start, connecting over Bolt and HTTP, and stop.

#### Scenario: kit list shows neo4j
- **WHEN** the user runs `easy-db-lab kit list`
- **THEN** `neo4j` appears in the output
