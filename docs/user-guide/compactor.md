# Account Compactor

Every cluster writes its metrics, logs and traces to one shared store in the account bucket.  The account compactor keeps that store readable: it merges small objects into larger ones and keeps Mimir's bucket index current, so every cluster's Grafana can read every tenant's metrics.

## Where it runs

Every cluster runs an `account-compactor` pod on its control node, redirect clusters included.  `up` deploys it with the rest of the observability stack.  There is nothing to install or start by hand, and no AWS service outside the cluster.

Only one compactor works on the store at a time.  The pods share a lease, `leases/compactor.json` in the account bucket.  The pod that holds the lease runs its compactors; every other pod is a standby and runs none.  When the holder's cluster goes away, a standby takes over the lease and starts its compactors.  When no cluster runs, nothing compacts; compaction continues when the next cluster comes up.

The pod runs in the pod network, with one replica.  It has these containers:

| Container | What it does |
|-----------|--------------|
| `gate-busybox` | An init container. It copies a static busybox into the pod, for the gate. |
| `lease` | The lease agent. It takes, renews and gives up the lease, and tells the gates whether they may run. The pod is ready when the agent has read the store once. |
| `mimir-compactor` | Mimir's compactor over `mimir/`. It rewrites every tenant's bucket index every minute. Every 5 minutes it merges the clusters' 1-minute blocks, once they are 2 minutes old, into 10-minute blocks, and those into 2-hour, 12-hour and 24-hour blocks. |
| `loki-compactor` | Loki's compactor over `loki/`. It compacts every day's index, today's included. |
| `tempo-backend-scheduler`, `tempo-backend-worker` | Tempo's compaction over `tempo/`. Tempo 3 runs one target per process, so they are two containers. |

Each compactor runs under a gate: a small script that starts the compactor only while the lease agent says the lease is valid, and stops it when the lease is not.  Mimir and Loki use the same configuration files as the clusters, with flag overrides, so the compactor always reads the store the way the clusters write it.  Profiles are not compacted here: Pyroscope compacts its own segments on each cluster.

The compactors keep their working files under `/mnt/db1/compactor/` on the control node.  The lease agent empties these directories each time it takes the lease.  The compactors' logs are in the cluster's Loki, as every pod's are.

## How the lease works

The holder renews the lease every 15 seconds with a conditional write: the write succeeds only when the lease is unchanged since the holder last wrote it.  A standby takes the lease only after it has seen the same lease, unchanged, for 2 minutes.

If a holder cannot renew for 60 seconds, its gates stop the compactors: they send SIGTERM, wait 20 seconds, then send SIGKILL.  A holder therefore stops compacting at most 81 seconds after its last renewal, well before a standby may take over at 2 minutes.  Each pod measures these times on its own clock, so clock differences between clusters do not matter.

A pod that takes the lease but cannot empty its working directories keeps its compactors stopped.  It then waits one extra lease period, so a healthy standby takes the lease first.

### A lease that cannot be read

If `leases/compactor.json` does not hold a valid lease, for example after a hand edit, no pod takes it over and nothing compacts.  Nothing replaces the file automatically.  Each lease agent logs this line once, followed by the file's content:

```text
Lease agent <cluster>/<pod> cannot parse the lease body, so it will not take it over; delete leases/compactor.json by hand to recover
```

To recover, delete `leases/compactor.json` from the account bucket.  Within 15 seconds a running pod creates a new lease and starts its compactors.  No compactor runs while the file is invalid, so the new holder cannot overlap with an old one.

## No data is deleted by age

Compaction is on; retention and every other deletion path are off:

- Mimir: `-compactor.blocks-retention-period=0` and `-compactor.partial-block-deletion-delay=0`.
- Loki: retention disabled, deletion mode `disabled`.
- Tempo: block retention `876000h` (100 years), no per-trace size limit, and empty tenants are never deleted.

The compactor removes a source object only after it wrote a merged copy that holds its data.  It uses the cluster's EC2 instance role, so that role may delete under `mimir/`, `loki/` and `tempo/`.  The cluster's own Mimir, Loki and Tempo still have no way to delete: Mimir runs no compactor, Loki's compactor never runs, and Tempo has compaction off and the same 100-year block retention.  The instance role may not delete under `grafana/` or `reports/`, and the account bucket policy denies deletes under the compacted roots to the EMR roles.  Deletes you make yourself from your workstation are not affected.

## Upgrades

A new easy-db-lab version brings a new lease agent jar and new compactor configuration.  `up` and `grafana update-config` roll the pod when either changes.  The new pod takes the lease again as any pod does.

## Commands

```bash
easy-db-lab observability compactor status   # who holds the lease, its sequence, and when it was last renewed
```

It works outside a cluster workspace and changes nothing.  A lease that has not been renewed for longer than 2 minutes is shown as expired.  See [Observability Commands](../reference/commands.md#observability-commands).

## Cost

The compactor runs on the control node every cluster already has, so it costs nothing extra.
