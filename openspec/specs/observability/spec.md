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

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`.

Core dashboards SHALL be delivered as a file tree, not as Kubernetes objects: `grafana update-config` SHALL copy every `dashboards/<folder>/<file>.json` on the classpath onto the control node's Grafana data hostPath (`/mnt/db1/grafana/dashboards`, `/var/lib/grafana/dashboards` inside the pod), and Grafana SHALL be provisioned with exactly one file provider whose `foldersFromFilesStructure` is true and which names no folder, so each directory becomes a Grafana folder of the same title. No code SHALL enumerate dashboards by hand; the set is discovered from the classpath.

All dashboards SHALL include a `cluster` multi-select variable and an ad hoc filters variable. All VictoriaMetrics-backed panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

#### Scenario: Dashboard JSON is not processed by TemplateService

- **WHEN** the dashboard tree is written for upload
- **THEN** each dashboard JSON SHALL be loaded directly from the classpath without passing through `TemplateService.substitute()`
- **AND** all Grafana built-in variables (e.g., `$__rate_interval`, `$__interval`) SHALL be preserved verbatim in the deployed JSON
- **AND** every occurrence of `__PYROSCOPE_URL__` in any dashboard SHALL be replaced with the cluster's Pyroscope URL; no other substitution SHALL be made

#### Scenario: Dashboard titles use descriptive names

- **WHEN** the user views the Grafana dashboard list
- **THEN** each dashboard title is a simple descriptive name (e.g., "System Overview", "EMR Overview", "Profiling") without any cluster name prefix

#### Scenario: Renderer container runs alongside Grafana

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the pod SHALL contain a `grafana-image-renderer` container using the `grafana/grafana-image-renderer:latest` image
- **AND** the renderer SHALL listen on port 8081

#### Scenario: Grafana is configured to use the renderer

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the `GF_RENDERING_SERVER_URL` env var SHALL point to `http://localhost:8081/render`
- **AND** the `GF_RENDERING_CALLBACK_URL` env var SHALL point to `http://localhost:3000/`

#### Scenario: All dashboards have a cluster variable

- **WHEN** any dashboard is deployed via `grafana update-config`
- **THEN** the dashboard JSON SHALL contain a `cluster` template variable with `multi: true` and `includeAll: true`
- **AND** the variable SHALL query `label_values(up, cluster)` against the VictoriaMetrics datasource

#### Scenario: All metric panels are cluster-scoped

- **WHEN** a VictoriaMetrics-backed panel renders its query
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
- **AND** those metrics flow into VictoriaMetrics via the existing remote-write pipeline

### Requirement: K8s workloads use hostPort for external port access
K8s workloads installed via `config.yaml` SHALL use standard pod networking (not `hostNetwork`). Client ports and metrics ports SHALL be exposed via `hostPort` mappings in the pod spec, making them accessible on the EC2 instance's network interface. Port remapping SHALL be used when a workload's native port conflicts with a host process.

#### Scenario: Database client port accessible on host network via hostPort
- **WHEN** a K8s workload declares `containerPort: 8123, hostPort: 8123`
- **THEN** the port SHALL be accessible at `<node-private-ip>:8123` from any node in the VPC

#### Scenario: Port remapping avoids conflict with Cassandra
- **WHEN** a workload uses CQL port 9042 (same as Cassandra) and declares `containerPort: 9042, hostPort: 9142`
- **THEN** external clients connect to `<node-private-ip>:9142`
- **AND** K8s-internal clients (stress pods) connect to the workload's ClusterIP Service on port 9042

#### Scenario: OTel DaemonSet scrapes workload metrics via hostPort
- **WHEN** a workload exposes metrics on `hostPort: 9180`
- **THEN** the OTel DaemonSet (hostNetwork) can scrape `localhost:9180` on the same node
- **AND** this is identical to how ebpf-exporter metrics are scraped at `localhost:9435`

### Requirement: OTel Collector is reachable via ClusterIP Service

A Kubernetes ClusterIP Service named `otel-collector` SHALL be deployed in the `default` namespace alongside the OTel DaemonSet. The Service SHALL expose the OTLP gRPC port (4317) so that any pod in the cluster can push traces to `otel-collector.default.svc.cluster.local:4317` without knowing the node IP.

#### Scenario: Standard pod can push OTLP traces

- **WHEN** a non-hostNetwork pod (e.g., a TiDB SQL pod) is configured to export traces to `otel-collector.default.svc.cluster.local:4317`
- **THEN** the OTel collector on the cluster receives and processes those traces

#### Scenario: Service selects DaemonSet pods

- **WHEN** the `otel-collector` ClusterIP Service is applied to the cluster
- **THEN** it SHALL select pods with label `app.kubernetes.io/name: otel-collector`
- **AND** kube-proxy (or Cilium's eBPF replacement) SHALL route gRPC traffic to one of the DaemonSet pods

#### Scenario: Existing host-network behaviour is unchanged

- **WHEN** the ClusterIP Service is added
- **THEN** Fluent Bit SHALL continue to reach the OTel collector via `127.0.0.1:4318`
- **AND** the OTel collector SHALL continue to scrape host-networked Prometheus targets via `localhost:<port>`

### Requirement: OTel Collector collects logs from multiple sources including K8s pods
The OTel Collector SHALL collect logs from the following sources, each in a dedicated pipeline:
- **`logs/local`**: Host file-based logs — system (`/var/log/**`), tools (`/var/log/easydblab/tools/`), Cassandra (`/mnt/db1/cassandra/logs/`), ClickHouse server and keeper logs.
- **`logs/containers`**: K8s pod stdout/stderr via `/var/log/containers/*.log`, enriched with Kubernetes metadata.
- **`logs/otlp`**: OTLP-pushed logs from remote sources (e.g. Spark nodes).

The `filelog/system` receiver in `logs/local` SHALL explicitly exclude `/var/log/containers/**` and `/var/log/pods/**` to prevent duplication with `logs/containers`.

#### Scenario: Host file logs reach VictoriaLogs
- **WHEN** a Cassandra or ClickHouse process writes to its log file on the host filesystem
- **THEN** that log entry SHALL appear in VictoriaLogs via the `logs/local` pipeline

#### Scenario: K8s pod logs reach VictoriaLogs with metadata
- **WHEN** a K8s-native kit pod writes to stdout or stderr
- **THEN** that log entry SHALL appear in VictoriaLogs via the `logs/containers` pipeline
- **AND** the entry SHALL include `k8s.pod.name`, `k8s.namespace.name`, and `app.kubernetes.io/instance` attributes

#### Scenario: Container logs are not duplicated in system logs
- **WHEN** any K8s pod emits a log line
- **THEN** that log line SHALL NOT appear in VictoriaLogs as a raw system log entry from `filelog/system`

### Requirement: kube-state-metrics reports Kubernetes object state
The system SHALL deploy kube-state-metrics with the core observability stack on every cluster regardless of CNI and telemetry mode, as a single-replica Deployment on the control node in the pod network, with a ServiceAccount, a read-only ClusterRole (every rule grants only `list` and `watch`), a ClusterRoleBinding, and a ClusterIP Service on port 8080. The image tag SHALL be pinned in `Constants.KubeStateMetrics.IMAGE`. The OTel collector SHALL scrape it through Kubernetes pod discovery filtered to the collector's own node, so exactly one collector scrapes it and `instance` is the pod name.

#### Scenario: Deployed on every cluster
- **WHEN** `easy-db-lab up` deploys the observability stack, in local or redirect mode, on Flannel or Cilium
- **THEN** the `kube-state-metrics` ServiceAccount, ClusterRole, ClusterRoleBinding, Service, and Deployment exist in the `default` namespace
- **AND** the Deployment's pod is scheduled on the control node

#### Scenario: Pod state metrics reach VictoriaMetrics
- **GIVEN** a cluster that is up
- **WHEN** a pod is running
- **THEN** `kube_pod_status_phase` for that pod is queryable in VictoriaMetrics with the cluster label

#### Scenario: RBAC is read-only
- **WHEN** the `kube-state-metrics` ClusterRole is applied
- **THEN** no rule grants a verb other than `list` or `watch`

