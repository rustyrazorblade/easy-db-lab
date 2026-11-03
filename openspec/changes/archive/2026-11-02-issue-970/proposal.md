## Why

Every cluster writes to one shared store in the account bucket, but a cluster's Grafana shows only its own data.  Mimir runs no store-gateway and its querier never reads S3, so metrics from other clusters and from past runs are invisible.  The owner cannot compare clusters, pick one customer, or see all customers from a cluster's Grafana.

Mimir's readers find blocks only through a per-tenant bucket index, and only Mimir's compactor writes it.  Nothing compacts the shared store today, so it also grows into many small objects.

`down` also runs seven verify checks around its flushes.  They were added by agents, not by the owner, and they add nothing: `down` flushes every backend, waits for the flush, and refuses to tear down if the flush did not finish.

## What Changes

**Account compactor (new `account-compactor` capability)**
- One ECS Fargate service per AWS account, `easy-db-lab-compactor` in ECS cluster `easy-db-lab`, in the account bucket's region, in its own small VPC `easy-db-lab-compactor`.
- The service runs 1 task (`desiredCount: 1`, `maximumPercent: 100`, `minimumHealthyPercent: 0`).  The task runs Mimir's compactor, Loki's compactor, and Tempo's backend-scheduler and backend-worker, plus a small init container that writes their configuration files.
- Compaction is on; retention and every other deletion path are off: Mimir `-compactor.blocks-retention-period=0` and `-compactor.partial-block-deletion-delay=0`; Loki retention off and deletion mode `disabled`; Tempo `block_retention: 876000h`, `max_bytes_per_trace: 0`, `empty_tenant_deletion_enabled: false`.  Pyroscope's compaction stays in each cluster.
- Mimir's compactor rewrites every tenant's bucket index every minute (`-compactor.cleanup-interval=1m`).  Loki's compactor compacts every table, today's included.
- The task role may delete under `mimir/`, `loki/` and `tempo/`.  A Deny in the account bucket policy stops every cluster-side role (EC2 instance role, EMR service role, EMR EC2 role) from deleting there.
- New commands: `observability compactor start`, `observability compactor stop`, `observability compactor status` (state, task, recent log lines).

**Cluster lifecycle**
- `up` starts the compactor service if it is not running and otherwise leaves it as it is.  It never rolls a running service to a new task definition; a new configuration takes effect the next time the service starts.
- `down`, after its infrastructure teardown, stops the service when no other VPC tagged `easy_cass_lab=1`, in any region, has a `bucket` tag that names the account bucket.
- `down --all` leaves the compactor VPC alone, as it does the packer VPC.

**Teardown verify checks removed**
- `down` keeps its save steps (mirror, collector stop, Loki flush, Mimir flush, Tempo drain, profiles report, annotations backup) and keeps "no teardown if the flush did not finish".
- Removed: the Loki running check, the Loki index WAL check, the Loki index-in-S3 check, the Mimir running check, the Mimir head-compaction check, the Mimir block-in-S3 check, and the Tempo running check.

**Read the whole shared store on every cluster**
- Mimir adds the store-gateway target.  Readers use `query_store_after: 0`, `bucket_store.sync_interval: 1m`, `bucket_store.ignore_blocks_within: 0`, and `bucket_index.max_stale_period: 87600h`.
- Mimir's local block retention drops from the cluster's life to 2 hours (`blocks_storage.tsdb.retention_period: 2h`); older blocks are read from S3.
- Loki and Tempo already read other clusters' uploaded data; nothing changes there.

**Tenant datasources**
- `up` and `grafana update-config` list the tenant directories under `mimir/` (`ListObjectsV2` with `Delimiter=/`) and rebuild the datasources.
- One pure function builds the datasource set from the tenants and the backend base URLs.  Metrics, logs and traces get one datasource per tenant (`<signal>-<tenant>`) and one all-tenants datasource (`<signal>--all`) that sends every tenant as `a|b|c`.  The existing `mimir`, `loki`, `tempo` and `pyroscope` UIDs stay and point at the cluster's own tenant.  Profiles keep one datasource.

**Docs**
- User docs (`docs/user-guide/mimir.md`, `docs/user-guide/loki.md`, a new compactor page, `docs/reference/commands.md`), root `CLAUDE.md` (the Observability paragraph's teardown steps and read path), `configuration/CLAUDE.md`, `commands/CLAUDE.md`, `services/aws/CLAUDE.md`, `events/CLAUDE.md`.

## Capabilities

### New Capabilities

- `account-compactor`: the per-account compactor service, its task, its IAM, its commands, and when `up` and `down` start and stop it.

### Modified Capabilities

- `observability-store`: Mimir reads the whole shared store; the deletion rule covers the account compactor; the bucket-policy delete deny; Grafana's tenant datasources.
- `cluster-lifecycle`: the teardown drops every verify check.

## Impact

- Code: new ECS and CloudWatch Logs SDK modules in `gradle/libs.versions.toml`; new `configuration/compactor/` (task definition and configs), `services/compactor/` (service, shutdown decision), `services/aws/` (compactor IAM, network, cluster census), `services/TenantDirectory`, `configuration/grafana/GrafanaDatasourceSet`; new `commands/observability/` group; `Event.Compactor.*`; changes to `mimir.yaml` and `MimirManifestBuilder`, `GrafanaDatasourceConfig`, `ObservabilityStackService`, `Up`, `Down`, `AwsInfrastructureService.teardownAllTagged`, `AWSPolicy` (bucket policy and a new user policy), `bin/set-policies`, `TeardownFlushService`, `LokiTailFlush`, `MimirTailFlush`, `TempoTailFlush`, `FlushStep`.
- AWS: one Fargate task per account while any cluster exists; one small VPC, two IAM roles and one log group per account that stay.
- Out of scope: the datasource picker on dashboards (#971); cross-cluster profiles; an `observability compact` command; multi-dc changes; tenant authentication; cross-cluster tests or QA.
