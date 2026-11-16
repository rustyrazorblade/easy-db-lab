## Why

The K3s server keeps cluster state in its default datastore: kine in front of one sqlite file (`/mnt/db1/k3s/server/db/state.db`).  sqlite takes one writer at a time, and kine polls it to produce watch events.  On a 79-node run (#1134), datastore writes took 0.4-1.0 s each (node creates about 1.0 s) while the agents registered.  The apiserver's priority and fairness rejected thousands of requests with 429 (node-high 2027, global-default 596) while the control node was 85-95% idle.  The Cilium operator could not create its CRDs, and `up` stalled for 10 minutes until k3s was restarted by hand.  Embedded etcd is K3s's other built-in datastore.  It is the datastore Kubernetes is built and tested against, it has no single-writer lock, and it serves watches natively.

## What Changes

- The K3s server starts with embedded etcd (`cluster-init: true`) instead of kine/sqlite.  It is still one server; this is not high availability.
- The datastore settings go in a K3s config drop-in, `/etc/rancher/k3s/config.yaml.d/20-datastore.yaml`, written by `start-k3s-server.sh` on every run, as the kubelet eviction drop-in is today.  The agent script writes no datastore settings.
- The etcd backend quota is raised from etcd's 2 GiB default to 8 GiB, so a large cluster does not hit etcd's `NOSPACE` alarm, which makes the apiserver refuse every write.
- K3s's scheduled etcd snapshots stay at their defaults (twice a day, the five newest kept, under `/mnt/db1/k3s/server/db/snapshots`).  The design states that these are K3s's internal files and not owner data.
- etcd data stays on the control node's data disk through the existing `/var/lib/rancher/k3s` link to `/mnt/db1/k3s`: `/mnt/db1/k3s/server/db/etcd`.  No `--data-dir`.
- `start-k3s-server.sh` fails, naming the reason, when `/mnt/db1` is not a mount point.  Today it puts the K3s data on the root volume in that case.  An agent with no data disk keeps today's behavior.
- Tests: the start-script integration test covers the datastore drop-in and the new failure; a new K3s TestContainers test boots K3s with the real drop-in and proves etcd is the datastore.
- Docs: `docs/user-guide/kubernetes.md` and `docs/user-guide/large-clusters.md` describe the datastore, where its data and snapshots live, and how to check it.
- Specs: `cluster-lifecycle` gains a requirement for the K3s server datastore.  `instance-storage-validation` is corrected: it says control and app nodes need no data disk, but the code (`DataDiskRequirement`) already requires one, and the etcd data depends on it.
- Real AWS verification: an 80-node run (76 db i4i.xlarge, 2 app c6id.4xlarge) that compares datastore write latency and apiserver 429 counts with the #1134 run, and a small-cluster regression run of kits, observability, backup and restore, and `down`.

No AMI bake: the AMI already has the K3s binary, and the flags are applied at runtime.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `cluster-lifecycle`: a new requirement, "K3s server runs on embedded etcd on the data disk", under Infrastructure Provisioning's K3s deployment.
- `instance-storage-validation`: the storage validation requirement covers control and app nodes as the code does, and names the K3s datastore among the reasons.

## Impact

- `src/main/resources/com/rustyrazorblade/easydblab/services/start-k3s-server.sh` (the only product file with a behavior change).
- `src/integrationTest/kotlin/com/rustyrazorblade/easydblab/services/StartK3sScriptsIntegrationTest.kt`; a new K3s TestContainers test in `src/integrationTest/`.
- `Constants.K3s` gains the etcd quota and the drop-in path, if the Kotlin test or code names them.
- Docs and specs listed above.
- Out of scope: more than one server, an external datastore, the join limit, the control-plane gate and health check, and node replacement (all #1158).
