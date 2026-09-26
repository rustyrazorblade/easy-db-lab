## MODIFIED Requirements

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering. Dashboard JSON SHALL be loaded directly from classpath resources without template substitution, preserving Grafana built-in variables like `$__rate_interval`.

Core dashboards SHALL be delivered as a file tree, not as Kubernetes objects: `grafana update-config` SHALL copy every `dashboards/<folder>/<file>.json` on the classpath onto the control node's Grafana data hostPath (`/mnt/db1/grafana/dashboards`, `/var/lib/grafana/dashboards` inside the pod), and Grafana SHALL be provisioned with exactly one file provider whose `foldersFromFilesStructure` is true and which names no folder, so each directory becomes a Grafana folder of the same title. No code SHALL enumerate dashboards by hand; the set is discovered from the classpath.

All dashboards SHALL include a `cluster` multi-select variable and an ad hoc filters variable. All metric panel queries SHALL be scoped by `{cluster=~"$cluster"}`. No native ClickHouse datasource SHALL be provisioned.

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
- **THEN** the pod SHALL contain a `grafana-image-renderer` container using the `grafana/grafana-image-renderer` image pinned to release v5.12.4, never `latest`
- **AND** the renderer SHALL listen on port 8081

#### Scenario: Grafana is configured to use the renderer

- **WHEN** the Grafana deployment is applied to the cluster
- **THEN** the `GF_RENDERING_SERVER_URL` env var SHALL point to `http://localhost:8081/render`
- **AND** the `GF_RENDERING_CALLBACK_URL` env var SHALL point to `http://localhost:3000/`

#### Scenario: All dashboards have a cluster variable

- **WHEN** any dashboard is deployed via `grafana update-config`
- **THEN** the dashboard JSON SHALL contain a `cluster` template variable with `multi: true` and `includeAll: true`
- **AND** the variable SHALL query `label_values(up, cluster)` against the Mimir datasource (uid `mimir`)

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

### Requirement: Cilium replaces Flannel as the K3s CNI
The K3s cluster SHALL use Cilium as its CNI plugin with `kube-proxy` replacement enabled. K3s SHALL be started with `--flannel-backend=none --disable-network-policy`. Cilium SHALL be installed via helm before any workloads are deployed. Hubble SHALL be enabled with Prometheus metrics export.

#### Scenario: Cilium DaemonSet ready before workloads start
- **WHEN** `easy-db-lab up` provisions a new cluster
- **THEN** the Cilium DaemonSet SHALL be fully Ready before any platform setup or workload installation proceeds

#### Scenario: Hubble metrics reachable by OTel collector
- **WHEN** the cluster is provisioned
- **THEN** Hubble exposes Prometheus metrics that the OTel collector can scrape
- **AND** those metrics flow into Mimir via the existing remote-write pipeline

### Requirement: OTel Collector collects logs from multiple sources including K8s pods
The OTel Collector SHALL collect logs from the following sources, each in a dedicated pipeline, and SHALL export them to Loki:
- **`logs/local`**: Host file-based logs — system (`/var/log/**`), tools (`/var/log/easydblab/tools/`), Cassandra (`/mnt/db1/cassandra/logs/`), ClickHouse server and keeper logs.
- **`logs/containers`**: K8s pod stdout/stderr via `/var/log/containers/*.log`, enriched with Kubernetes metadata.
- **`logs/otlp`**: OTLP-pushed logs from remote sources (e.g. Spark nodes).

The `file_log/system` receiver in `logs/local` SHALL explicitly exclude `/var/log/containers/**` and `/var/log/pods/**` to prevent duplication with `logs/containers`.

#### Scenario: Host file logs reach Loki
- **WHEN** a Cassandra or ClickHouse process writes to its log file on the host filesystem
- **THEN** that log entry SHALL appear in Loki via the `logs/local` pipeline

#### Scenario: K8s pod logs reach Loki with metadata
- **WHEN** a K8s-native kit pod writes to stdout or stderr
- **THEN** that log entry SHALL appear in Loki via the `logs/containers` pipeline
- **AND** the entry SHALL include `k8s.pod.name`, `k8s.namespace.name`, and `app.kubernetes.io/instance` attributes

#### Scenario: Container logs are not duplicated in system logs
- **WHEN** any K8s pod emits a log line
- **THEN** that log line SHALL NOT appear in Loki as a raw system log entry from `file_log/system`

### Requirement: kube-state-metrics reports Kubernetes object state
The system SHALL deploy kube-state-metrics with the core observability stack on every cluster regardless of CNI and telemetry mode, as a single-replica Deployment on the control node in the pod network, with a ServiceAccount, a read-only ClusterRole (every rule grants only `list` and `watch`), a ClusterRoleBinding, and a ClusterIP Service on port 8080. The image tag SHALL be pinned in `Constants.KubeStateMetrics.IMAGE`. The OTel collector SHALL scrape it through Kubernetes pod discovery filtered to the collector's own node, so exactly one collector scrapes it and `instance` is the pod name.

#### Scenario: Deployed on every cluster
- **WHEN** `easy-db-lab up` deploys the observability stack, in local or redirect mode, on Flannel or Cilium
- **THEN** the `kube-state-metrics` ServiceAccount, ClusterRole, ClusterRoleBinding, Service, and Deployment exist in the `default` namespace
- **AND** the Deployment's pod is scheduled on the control node

#### Scenario: Pod state metrics reach Mimir
- **GIVEN** a cluster that is up
- **WHEN** a pod is running
- **THEN** `kube_pod_status_phase` for that pod is queryable in Mimir with the cluster label

#### Scenario: RBAC is read-only
- **WHEN** the `kube-state-metrics` ClusterRole is applied
- **THEN** no rule grants a verb other than `list` or `watch`
