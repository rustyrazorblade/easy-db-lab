# Port Reference

This page documents the ports used by easy-db-lab and the services it provisions.

Every node uses the ephemeral port range 1024-65535 and reserves the NodePort range and every
fixed host listener port below it, so no outbound connection takes one. A new fixed host port must
be added to the reserved list. See [Node OS Tuning](../user-guide/node-tuning.md#network-settings).

## Cassandra Ports

| Port | Purpose |
|------|---------|
| 9042 | Cassandra Native Protocol (CQL) |
| 7000 | Inter-node communication |
| 7001 | Inter-node communication (SSL) |
| 7199 | JMX monitoring |

## Observability Ports (Control Node)

Mimir, Loki, Tempo, the Pyroscope server and Grafana run in the Kubernetes pod network behind Services. No backend port is opened on a node as a host port. Callers reach them three ways:

- **In-cluster producers** (the OTel collector, Alloy, the sidecar, stress pods, kit agents, Grafana) dial the Service DNS name and Service port, for example `mimir.default.svc:9009` or `pyroscope.default.svc:4040`. Pods on the host network resolve it through `ClusterFirstWithHostNet`.
- **Host processes on a node** (the db-node profiling reconciler) post profiles to the Alloy relay on `127.0.0.1:4041`, which forwards them to the Pyroscope Service.
- **Callers outside the cluster** (EMR, a browser, a redirected cluster, operator scripts such as `bin/export-workload-metrics`) use a node's private IP and the fixed NodePorts below. These NodePorts are published only on clusters easy-db-lab provisions; elsewhere the Services are ClusterIP and `status` prints the port-forward commands.

| NodePort | Service port | Service |
|----------|--------------|---------|
| 31300 | 3000 | Grafana (and the test documents under `/reports/`) |
| 31009 | 9009 | Mimir HTTP (Prometheus API under `/prometheus`, remote write at `/api/v1/push`) |
| 31100 | 3100 | Loki HTTP (OTLP ingest at `/otlp`) |
| 31200 | 3200 | Tempo query API |
| 31320 | 4320 | Tempo OTLP gRPC receiver |
| 31040 | 4040 | Pyroscope |

The cluster's security group opens every TCP and UDP port to the VPC's own address range and only SSH to the addresses `init` and `ingress allow` name. So the NodePorts answer anything inside the VPC (EMR, another easy-db-lab cluster in the same VPC, a host where you run the export script) and nothing outside it: from your laptop, reach Grafana and the backends through the SOCKS tunnel or Tailscale, which arrive from a cluster node. No rule names a backend port.

The Service and pod ports:

| Port | Service |
|------|---------|
| 3000 | Grafana, through the nginx front in the Grafana pod, which also serves the test documents under `/reports/` (read-only) |
| 3001 | Grafana itself (loopback only, in the Grafana pod, behind the front) |
| 3081 | Test documents signing proxy (loopback only, in the Grafana pod) |
| 8081 | Grafana image renderer (in the Grafana pod) |
| 4040 | Pyroscope (continuous profiling) |
| 9009 | Mimir HTTP (metrics storage; Prometheus API under `/prometheus`) |
| 9097 | Mimir gRPC |
| 7947 | Mimir memberlist (loopback only) |
| 11211 | Mimir's memcached caches (loopback only, in the Mimir pod) |
| 3100 | Loki HTTP (log storage) |
| 9098 | Loki gRPC |
| 3200 | Tempo (trace storage) |
| 5001 | YACE CloudWatch exporter (Prometheus) |
| 8080 | kube-state-metrics (Prometheus; ClusterIP Service, pod network) |
| 2381 | K3s embedded etcd metrics (Prometheus, plain HTTP, control node; localhost and the private IP) |

## Observability Ports (All Nodes — DaemonSets)

| Port | Service |
|------|---------|
| 4317 | OTel Collector gRPC |
| 4318 | OTel Collector HTTP — the Cassandra JVM agent exports here |
| 4041 | Alloy profiles relay (loopback only, every node) — the db-node profiling reconciler posts JFR here |
| 9400 | Beyla eBPF metrics (Prometheus) |
| 9435 | ebpf_exporter metrics (Prometheus) |

## Cilium Ports (Cilium clusters only; the default CNI)

| Port | Service |
|------|---------|
| 9962 | Cilium agent metrics (Prometheus, every node) |
| 9963 | Cilium operator metrics (Prometheus, the node that runs the operator) |
| 9965 | Hubble metrics (Prometheus, every node) |
| 31234 | Hubble UI (NodePort, any node's private IP) |

## Stress Ports (App Nodes)

| Port | Service |
|------|---------|
| 9500-9599 | Stress metrics (Prometheus). Each `cassandra stress start` takes the next port, wrapping after 100 runs, so a run cannot bind its port while the run 100 before it is still running on that node. Reserved by node tuning. |

## Flannel

| Port | Service |
|------|---------|
| 8472/udp | flannel VXLAN, on every node when the CNI is flannel (`init --cni=flannel`). Reserved by node tuning. |

## Tailscale

| Port | Service |
|------|---------|
| 41641/udp | tailscaled WireGuard (every node that joins the tailnet). Reserved by node tuning. |

## Kit Ports

Kits publish client ports through NodePort Services, reachable on any node's private IP.

The cluster's security group decides who can reach them. It allows TCP 22 from your public IP (or
from anywhere with `init --open`), and all TCP, UDP and ICMP only from the cluster's own VPC CIDR.
No NodePort is open to the internet. Anything inside the VPC can connect to every NodePort, as can
your machine through the SOCKS tunnel or, on a Tailscale cluster, any tailnet device your ACL lets
use the control node's subnet route. A kit port has only the authentication its database
provides; the table says where there is none.

| Port | Kit | Service |
|------|-----|---------|
| 30816 | cqlite-flight | Arrow Flight gRPC (NodePort → 8815, node-local: each db node's IP reaches that node's own pod). **No authentication**: anyone who can reach it can read that node's SSTables |
| 31211 | memcached | memcached client port (NodePort → 11211) |
| 9150 | memcached | memcached-exporter metrics (pod port, scraped by pod discovery) |
| 30943 | ferrosa | CQL (NodePort → 9042, first pod) |
| 30909 | ferrosa | console, `/metrics` and `/readyz` (NodePort → 9090, first pod; metrics are scraped by pod discovery) |
| 30787 | ferrosa | Bolt (NodePort → 7687, first pod) |
| 30747 | ferrosa | graph HTTP API (NodePort → 7474, first pod) |
| 30880 | ferrosa | SPARQL (NodePort → 8080, first pod) |
| 30532 | ferrosa | Postgres wire stub (NodePort → 5432, first pod) |
| 30815 | ferrosa | Arrow Flight (NodePort → 8815, first pod) |
| 30687 | neo4j | Bolt (NodePort → 7687) |
| 30474 | neo4j | HTTP (NodePort → 7474) |
| 30432 | postgres | PostgreSQL (NodePort → 5432) |
| 30987 | postgres | metrics (NodePort → 9187, for manual inspection; scraped by pod discovery) |
| 30435 | postgres-duckdb | PostgreSQL (NodePort → 5432) |
| 30990 | postgres-duckdb | metrics (NodePort → 9187) |
| 30433 | postgres-postgis | PostgreSQL (NodePort → 5432) |
| 30988 | postgres-postgis | metrics (NodePort → 9187) |
| 30434 | postgres-timescaledb | PostgreSQL (NodePort → 5432) |
| 30989 | postgres-timescaledb | metrics (NodePort → 9187) |
| 30882 | presto | coordinator HTTP: UI, REST and JDBC (NodePort → 8080) |
| 30881 | trino | coordinator HTTP: UI, REST and JDBC (NodePort → 8080) |

Every built-in kit's NodePorts, each postgres instance's, and the Hubble UI's 31234 are distinct,
so any combination of kits can run at once.

## Reaching Services on a Port-Forward Cluster

On a cluster easy-db-lab does not own (`exposure` PortForward), the CLI reaches each Service through
a Kubernetes port-forward instead of a node address. It binds the forward to `127.0.0.1` only, on a
local port from 20000-29999, and closes it when the command ends. Grafana has a ClusterIP Service
(`grafana`, port 3000) for this. A kit's NodePort is forwarded to the Service port behind it.

`status` prints each Service by its DNS name (for example
`http://grafana.default.svc.cluster.local:3000`), followed by the command that forwards it to your
machine:

```bash
bin/kubectl port-forward -n default svc/grafana 3000
```

A kit endpoint is printed at its Service port, and the command forwards the kit's NodePort on your
machine to it. For ClickHouse's HTTP endpoint that is
`http://clickhouse-nodeport.default.svc.cluster.local:8123` and:

```bash
bin/kubectl port-forward -n default svc/clickhouse-nodeport 30123:8123
```

## Server

| Port | Purpose |
|------|---------|
| 8080 | Default server port (configurable via `--port`) |

## SSH

SSH access is configured automatically through the `sshConfig` file generated by `source env.sh`.
