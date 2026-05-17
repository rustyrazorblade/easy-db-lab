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
