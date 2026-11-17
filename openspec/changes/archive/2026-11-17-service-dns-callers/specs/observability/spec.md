## MODIFIED Requirements

### Requirement: Observability backends and Grafana run on the pod network behind ClusterIP Services

Mimir, Loki, Tempo, Pyroscope and Grafana SHALL run as ordinary pod-network workloads behind Services, with no `hostNetwork`. Their placement SHALL come from a resolved `Placement` value — a node selector and tolerations — derived once from the recorded environment, never from testing an environment name. On AWS the resolved placement SHALL keep today's control-node selector and its matching toleration; where the environment declares no such node the selector and tolerations SHALL be empty. An `init` user node label SHALL merge into the selector. The same container specs SHALL be used on every environment.

No backend or Grafana container SHALL declare a `hostPort` in any environment. Callers outside the pod network SHALL reach them through the published NodePorts defined in the networking specification, never through a container host port.

Every in-cluster caller of these backends SHALL address them by Service DNS (`<service>.<namespace>.svc`), not `localhost` or a node IP, using one shared source of backend addresses. This SHALL include the Grafana datasources' backend base URLs and the OTel collector's backend exporter endpoints.

The `cluster-config` ConfigMap SHALL NOT carry a `control_node_ip` key, and the observability deploy and the collector sync SHALL NOT require a control node in order to publish one.

Each backend's local state SHALL be stored on a `hostPath` where the environment provides `HOST_PATH` (keeping today's `/mnt/db1/<backend>` on AWS), and otherwise on a per-backend claim whose StorageClass reclaims with `Retain`; `emptyDir` SHALL NOT be used for backend state. Each backend workload SHALL expose a `serviceAccountName` and an optional store-credentials Secret mount as plug points for pod credentials; on AWS neither alters today's instance-role behaviour.

#### Scenario: Backends reach Ready without a control-plane node
- **GIVEN** a cluster where no node carries `node-role.kubernetes.io/control-plane=true`
- **WHEN** the observability stack deploys with an empty placement
- **THEN** Mimir, Loki, Tempo, Pyroscope and Grafana each reach Ready
- **AND** none of them runs with `hostNetwork`

#### Scenario: AWS placement and storage are unchanged
- **GIVEN** an AWS environment
- **WHEN** the five workloads are rendered
- **THEN** each carries the `node-role.kubernetes.io/control-plane=true` selector and its matching toleration
- **AND** each mounts its state from the `/mnt/db1/<backend>` host path, exactly as today

#### Scenario: A node label no node carries is refused
- **GIVEN** an `init --node-label` that no node carries together with the environment's own selector
- **WHEN** the observability stack deploys
- **THEN** it fails before applying anything, naming the merged selector and that the label must already be on a node

#### Scenario: On AWS no backend or Grafana container declares a host port
- **GIVEN** an AWS environment
- **WHEN** the observability stack is rendered and deployed
- **THEN** no container of Mimir, Loki, Tempo, Pyroscope or Grafana (including the nginx front) declares a `hostPort`
- **AND** none runs with `hostNetwork`

#### Scenario: Off owned nodes no workload declares a host port
- **GIVEN** an environment that does not own its nodes
- **WHEN** the five workloads are rendered
- **THEN** none declares a host port

#### Scenario: Grafana datasources use Service DNS
- **WHEN** Grafana's datasources are built
- **THEN** each backend datasource URL is that backend's Service DNS name, not `localhost` or a node IP

#### Scenario: The collector reaches the backends by Service DNS
- **GIVEN** the backends run behind Services with no node-IP listener
- **WHEN** the OTel collector exports metrics, logs and traces
- **THEN** it addresses Mimir, Loki and Tempo by Service DNS
- **AND** the dashboards show data

#### Scenario: A replaced backend on a retained claim stays reachable
- **GIVEN** an environment without `HOST_PATH`, so the backend's state is on its retained claim
- **WHEN** the backend's pod is deleted and its replacement reattaches the same claim (on another node only within the volume's zone, since the claim is zonal)
- **THEN** Grafana reaches the replacement through the backend's Service, at the same URL its datasource uses

#### Scenario: The stack and the collector sync need no control node IP
- **GIVEN** any environment
- **WHEN** the observability stack deploys, or a kit start or stop resyncs the collector
- **THEN** the `cluster-config` ConfigMap carries no `control_node_ip` key
- **AND** the collector configuration has no endpoint at a node IP

#### Scenario: Off owned nodes, backend state is on a retained claim
- **GIVEN** an environment without `HOST_PATH`
- **WHEN** a backend is rendered
- **THEN** its state is on a per-backend claim whose StorageClass reclaims with `Retain`

## ADDED Requirements

### Requirement: Every in-cluster profile producer reaches Pyroscope by Service DNS

Every pod that sends profiles — the Grafana Alloy eBPF agent, the Cassandra sidecar Pyroscope agent, the stress jobs' Pyroscope agent and every kit Pyroscope agent (`__PYROSCOPE_URL__` / `PYROSCOPE_URL`) — SHALL send to the Pyroscope Service DNS address from the shared source of backend addresses, or to the telemetry redirect's profiles endpoint when the cluster redirects. No function that builds a Pyroscope or other backend URL for an in-cluster producer SHALL take a node IP. Profiles SHALL land in Pyroscope under the cluster's tenant as they do today.

#### Scenario: Pod producers render the Pyroscope Service address
- **GIVEN** a cluster with no telemetry redirect, in any environment
- **WHEN** the Alloy DaemonSet, the sidecar DaemonSet, a stress Job and the kit template variables are rendered
- **THEN** each Pyroscope address is `http://pyroscope.<namespace>.svc:4040`
- **AND** none contains a node IP or the control node's address

#### Scenario: Pod producers render the redirect endpoint under redirect
- **GIVEN** a cluster initialized with `--redirect-telemetry <host>`
- **WHEN** the same producers are rendered
- **THEN** each Pyroscope address is the redirect's profiles endpoint

#### Scenario: A host-network producer's profile lands in Pyroscope
- **GIVEN** the AWS-hosted observability stack running on K3s with no container host ports
- **WHEN** a pod with `hostNetwork` and `ClusterFirstWithHostNet` posts a JFR profile to the Pyroscope Service DNS address with the cluster tenant
- **THEN** Pyroscope returns that profile when queried with the same tenant

#### Scenario: Stress jobs need no control node to address Pyroscope
- **GIVEN** a cluster state with no control node
- **WHEN** a stress Job is built
- **THEN** it builds successfully and its Pyroscope address is the Service DNS address

#### Scenario: No source builds a backend URL from a node IP
- **WHEN** the packaged kit resources are scanned
- **THEN** no file pairs `CONTROL_HOST_PRIVATE` or `HOST_IP` with a backend port (9009, 3100, 3200, 4320, 4040)

### Requirement: Node host processes send profiles through a node-local relay

The Grafana Alloy DaemonSet SHALL listen for Pyroscope pushes on `127.0.0.1:<ALLOY_RELAY_PORT>` (a constant) on every node it runs on and forward them, with the cluster tenant, to the same destination as its own eBPF profiles: the Pyroscope Service, or the redirect's profiles endpoint. Profile senders that run as host processes on a cluster node (the db-node profiling reconciler) SHALL send to that loopback relay, and SHALL NOT be given a node IP or a cluster DNS name.

#### Scenario: The reconciler is configured with the relay
- **WHEN** profiling is configured by `setup-instance`, `profiling start` or `profiling stop`, with or without telemetry redirect
- **THEN** the node's profiling configuration names `http://127.0.0.1:<ALLOY_RELAY_PORT>` as its Pyroscope URL

#### Scenario: A JFR pushed to the relay lands in Pyroscope
- **GIVEN** a running Alloy with the relay configured and a running Pyroscope
- **WHEN** a JFR chunk is posted to the relay's `/ingest` with `format=jfr`, a series name and labels, and the tenant header
- **THEN** Pyroscope returns the profile under that tenant with the same series name and labels
