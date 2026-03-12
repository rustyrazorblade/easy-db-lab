# Observability

Full observability stack for cluster environments: metrics collection, log aggregation, distributed tracing, continuous profiling, and dashboards.

## Requirements

### Requirement: Metrics Collection and Storage

The system MUST collect metrics from all cluster nodes and store them in a Prometheus-compatible backend with configurable retention.

#### Scenario: YACE scrapes CloudWatch metrics excluding EMR

- **WHEN** YACE is deployed and AWS resources are active
- **THEN** metrics from S3, EBS, EC2, and OpenSearch namespaces are collected by OTel Collector and stored in VictoriaMetrics with `aws_` prefix
- **AND** the `AWS/ElasticMapReduce` namespace is NOT scraped by YACE

#### Scenario: YACE CloudWatch scrape job configuration

- **WHEN** OTel Collector is active on the control node
- **THEN** OTel Collector's Prometheus receiver scrapes YACE's metrics endpoint via a `yace` scrape job

#### Scenario: Tool runner logs shipped to VictoriaLogs

- **WHEN** the OTel Collector DaemonSet is running on a node
- **THEN** a `filelog/tools` receiver SHALL watch `/var/log/easydblab/tools/*.log`
- **AND** log entries SHALL include the attribute `source: tool-runner`
- **AND** logs SHALL be shipped to VictoriaLogs via the `logs/local` pipeline

#### Scenario: Tool-runner logs have accurate timestamps

- **WHEN** a user queries VictoriaLogs for tool-runner logs within a specific time window
- **THEN** the results SHALL include only entries that were actually produced during that window, enabling correlation with Cassandra and system logs from the same period

### Requirement: Continuous Profiling

The system MUST support continuous profiling for cluster workloads including Spark.

#### Scenario: Pyroscope stores profiles in S3

- **WHEN** the Pyroscope server is deployed on the control node
- **THEN** it uses S3 as its storage backend with bucket and region from cluster configuration

#### Scenario: Spark JVM profiles collected

- **WHEN** a Spark job is running with the Pyroscope Java agent
- **THEN** CPU, allocation, and lock profiles from driver and executor JVMs are stored in Pyroscope and viewable in Grafana

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure. Dashboard titles MUST use simple descriptive names without cluster name prefixes. The Grafana pod SHALL include an image renderer sidecar for server-side panel rendering.

#### Scenario: Dashboard titles use descriptive names

- **WHEN** the user views the Grafana dashboard list
- **THEN** each dashboard title is a simple descriptive name (e.g., "System Overview", "EMR Overview", "Profiling") without any cluster name prefix

#### Scenario: EMR dashboard shows OTel host metrics

- **WHEN** the user views the EMR dashboard in Grafana
- **THEN** the dashboard displays CPU, memory, disk, and network metrics from OTel host metrics collected on Spark/EMR nodes

#### Scenario: EMR dashboard shows Spark JVM metrics

- **WHEN** the user views the EMR dashboard in Grafana
- **THEN** the dashboard displays JVM heap usage, GC activity, and thread metrics from the OTel Java agent on Spark driver/executors

#### Scenario: System Overview dashboard hostname filter includes all node types

- **WHEN** the user views the System Overview dashboard in Grafana
- **THEN** the hostname filter SHALL list hosts from all node types: db, app, control, and spark
- **AND** the service filter SHALL list all node_role values present in metrics

#### Scenario: Grafana pod includes image renderer sidecar

- **WHEN** the Grafana deployment is applied
- **THEN** the pod SHALL contain both the Grafana container and a `grafana-image-renderer` sidecar container

### Requirement: Status endpoint includes Tempo and Pyroscope S3 paths

The system SHALL expose Tempo and Pyroscope S3 storage paths in the `/status` endpoint's S3 section.

#### Scenario: Status response includes observability S3 paths

- **WHEN** the user queries the `/status` endpoint
- **THEN** the `s3.paths` section includes `tempo` and `pyroscope` fields with their S3 paths in the data bucket

### Requirement: OTel Collector adds node_role from K8s node labels

The OTel Collector on cluster nodes SHALL use the k8sattributes processor to extract the K8s node label `type` and set it as the `node_role` resource attribute on all locally-collected metrics and logs.

#### Scenario: Cassandra node metrics include node_role=db

- **WHEN** the OTel Collector is running on a Cassandra node with K8s label `type=db`
- **THEN** all metrics in the `metrics/local` pipeline SHALL have `node_role=db` as a resource attribute
- **AND** the attribute is converted to the `node_role` metric label by the prometheusremotewrite exporter

#### Scenario: Stress node metrics include node_role=app

- **WHEN** the OTel Collector is running on a stress node with K8s label `type=app`
- **THEN** all metrics in the `metrics/local` pipeline SHALL have `node_role=app` as a resource attribute

#### Scenario: Control node metrics include node_role=control

- **WHEN** the OTel Collector is running on the control node with K8s label `type=control`
- **THEN** all metrics in the `metrics/local` pipeline SHALL have `node_role=control` as a resource attribute

#### Scenario: Logs include node_role

- **WHEN** the OTel Collector is running on any cluster node
- **THEN** all logs in the `logs/local` pipeline SHALL have `node_role` set to the node's type label value

#### Scenario: OTLP pipelines are not affected

- **WHEN** metrics or logs arrive via the OTLP receiver from remote nodes (e.g., Spark)
- **THEN** the k8sattributes processor SHALL NOT be applied to those pipelines
- **AND** existing `node_role` attributes from remote sources are preserved unchanged

### Requirement: OTel Collector has RBAC for K8s API access

The OTel Collector DaemonSet SHALL run with a ServiceAccount that has read access to K8s pods and nodes, required by the k8sattributes processor.

#### Scenario: RBAC resources are created

- **WHEN** OTel Collector K8s resources are applied
- **THEN** a ServiceAccount, ClusterRole, and ClusterRoleBinding SHALL be created
- **AND** the ClusterRole SHALL grant `get`, `watch`, `list` on `pods` and `nodes`
- **AND** the DaemonSet SHALL reference the ServiceAccount

### Requirement: Control node receives type label during cluster setup

The control node SHALL be labeled with `type=control` in K8s during the cluster `up` command, since it is the K3s server and does not receive labels via K3s agent configuration.

#### Scenario: Control node labeled during Up

- **WHEN** the `up` command reaches the node labeling phase
- **THEN** the control node SHALL be labeled with `type=control` via `k8sService.labelNode()`
- **AND** this occurs before OTel and Grafana resources are deployed
