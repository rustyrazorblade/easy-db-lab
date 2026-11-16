## Context

`up` starts the K3s server on the control node with `start-k3s-server.sh` (packaged resource, run over SSH by `DefaultK3sService.startServer`).  The script:

- links `/var/lib/rancher/k3s` (K3s's data dir) to `/mnt/db1/k3s` when `/mnt/db1` is a mount point, and otherwise leaves it on the root volume;
- writes `/etc/rancher/k3s/config.yaml.d/10-kubelet-eviction.yaml` on every run;
- runs the airgap installer with `INSTALL_K3S_EXEC` only when `k3s.service` is not installed yet;
- adds `RequiresMountsFor=/mnt/db1` to `k3s.service` and runs `systemctl start k3s`.

It sets no datastore option, so K3s uses kine over sqlite at `/mnt/db1/k3s/server/db/state.db`.

Nothing else in the code names the datastore.  The places that touch the server's files or process:

- `Constants.K3s.NODE_TOKEN_PATH` (`/var/lib/rancher/k3s/server/node-token`) and `REMOTE_KUBECONFIG` (`/etc/rancher/k3s/k3s.yaml`).  Both are the same with etcd.
- `DefaultK3sService` (`AbstractSystemDServiceManager("k3s")`): start, stop, restart and status through systemd.
- `ClusterBackupService` backs up workspace files only (`state.json`, `kubeconfig`, `k8s/`, scripts).  It has never backed up the K3s datastore.
- `down` flushes the observability backends, then terminates the instances.  It never reads or writes K3s's data dir.  The datastore goes with the control node, as the sqlite file does today.
- `setup_instance.sh` mounts the data disk at `/mnt/db1` and fails when there is none.  `DataDiskRequirement` refuses a control or app instance type with no instance store at `init`.

The integration tier boots K3s from `rancher/k3s:v1.35.1-k3s1` (`SharedK3s`), the same release as the AMI (`install_k3s.sh`).

## Goals / Non-Goals

**Goals:**
- The K3s server's datastore is embedded etcd, with one member.
- etcd data is on the control node's data disk.
- Nothing in the cluster deletes owner data, and nothing K3s writes for etcd can fill the data disk.
- Every existing command works as before.
- An 80-node run measures the effect against #1134.

**Non-Goals:**
- More than one server, or an external datastore.
- The join limit, the control-plane gate, the control-plane health check and node replacement.  These are #1158.
- Raising apiserver request limits or changing priority and fairness.
- Backing up or restoring the K3s datastore.  It is not backed up today.

## Decisions

### D1. Embedded etcd through a K3s config drop-in

`start-k3s-server.sh` writes `/etc/rancher/k3s/config.yaml.d/20-datastore.yaml` on every run, before `systemctl start k3s`:

```yaml
cluster-init: true
etcd-arg:
  - "quota-backend-bytes=8589934592"
```

The script writes it with a heredoc of fixed content, as it writes the eviction drop-in.  It is a static file in a shell script, not YAML built in Kotlin.

Why a drop-in and not `INSTALL_K3S_EXEC`: the installer runs only when `k3s.service` is not installed, so a flag there is fixed at first install.  The drop-in is rewritten on every run and read on every K3s start, and it keeps the datastore settings in one file a person can read on the node.  K3s reads `config.yaml.d/*.yaml` in lexical order and merges list keys, so it does not interfere with `10-kubelet-eviction.yaml`.

`start-k3s-agent.sh` writes no datastore drop-in.  `cluster-init` is a server option.

Alternatives considered:
- `--datastore-endpoint` to an external etcd or a database: out of scope (issue), and it adds a process to run and manage.
- Keep kine and tune sqlite: kine's single writer and its polled watch are the suspected bottleneck.  No sqlite setting removes them.

### D2. etcd data on the data disk, through the existing link

etcd lives at `${data-dir}/server/db/etcd`.  The data dir stays `/var/lib/rancher/k3s`, a link to `/mnt/db1/k3s`, so etcd lands at `/mnt/db1/k3s/server/db/etcd`, where the sqlite file is today.  No `--data-dir`: the AMI's airgap images, the `portmap` link (`/var/lib/rancher/k3s/data/cni/portmap`) and the node token path all assume the default.

The server script fails, naming the reason, when `/mnt/db1` is not a mount point.  Today it would put K3s's data, now etcd, on the 20 GB root volume.  The control node always has a data disk (`DataDiskRequirement`, `setup_instance.sh`), so this only turns a silent misplacement into an error.  The agent script keeps its current behavior for a host with no data disk.

The existing `RequiresMountsFor=/mnt/db1` drop-in keeps K3s from starting before the data disk is mounted after a reboot.  It covers etcd unchanged.

### D3. Scheduled etcd snapshots stay at K3s's defaults

With embedded etcd, K3s takes a snapshot at 00:00 and 12:00 (cron `0 */12 * * *`) into `${data-dir}/server/db/snapshots` (`/mnt/db1/k3s/server/db/snapshots`), and keeps the five newest scheduled snapshots: after each new one it removes the oldest.

These snapshots are K3s's internal recovery files for an ephemeral cluster's control plane.  They are not owner data.  Owner data (test results, metrics, logs, traces, profiles, annotations, backups and exports) never goes into etcd or into this directory, so this retention deletes no owner data.  The design says so here, and the docs say so.

Disk: five snapshots, each about the size of the etcd database (tens to a few hundred MB at 80 nodes, 8 GiB at most by D4), on the control node's data disk (150 GB NVMe on the default m5d.xlarge).  Retention keeps the total bounded, so the snapshots cannot fill the disk.

Alternatives considered:
- `etcd-disable-snapshots`: removes K3s's only recovery point for the control plane.  Not done: disabling a feature is not the answer.
- Unlimited retention: the snapshots then grow without bound on a long-running cluster.  Retention exists for exactly this.
- Snapshots to S3 (`etcd-s3`): adds cluster files to the bucket that nothing reads, and an S3 path that `down` does not flush.  Not requested.

### D4. One-member etcd settings

- `quota-backend-bytes` is 8 GiB (`8589934592`), etcd's recommended maximum, raised from the 2 GiB default.  When etcd reaches its quota it raises `NOSPACE`, and the apiserver refuses every write until someone compacts and defragments by hand.  At 1,000+ nodes (the pod range already plans for 4,096), events and leases can approach 2 GiB between compactions.  The quota is a ceiling, not an allocation.
- Heartbeat interval and election timeout stay at their defaults.  They govern messages between members, and there is only one.
- Compaction and defragmentation stay as K3s configures them for embedded etcd.
- The data is on local NVMe, which gives etcd the low fsync latency it needs.  No other tuning.

The quota is a constant in the script, with a comment that names why.  The Kotlin side does not pass it, because nothing in the CLI varies it.

### D5. How to tell etcd is the datastore

`/readyz?verbose` lists `[+]etcd ok` under kine too: the apiserver talks to kine through the etcd API.  The check proves the datastore answers, not which datastore it is.  The proof is three facts together:

- `kubectl get --raw '/readyz?verbose'` lists `[+]etcd ok` (the issue's acceptance check);
- the control node has the label `node-role.kubernetes.io/etcd=true`, which K3s sets only on an etcd member;
- on the control node, `/mnt/db1/k3s/server/db/etcd/member` exists and `/mnt/db1/k3s/server/db/state.db` does not.

The tests and the AWS run use these.  `up` adds no check of its own.

### D6. Ports

etcd listens on 2379 (clients) and 2380 (peers) on the control node's address and loopback, and on 2381 (metrics) on loopback.  No host-network workload on the control node uses these ports.  The TiDB kit's PD uses 2379 inside its pods, on pod addresses, and NodePort 32379, so it does not clash.  The security group already allows all TCP within the VPC; no rule changes.

### D7. What does not change

- The node token, the kubeconfig, agent joins and the server URL (`https://<control>:6443`).
- `restart`, `stop` and `start` of the K3s server: systemd, as today.  etcd's data persists across restarts on the data disk.
- `ClusterBackupService` and the restore path: they copy workspace files.  The K3s datastore was never backed up, and this change adds no backup.
- `down`: the datastore and its snapshots go with the control node, as the sqlite file does today.

### D8. Measuring the effect

The cluster runs no scrape of the apiserver's own metrics.  The 80-node run reads them with `kubectl get --raw /metrics` from the workstation, at the end of the join and again after `up` finishes, and records:

- datastore write latency: `etcd_request_duration_seconds` by `operation` and `type`, the p50 and p99 for `create` on `nodes` and `update` on `leases`;
- 429s: `apiserver_flowcontrol_rejected_requests_total` by `priority_level`, and `apiserver_flowcontrol_request_wait_duration_seconds` for `node-high`;
- `up` wall time, the time from the first agent start to the last node Ready, cilium-operator restarts, and whether any manual step was needed.

The run uses `main` plus this change only, the #1134 shape (1 control m5d.xlarge, 76 db i4i.xlarge, 2 app c6id.4xlarge), the default profile, and the tag `embark_offhours=off`.  The comparison goes in the PR and on #1134.

## Risks / Trade-offs

- [The sqlite datastore is a likely cause, not a proven one.  The 429 storm may come from 50 agents registering at once, whatever the datastore.] → The 80-node run measures it with no other change.  Join throttling is #1158; this change does not depend on it.
- [etcd uses more memory than kine on a control node that was at 7.2 of 15.8 GB.] → The 80-node run records the k3s process RSS on the control node.
- [A re-run of `up` on a cluster whose K3s is already running writes the drop-in, but `systemctl start k3s` does not restart K3s, so the running server stays on sqlite.] → Clusters are ephemeral and are made new by `up`.  K3s migrates a sqlite datastore to etcd on the next K3s start with `cluster-init`, so a restart also converges.  No migration path is built.
- [etcd's fsync latency is its main performance limit.] → Its data is on local NVMe, never the root EBS volume (D2).

## Migration Plan

None.  Clusters are ephemeral; a new `up` makes a new etcd-backed server.  Rollback is a revert of the script change.

## Open Questions

- Snapshots: keep K3s's defaults (local, twice a day, the five newest kept, older K3s snapshots removed by K3s), or also send them to the cluster's S3 path, where they would be owner data with no retention?
- `instance-storage-validation` says control and app nodes need no data disk, while `DataDiskRequirement` refuses them without instance store.  This change relies on the code's rule.  Correct the spec to match the code, as proposed here?
- The 80-node run must be "main plus this change only".  Should it run before #1158 merges, so #1158's join limit does not mask the datastore's effect?
- If the 80-node run stalls again, may the agent restart k3s by hand to finish the run and record the numbers, as #1134 did, and leave join throttling to #1158?
