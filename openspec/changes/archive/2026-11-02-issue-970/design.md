## Context

Issue #966 put every backend's root at the top level of the account bucket (`mimir/`, `loki/`, `tempo/`, `pyroscope/`, `grafana/`) and made uploads fast.  Its OpenSpec change is merged but not archived, so its delta specs are the current behavior.  This change adds the compactor that keeps the store readable, the Mimir read path over S3, and the tenant datasources, and removes the teardown verify checks.

The owner chose the minimal design on 2026-09-27.  Nothing here handles an S3 outage, concurrent `up` runs, two profiles in one account, or partial-teardown re-runs.

Versions: Mimir 3.2.1, Loki 3.7.8, Tempo 3.0.3, Pyroscope 2.3.1.

## Goals / Non-Goals

**Goals:** one compactor per account; every cluster's Grafana reads every tenant's metrics, logs and traces; `down` only flushes, waits, and refuses teardown if the flush did not finish.

**Non-Goals:** cross-cluster profiles; the dashboard datasource picker (#971); multi-dc; tenant authentication; cross-cluster tests; nearby refactoring.

## Decisions

### Compactor service

- ECS cluster `easy-db-lab`, service `easy-db-lab-compactor`, task family `easy-db-lab-compactor`, all in the bucket's region (from `GetBucketLocation`).
- Service: `FARGATE`, `desiredCount: 1`, `maximumPercent: 100`, `minimumHealthyPercent: 0`, `awsvpc`, `assignPublicIp: ENABLED`, `stopTimeout: 120`.
- Task: `ARM64`, 2 vCPU, 8 GiB, 100 GiB ephemeral storage.  Images reuse the image constants of the cluster's manifest builders.  Loki and Tempo run as user 0.  Each container listens on its own port.

| Container | Image | Arguments |
|---|---|---|
| `config` (non-essential, runs first) | busybox, pinned | decodes base64 configs from its environment into a shared volume at `/config` |
| `mimir-compactor` | `grafana/mimir:3.2.1` | cluster `mimir.yaml` plus `-target=compactor -compactor.cleanup-interval=1m -compactor.partial-block-deletion-delay=0 -compactor.blocks-retention-period=0` |
| `loki-compactor` | `grafana/loki:3.7.8` | cluster `loki.yaml` plus `-target=compactor -compactor.compaction-interval=10m -compactor.retention-enabled=false`, deletion mode `disabled` |
| `tempo-backend-scheduler` | `grafana/tempo:3.0.3` | `tempo-backend.yaml`, `-target=backend-scheduler` |
| `tempo-backend-worker` | `grafana/tempo:3.0.3` | `tempo-backend.yaml`, `-target=backend-worker`, scheduler at `127.0.0.1` |

- Tempo 3.0.3 runs one target per process, so the scheduler and the worker are two containers.
- `tempo-backend.yaml` sets `backend_worker.compaction.block_retention: 876000h`, a very long `backend_scheduler.provider.retention.interval`, `overrides.defaults.global.max_bytes_per_trace: 0`, and `storage.trace.empty_tenant_deletion_enabled: false`, with no per-tenant retention override.  Tempo has no literal "retention off" once compaction runs; a zero retention makes every block eligible, so the unreachable value is the off switch.  `max_bytes_per_trace: 0` stops compaction from dropping spans of large traces.
- Mimir's `-compactor.partial-block-deletion-delay` defaults to `1d` and would delete partial blocks that no merged copy replaces, so it is set to 0 (disabled).  `deletion-delay` (12h) removes merged sources only, which is compaction.  `-compactor.block-ranges` stays at `2h,12h,24h`.
- Loki's compactor compacts every table, today's included (`skip-latest-n-tables` stays 0).
- The task definition is built by a pure builder from `(bucket, region, taskRoleArn, executionRoleArn, logGroup)`.  It reads its config files from the classpath, not through `TemplateService`, because `observability compactor start` must work outside a cluster workspace.
- Logs go to CloudWatch Logs group `/easy-db-lab/compactor` through `awslogs`, with no retention policy.

### Start and stop

- `CompactorService.ensureRunning(bucket)`: find or create the IAM roles, the ECS service-linked role, the VPC, the log group and the ECS cluster; then `DescribeServices`:
  - missing or `INACTIVE` → register the task definition, `CreateService`;
  - `desiredCount` 0 → register the task definition if its config hash differs from the latest revision's `easydblab.com/config-hash` tag, then `UpdateService(desiredCount=1, taskDefinition=latest)`;
  - `desiredCount` 1 → nothing.
- `up` calls it once, after the account bucket and its policy are configured.  `up` does not wait for the task to reach `RUNNING`.
- `down` calls `stopIfLastCluster` once, after the infrastructure teardown succeeds.  The census runs `DescribeRegions`, then `DescribeVpcs` in every enabled region with filters `tag:easy_cass_lab=1` and `tag:bucket=<bucket>`, and drops the VPC IDs this `down` tore down.  The pure `CompactorShutdownPolicy` returns stop when the rest is empty, keep otherwise.  Stop is `UpdateService(desiredCount=0)`.
- `observability compactor start` calls `ensureRunning`; `stop` sets `desiredCount` 0; `status` prints the service's desired and running counts, the current (or last stopped) task's ID and state, and the last 20 log lines of its streams (`GetLogEvents`).  `status` is read-only and uses `println`; `start` and `stop` emit `Event.Compactor.*`.
- Nothing is added to `state.json`; every resource is found by name.

### Network and region

- VPC `easy-db-lab-compactor` in the bucket's region: one public subnet, an internet gateway, a route, and a security group with no ingress.  It is tagged `easy_cass_lab=1` with no `bucket` tag, so the census never counts it.  It is created through the same find-or-create path as the packer VPC, with an EC2 client for the bucket's region.
- `teardownAllTagged` skips it the same way it skips the packer VPC.
- ECS, CloudWatch Logs and EC2 clients are built for a given region, because the profile region can differ from the bucket's.

### IAM

- Task role `EasyDBLabCompactorTaskRole` (trusts `ecs-tasks.amazonaws.com`): `s3:ListBucket` and `s3:GetBucketLocation` on `easy-db-lab-*`; `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on `mimir/*`, `loki/*` and `tempo/*`; nothing on `grafana/` or `pyroscope/`.
- Execution role `EasyDBLabCompactorExecutionRole` with the managed `AmazonECSTaskExecutionRolePolicy`.
- The account bucket policy gains one Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` on `mimir/*`, `loki/*` and `tempo/*` for the three principals it already allows (EC2 instance role, EMR service role, EMR EC2 role).  The deny names cluster principals, not "everyone but the compactor", so the owner's deletes from a workstation keep working.  The instance-role deny from #966 stays.
- New user policy `iam-policy-compactor.json`: `ecs:*`, CloudWatch Logs create, describe and get, `iam:PassRole` on the two roles, and `iam:CreateServiceLinkedRole` for ECS.  Loaded by `AWSPolicy.UserIAM.loadAll` and by `bin/set-policies`.
- New SDK modules: `ecs` and `cloudwatchlogs`.

### Read path

`mimir.yaml` changes:
- `target` adds `store-gateway`.
- `store_gateway.sharding_ring` uses `inmemory`, replication factor 1.
- `blocks_storage.bucket_store`: `sync_dir: /data/tsdb-sync`, `sync_interval: 1m`, `ignore_blocks_within: 0`, `bucket_index.max_stale_period: 87600h`.
- `querier.query_store_after: 0`; `limits.query_ingesters_within: 0` stays.
- `blocks_storage.tsdb.retention_period: 2h`.

A tenant with no bucket index returns no stored data rather than an error.  Because the stale period is about 10 years, a stopped compactor never makes a query fail.

### Tenant listing

`TenantDirectory.list(bucket, home)` lists `mimir/` with `Delimiter=/` through the existing `ObjectStore.listFiles(..., recursive=false)`, keeps the prefixes that match the tenant name rule (which drops `__mimir_cluster/`), adds the cluster's own tenant, and returns them sorted as `TenantSet(home, all)`.  `ObservabilityStackService.deploy()` calls it, so `up` and `grafana update-config` both refresh it.

### Datasource function

`GrafanaDatasourceSet.build(tenants: TenantSet, urls: BackendUrls): GrafanaDatasourceConfig` is pure and replaces `GrafanaDatasourceConfig.create(tenant)`.  `BackendUrls` holds the metrics, logs, traces and profiles base URLs; the cluster passes its control-node URLs and #902 passes its compose service names.

- Stable datasources `mimir`, `loki`, `tempo`, `pyroscope` keep their UIDs, names and types, and send the home tenant.  `mimir` stays the default.
- Per tenant, for metrics, logs and traces: UID `<signal>-<tenant>`, name "Mimir (acme)", sending that tenant.
- All tenants, for metrics, logs and traces: UID `<signal>--all`, name "Mimir (all tenants)", sending the sorted list joined with `|`.
- A UID past Grafana's 40-character limit becomes `<signal>-<prefix>-<first 8 hex of sha256(tenant)>`.
- Cross links stay within one tenant view: `loki-acme` links trace IDs to `tempo-acme`, `tempo-acme` links to `loki-acme`, `--all` links to `--all`, the stable ones link to each other.
- Profiles: one datasource, `pyroscope`, home tenant.  Pyroscope reads only blocks its own metastore knows.

### Teardown

`TeardownFlushService` and the tail flushes drop the seven verify steps: `LOKI_RUNNING`, `LOKI_WAL_CHECK`, `LOKI_S3_CHECK`, `MIMIR_RUNNING`, `MIMIR_COMPACTION_CHECK`, `MIMIR_S3_CHECK`, `TEMPO_RUNNING`.  What stays:
- Loki: `POST /ingester/shutdown?flush=true` must return 204 (it returns only once every chunk is in S3), then scale Loki to 0 and wait for the pod to go, which builds and uploads the index.
- Mimir: `POST /ingester/shutdown` must succeed (it compacts the head and ships it before it returns), then scale Mimir to 0.
- Tempo: the drain waits for no live traces, then for every local block's `flushed` marker.  That is the wait for Tempo's upload, since Tempo has no flush endpoint.
- Any step that fails or times out still stops `down` before teardown.  The per-signal record from #966 stays.

## Alternatives Considered

- **D1 Network.**  Chosen: a dedicated per-account VPC in the bucket's region.  Rejected: the default VPC (often deleted); the packer VPC (SSH open, deleted by `down --packer`, exists only after an AMI build); a cluster VPC (the service outlives every cluster).
- **D2 Region.**  Chosen: the bucket's region.  Rejected: the profile region, because a profile in another region would start a second compactor on the same store.
- **D3 Task layout.**  Chosen: one service, one task, all containers.  Rejected: three services, which triples the lifecycle code for no gain.
- **D4 Config delivery.**  Chosen: a busybox init container writes base64 configs from its environment to a shared volume.  Rejected: uploading configs to S3 with an aws-cli init container (more IAM, more moving parts); a custom ECR image (needs Docker at runtime and breaks Homebrew installs).
- **D4b Config files.**  Chosen: reuse the cluster's `mimir.yaml` and `loki.yaml` with flag overrides, and a new `tempo-backend.yaml`.  Rejected: one dedicated compactor file per tool plus a test that Loki's `schema_config` matches the cluster's, which duplicates config that must stay identical.
- **D5 `up` when the task definition changed.**  Architect recommended rolling to the new revision.  Owner chose to leave a running service as it is: the acceptance criteria say `up` leaves it alone, and a roll stops compaction mid-run.  A new configuration takes effect on the next start.
- **D6 Meaning of the stable UIDs.**  Chosen: they point at the cluster's own tenant, so existing dashboards keep today's view.  Rejected: pointing them at all tenants, which fans every dashboard out across the whole store by default.
- **D7 Mimir local retention.**  Chosen: 2 hours.  Rejected: 13 hours (keeps local blocks past the compactor's 12h `deletion_delay` for no read benefit, and costs disk, memory and open files); 1 hour (less margin for a compactor restart before a newly shipped block appears in the bucket index).
- **D8 When `up` checks.**  Architect recommended ensure early and verify late (wait at the end of `up` for the task to run).  Owner dropped the verify step: `up` ensures once and does not wait.  Also rejected: blocking right after ensure.
- **D9 Redirect clusters.**  Chosen: `up` ensures the compactor on every cluster, with no redirect branch.  Rejected: skipping it on redirected clusters, which adds a branch for no benefit.
- **Loki: skip today's table.**  Rejected by the owner.  Loki compacts every table, today's included.  The critic raised it to protect `down`'s Loki index-in-S3 check; that check is removed instead.
- **Two profiles in one account, auto-roll, an end-of-`up` wait, nearby refactoring** (split `AWS.kt`, slim `DefaultObservabilityStackService`, a shared regional client module, the EMR EC2 role inline deny): dropped by the owner.

### Design-critic findings

1. **Loki compactor breaks `down`'s Loki index-in-S3 check.**  Fixed: the check is removed with every other verify check.
2. **Tempo compaction drops spans past `max_bytes_per_trace`.**  Fixed: `tempo-backend.yaml` sets `max_bytes_per_trace: 0`, locked by a unit test.
3. **Fixed resource names clash when two profiles share one account.**  Dropped: out of scope per the owner.
4. **D7 bounds (13h breaks the Mimir S3 check; 2h leaves gaps if the compactor is down for more than 2 hours).**  The 13h concern is gone with the Mimir block-in-S3 check.  The owner chose 2h; the compactor runs whenever a cluster exists, and the acceptance criterion requires only that queries succeed while it is stopped, which the 10-year stale period guarantees.
5. **D5 roll contradicts the acceptance criterion.**  Fixed: `up` leaves a running service as it is.
6. **The EMR service role can still delete.**  Fixed: the bucket-policy Deny covers all three cluster principals.
7. **Tempo `empty_tenant_deletion_enabled` not locked.**  Fixed: set false and locked by a unit test, with no per-tenant retention override.
8. **The home tenant gets two datasources per signal.**  Kept per the owner's decision: the stable UIDs stay for existing dashboards and point at the home tenant, and every tenant, the home one included, has exactly one `<signal>-<tenant>` datasource.

## Risks / Trade-offs

- A compactor bug can delete data under `mimir/`, `loki/`, `tempo/` → the retention and deletion flags are locked by unit tests on the task definition and `tempo-backend.yaml`.
- Every cluster's Tempo and the Fargate scheduler share `tempo/`'s work cache → at worst repeated compaction work, no data loss.
- The store-gateway on each control node syncs every tenant's block metadata → bounded by the compactor merging 1-minute blocks into 2-hour ones.
- About $0.10 an hour while any cluster exists.
