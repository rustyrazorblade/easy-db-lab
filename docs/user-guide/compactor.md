# Account Compactor

Every cluster writes its metrics, logs and traces to one shared store in the account bucket. The account compactor keeps that store readable: it merges small objects into larger ones and keeps Mimir's bucket index current, so every cluster's Grafana can read every tenant's metrics.

## What it runs

The compactor is one ECS Fargate service per AWS account, `easy-db-lab-compactor` in the ECS cluster `easy-db-lab`. It runs in the account bucket's region, in its own small VPC, `easy-db-lab-compactor`: one public subnet, an internet gateway, and a security group with no ingress. The subnet is in the first availability zone, by name, where Fargate runs; `up` never places it in a zone without Fargate, such as `use1-az3`.

The service runs exactly 1 task (ARM64, 2 vCPU, 8 GiB, 100 GiB of disk). ECS never runs a second one: a new task starts only after the old one stopped. The task runs:

| Container | What it does |
|-----------|--------------|
| `mimir-compactor` | Mimir's compactor over `mimir/`. It rewrites every tenant's bucket index every minute. |
| `loki-compactor` | Loki's compactor over `loki/`. It compacts every day's index, today's included. |
| `tempo-backend-scheduler`, `tempo-backend-worker` | Tempo's compaction over `tempo/`. Tempo 3 runs one target per process, so they are two containers. |
| `config` | Writes the configuration files for the others, then exits. |

Mimir and Loki use the same configuration files as the clusters, with flag overrides, so the compactor always reads the store the way the clusters write it. Profiles are not compacted here: Pyroscope compacts its own segments on each cluster.

Logs go to the CloudWatch Logs group `/easy-db-lab/compactor`, with no retention policy.

## No data is deleted by age

Compaction is on; retention and every other deletion path are off:

- Mimir: `-compactor.blocks-retention-period=0` and `-compactor.partial-block-deletion-delay=0`.
- Loki: retention disabled, deletion mode `disabled`.
- Tempo: block retention `876000h` (100 years), no per-trace size limit, and empty tenants are never deleted.

The compactor removes a source object only after it wrote a merged copy that holds its data. Its task role, `EasyDBLabCompactorTaskRole`, is the only role that may delete under `mimir/`, `loki/` and `tempo/`, and it has no access to `grafana/` or `pyroscope/`. The account bucket policy denies those deletes to every cluster-side role: the EC2 instance role, the EMR service role and the EMR EC2 role. Deletes you make yourself from your workstation are not affected.

## Lifecycle

- `up` starts the compactor when it is not running: it creates it when it is missing and starts it at 1 task when it is stopped. When it already runs, `up` leaves it as it is. `up` does not wait for the task to run.
- A new compactor configuration or version takes effect only when the service starts. To apply it to a running compactor, run `observability compactor stop`, then `observability compactor start`.
- `down`, after its teardown succeeds, stops the compactor when no other cluster uses the account bucket. It counts the VPCs tagged `easy_cass_lab=1`, in every enabled region, whose `bucket` tag names the bucket. The compactor's own VPC has no `bucket` tag, so it is never counted.
- `down --all` keeps the compactor's VPC, as it keeps the packer VPC.

Metrics queries keep working while the compactor is stopped. Blocks shipped after it stopped become readable once it runs again.

## Commands

```bash
easy-db-lab observability compactor start    # start it as up does
easy-db-lab observability compactor stop     # set its desired count to 0
easy-db-lab observability compactor status   # state, why the task stopped, service events, the last 20 log lines
```

They work outside a cluster workspace. See [Observability Commands](../reference/commands.md#observability-commands).

## Permissions

The compactor needs the `EasyDBLabCompactor` user policy: ECS, its CloudWatch Logs group, `iam:PassRole` on its two roles, and ECS's service-linked role. `easy-db-lab show-iam-policies compactor` prints it.

## Cost

A 2 vCPU, 8 GiB ARM64 Fargate task costs about $0.10 an hour, while any cluster exists.
