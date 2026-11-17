# Platform Substrate Spec

## Purpose

The platform substrate is the set of K8s primitives that easy-db-lab provisions on every cluster, allowing any kit to be deployed without a bespoke Kotlin manifest builder.
## Requirements
### Requirement: StorageClasses

The system SHALL NOT provision a shared kit StorageClass at `up` time. On owned nodes each kit's `kit-storage` step SHALL create that kit's class `edl-local-<kit>`:

| Name | Provisioner | Binding Mode | Reclaim Policy | Use |
|---|---|---|---|---|
| `edl-local-<kit>` | `kubernetes.io/no-provisioner` | `WaitForFirstConsumer` | `Retain` | that kit's local PVs only |

Elsewhere kit claims use the recorded kit StorageClass (see `kit-storage`), which easy-db-lab never creates or deletes.

#### Scenario: Per-kit class created at kit install on owned nodes
- **WHEN** a stateful kit is installed on an owned-node cluster
- **THEN** the StorageClass `edl-local-<kit>` exists with `WaitForFirstConsumer` binding and `Retain` reclaim
- **AND** `up` creates no StorageClass for kits

### Requirement: Node Labels

The system SHALL label all cluster nodes at `up` time:

| Label | Values | Applied to |
|---|---|---|
| `type` | `db`, `app`, `control` | All nodes |
| `easydblab.com/node-ordinal` | `0`, `1`, `2`, … | `db` and `app` nodes |

Kits use `nodeSelector: type: db` (or `app`) to constrain placement, and local PVs match `type` and `easydblab.com/node-ordinal` in their node affinity.

#### Scenario: Nodes labeled at up time
- **WHEN** a cluster is brought up
- **THEN** all nodes carry a `type` label of `db`, `app`, or `control`
- **AND** `db` and `app` nodes carry an `easydblab.com/node-ordinal` label starting at `0`

### Requirement: Persistent Volumes

Per-kit PVs SHALL be created lazily by the `kit-storage` step at install time, and only under `LocalVolumes`. One PV per node of the step's node type (or `count` PVs) is created:

- **Path**: `/mnt/db1/<kit>/<claim template>` on each host
- **StorageClass**: `edl-local-<kit>`
- **Reclaim policy**: `Retain`
- **Affinity**: `type=<node type>` and `easydblab.com/node-ordinal=N`, so PV N binds only on node N of its pool

PVs SHALL NOT be pre-bound by `claimRef`; reservation comes from the per-kit class.

#### Scenario: PVs created lazily at install time
- **WHEN** the `kit-storage` step runs for a kit on owned nodes
- **THEN** one PV per node is created at `/mnt/db1/<kit>/<claim template>` with StorageClass `edl-local-<kit>` and `Retain` reclaim
- **AND** each PV has affinity `type=<node type>` and `easydblab.com/node-ordinal=N`

### Requirement: `platform` Commands

The `platform` command group SHALL provide `create-pvs` and `info` subcommands.

#### `platform create-pvs`

```
platform create-pvs --kit <name> --size <quantity> [--node-type db|app] [--pvc-name <name>]
```

Runs the same provisioning as the `kit-storage` step. Under `LocalVolumes` it creates the kit's class and one PV per node of the specified type (default `db`) for the claim template `--pvc-name` (default `data`), at `/mnt/db1/<kit>/<pvc-name>`, and is safe to re-run: a PV with a stale claimRef UID has the UID cleared and returns to `Available`. Under `Provisioned` it checks the class and creates nothing.

#### `platform info`

Displays the resolved kit storage (mode, class, default size), available PV counts per node pool under `LocalVolumes`, node selector labels, and the ordinal key.

#### Scenario: create-pvs is safe to re-run
- **WHEN** `platform create-pvs` runs against a PV that exists with a stale claimRef UID because the PVC was deleted
- **THEN** the UID is cleared and the PV is returned to `Available`

#### Scenario: platform info reports kit storage
- **WHEN** the user runs `platform info`
- **THEN** the output shows the kit storage mode, its class and default size
- **AND** under `LocalVolumes`, available PV counts per node pool, node selector labels and the ordinal key

### Requirement: Template Variable Contract

All install templates SHALL receive these standard variables from cluster state:

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
| `__KIT_STORAGE_CLASS__` | `KitStorage.storageClassFor(kit)`: `edl-local-<kit>` or the recorded kit StorageClass; blank when no kit is named |
| `__KIT_STORAGE_SIZE__` | `KitStorage.defaultSize`: `init --kit-storage-size`, else `10Ti` (local) or the provisioned default |
| `__KIT_NAME__` | Kit name (e.g. `clickhouse`) |
| `__STORAGE_SIZE__` | The kit's `--storage-size` value, defaulting to `${KIT_STORAGE_SIZE}` |
| `__KUBECONFIG__` | Path to local kubeconfig |

`__STORAGE_CLASS_WFC__` SHALL NOT exist. Kit-specific subcommands add extra variables (e.g. `__REPLICAS__`, `__WORKERS__`). Unresolved `__VAR__` placeholders emit a warning but do not fail the render.

#### Scenario: Standard variables substituted into templates
- **WHEN** an install template is rendered
- **THEN** the standard variables are substituted from cluster state
- **AND** an unresolved `__VAR__` placeholder emits a warning but does not fail the render

#### Scenario: Kit storage variables follow the environment
- **WHEN** a kit template is rendered with no recorded kit StorageClass
- **THEN** `__KIT_STORAGE_CLASS__` is `edl-local-<kit>` and `__KIT_STORAGE_SIZE__` is `10Ti`
- **WHEN** it is rendered with a recorded kit StorageClass `retain-x`
- **THEN** `__KIT_STORAGE_CLASS__` is `retain-x` and `__KIT_STORAGE_SIZE__` is the provisioned default

