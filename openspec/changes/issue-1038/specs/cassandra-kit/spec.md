## ADDED Requirements

### Requirement: Built-in cassandra-pods kit
easy-db-lab SHALL ship a built-in `db` kit named `cassandra-pods` that runs a Cassandra cluster as pods. The kit SHALL be packaged as classpath resources, so it installs and starts from a Homebrew install with no source checkout. Every install arg SHALL be a named option: `--version` (default `5.0`), `--image` (default empty), `--nodes` (default the cluster's db node count), `--memory` (default `4Gi`), `--heap` (default half of `--memory`), `--dc` (default `dc1`) and `--storage-size` (default `10Ti`).

#### Scenario: Install from the classpath
- **WHEN** `kit install cassandra-pods` runs from an install with no source checkout
- **THEN** the kit directory `cassandra-pods/` is written in the workspace from packaged resources
- **AND** `cassandra-pods start` and `cassandra-pods stop` are available as commands

#### Scenario: kit.yaml parsed
- **WHEN** the packaged `cassandra-pods/kit.yaml` is parsed
- **THEN** it declares type `db`, the seven named args with their defaults, a `CQL` endpoint of type `native` on port 30942, and Java-agent metrics with service name `cassandra`

### Requirement: Image selection and check before any resource
The kit SHALL run the image `cassandra:<--version>`, or the `--image` reference when one is given. Before any Kubernetes resource is created, `start` SHALL check through the workspace `curl` wrapper that the image's manifest exists in its registry. It SHALL exit non-zero, with a message naming the image, when the manifest does not exist or cannot be resolved.

#### Scenario: Default version
- **WHEN** the kit is started with no `--version` and no `--image`
- **THEN** the pods run `cassandra:5.0`

#### Scenario: Explicit version
- **WHEN** the kit is installed with `--version 4.1` and started
- **THEN** the pods run `cassandra:4.1`

#### Scenario: Image override
- **WHEN** the kit is installed with `--image ghcr.io/example/cassandra:sha-abc` and started
- **THEN** the pods run that image, whatever `--version` is

#### Scenario: Unknown version fails before any pod
- **WHEN** `start` runs with an image whose manifest the registry does not have
- **THEN** `start` exits non-zero with a message naming the image
- **AND** no StatefulSet, Service, ConfigMap or pod is created

### Requirement: StatefulSet topology
The kit SHALL run Cassandra as a StatefulSet with `--nodes` replicas, at most one pod per `type=db` node, and pods started one at a time. Pod 0 SHALL be the seed. The snitch SHALL be GossipingPropertyFileSnitch with DC `--dc` and the single rack `rack1`. `start` SHALL succeed only when `nodetool status` reports `--nodes` nodes as UN within a bounded timeout. On timeout it SHALL exit non-zero and print the pods' recent events.

#### Scenario: Cluster reaches UN
- **WHEN** the kit is started on a cluster with three db nodes and `--nodes 3`
- **THEN** three pods run, one on each db node
- **AND** `nodetool status` lists three nodes as UN in DC `dc1`, rack `rack1`
- **AND** `start` exits 0

#### Scenario: More nodes than db nodes
- **WHEN** `--nodes` is greater than the number of db nodes
- **THEN** `start` exits non-zero before any resource is created, naming both numbers

#### Scenario: Start times out
- **WHEN** the requested nodes do not all reach UN before the timeout
- **THEN** `start` exits non-zero and prints the pods' recent events

#### Scenario: AMI Cassandra already running
- **WHEN** `start` runs on a cluster where the AMI-based Cassandra service is running
- **THEN** `start` exits non-zero before any resource is created, with a message saying the two would report under the same metrics job

### Requirement: Memory request, limit and heap
The cassandra container SHALL request and limit the same memory, taken from `--memory` (default `4Gi`), so each pod is Guaranteed for memory and is scheduled only on a node where it fits. The pod SHALL set the JVM heap itself, because the image's `cassandra-env.sh` sizes it from the host's memory rather than the container limit: `--heap` when given, otherwise half of the memory limit. `start` SHALL exit non-zero, naming both values, when `--memory` is not a whole number of `Gi` or `Mi`, or when `--heap` is not smaller than `--memory`, before any resource is created. A pod SHALL become Ready only while the native transport is running.

#### Scenario: Default memory fits
- **WHEN** the kit is started with no `--memory` and no `--heap`
- **THEN** the cassandra container requests and limits `4Gi`, the JVM heap is 2048M, and the pod reaches UN without being OOM-killed on a single K3s node of a 7 GB host

#### Scenario: Heap larger than memory
- **WHEN** the kit is started with `--heap 3G --memory 2Gi`
- **THEN** `start` exits non-zero with a message naming `--heap 3G` and `--memory 2Gi`, and creates no resource

#### Scenario: Not Ready while CQL is down
- **WHEN** `nodetool statusbinary` prints `not running`
- **THEN** the readiness probe fails

### Requirement: CQL from the operator's machine
Each pod SHALL advertise its node's IP as `broadcast_rpc_address` and listen for CQL on port 30942. A NodePort Service SHALL map 30942 to 30942 with `externalTrafficPolicy: Local`. The kit SHALL NOT use `hostNetwork` or `hostPort`.

#### Scenario: CQL answers on the NodePort
- **WHEN** the kit is running
- **THEN** a CQL client on the operator's machine connected to any db node IP on port 30942 gets an answer to `SELECT release_version FROM system.local`

#### Scenario: Peer addresses are dialable
- **WHEN** a driver connected through one node reads `system.peers_v2`
- **THEN** every peer's native address is that peer's node IP with port 30942

### Requirement: Data survives stop and start
`stop` SHALL delete the kit's StatefulSet, Services and ConfigMap, and SHALL NOT delete its PersistentVolumeClaims or volumes. `uninstall` SHALL delete the kit's volumes.

#### Scenario: Stop then start keeps data
- **WHEN** a keyspace and a row are written, the kit is stopped, and the kit is started again
- **THEN** the row can be read back

#### Scenario: Stop keeps claims
- **WHEN** `stop` completes
- **THEN** no kit pod, StatefulSet, Service or ConfigMap remains
- **AND** every PersistentVolumeClaim of the kit remains

### Requirement: Telemetry without the AMI
Each pod SHALL load the OpenTelemetry Java agent, copied by an init container from the pinned `autoinstrumentation-java` image whose tag equals the build's OpenTelemetry agent version. It SHALL NOT read any host path the AMI provides. The agent SHALL send OTLP to the collector on the pod's node, with service name `cassandra`, `host.name` set to the node name, `node_role=db`, `cassandra_build` set to the image tag (`sha256-` plus the first 12 hex chars of the digest for a digest-only reference), and the packaged Cassandra JMX rules.

#### Scenario: Metrics and logs reach the stack
- **WHEN** the kit has been running for two minutes
- **THEN** Mimir returns Cassandra series with `job="cassandra"`, this cluster's `cluster` label, and a `host_name` for each db node running a pod
- **AND** Loki returns Cassandra log lines for this cluster

#### Scenario: Agent version pinned to the build
- **WHEN** the kit's StatefulSet template is rendered
- **THEN** the init container's image tag equals the build's OpenTelemetry agent version

#### Scenario: No AMI host path
- **WHEN** the kit's StatefulSet template is rendered
- **THEN** no volume is a hostPath under `/usr/local/otel`

### Requirement: Existing Cassandra dashboards show kit pods
The kit's series SHALL carry the labels the `dashboards/cassandra/` dashboards filter on, so those dashboards show kit pods with no dashboard change.

#### Scenario: Overview dashboard shows the kit
- **WHEN** the kit is running and the Cassandra overview dashboard is opened for this cluster
- **THEN** its panels show data for each kit pod's node

### Requirement: Kit documentation
The kit SHALL have a user-guide page listed in `docs/SUMMARY.md` and in the kit list. The page SHALL cover the args, `--image` with branch builds, CQL access, and persistence. `docs/development/kits.md` SHALL describe the init-container agent pattern, and the root `CLAUDE.md` SHALL list NodePort 30942.

#### Scenario: Docs list the kit
- **WHEN** the docs are built
- **THEN** the kit's page is in the table of contents, and the kit list names `cassandra-pods`
