# Observability

## Purpose

Provides the cluster's metrics, logging, and tracing stack: dynamically generated OTel Collector scrape configuration, Grafana dashboards backed by VictoriaMetrics, Cilium/Hubble network visibility, hostPort-based metrics exposure, and a ClusterIP Service for pushing OTLP traces.
## Requirements
### Requirement: OTel Collector scrapes workload metrics dynamically
The OTel Collector ConfigMap SHALL be generated dynamically by `OtelManifestBuilder`, combining a fixed set of static scrape jobs for host processes with a dynamic set of per-workload scrape jobs read from the K8s metrics registry (`easydblab-metrics-*` ConfigMaps). The static ClickHouse scrape job previously hardcoded in `otel-collector-config.yaml` SHALL be removed.

#### Scenario: OTel config reflects currently running workloads
- **WHEN** `install scylladb start` completes
- **THEN** the OTel Collector ConfigMap SHALL include a prometheus scrape job for ScyllaDB targeting `localhost:<port>`
- **AND** all static infrastructure scrape jobs (Beyla, ebpf-exporter, YACE) SHALL remain present

#### Scenario: OTel config updated when workload stops
- **WHEN** `install scylladb stop` completes
- **THEN** the ScyllaDB scrape job SHALL no longer appear in the OTel Collector ConfigMap

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering, and the `aws-sigv4-proxy` and documents web server sidecars that the `test-documents` capability specifies. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`. The only change made to a dashboard on its way to Grafana SHALL be the install-time pass that the `multi-cluster-dashboards` capability specifies ("Installed dashboards default to the current cluster"), plus the `__PYROSCOPE_URL__` substitution.

Core dashboards SHALL be delivered as a file tree, not as Kubernetes objects: `grafana update-config` SHALL copy every `dashboards/<folder>/<file>.json` on the classpath onto the control node's Grafana data hostPath (`/mnt/db1/grafana/dashboards`, `/var/lib/grafana/dashboards` inside the pod), and Grafana SHALL be provisioned with exactly one file provider whose `foldersFromFilesStructure` is true and which names no folder, so each directory becomes a Grafana folder of the same title. No code SHALL enumerate dashboards by hand; the set is discovered from the classpath.

Every dashboard that has a `cluster` variable SHALL make it multi-select with an "All" option, except the Tests dashboard, whose `cluster` variable is single-select. All metric dashboards SHALL include an ad hoc filters variable. All metric panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

#### Scenario: Dashboard JSON is not processed by TemplateService

- **WHEN** the dashboard tree is written for upload
- **THEN** each dashboard JSON SHALL be loaded directly from the classpath without passing through `TemplateService.substitute()`
- **AND** all Grafana built-in variables (e.g., `$__rate_interval`, `$__interval`) SHALL be preserved verbatim in the deployed JSON
- **AND** every occurrence of `__PYROSCOPE_URL__` in any dashboard SHALL be replaced with the cluster's Pyroscope URL
- **AND** the install-time pass SHALL be applied; no other change SHALL be made

#### Scenario: Dashboard titles use descriptive names

- **WHEN** the user views the Grafana dashboard list
- **THEN** each dashboard title is a simple descriptive name (e.g., "System Overview", "EMR Overview", "Profiling") without any cluster name prefix

#### Scenario: Renderer container runs alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain a `grafana-image-renderer` container using the `grafana/grafana-image-renderer` image pinned to release v5.12.4, never `latest`
- **AND** the renderer SHALL listen on port 8081

#### Scenario: Grafana is configured to use the renderer

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the `GF_RENDERING_SERVER_URL` env var SHALL point to `http://localhost:8081/render`
- **AND** the `GF_RENDERING_CALLBACK_URL` env var SHALL point to `http://localhost:3000/`

#### Scenario: Documents sidecars run alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain the `aws-sigv4-proxy` container and the documents web server container
- **AND** Grafana SHALL run with `disable_sanitize_html` set to true

#### Scenario: Cluster variables read the metrics picker

- **WHEN** any dashboard that has a `cluster` variable is deployed via `grafana update-config`
- **THEN** the variable SHALL query `label_values(up, cluster)` against `${metrics_datasource}`, never a fixed datasource uid, except on the Tests dashboard, whose variable lists the clusters with `up` samples within `lookback` against `${metrics_datasource}`
- **AND** the variable SHALL have `multi: true` and `includeAll: true`, except on the Tests dashboard

#### Scenario: All metric panels are cluster-scoped

- **WHEN** a metric panel renders its query
- **THEN** the PromQL expression SHALL include a `cluster=~"$cluster"` label selector

#### Scenario: No native ClickHouse datasource is provisioned

- **WHEN** Grafana loads its datasource configuration
- **THEN** no datasource of type `grafana-clickhouse-datasource` SHALL be present

#### Scenario: Cluster comparison dashboard appears in Grafana

- **WHEN** `grafana update-config` is run
- **THEN** `dashboards/cassandra/cluster-comparison.json` SHALL be copied to `/mnt/db1/grafana/dashboards/cassandra/cluster-comparison.json` on the control node
- **AND** the single file provider SHALL file it into the `cassandra` folder from its directory name
- **AND** no ConfigMap, volume, or volume mount SHALL exist for it

#### Scenario: Copied tree replaces the previous one

- **WHEN** `grafana update-config` is run against a cluster that already has a dashboard tree
- **THEN** the new tree SHALL be staged on the same filesystem as `/mnt/db1/grafana/dashboards` and renamed into place, so the provider never reads an empty or partial directory
- **AND** the previous tree SHALL be removed once the new one is in place, so a dashboard deleted from the repo disappears from Grafana
- **AND** the tree SHALL be owned by the Grafana user (uid 472)

#### Scenario: Same file name in two folders

- **WHEN** `dashboards/<a>/<name>.json` and `dashboards/<b>/<name>.json` both exist
- **THEN** both SHALL be deployed, each in its own folder

#### Scenario: System Overview is grouped by purpose

- **WHEN** a user opens System Overview
- **THEN** panels are grouped into collapsible rows named CPU, Memory, Disk, Network, and Processes, by what the metric explains, not by which collector produced it
- **AND** each row lays panels out two per line at half width, one metric per panel

#### Scenario: Disk and network panels exclude virtual devices

- **WHEN** a Disk panel queries per-device metrics
- **THEN** loop devices and partitions (`device!~"loop.*|.*p[0-9]+"`) are excluded
- **AND** Network panels match only `ens.*` and `eth.*` interfaces

### Requirement: ebpf_exporter runs a fixed, verified program set

The ebpf_exporter DaemonSet SHALL load an explicit list of programs by name (`--config.names`); every name SHALL exist in the pinned image's `/examples`. A program whose series are unbounded or whose readings are wrong on the base image's kernel SHALL be excluded, with the reason recorded next to the list.

When the image's copy of a program is wrong for the base image's kernel, the AMI SHALL carry a replacement compiled at bake time from `packer/base/install/ebpf/<name>.bpf.c` against that kernel's BTF, installed at `/usr/local/lib/ebpf_exporter/<name>.bpf.o`, and the DaemonSet SHALL mount it over `/examples/<name>.bpf.o` with a hostPath of type `File`. The set of overridden programs SHALL equal the set of sources in `packer/base/install/ebpf/`.

#### Scenario: Override object is built against the bake kernel

- **WHEN** the base image is baked
- **THEN** each `*.bpf.c` is compiled against the running kernel's BTF
- **AND** every attach point named in the compiled object (fentry, kprobe, raw_tp, tp_btf) is checked against that kernel; a missing symbol fails the bake

#### Scenario: Missing override fails the pod, not the metric

- **WHEN** an overridden object is absent from the node
- **THEN** the ebpf_exporter pod fails to start rather than falling back to the image's copy

#### Scenario: Syscall errno labels are bounded

- **WHEN** the `syscalls` program records a syscall return
- **THEN** only returns in `[-4095, -1]` are counted, so the `errno` label has at most 4095 values

#### Scenario: Page cache counters on folio kernels

- **WHEN** the `cachestat` program runs on a kernel where the page cache is folio-based
- **THEN** `page_cache_ops_total{operation="cache_access"}` and `page_add_lru` increase under file I/O

### Requirement: Cilium replaces Flannel as the K3s CNI
The K3s cluster SHALL use Cilium as its CNI plugin with `kube-proxy` replacement enabled. K3s SHALL be started with `--flannel-backend=none --disable-network-policy`. Cilium SHALL be installed via helm before any workloads are deployed. Hubble SHALL be enabled with Prometheus metrics export.

#### Scenario: Cilium DaemonSet ready before workloads start
- **WHEN** `easy-db-lab up` provisions a new cluster
- **THEN** the Cilium DaemonSet SHALL be fully Ready before any platform setup or workload installation proceeds

#### Scenario: Hubble metrics reachable by OTel collector
- **WHEN** the cluster is provisioned
- **THEN** Hubble exposes Prometheus metrics that the OTel collector can scrape
- **AND** those metrics flow into Mimir via the existing remote-write pipeline

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

### Requirement: kube-state-metrics reports Kubernetes object state
The system SHALL deploy kube-state-metrics with the core observability stack on every cluster regardless of CNI and telemetry mode, as a single-replica Deployment in the pod network, with a ServiceAccount, a read-only ClusterRole (every rule grants only `list` and `watch`), a ClusterRoleBinding, and a ClusterIP Service on port 8080. Its placement SHALL come from the same resolved `Placement` value as the observability backends, so on AWS it stays on the control node and elsewhere it is scheduled by the resolved selector. The image tag SHALL be pinned in `Constants.KubeStateMetrics.IMAGE`. The OTel collector SHALL scrape it through Kubernetes pod discovery filtered to the collector's own node, so exactly one collector scrapes it and `instance` is the pod name.

#### Scenario: Deployed on every cluster
- **WHEN** `easy-db-lab up` deploys the observability stack, in local or redirect mode, on Flannel or Cilium
- **THEN** the `kube-state-metrics` ServiceAccount, ClusterRole, ClusterRoleBinding, Service, and Deployment exist in the namespace
- **AND** on AWS the Deployment's pod is scheduled on the control node

#### Scenario: Placement follows the environment
- **GIVEN** a cluster with no control-plane node
- **WHEN** kube-state-metrics is rendered with an empty placement
- **THEN** its pod carries no control-plane selector or toleration and is schedulable on any node

#### Scenario: Pod state metrics reach Mimir
- **GIVEN** a cluster that is up
- **WHEN** a pod is running
- **THEN** `kube_pod_status_phase` for that pod is queryable in Mimir with the cluster label

#### Scenario: RBAC is read-only
- **WHEN** the `kube-state-metrics` ClusterRole is applied
- **THEN** no rule grants a verb other than `list` or `watch`

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

#### Scenario: Host-network scrapes stay on localhost

- **GIVEN** an environment that has `PRIVILEGED`
- **WHEN** the host add-on, on the host network, scrapes Beyla, ebpf_exporter or the Cilium agent and Hubble
- **THEN** it scrapes them at `localhost:<port>` on its own node, as before the `otel-collector` Service existed
- **AND** the `instance` label is `<node>:<port>`

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
- **AND** the push leaves no variable of its own in the caller's shell

#### Scenario: A gauge push skips a terminating core pod

- **GIVEN** a rollout in which the control node runs a core pod that is being deleted and its replacement
- **WHEN** a kit script pushes a gauge
- **THEN** the push goes to the pod that is not being deleted, and with only the terminating pod it warns and posts nothing

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

#### Scenario: A failed collector stop always stops down

- **GIVEN** any set of signals still to save, with or without traces
- **WHEN** the telemetry-sender stop fails or times out
- **THEN** the save reports a failure at the collector-stop step, `down` stops, and nothing is torn down

### Requirement: Observability backends and Grafana run on the pod network behind ClusterIP Services

Mimir, Loki, Tempo, Pyroscope and Grafana SHALL run as ordinary pod-network workloads behind ClusterIP Services, with no `hostNetwork`. Their placement SHALL come from a resolved `Placement` value — a node selector, tolerations, and whether host ports are published — derived once from the recorded environment, never from testing an environment name. On AWS the resolved placement SHALL keep today's control-node selector and its matching toleration; where the environment declares no such node the selector and tolerations SHALL be empty. An `init` user node label SHALL merge into the selector. The same manifests SHALL be used on every environment.

On an environment that owns its nodes (AWS), each of these workloads SHALL also publish its client ports on the node as container `hostPort`s, with no `hostNetwork`. Callers outside the pod network reach those ports at the control node's private IP: redirected clusters and node-side agents. The CLI and the teardown flush reach them through the service-access seam, not the host ports. The ports are Mimir 9009, Loki 3100, Tempo 3200, 4320 and 4321, Pyroscope 4040 and Grafana 3000, which the nginx front in the Grafana pod holds. These host ports are temporary: a follow-up moves each node-IP caller to Service DNS or a NodePort and removes them. Where the environment does not own its nodes, no workload SHALL declare a host port.

Every in-cluster caller of these backends SHALL address them by Service DNS (`<service>.<namespace>.svc`), not `localhost` or a node IP. This SHALL include the Grafana datasources' backend base URLs and the OTel collector's backend exporter endpoints.

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

#### Scenario: On AWS the client ports stay published on the control node
- **GIVEN** an AWS environment
- **WHEN** the five workloads are rendered
- **THEN** each declares exactly its client ports as host ports, and none runs with `hostNetwork`

#### Scenario: Off owned nodes no workload declares a host port
- **GIVEN** an environment that does not own its nodes
- **WHEN** the five workloads are rendered
- **THEN** none declares a host port

#### Scenario: Grafana datasources use Service DNS
- **WHEN** Grafana's datasources are built
- **THEN** each backend datasource URL is that backend's Service DNS name, not `localhost` or a node IP

#### Scenario: The collector reaches the backends by Service DNS
- **GIVEN** the backends run behind ClusterIP Services with no node-IP listener
- **WHEN** the OTel collector exports metrics, logs and traces
- **THEN** it addresses Mimir, Loki and Tempo by Service DNS
- **AND** the dashboards show data

#### Scenario: A replaced backend on a retained claim stays reachable
- **GIVEN** an environment without `HOST_PATH`, so the backend's state is on its retained claim
- **WHEN** the backend's pod is deleted and its replacement reattaches the same claim (on another node only within the volume's zone, since the claim is zonal)
- **THEN** Grafana reaches the replacement through the backend's ClusterIP Service, at the same URL its datasource uses

#### Scenario: Off owned nodes the stack and the collector sync need no control node
- **GIVEN** an environment that does not own its nodes and a cluster state with no control node
- **WHEN** the observability stack deploys, or a kit start or stop resyncs the collector
- **THEN** it succeeds
- **AND** the `cluster-config` ConfigMap carries no `control_node_ip` key
- **AND** the collector configuration has no endpoint at a node IP, and the eBPF profiling agent writes to the Pyroscope Service

#### Scenario: On AWS the control node IP is still published
- **GIVEN** an AWS environment
- **WHEN** the observability stack deploys
- **THEN** `cluster-config` carries `control_node_ip` set to the control node's private IP, with every other key as today
- **AND** a cluster state with no control node fails the deploy before anything is applied, naming the callers that reach the backends at that IP

#### Scenario: Off owned nodes, backend state is on a retained claim
- **GIVEN** an environment without `HOST_PATH`
- **WHEN** a backend is rendered
- **THEN** its state is on a per-backend claim whose StorageClass reclaims with `Retain`
- **AND** no backend uses `emptyDir` for its state

#### Scenario: On a claim the dashboards reach Grafana through the K8s API
- **GIVEN** an environment without `HOST_PATH`, so Grafana's state is on its retained claim
- **WHEN** the observability stack deploys
- **THEN** the dashboard tree is applied as ConfigMaps the Grafana Deployment mounts at its dashboard path, with no copy to a node
- **AND** each dashboard is filed in the folder named for its directory

#### Scenario: A claim StorageClass that deletes its volumes is refused
- **GIVEN** an environment without `HOST_PATH` whose backend StorageClass reclaims with `Delete`, sets no reclaim policy, or does not exist
- **WHEN** the observability stack deploys
- **THEN** it fails before applying anything, naming the StorageClass and that it must reclaim with `Retain`

