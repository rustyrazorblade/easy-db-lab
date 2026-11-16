## Why

Part of #1032 (Kubernetes-forward epic), phase A. Sub-issue of #1055 (kit target contract). Design: `docs/plans/2026-11-11-kubernetes-forward-architecture.md`, section "Kit target contract".

Six stateful kits bind their claims to easy-db-lab's own local PVs with a label `selector` (cassandra-pods, neo4j, postgres, clickhouse, kafka) or a `volumeName` (memcached). Kubernetes never dynamically provisions a claim that has a selector or a volume name, so on a StorageClass the user brings these kits stay Pending. TiDB hard-codes K3s `local-path`, and ignite3 names no class at all, so it takes the cluster default, which is usually `Delete` and would remove kit data when a claim goes (owner-data rule). The local PVs are created by an SSH `mkdir` on each node, which only owned nodes allow.

`10Ti` defaults are correct on `local-storage`, which does not enforce capacity. A dynamic provisioner (EBS, PD, Azure Disk) allocates and bills the declared size, so on those classes the size must be real.

## What Changes

- **One claim shape for every environment.** Every kit claim names `__KIT_STORAGE_CLASS__` and requests `__STORAGE_SIZE__`. No claim has a `selector` or a `volumeName`.
- **`KitStorage`, resolved from state.** `LocalVolumes(defaultSize = 10Ti)` on owned nodes; `Provisioned(storageClass, defaultSize)` elsewhere. Recorded by `init` as `kitStorageClass` / `kitStorageSize` on `InitConfig`; null reads as `LocalVolumes`, which is AWS today.
- **Per-kit local class on owned nodes.** The step creates a no-provisioner, `WaitForFirstConsumer`, `Retain` StorageClass `edl-local-<kit>` and that kit's local PVs of that class. A claim can bind only its own kit's PVs, which is what the selector did. The PV path becomes `/mnt/db1/<kit>/<claim template>`, so one node can hold two PVs of one kit (TiDB TiKV and TiFlash).
- **`kit-storage` / `kit-storage-delete` steps** replace `platform-pvs` / `platform-pvs-delete`. On `Provisioned` the step creates nothing and only checks the class; the delete step is a no-op there and never touches claims or volumes.
- **Size precedence:** the kit's named option (`--storage-size`) > `init --kit-storage-size` > the environment default (`10Ti` for `LocalVolumes`, `Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE` for `Provisioned`). Every kit size arg defaults to `${KIT_STORAGE_SIZE}`.
- **TiDB, Kafka, memcached, ignite3** use the environment's class. TiDB gets `kit-storage` steps, a size arg and `pvReclaimPolicy: Retain` (today `Delete`, which overrides a Retain class). Kafka drops its literal `local-storage-wfc` and sets `deleteClaim: false`. memcached drops `volumeName` and its literal `10Ti`. ignite3 gets a class, a step and a size arg.
- **Retain check.** `RetainedClaimCheck` is generalized to `StorageClassRetainCheck`. A kit StorageClass that does not reclaim with `Retain`, or does not exist, is refused, naming the class and the fix — at `init` when a kubeconfig is reachable, at `up` when `--kit-storage-class` was given, and in the `kit-storage` step. `LocalVolumes` is exempt (its class and PVs are easy-db-lab's own and `Retain`).
- **Guard test** over the packaged kit resources: no kit names `local-path`, `local-storage`, `STORAGE_CLASS_WFC` or any fixed class; no claim has a selector or a volume name.
- **Removed:** `local-storage-wfc`, `local-storage`, `STORAGE_CLASS_WFC` and their `up` steps (D9).

## Capabilities

### New Capabilities
- `kit-storage`: what kit claims are made from in each environment, their size, and the Retain refusal.

### Modified Capabilities
- `platform-substrate`: StorageClasses, Persistent Volumes, `platform` commands and the template variable contract follow `KitStorage`.

## Impact

- **On AWS:** claims bind to local PVs on NVMe as today, at the same sizes. The class name changes from `local-storage-wfc` to `edl-local-<kit>`. TiDB moves from `local-path` on the root EBS to local PVs on `/mnt/db1` (better, but a different medium; recorded in the kit docs).
- **Off owned nodes:** stateful kits bind on the environment's retained class at a real size. Pods still need #1101 (role placement) to schedule on an unlabelled cluster.
- Specs touched: `platform-substrate`, plus kit specs that name the class (`kafka-kit`, `tidb`, `memcached-kit`, `ignite3`) during implementation. Docs: `docs/user-guide/platform-substrate.md`, `docs/development/kits.md`, kit user guides, `docs/reference/commands.md`, `configuration/CLAUDE.md`, `create-kit` guidance.

## Out of scope

- Changing AWS sizes; `10Ti` on local volumes stays.
- Role-to-node placement, hostPath, hostPort, hostNetwork (#1101).
- Observability backend storage (#1091 / #1035).
- `init --kubeconfig` wiring itself (#1082); this change ships the resolver and the `up` and step-time checks, and plugs into #1082's init path.
