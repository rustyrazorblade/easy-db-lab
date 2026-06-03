# Platform Substrate Spec

## Overview

The platform substrate is the set of K8s primitives that easy-db-lab provisions on every cluster, allowing any kit to be deployed without a bespoke Kotlin manifest builder.

## StorageClasses

Two StorageClasses are provisioned at `up` time:

| Name | Binding Mode | Reclaim Policy | Use |
|---|---|---|---|
| `local-storage` | `Immediate` | `Retain` | Legacy / direct-bound PVs |
| `local-storage-wfc` | `WaitForFirstConsumer` | `Delete` | Per-kit PVs via `install` / `platform create-pvs` |

`local-storage-wfc` is required for StatefulSet kits because Kubernetes must know which node the pod schedules on before binding the PV.

## Node Labels

All cluster nodes are labeled at `up` time:

| Label | Values | Applied to |
|---|---|---|
| `type` | `db`, `app`, `control` | All nodes |
| `easydblab.com/node-ordinal` | `0`, `1`, `2`, … | `db` and `app` nodes |

Kits use `nodeSelector: type: db` (or `app`) to constrain placement, and PVs use `easydblab.com/node-ordinal` for pre-binding.

## Persistent Volumes

Per-kit PVs are created lazily at install time, not at cluster-up time. One PV per db node is created by `platform create-pvs`:

- **Path**: `/mnt/db1/<kit>` on each host
- **StorageClass**: `local-storage-wfc`
- **Affinity**: `easydblab.com/node-ordinal=N` matches ordinal N
- **ClaimRef**: pre-bound to `<volumeClaimTemplateName>-<kit>-N` so StatefulSets bind deterministically

## `platform` Commands

### `platform create-pvs`

```
platform create-pvs --kit <name> --size <Gi> [--node-type db|app]
```

Creates one PV per node of the specified type. Safe to re-run: if the PV exists with a stale claimRef UID (the PVC was deleted), the UID is cleared and the PV is returned to `Available`.

### `platform info`

Displays StorageClasses, available PV counts per node pool, node selector labels, and ordinal key. Used to verify substrate readiness before deploying a kit.

## Template Variable Contract

All install templates receive these standard variables from cluster state:

| Variable | Source |
|---|---|
| `__CLUSTER_NAME__` | `ClusterState.name` |
| `__CONTROL_HOST__` | Control node public IP (alias for `__CONTROL_HOST_PUBLIC__`) |
| `__CONTROL_HOST_PUBLIC__` | Control node public IP |
| `__CONTROL_HOST_PRIVATE__` | Control node private IP (for intra-cluster connectivity) |
| `__DB_NODE_COUNT__` | Count of `ServerType.Cassandra` hosts |
| `__APP_NODE_COUNT__` | Count of `ServerType.Stress` hosts |
| `__BUCKET_NAME__` | `ClusterState.dataBucket` (falls back to `s3Bucket`) |
| `__REGION__` | `InitConfig.region` |
| `__STORAGE_CLASS_WFC__` | `Constants.K8s.LOCAL_STORAGE_WFC_CLASS` |
| `__KIT_NAME__` | Kit name (e.g. `clickhouse`) |
| `__STORAGE_SIZE__` | `--size` flag value (e.g. `100Gi`) |
| `__KUBECONFIG__` | Path to local kubeconfig |

Kit-specific subcommands add extra variables (e.g. `__REPLICAS__`, `__WORKERS__`). Unresolved `__VAR__` placeholders emit a warning but do not fail the render.
