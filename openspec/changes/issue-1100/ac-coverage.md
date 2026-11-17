| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | WHEN a stateful kit starts on a cluster with a dynamic StorageClass THEN its claims bind, with the environment's default size | `kit-storage: A stateful kit binds on a dynamic StorageClass` | Pods need #1101 to schedule on an unlabelled cluster; tested on a labelled K3s |
| AC2 | WHEN the kit StorageClass reclaims with `Delete` THEN `init` fails naming the class and how to fix it | `kit-storage: A deleting class fails init` | **Not met at `init` yet.** `init` has no cluster API until #1082 (`init --kubeconfig`) lands, so the class is checked at `up` (before anything deploys) and in every `kit-storage` step instead. `KitStorageResolver` (named class, else the cluster default, else `NoDefaultKitStorageClass`) is ready for #1082 to call; only `up` calls it today, and only with a recorded class. |
| AC3 | WHEN a kit starts on AWS THEN its claims bind to local PVs exactly as today | `kit-storage: A kit starts on AWS exactly as today`; `platform-substrate: PVs created lazily at install time` | Class name changes to `edl-local-<kit>` |
| AC4 | WHEN a search runs over the kits THEN none names `local-path` or a fixed class | `kit-storage: No kit names a fixed storage class` | |
| Scope | Storage step reads the environment | `kit-storage: The step creates nothing on a provisioned class`; `platform-substrate: Per-kit class created at kit install on owned nodes` | |
| Scope | Per-environment default size, overridable per kit and per init | `kit-storage: The kit option wins`; `The init setting overrides the environment default`; `A bad size is refused at init` | |
| Scope | TiDB and Kafka use the environment class | `kit-storage: TiDB and Kafka take the environment's class` | |
| Scope | Refuse non-Retain kit class | `kit-storage: A deleting class fails init`; `A class swapped after init is refused at kit install`; `A missing class is reported as missing`; `No default class and no flag` | |
| Risk | Losing per-kit reservation without selectors | `kit-storage: Two kits never take each other's volumes` | |
| Risk | Two PVs of one kit on one node share a directory (TiDB) | `kit-storage: One kit holds two volumes on one node` | |
| Risk | ignite3 claim lands on a `Delete` default class | `kit-storage: Kit claims follow the environment's kit storage` (ignite3 listed) | D8 |
| Risk | TiDB `pvReclaimPolicy: Delete` overrides Retain | `kit-storage: TiDB and Kafka take the environment's class` | |
| Risk | Kafka `deleteClaim: true` leaves Released PVs | task 6.5 | D10 decided (a): `deleteClaim: false`; task 6.5 is the proof |
| Risk | Operators that delete claims on stop (CNPG, Altinity) strand data on Released PVs of a provisioned class | `kit-storage: postgres data survives stop and start on a provisioned class` | `rebind-released` (task 6.7) |
| Risk | Root-owned dynamic volumes vs non-root images | task 5.6, `A stateful kit binds on a dynamic StorageClass` | Checked per kit in implementation |
| Excluded | Pod placement off labelled nodes | — | #1101 |
