## ADDED Requirements

### Requirement: OTel Collector is split into an unprivileged core and a privileged host add-on

The system SHALL run the OTel collector as two DaemonSets. The **core** collector `otel-collector` SHALL run on the pod network with no `hostNetwork`, no `hostPort` and no privilege, and SHALL be the only collector that exports to the backends. The **host add-on** `otel-collector-host` SHALL run with `hostNetwork` and privileged, SHALL be deployed only where the environment has the `PRIVILEGED` capability, and SHALL forward everything it collects to the core over OTLP through the `otel-collector` Service. The core SHALL own the OTLP receivers, every pod-discovery scrape, the `kubeletstats` receiver, `k8s_attributes`, the spanmetrics and servicegraph connectors and every backend exporter. The host add-on SHALL own host metrics, host file logs, the localhost scrapes of Beyla, ebpf_exporter and Cilium, and the node's OTLP listener on ports 4317 and 4318. Each collector SHALL take `host.name` from its node name (downward API `spec.nodeName`), not from the OS hostname. No collector SHALL carry a `memory_limiter` processor.

#### Scenario: Core runs unprivileged on the pod network

- **WHEN** the observability stack deploys
- **THEN** the `otel-collector` DaemonSet's pods use neither `hostNetwork` nor any `hostPort`, and are not privileged
- **AND** their root filesystem is read-only, whether or not they read pod logs from the node

#### Scenario: Core verifies the kubelet it reads stats from

- **WHEN** the core's `kubeletstats` receiver connects to its node's kubelet
- **THEN** it verifies the kubelet's serving certificate against the service-account CA and does not skip verification

#### Scenario: Core reaches Ready without PRIVILEGED

- **GIVEN** an environment that lacks `PRIVILEGED`
- **WHEN** the observability stack deploys
- **THEN** the `otel-collector` core DaemonSet reaches Ready
- **AND** pod and application telemetry reaches the backends

#### Scenario: Host add-on forwards to the core

- **GIVEN** an environment that has `PRIVILEGED`
- **WHEN** the host add-on collects a host metric or a host log line
- **THEN** it exports that data over OTLP to the `otel-collector` Service
- **AND** it exports nothing directly to a backend

#### Scenario: A core restart does not drop the node's host data

- **GIVEN** the host add-on on a node whose core pod is restarting or crash-looping
- **WHEN** the add-on's export to the core fails
- **THEN** it keeps the batch in its sending queue and retries with no time limit until the core accepts it

#### Scenario: Host processes keep their node endpoint

- **GIVEN** an environment that has `PRIVILEGED`
- **WHEN** a host process that is not a pod (the Cassandra Java agent on an AMI node, the profiling reconcile script, Fluent Bit, or an EMR node) sends OTLP to the node or control node on port 4317 or 4318
- **THEN** the host add-on receives it and it reaches the backends through the core

#### Scenario: Node identity comes from the node name

- **WHEN** the core collector, on the pod network, stamps `host.name` on a resource
- **THEN** `host.name` is the Kubernetes node name, not the collector pod's name

#### Scenario: No collector has a memory limiter

- **WHEN** the core and host add-on configs are rendered
- **THEN** neither contains a `memory_limiter` processor

### Requirement: Collector stages report each missing capability

The system SHALL let an observability stage require a set of node capabilities. A stage whose required set is not satisfied SHALL NOT be built or awaited, and the deploy SHALL emit one `StageSkipped` event per missing capability, naming the stage and the capability. The host add-on, Beyla, ebpf_exporter and the Pyroscope eBPF agent SHALL each require `PRIVILEGED`. The journald collector SHALL require both `SYSTEMD_JOURNAL` and `PRIVILEGED`. The core's pod-log collection SHALL require `HOST_PATH`. The Pyroscope eBPF agent SHALL be its own stage, separate from the Pyroscope server, so that its absence does not fail the rollout wait.

#### Scenario: Privileged components skipped without PRIVILEGED

- **GIVEN** an environment that lacks `PRIVILEGED`
- **WHEN** the observability stack deploys
- **THEN** the host add-on, Beyla, ebpf_exporter, the journald collector and the Pyroscope eBPF agent are not applied or awaited
- **AND** a `StageSkipped` event is emitted for each, naming `PRIVILEGED`

#### Scenario: Pod logs skipped without HOST_PATH

- **GIVEN** an environment that lacks `HOST_PATH`
- **WHEN** the observability stack deploys
- **THEN** the core renders no pod-log pipeline and mounts no hostPath
- **AND** a `StageSkipped` event is emitted for pod log collection, naming `HOST_PATH`

#### Scenario: Pyroscope server deploys without the eBPF agent

- **GIVEN** an environment that lacks `PRIVILEGED`
- **WHEN** the observability stack deploys
- **THEN** the Pyroscope server reaches Ready and the rollout wait completes

#### Scenario: Every component deploys on AWS

- **GIVEN** an AWS environment
- **WHEN** the observability stack deploys
- **THEN** the core, the host add-on, Beyla, ebpf_exporter, the journald collector and the Pyroscope eBPF agent are all applied, and none is reported skipped

### Requirement: In-cluster producers send OTLP to the collector Service

Every in-cluster producer SHALL send OTLP to the `otel-collector` Service by its `.svc` DNS name (`otel-collector.<namespace>.svc`) rather than to a node IP or the control node IP. This covers the Cassandra sidecar, the stress JVM agent, the stress sidecar, and every built-in kit template that sends OTLP. No built-in kit template or kit script SHALL refer to `HOST_IP:43` or `CONTROL_HOST_PRIVATE:43`. The kit shell library's gauge push, which runs on the operator's machine, SHALL push through the Kubernetes API server's pod proxy to the running core collector pod on the control node, using the workspace kubectl wrapper, so every push of a gauge carries the same `host.name` (the control node's) and the gauge stays one series. It SHALL NOT go through the Service proxy, which picks a core pod on any node per request.

#### Scenario: Kit pod uses the Service DNS name

- **WHEN** a built-in kit pod sends OTLP
- **THEN** its endpoint is the `otel-collector` Service DNS name

#### Scenario: No kit refers to a node OTLP address

- **WHEN** the built-in kit templates and kit scripts are scanned
- **THEN** none contains `HOST_IP:43` or `CONTROL_HOST_PRIVATE:43`

#### Scenario: Stress telemetry reaches the backends

- **WHEN** a stress job runs and its JVM agent and sidecar export OTLP
- **THEN** both send to the `otel-collector` Service and the data reaches the backends

#### Scenario: Gauge push goes through the API server

- **WHEN** a kit script calls the shared library's gauge push from the operator's machine
- **THEN** the gauges are posted to the core collector pod on the control node through the API server's pod proxy, using the workspace kubectl wrapper
- **AND** the gauges reach Mimir

#### Scenario: Every gauge push lands on one node

- **WHEN** a kit script pushes the same gauge many times while core pods run on several nodes
- **THEN** every push goes to the control node's core pod, and the gauge has one `host_name` (the control node's)

#### Scenario: An interrupted gauge push leaves nothing running

- **WHEN** the kit script receives TERM or INT while a gauge push runs
- **THEN** the push's `kubectl proxy` is stopped and its temporary file removed, and the caller's trap (or the default action) handles the signal

### Requirement: down stops both collectors before teardown

During `down`, the telemetry-sender stop SHALL delete and wait for both collector DaemonSets, the host add-on first and then the core, within the existing flush deadline, so the add-on drains into a still-running core. A DaemonSet that is already absent SHALL count as stopped.

#### Scenario: Both DaemonSets deleted and awaited

- **WHEN** `down` stops the telemetry senders
- **THEN** the `otel-collector-host` and `otel-collector` DaemonSets are deleted
- **AND** `down` waits until the pods of both are gone before the flush continues

#### Scenario: Host add-on stopped before the core

- **WHEN** `down` stops the telemetry senders
- **THEN** the host add-on is deleted and gone before the core is deleted

#### Scenario: Missing add-on does not fail down

- **GIVEN** an environment that lacks `PRIVILEGED`, so no host add-on was deployed
- **WHEN** `down` stops the telemetry senders
- **THEN** the absent add-on counts as stopped and the core is deleted and awaited
- **AND** a failure report lists the add-on as not deployed, not as deleted or stopped by the save

#### Scenario: An add-on that does not drain leaves the core running

- **GIVEN** a host add-on pod that does not go before the flush deadline
- **WHEN** `down` stops the telemetry senders
- **THEN** the stop fails at that one deadline, not a fresh one per collector, naming the add-on
- **AND** the core is not deleted, and the failure reports the add-on deleted and the core running

## MODIFIED Requirements

### Requirement: OTel Collector is reachable via ClusterIP Service

A Kubernetes ClusterIP Service named `otel-collector` SHALL be deployed alongside the core OTel collector DaemonSet. The Service SHALL expose the OTLP gRPC port (4317), the OTLP HTTP port (4318) and the Jaeger compact port (6831/UDP), and SHALL set `internalTrafficPolicy: Local` so that each producer reaches the core collector on its own node. The Service SHALL select only the core collector's pods. The core DaemonSet SHALL tolerate every taint so that every node runs a core pod.

#### Scenario: Standard pod can push OTLP traces

- **WHEN** a pod on the pod network is configured to export traces to `otel-collector.<namespace>.svc:4317`
- **THEN** the core OTel collector receives and processes those traces

#### Scenario: Traffic stays on the producer's node

- **WHEN** a producer sends OTLP to the `otel-collector` Service
- **THEN** the traffic is routed to the core collector pod on the producer's own node

#### Scenario: Service selects only core pods

- **WHEN** the `otel-collector` ClusterIP Service is applied to the cluster
- **THEN** it SHALL select pods with label `app.kubernetes.io/name: otel-collector`
- **AND** it SHALL NOT select the host add-on's pods

#### Scenario: Every node runs a core pod

- **WHEN** the core DaemonSet is applied to a cluster with tainted nodes, including the control node
- **THEN** a core pod is scheduled on every node

### Requirement: OTel Collector collects logs from multiple sources including K8s pods

The OTel collectors SHALL collect logs from the following sources, each in a dedicated pipeline, and SHALL export them to Loki through the core:
- **`logs/local`** (host add-on, where `PRIVILEGED`): host file-based logs — system (`/var/log/**`), tools (`/var/log/easydblab/tools/`), and the Cassandra JVM GC log (`/mnt/db1/cassandra/logs/gc.log*`, with `source: cassandra-gc`).
- **`logs/containers`** (core, where `HOST_PATH`): K8s pod stdout/stderr via `/var/log/pods`, enriched with Kubernetes metadata.
- **`logs/otlp`** (core): OTLP-pushed logs — Cassandra's application logs from the OpenTelemetry Java agent (`service_name="cassandra"`), Fluent Bit's journald lines, host-add-on forwarded lines, and remote sources (e.g. Spark nodes).

The `file_log/system` receiver in `logs/local` SHALL explicitly exclude `/var/log/containers/**` and `/var/log/pods/**` to prevent duplication with `logs/containers`.

Cassandra's application logs (`system.log`, `debug.log`) SHALL reach Loki only over OTLP. The `file_log/cassandra` receiver SHALL tail only the JVM GC log, which the JVM writes directly and the Java agent never sees.

Host file logs forwarded by the add-on SHALL NOT be counted by the `cassandra.log.*` log counters.

#### Scenario: Host file logs reach Loki

- **GIVEN** an environment that has `PRIVILEGED`
- **WHEN** the Cassandra JVM writes to its GC log on the host filesystem
- **THEN** that log entry SHALL appear in Loki via the host add-on's `logs/local` pipeline with `source="cassandra-gc"`

#### Scenario: Cassandra application logs are stored once

- **WHEN** Cassandra logs a line to `system.log`
- **THEN** that line SHALL appear in Loki once, via the `logs/otlp` pipeline with `service_name="cassandra"`
- **AND** it SHALL NOT also appear as a file entry from `file_log/cassandra`

#### Scenario: K8s pod logs reach Loki with metadata

- **GIVEN** an environment that has `HOST_PATH`
- **WHEN** a K8s-native kit pod writes to stdout or stderr
- **THEN** that log entry SHALL appear in Loki via the core's `logs/containers` pipeline
- **AND** the entry SHALL include `k8s.pod.name`, `k8s.namespace.name`, and `app.kubernetes.io/instance` attributes

#### Scenario: Container logs are not duplicated in system logs

- **WHEN** any K8s pod emits a log line
- **THEN** that log line SHALL NOT appear in Loki as a raw system log entry from `file_log/system`

#### Scenario: Forwarded host logs are not counted as Cassandra logs

- **WHEN** the host add-on forwards a host file log line to the core
- **THEN** the `cassandra.log.*` log counters do not count it

### Requirement: K8s workloads use hostPort for external port access

K8s workloads installed via `config.yaml` SHALL use standard pod networking (not `hostNetwork`). Client ports and metrics ports SHALL be exposed via `hostPort` mappings in the pod spec, making them accessible on the EC2 instance's network interface. Port remapping SHALL be used when a workload's native port conflicts with a host process. Because the core OTel collector runs on the pod network, it SHALL scrape a workload's host-exposed metrics port at the node's IP (`${env:K8S_NODE_IP}:<port>`), not at `localhost`.

#### Scenario: Database client port accessible on host network via hostPort

- **WHEN** a K8s workload declares `containerPort: 8123, hostPort: 8123`
- **THEN** the port SHALL be accessible at `<node-private-ip>:8123` from any node in the VPC

#### Scenario: Port remapping avoids conflict with Cassandra

- **WHEN** a workload uses CQL port 9042 (same as Cassandra) and declares `containerPort: 9042, hostPort: 9142`
- **THEN** external clients connect to `<node-private-ip>:9142`
- **AND** K8s-internal clients (stress pods) connect to the workload's ClusterIP Service on port 9042

#### Scenario: Core collector scrapes a host-exposed metrics port at the node IP

- **WHEN** a workload exposes metrics on a host port such as `hostPort: 9180`
- **THEN** the core OTel collector, on the pod network, scrapes `<node-ip>:9180` on its own node
- **AND** the `instance` label stays `<node>:<port>`
