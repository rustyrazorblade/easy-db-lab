# Platform Substrate

The platform substrate is the set of Kubernetes primitives that easy-db-lab provisions on every cluster. It provides a stable foundation so any workload can be deployed without bespoke manifest code.

## Two-Layer Model

```
┌─────────────────────────────────────┐
│         Workload Layer              │
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
| `local-storage-wfc` | `WaitForFirstConsumer` | `Delete` | Per-workload PVs via `install` / `platform create-pvs` |

`local-storage-wfc` is used for StatefulSet workloads. Kubernetes must know which node a pod schedules on before binding the volume — `WaitForFirstConsumer` enforces this ordering.

## Node Labels

All cluster nodes are labeled at `up` time:

| Label | Values | Applied to |
|---|---|---|
| `type` | `db`, `app`, `control` | All nodes |
| `easydblab.com/node-ordinal` | `0`, `1`, `2`, … | `db` and `app` nodes |

Use `nodeSelector: type: db` (or `app`) in pod specs to constrain placement. The ordinal label is used by the PV pre-binding mechanism so each StatefulSet replica lands on the right node.

## Persistent Volumes

Per-workload PVs are created lazily at install time, not at cluster-up time. Run `platform create-pvs` before starting a stateful workload:

```bash
easy-db-lab platform create-pvs --workload clickhouse --size 100Gi
```

This creates one PV per db node with:
- **Path**: `/mnt/db1/<workload>` on each host
- **StorageClass**: `local-storage-wfc`
- **Node affinity**: `easydblab.com/node-ordinal=N` for deterministic binding
- **ClaimRef**: pre-bound to `<volumeClaimTemplateName>-<workload>-N`

The command is safe to re-run. If a PV exists with a stale claimRef (the PVC was deleted), the UID is cleared and the PV is returned to `Available`.

## `platform` Commands

### `platform create-pvs`

```
easy-db-lab platform create-pvs --workload <name> --size <Gi> [--node-type db|app]
```

Creates one PV per node of the specified type. Defaults to `db` nodes. For stateless workloads that need app-node storage, use `--node-type app`.

### `platform info`

```
easy-db-lab platform info
```

Displays StorageClasses, available PV counts per node pool, node selector labels, and the ordinal label key. Use this to verify substrate readiness before deploying a workload.

## Custom Templates

The `install` command can render templates from a custom directory:

```bash
easy-db-lab install --from ./my-workload/ --workload my-workload --size 50Gi
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
| `__BUCKET_NAME__` | S3 bucket name |
| `__REGION__` | AWS region |
| `__STORAGE_CLASS_WFC__` | `local-storage-wfc` |
| `__WORKLOAD_NAME__` | Workload name |
| `__STORAGE_SIZE__` | Storage size (e.g., `100Gi`) |
| `__KUBECONFIG__` | Path to local kubeconfig |

Unresolved `__VAR__` placeholders emit a warning but do not fail the render.

### Template Directory Layout

```
my-workload/
├── README.md.template
├── values.yaml.template
├── start.sh.template
└── stop.sh.template
```

Files without `.template` suffix are copied verbatim.

### Profile Templates

Place templates in `~/.easy-db-lab/profiles/<profile>/install/<name>/` to make them discoverable via `install --list`. Profile templates take priority over built-in templates with the same name.

## Verifying the Substrate

```bash
# Check StorageClasses and PV availability
easy-db-lab platform info

# List all nodes and their labels
kubectl get nodes --show-labels

# List PVs for a workload
kubectl get pv | grep clickhouse
```
