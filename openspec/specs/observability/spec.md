## MODIFIED Requirements

### Requirement: OTel Collector scrapes workload metrics dynamically
The OTel Collector ConfigMap SHALL be generated dynamically by `OtelManifestBuilder`, combining a fixed set of static scrape jobs for host processes with a dynamic set of per-workload scrape jobs read from the K8s metrics registry (`easydblab-metrics-*` ConfigMaps). The static ClickHouse scrape job previously hardcoded in `otel-collector-config.yaml` SHALL be removed.

#### Scenario: OTel config reflects currently running workloads
- **WHEN** `install scylladb start` completes
- **THEN** the OTel Collector ConfigMap SHALL include a prometheus scrape job for ScyllaDB targeting `localhost:<port>`
- **AND** all static infrastructure scrape jobs (MAAC, Beyla, ebpf-exporter, YACE) SHALL remain present

#### Scenario: OTel config updated when workload stops
- **WHEN** `install scylladb stop` completes
- **THEN** the ScyllaDB scrape job SHALL no longer appear in the OTel Collector ConfigMap

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`.

All dashboards SHALL include a `cluster` multi-select variable and an ad hoc filters variable. All VictoriaMetrics-backed panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

#### Scenario: Dashboard JSON is not processed by TemplateService

- **WHEN** `GrafanaManifestBuilder` builds a dashboard ConfigMap
- **THEN** the dashboard JSON SHALL be loaded directly from the classpath without passing through `TemplateService.substitute()`
- **AND** all Grafana built-in variables (e.g., `$__rate_interval`, `$__interval`) SHALL be preserved verbatim in the deployed JSON

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
- **THEN** a ConfigMap named `grafana-dashboard-cluster-comparison` SHALL be created
- **AND** the dashboard SHALL be mounted at `/var/lib/grafana/dashboards/cluster-comparison`
- **AND** the volume mount SHALL use `optional: true` so absence of the file does not block Grafana startup

## ADDED Requirements

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
K8s workloads installed via `install.yaml` SHALL use standard pod networking (not `hostNetwork`). Client ports and metrics ports SHALL be exposed via `hostPort` mappings in the pod spec, making them accessible on the EC2 instance's network interface. Port remapping SHALL be used when a workload's native port conflicts with a host process.

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
- **AND** this is identical to how MAAC metrics are scraped at `localhost:9000`
