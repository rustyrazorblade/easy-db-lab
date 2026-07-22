# Platform Substrate

The platform substrate is the set of Kubernetes primitives that easy-db-lab provisions on every cluster. It provides a stable foundation so any kit can be deployed without bespoke manifest code.

## Two-Layer Model

```
┌─────────────────────────────────────┐
│         Kit Layer              │
│  (ClickHouse, Presto, custom, …)    │
│  deployed via install + helm/kubectl│
└────────────────┬────────────────────┘
                 │ uses
┌────────────────▼────────────────────┐
│        Platform Substrate           │
│  StorageClasses · Node Labels · PVs │
│  provisioned at cluster `up` time   │
└─────────────────────────────────────┘
```

The platform substrate is provisioned automatically when you run `easy-db-lab up`. You do not need to configure it manually.

## StorageClasses

Two StorageClasses are created at `up` time:

| Name | Binding Mode | Reclaim Policy | Use |
|---|---|---|---|
| `local-storage` | `Immediate` | `Retain` | Legacy / direct-bound PVs |
| `local-storage-wfc` | `WaitForFirstConsumer` | `Delete` | Per-kit PVs via `install` / `platform create-pvs` |

`local-storage-wfc` is used for StatefulSet kits. Kubernetes must know which node a pod schedules on before binding the volume — `WaitForFirstConsumer` enforces this ordering.

## Node Labels

All cluster nodes are labeled at `up` time:

| Label | Values | Applied to |
|---|---|---|
| `type` | `db`, `app`, `control` | All nodes |
| `easydblab.com/node-ordinal` | `0`, `1`, `2`, … | `db` and `app` nodes |

Use `nodeSelector: type: db` (or `app`) in pod specs to constrain placement. The ordinal label is used by the PV pre-binding mechanism so each StatefulSet replica lands on the right node.

## Persistent Volumes

Per-kit PVs are created lazily at install time, not at cluster-up time. Run `platform create-pvs` before starting a stateful kit:

```bash
easy-db-lab platform create-pvs --kit clickhouse --size 100Gi
```

This creates one PV per db node with:
- **Path**: `/mnt/db1/<kit>` on each host
- **StorageClass**: `local-storage-wfc`
- **Node affinity**: `easydblab.com/node-ordinal=N` for deterministic binding
- **ClaimRef**: pre-bound to `<volumeClaimTemplateName>-<kit>-N`

The command is safe to re-run. If a PV exists with a stale claimRef (the PVC was deleted), the UID is cleared and the PV is returned to `Available`.

## `platform` Commands

### `platform create-pvs`

```
easy-db-lab platform create-pvs --kit <name> --size <Gi> [--node-type db|app] [--pvc-name <name>]
```

Creates one PV per node of the specified type. Defaults to `db` nodes. For stateless kits that need app-node storage, use `--node-type app`. `--pvc-name` sets the volumeClaimTemplate name to pre-bind against (default: `data`).

### `platform info`

```
easy-db-lab platform info
```

Displays StorageClasses, available PV counts per node pool, node selector labels, and the ordinal label key. Use this to verify substrate readiness before deploying a kit.

## Custom Templates

The `install` command can render templates from a custom directory:

```bash
easy-db-lab kit install --from ./my-kit/ --kit my-kit --size 50Gi
```

### Template Variable Contract

All templates receive these standard variables from cluster state:

| Variable | Description |
|---|---|
| `__CLUSTER_NAME__` | Cluster name |
| `__CONTROL_HOST__` | Control node public IP (alias for `__CONTROL_HOST_PUBLIC__`) |
| `__CONTROL_HOST_PUBLIC__` | Control node public IP |
| `__CONTROL_HOST_PRIVATE__` | Control node private IP (use for intra-cluster connectivity) |
| `__DB_NODE_COUNT__` | Number of database nodes |
| `__APP_NODE_COUNT__` | Number of app (stress) nodes |
| `__DB_NODE_IPS__` | Private IPs of database nodes |
| `__APP_NODE_IPS__` | Private IPs of app nodes |
| `__BUCKET_NAME__` | Per-cluster S3 bucket prefix |
| `__ACCOUNT_BUCKET__` | Account-level S3 bucket (survives cluster teardown) |
| `__REGION__` | AWS region |
| `__VPC_CIDR__` | VPC CIDR block |
| `__STORAGE_CLASS_WFC__` | `local-storage-wfc` |
| `__KIT_NAME__` | Kit name |
| `__STORAGE_SIZE__` | Storage size (e.g., `100Gi`) |
| `__KUBECONFIG__` | Path to local kubeconfig |
| `__EASY_DB_LAB_EXEC__` | Path to the easy-db-lab executable |
| `__RUNNING_KITS__` | Names of currently running kits |
| `__OPENSEARCH_ENDPOINT__` | OpenSearch domain endpoint, if provisioned |

Unresolved `__VAR__` placeholders emit a warning but do not fail the render.

### Template Directory Layout

```
my-kit/
├── kit.yaml
├── README.md.template
├── values.yaml.template
└── bin/
    ├── start.sh.template
    └── stop.sh.template
```

Files without `.template` suffix are copied verbatim.

### Profile Templates

Place templates in `~/.easy-db-lab/profiles/<profile>/kits/<name>/` to make them discoverable via `kit list`. Additional template directories can be registered with `kit source add`. Resolution priority: profile templates override additional sources, which override built-in templates of the same name.

## Port Exposure Model

Kits use **standard pod networking** (not `hostNetwork`). Client and metrics ports are surfaced on each EC2 instance's network interface in one of two ways:

- **NodePort services** — stateful db kits (ClickHouse, TiDB) expose their ports through a NodePort service, remapping native ports into the NodePort range (30000–32767). Example: ClickHouse HTTP `8123 → 30123`.
- **hostPort patches** — helm-based app kits (Presto, Trino) patch `hostPort` mappings onto the coordinator pod at start time, keeping the native port (e.g. `8080 → 8080`).

### Why ports must reach the host

The OTel collector DaemonSet runs with `hostNetwork: true` so it can scrape both host processes (Cassandra/MAAC at `localhost:9000`) and kit metrics endpoints. It scrapes each kit's declared metrics port at `localhost:<port>`, so that port must be reachable on every node's host network — which both NodePort (listens on all nodes) and hostPort provide. This also avoids conflicts with host processes: a NodePort-range port can never collide with a database listening on its native port on the host.

### Port Assignments

| Kit | Protocol | Native port | Node port | Exposure |
|---|---|---|---|---|
| ClickHouse | HTTP | 8123 | 30123 | NodePort |
| ClickHouse | Native TCP | 9000 | 30900 | NodePort |
| ClickHouse | MySQL wire | 9004 | 30904 | NodePort |
| ClickHouse | PostgreSQL wire | 9005 | 30905 | NodePort |
| ClickHouse | Prometheus | 9363 | 30936 | NodePort |
| TiDB | MySQL (SQL layer) | 4000 | 30400 | NodePort |
| TiDB | Prometheus (tidb-sql) | — | 31080 | NodePort |
| TiDB | Prometheus (tikv) | 20180 | — | pod SD (per-store) |
| TiDB | Prometheus (pd) | — | 32379 | NodePort |
| TiDB | Prometheus (tiflash) | — | 32234 | NodePort |
| Presto | HTTP (coordinator) | 8080 | 8080 | hostPort |
| Presto | Prometheus | 9090 | 9090 | hostPort |
| Trino | HTTP (coordinator) | 8080 | 8080 | hostPort |

When adding a new kit, choose ports that do not conflict with any host process or existing kit in the table above. Each kit's ports are declared in its `kit.yaml` (`metrics` and `endpoints` sections).

## Kit Observability

Each kit declares its metrics targets in `kit.yaml`. `metrics` is a list — kits with multiple components declare one entry per scrape target, each with a unique `job` name:

```yaml
metrics:
  - type: scrape     # Prometheus endpoint — OTel DaemonSet scrapes it at localhost:<port>
    port: 31080
    path: /metrics
    job: tidb-sql
  - type: scrape     # pod service discovery — each pod scraped directly, per-pod `instance`
    job: tikv
    pod-selector: "app.kubernetes.io/component=tikv,app.kubernetes.io/instance=tidb"
    port: 20180      # container metrics port, not a NodePort
    path: /metrics
```

If `job` is omitted, the kit name is used.

**Static NodePort vs. pod discovery.** By default a scrape target is a static `localhost:<port>`
NodePort — every collector scrapes it and `instance` is the collector's hostname. When a target
sets `pod-selector` (a comma-separated K8s label selector), the OTel collector instead uses
Prometheus pod service discovery (`kubernetes_sd_configs`, role: pod): each collector scrapes only
the matching pods co-located on its own node, and `instance` becomes the pod name. Use this when a
component has multiple pods behind one service and you need per-pod attribution — a NodePort
load-balances scrapes across all pods, so a single store/pod cannot be distinguished. TiKV uses this
so each of the 3 stores reports under its own `instance` (e.g. `tidb-tikv-0`).

Three modes are supported:

| Mode | How metrics reach the OTel collector |
|---|---|
| `scrape` | OTel DaemonSet scrapes a Prometheus endpoint via hostPort |
| `java-agent` | OTel Java agent inside the JVM pushes OTLP to `localhost:4317` |
| `helm-native` | Kit has built-in OTLP support configured via helm values |

### Metrics Registration Lifecycle

When a kit with `type: scrape` targets starts successfully, easy-db-lab:

1. Creates one ConfigMap `easydblab-metrics-<job>` per scrape target in the `default` namespace, labeled `easydblab.com/workload-metrics=true` and `easydblab.com/kit=<kit>`, containing the job name, port, and path.
2. Regenerates the OTel collector ConfigMap to include a Prometheus scrape job per target.
3. Applies the updated OTel ConfigMap so the running collector picks it up.

When the kit stops:

1. Deletes all of the kit's metrics ConfigMaps via the `easydblab.com/kit` label selector.
2. Regenerates and applies the OTel collector ConfigMap without the kit's scrape jobs.

This is fully automatic — no manual OTel configuration is required when starting or stopping kits.

## Verifying the Substrate

```bash
# Check StorageClasses and PV availability
easy-db-lab platform info

# List all nodes and their labels
kubectl get nodes --show-labels

# List PVs for a kit
kubectl get pv | grep clickhouse
```
