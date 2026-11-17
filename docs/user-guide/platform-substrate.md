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
│ Node Labels · Kit Storage (per kit) │
│  labels at `up`, storage per kit    │
└─────────────────────────────────────┘
```

The platform substrate is provisioned automatically when you run `easy-db-lab up`. You do not need to configure it manually.

## Kit Storage

A kit's claims never name a fixed StorageClass. Every claim names `__KIT_STORAGE_CLASS__` and
requests `__STORAGE_SIZE__`, with no selector and no PV name, and the environment decides what
those resolve to. `init` records the choice:

| `init` options | Kit storage | Class a kit's claims name | Default size |
|---|---|---|---|
| none (AWS, owned nodes) | Local volumes on `/mnt/db1` | `edl-local-<kit>`, one per kit | `10Ti` |
| `--kit-storage-class <name>` | The cluster provisions volumes | `<name>` | `100Gi` |

`--kit-storage-size <quantity>` (e.g. `500Gi`) replaces either default, and a kit's own
`--storage-size` replaces both. A size that is not a positive Kubernetes quantity is refused at
`init`. On local volumes the size is metadata only: the local provisioner does not enforce it.

On local volumes `up` creates no StorageClass. Each kit's `kit-storage` step creates the kit's
own class:

| Name | Provisioner | Binding Mode | Reclaim Policy |
|---|---|---|---|
| `edl-local-<kit>` | `kubernetes.io/no-provisioner` | `WaitForFirstConsumer` | `Retain` |

The class is labelled `app.kubernetes.io/managed-by=easy-db-lab` and
`easydblab/cluster=<cluster id>`. Because only that kit's PVs carry its class, a kit's claim can
only bind its own kit's PVs. `WaitForFirstConsumer` makes Kubernetes wait until it knows which
node a pod schedules on before it binds the volume.

If a class named `edl-local-<kit>` already exists, the kit reuses it only when it has exactly that
provisioner, binding mode and reclaim policy. Otherwise the kit's start fails, naming the fields
that differ. Delete the class (`kubectl delete storageclass edl-local-<kit>`) and start again: the
kit's PVs and their data are kept.

### A provisioned class must retain

A class whose `reclaimPolicy` is `Delete` (the Kubernetes default, and the default of most cloud
classes such as `gp2`) deletes a volume, and so the kit's data, when its claim is deleted. The
class is therefore checked before any claim exists, at `up` and again by every kit's
`kit-storage` step. A class that deletes, has no policy, or does not exist is refused. Copy the
class with `reclaimPolicy: Retain` and pass that copy to `--kit-storage-class`. easy-db-lab never
creates or deletes a provisioned class, its claims' volumes, or the cluster's default class.
A refusal is also reported as a `Setup.KitStorageClassRefused` event, from `up`, a kit's
`kit-storage` step and `platform create-pvs` alike.

### Stop and start on a provisioned class

postgres and clickhouse `stop` delete the CNPG `Cluster` or the `ClickHouseInstallation`, and the
operator deletes the claims with it. Their `Retain` volumes stay, in the `Released` phase, holding
the data. The next `start` reopens them before it applies the resource again. Its `kit-storage`
step (`rebind-released: true`) clears the deleted claim's UID from each Released volume of the
class whose claim was in the kit namespace, so the claim of the same name that the operator creates
binds that volume, not a new empty one. A claim of any other name cannot take it. Kafka, TiDB and
the StatefulSet kits keep their claims across `stop` and need no reopening.


## Node Labels

All cluster nodes are labeled at `up` time:

| Label | Values | Applied to |
|---|---|---|
| `type` | `db`, `app`, `control` | All nodes |
| `easydblab.com/node-ordinal` | `0`, `1`, `2`, … | `db` and `app` nodes |

Use `nodeSelector: type: db` (or `app`) in pod specs to constrain placement. Each local PV's node affinity names an ordinal, so PV N can only bind on node N of its pool.

## Persistent Volumes

On local volumes, a kit's PVs are created lazily by its `kit-storage` step, at install or start,
not at cluster-up time. A built-in kit does this itself. `platform create-pvs` runs the same
provisioning by hand:

```bash
easy-db-lab platform create-pvs --kit clickhouse --size 100Gi
```

This creates the class `edl-local-<kit>` and one PV per db node with:
- **Path**: `/mnt/db1/<kit>/<claim template>` on each host, so two claim templates of one kit on
  one node (TiDB's TiKV and TiFlash) keep separate directories
- **StorageClass**: `edl-local-<kit>`
- **Reclaim policy**: `Retain`
- **Node affinity**: `type=<node-type>` and `easydblab.com/node-ordinal=N` for deterministic binding.
  Both pools carry ordinals, so the node type keeps a db PV off the app node with the same ordinal.
PVs are not pre-bound by `claimRef`; the per-kit class is what reserves them.

The command is safe to re-run. If a PV exists with a stale claimRef (the PVC was deleted), the UID is cleared and the PV is returned to `Available`.

## `platform` Commands

### `platform create-pvs`

```
easy-db-lab platform create-pvs --kit <name> --size <Gi> [--node-type db|app] [--pvc-name <name>]
```

Creates the kit's class and one PV per node of the specified type. Defaults to `db` nodes; use `--node-type app` for app-node storage. `--pvc-name` sets the claim template the PVs serve, which names them `<pvc-name>-<kit>-N` and their directory `/mnt/db1/<kit>/<pvc-name>` (default: `data`). On a cluster with a provisioned kit StorageClass the cluster makes the volumes, so it creates nothing: it only checks that the class reclaims with `Retain`, and fails, naming the class, when it would delete the kit's data.

### `platform info`

```
easy-db-lab platform info
```

Displays the kit storage (local volumes or the provisioned class, and the default size), available PV counts per node pool, node selector labels, and the ordinal label key. Use this to verify substrate readiness before deploying a kit.

### `platform cni`

```
easy-db-lab platform cni
```

Shows the pod-network datapath. On a Cilium cluster it reads the `cilium-config` ConfigMap and the `CiliumNode` objects from the control node and prints the routing mode, IPAM mode, kube-proxy replacement, masquerade interfaces, native routing CIDR, the Hubble UI URL, and per node the ENI count, subnet CIDRs, and IPs allocated, used, and available. On a Flannel cluster it prints one line that names Flannel. Cilium is the default datapath; `init --cni=flannel` selects Flannel. Read-only. See [Pod Networking (CNI)](networking.md).

## Custom Templates

The `install` command can render templates from a custom directory:

```bash
easy-db-lab kit install --from ./my-kit/ --kit my-kit --size 50Gi
```

The directory must contain a `kit.yaml`. Without it the install is rejected before anything is
written to the workspace, because `kit.yaml` is what marks an installed directory as a kit.

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
| `__KIT_STORAGE_CLASS__` | The class every kit claim names: `edl-local-<kit>`, or the `init --kit-storage-class` class |
| `__KIT_STORAGE_SIZE__` | The environment's default claim size: `init --kit-storage-size`, else `10Ti` or `100Gi` |
| `__KIT_NAME__` | Kit name |
| `__STORAGE_SIZE__` | The kit's `--storage-size` (e.g. `100Gi`); a kit declares it with default `${KIT_STORAGE_SIZE}` |
| `__KUBECONFIG__` | Path to local kubeconfig |
| `__EASY_DB_LAB_EXEC__` | Path to the easy-db-lab executable |
| `__RUNNING_KITS__` | Names of currently running kits |
| `__OPENSEARCH_ENDPOINT__` | OpenSearch domain endpoint, if provisioned |
| `__PYROSCOPE_URL__` | Where a kit's Pyroscope agent ships profiles: the Pyroscope Service (`http://pyroscope.default.svc:4040`), or the external stack on a telemetry-redirect cluster |
| `__TENANT__` | The cluster's observability tenant; send it as `-Dpyroscope.tenant.id` (it becomes `X-Scope-OrgID`) |

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

Kits use **standard pod networking** (not `hostNetwork`). Client and metrics ports are surfaced on every node through a NodePort Service:

- **NodePort services** — every kit (ClickHouse, TiDB, Presto, Trino and the rest) exposes its ports through a NodePort service, remapping native ports into the NodePort range (30000–32767). Example: ClickHouse HTTP `8123 → 30123`.

### Why ports must reach the host

The OTel collector DaemonSet runs with `hostNetwork: true` so it can scrape host processes and kit metrics endpoints, and so host JVMs such as Cassandra can push OTLP to it at `localhost:4318`. Built-in kits are scraped by pod discovery: each collector scrapes the kit pods on its own node directly, on the pod IP and container metrics port, so a metrics port does not need to reach the host for the collector (see [Kit Observability](#kit-observability)). The NodePorts that still expose metrics ports are there for manual inspection (`curl <node-ip>:<port>/metrics`). Client ports do need to reach the host, and a NodePort-range port can never collide with a database listening on its native port on the host.

### Port Assignments

| Kit | Protocol | Native port | Node port | Exposure |
|---|---|---|---|---|
| ClickHouse | HTTP | 8123 | 30123 | NodePort |
| ClickHouse | Native TCP | 9000 | 30900 | NodePort |
| ClickHouse | MySQL wire | 9004 | 30904 | NodePort |
| ClickHouse | PostgreSQL wire | 9005 | 30905 | NodePort |
| ClickHouse | Prometheus | 9363 | 30936 | pod SD (per-replica); NodePort also exposed |
| TiDB | MySQL (SQL layer) | 4000 | 30400 | NodePort |
| TiDB | Prometheus (tidb-sql) | 10080 | 31080 | pod SD (per-pod); NodePort also exposed |
| TiDB | Prometheus (tikv) | 20180 | — | pod SD (per-store) |
| TiDB | Prometheus (pd) | 2379 | 32379 | pod SD (per-pod); NodePort also exposed |
| TiDB | Prometheus (tiflash) | 8234 | 32234 | pod SD (per-pod); NodePort also exposed |
| Presto | HTTP (coordinator) | 8080 | 30882 | NodePort |
| Presto | Prometheus | 9090 | — | pod SD |
| Trino | HTTP (coordinator) | 8080 | 30881 | NodePort |

When adding a new kit, choose ports that do not conflict with any host process or existing kit in the table above. Each kit's ports are declared in its `kit.yaml` (`metrics` and `endpoints` sections).

## Kit Observability

Each kit declares its metrics targets in `kit.yaml`. `metrics` is a list — kits with multiple components declare one entry per scrape target, each with a unique `job` name:

```yaml
metrics:
  - type: scrape     # pod service discovery — each pod scraped directly, per-pod `instance`
    job: tidb-sql
    pod-selector: "app.kubernetes.io/component=tidb,app.kubernetes.io/instance=tidb"
    port: 10080      # container metrics port, not a NodePort
    path: /metrics
  - type: scrape
    job: tikv
    pod-selector: "app.kubernetes.io/component=tikv,app.kubernetes.io/instance=tidb"
    port: 20180
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
so each of the 3 stores reports under its own `instance` (e.g. `tidb-tikv-0`). Every built-in kit
uses pod discovery: a static job runs on every collector, so even a single-instance kit behind a
NodePort reports one duplicate series per node.

Three modes are supported:

| Mode | How metrics reach the OTel collector |
|---|---|
| `scrape` | OTel DaemonSet scrapes a Prometheus endpoint via hostPort |
| `java-agent` | OTel Java agent inside the JVM pushes OTLP to `localhost:4317` |
| `helm-native` | Kit has built-in OTLP support configured via helm values |

### Metrics Registration Lifecycle

When a kit with `type: scrape` targets starts successfully, easy-db-lab:

1. Creates one ConfigMap `easydblab-metrics-<kit>-<job>` per scrape target in the `default` namespace, labeled `easydblab.com/workload-metrics=true` and `easydblab.com/kit=<kit>`, containing the job name, port, and path.
2. Regenerates the OTel collector ConfigMap to include a Prometheus scrape job per target.
3. Applies the updated OTel ConfigMap so the running collector picks it up.

When the kit stops:

1. Deletes all of the kit's metrics ConfigMaps via the `easydblab.com/kit` label selector.
2. Regenerates and applies the OTel collector ConfigMap without the kit's scrape jobs.

This is fully automatic — no manual OTel configuration is required when starting or stopping kits.

## Verifying the Substrate

```bash
# Check kit storage and PV availability
easy-db-lab platform info

# List all nodes and their labels
kubectl get nodes --show-labels

# List PVs for a kit
kubectl get pv | grep clickhouse
```
