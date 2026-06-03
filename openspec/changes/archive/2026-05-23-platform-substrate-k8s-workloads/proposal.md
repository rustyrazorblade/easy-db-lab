## Why

Today every new K8s workload requires a bespoke Kotlin manifest builder (`ClickHouseManifestBuilder` is ~670 lines). This prevents users from deploying workloads not yet supported by easy-db-lab — including private databases from consulting engagements that can never be added to an open-source project. A platform-substrate model lets easy-db-lab provide K8s primitives (StorageClass, local PVs, node labels) that any workload can target, with per-workload complexity owned by upstream operators and Helm charts.

## What Changes

- **New `platform` command group**: `platform create-pvs` (create per-workload local PVs) and `platform info` (show substrate details: StorageClasses, node selectors, available PVs).
- **New `install` command group**: scaffolds a workload — renders templates, calls `platform create-pvs` — and writes artifacts (`start.sh`, `stop.sh`, `values.yaml`, `README.md`) to `./<workload>/`. Built-in subcommands: `install clickhouse`, `install presto`. Custom workloads: `install --from ./template-dir/ --workload name`.
- **New `cleanup <workload>` command**: opt-in disk wipe via K8s Job per db node.
- **New StorageClass `local-storage-wfc`** (`WaitForFirstConsumer` + `Delete` reclaim) provisioned at `up` time alongside existing `local-storage`.
- **Node ordinal labeling extended** to app (`ServerType.Stress`) nodes in addition to existing db nodes.
- **Container updated**: Jib config adds `helm` and `kubectl` binaries so generated scripts run without external prerequisites.
- **`status` workloads section**: queries K8s directly for pod placement and readiness.
- **Existing `clickhouse` commands unchanged**: `clickhouse start/stop/status/backup/restore` are left in place.

## Capabilities

### New Capabilities

- `platform-substrate`: The K8s substrate contract — StorageClass `local-storage-wfc`, per-workload local PVs at `/mnt/db1/<workload>`, node labels (`type=db`/`type=app`/`type=control`, `easydblab.com/node-ordinal=N`) — that workloads target via `nodeSelector` and `storageClassName`.
- `install-command`: The `install` command group that scaffolds workload artifacts (rendered templates + PV creation) into `./<workload>/`. Includes built-in subcommands and the `--from` custom template path.

### Modified Capabilities

- `instance-storage-validation`: PVs are now workload-scoped and created lazily at install time (not pre-provisioned at `up`). The spec's assumption that db nodes always have storage from cluster-up time needs clarification.

## Impact

- `commands/Up.kt`: extend `labelDbNodesWithOrdinals` to cover app nodes; call `ensureLocalStorageWfcClass` at `up` time.
- `services/DefaultK8sStorageOperations.kt`: add `ensureLocalStorageWfcClass` alongside existing `ensureLocalStorageClass`.
- New command packages: `commands/install/`, `commands/platform/`, `commands/Cleanup.kt`.
- New template resources: `install/clickhouse/`, `install/presto/`.
- Jib build config (`build.gradle.kts` or `jib` config): add helm and kubectl layer.
- `commands/Status.kt`: add workloads section via K8s pod query.
- Docs: new `docs/platform-substrate.md`, `docs/install-clickhouse.md`, `docs/install-presto.md`.
- New spec: `openspec/specs/platform-substrate/spec.md`, `openspec/specs/install-command/spec.md`.
- Updated spec: `openspec/specs/instance-storage-validation/spec.md`.
