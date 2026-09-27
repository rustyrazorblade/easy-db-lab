## 1. Teardown: remove the verify checks

- [x] 1.1 Remove the `LOKI_RUNNING`, `LOKI_WAL_CHECK`, `LOKI_S3_CHECK`, `MIMIR_RUNNING`, `MIMIR_COMPACTION_CHECK`, `MIMIR_S3_CHECK` and `TEMPO_RUNNING` steps from `FlushStep` and `TeardownFlushService` (drop `requireRunning` and its callers).
- [x] 1.2 `LokiTailFlush`: keep the synchronous ingester shutdown (204 required) and the scale to 0 with its wait; remove the chunk counter reads, the WAL listing and the S3 index check, and the `ObjectStore` and `RemoteOperationsService` dependencies they needed.
- [x] 1.3 `MimirTailFlush`: keep the ingester shutdown (2xx required) and the scale to 0; remove the metric scrapes, the local block listing and the S3 block listing.
- [x] 1.4 `TempoTailFlush`: keep the drain; remove the running check.
- [x] 1.5 Update the per-signal record to store the completion time only, and the `SignalReport` and teardown events to stop reporting verified counts.
- [x] 1.6 Delete the unit tests of the removed checks; keep the tests of the flush-and-wait steps and of "a failed flush stops `down`".

## 2. Mimir read path

- [x] 2.1 `mimir.yaml`: add `store-gateway` to `target`; in-memory store-gateway ring with replication factor 1; `bucket_store` `sync_dir: /data/tsdb-sync`, `sync_interval: 1m`, `ignore_blocks_within: 0`, `bucket_index.max_stale_period: 87600h`; `query_store_after: 0`; `tsdb.retention_period: 2h`; rewrite the header comment.
- [x] 2.2 `MimirManifestBuilder`: mount the sync directory on the control node's data path.
- [x] 2.3 Unit test on the rendered Mimir config: targets include `store-gateway` and exclude `compactor`, retention 2h, `query_store_after` 0, stale period 87600h.

## 3. Tenant datasources

- [ ] 3.1 `TenantDirectory.list(bucket, home)`: non-recursive listing of `mimir/`, keep names matching the tenant rule, add the home tenant, sort; returns `TenantSet(home, all)`.
- [ ] 3.2 `GrafanaDatasourceSet.build(tenants, urls)` in `configuration/grafana/`, replacing `GrafanaDatasourceConfig.create(tenant)`: stable `mimir`/`loki`/`tempo`/`pyroscope` on the home tenant; `<signal>-<tenant>` and `<signal>--all` for metrics, logs and traces; 40-character UID rule; cross links within one tenant view; one profiles datasource.  Add `BackendUrls` with the control-node URLs.
- [ ] 3.3 `ObservabilityStackService.deploy()` lists the tenants and builds the datasources, so `up` and `grafana update-config` both refresh them.
- [ ] 3.4 Unit tests: `TenantDirectory` parsing (drops `__mimir_cluster`, adds home, sorts); `GrafanaDatasourceSet` (tenants `default` and `acme` give per-tenant and all-tenants datasources with `acme|default`; exactly one profiles datasource; same tenants with different URLs differ only in URLs; long tenant UID fits 40 characters; stable UIDs send the home tenant; cross links stay in their view).

## 4. Account compactor

- [ ] 4.1 Add the `ecs` and `cloudwatchlogs` AWS SDK modules to `gradle/libs.versions.toml` and the build; build ECS, CloudWatch Logs and EC2 clients for a given region.
- [ ] 4.2 Constants for the ECS cluster, service, task family, log group, VPC name, role names, CPU, memory, ephemeral storage, status log line count, and the Tempo retention value.
- [ ] 4.3 Config resources: `tempo-backend.yaml` (`block_retention: 876000h`, long retention interval, `max_bytes_per_trace: 0`, `empty_tenant_deletion_enabled: false`, no per-tenant overrides); reuse the cluster `mimir.yaml` and `loki.yaml`.
- [ ] 4.4 `CompactorTaskDefinition` (pure): the busybox init container and the Mimir, Loki, Tempo scheduler and Tempo worker containers with the flags in design.md, `awslogs`, ARM64, 2 vCPU / 8 GiB / 100 GiB, plus its config hash.
- [ ] 4.5 Unit tests on the task definition and configs: Mimir `-compactor.blocks-retention-period=0`, `-compactor.partial-block-deletion-delay=0`, `-compactor.cleanup-interval=1m`; Loki retention disabled and deletion mode `disabled`; Tempo retention `876000h`, `max_bytes_per_trace` 0, `empty_tenant_deletion_enabled` false, no per-tenant override; no Pyroscope container.
- [ ] 4.6 `CompactorIam`: task role and execution role as in design.md, and the ECS service-linked role.
- [ ] 4.7 `CompactorNetwork`: find or create the `easy-db-lab-compactor` VPC in the bucket's region, tagged `easy_cass_lab=1` with no `bucket` tag; `teardownAllTagged` skips it as it skips the packer VPC.
- [ ] 4.8 `CompactorService` / `DefaultCompactorService`: `ensureRunning` (create when missing, start when at 0, leave when running), `stop`, `stopIfLastCluster`, `status`; register in Koin.
- [ ] 4.9 `ClusterCensus` (tagged VPCs naming the bucket in every enabled region) and the pure `CompactorShutdownPolicy`, with a unit test of the decision (only this cluster's VPCs → stop; another VPC in another region → keep).
- [ ] 4.10 `AWSPolicy`: bucket-policy Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/` for the EC2, EMR service and EMR EC2 roles; new user policy `iam-policy-compactor.json` in `AWSPolicy.UserIAM.loadAll` and `bin/set-policies`.
- [ ] 4.11 `Event.Compactor.*` events (started, already running, stopped, kept running) in `events/Event.kt`.

## 5. Lifecycle and commands

- [ ] 5.1 `Up`: call `CompactorService.ensureRunning` once, after the account bucket and its policy are configured.
- [ ] 5.2 `Down`: call `CompactorService.stopIfLastCluster` once, after the infrastructure teardown succeeds, with the torn-down VPC IDs.
- [ ] 5.3 New `observability` command group with `compactor start`, `compactor stop` and `compactor status` (status read-only, `println`); works outside a cluster workspace.

## 6. Docs

- [ ] 6.1 `docs/user-guide/mimir.md` and `docs/user-guide/loki.md`: the read path over the shared store, 2h local retention, tenant datasources, and the teardown without verify checks.
- [ ] 6.2 New `docs/user-guide/compactor.md` (what it runs, lifecycle, commands, cost, no retention) and its entry in `docs/SUMMARY.md`; `docs/reference/commands.md` for the three commands; IAM docs for the new user policy.
- [ ] 6.3 Root `CLAUDE.md` Observability paragraph: remove the Loki running check and the verify checks from the teardown description, describe the store-gateway and 2h local retention, the account compactor and its deletion scope, and the tenant datasources.
- [ ] 6.4 `configuration/CLAUDE.md` (compactor configs, datasource set, Mimir config, tail-flush record), `commands/CLAUDE.md` (the `observability` group), `services/aws/CLAUDE.md` (ECS, census, IAM, network), `events/CLAUDE.md` (the `Compactor` domain).
- [ ] 6.5 Run `./gradlew ktlintFormat`, `./gradlew detekt` and `./gradlew test` in a subagent; run `./gradlew installDist`.
