## MODIFIED Requirements

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
| `__DB_NODE_ALLOCATABLE_MIB__` | The smallest allocatable memory, in MiB, of the `type=db` nodes, read from the Kubernetes API |
| `__DB_NODE_ALLOCATABLE_CPU__` | The smallest allocatable CPU count, as a whole number, of the `type=db` nodes, read from the Kubernetes API |
| `__GUARANTEED_PODS__` | `true` when the environment asks for Guaranteed pods (owned nodes), else `false` |
| `__KIT_NAME__` | Kit name (e.g. `clickhouse`) |
| `__STORAGE_SIZE__` | The kit's `--storage-size` value, defaulting to `${KIT_STORAGE_SIZE}` |
| `__KUBECONFIG__` | Path to local kubeconfig |

The same values SHALL reach kit scripts as the environment variables `DB_NODE_ALLOCATABLE_MIB`, `DB_NODE_ALLOCATABLE_CPU` and `GUARANTEED_PODS`, so a kit arg can default to `${DB_NODE_ALLOCATABLE_MIB}`. `__STORAGE_CLASS_WFC__` SHALL NOT exist. Kit-specific subcommands add extra variables (e.g. `__REPLICAS__`, `__WORKERS__`). Unresolved `__VAR__` placeholders emit a warning but do not fail the render.

#### Scenario: Standard variables substituted into templates
- **WHEN** an install template is rendered
- **THEN** the standard variables are substituted from cluster state
- **AND** an unresolved `__VAR__` placeholder emits a warning but does not fail the render

#### Scenario: Kit storage variables follow the environment
- **WHEN** a kit template is rendered with no recorded kit StorageClass
- **THEN** `__KIT_STORAGE_CLASS__` is `edl-local-<kit>` and `__KIT_STORAGE_SIZE__` is `10Ti`
- **WHEN** it is rendered with a recorded kit StorageClass `retain-x`
- **THEN** `__KIT_STORAGE_CLASS__` is `retain-x` and `__KIT_STORAGE_SIZE__` is the provisioned default

#### Scenario: Placement variables follow the db nodes
- **WHEN** a kit is rendered on an owned cluster whose db nodes report 30000 MiB and 31000 MiB allocatable memory and 8 allocatable CPUs each
- **THEN** `__DB_NODE_ALLOCATABLE_MIB__` is `30000`, `__DB_NODE_ALLOCATABLE_CPU__` is `8`, and `__GUARANTEED_PODS__` is `true`

#### Scenario: Not Guaranteed off owned nodes
- **WHEN** a kit is rendered on a local or bring-your-own Kubernetes cluster
- **THEN** `__GUARANTEED_PODS__` is `false`
