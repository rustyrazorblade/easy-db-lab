## 1. Conditional writes on the object store

- [x] 1.1 Create the `:compactor-lease` Gradle subproject (no dependency on the root module), with a shadow jar and test fixtures; add it to the distribution as `agents/compactor-lease.jar` and to the Jib image under `/agents`
- [x] 1.2 Write the conditional-write contract tests (create race, stale replace, matching replace, missing object on replace) in the subproject's test fixtures
- [x] 1.3 Implement `ConditionalObjects`, `ObjectVersion`, `CreateResult`, `ReplaceResult` and `InMemoryConditionalObjects`; make the contract pass in memory
- [x] 1.4 Implement `S3ConditionalObjects` with `If-None-Match: *` and `If-Match`, mapping 412 and 409 to the lost results and 404 on replace to `VersionMismatch`, with no retries
- [x] 1.5 Bump the LocalStack image in `SharedLocalStack` to a version with conditional writes and run the contract against it; if no version works without an auth token, add a `requires-aws` real-S3 contract test instead
- [x] 1.6 Add `readVersioned`, `createIfAbsent` and `replaceIfVersionMatches` to `services/ObjectStore.kt`; delegate from `S3ObjectStore`; add an in-memory `ObjectStore` adapter for the whole interface; run the shared contract against both

## 2. The lease

- [x] 2.1 Add `Constants.Compactor.Lease` (TTL, renew interval, renew deadline, standby poll, gate poll, gate grace, termination grace) and `Constants.Observability.LEASES_ROOT`
- [x] 2.2 Test and implement `LeaseTiming`, refusing a timing where renew deadline plus gate poll plus gate grace is not less than the TTL
- [x] 2.3 Test and implement `LeaseRecord` (kotlinx.serialization) with holder, sequence, TTL and renewal time
- [x] 2.4 Test `LeaseLoop` with the in-memory store and a fake monotonic clock: create when absent, renew with a rising sequence, stand by while the version changes, take over only after the TTL on the standby's own clock, revoke on `VersionMismatch` or a missing lease, never take back its own lease after a restart
- [x] 2.5 Implement `LeaseLoop`, `GateFile` (write and rename, revoke) and the scratch wipe on acquire
- [x] 2.6 Implement `LeaseAgentMain` (environment, default credentials chain, bucket region, stdout logging of every transition, a readiness file after the first lease read)

## 3. The gate script

- [x] 3.1 Write a POSIX test for `compactor-gate.sh` in the `*.test.sh` pattern: no start while invalid, start when valid, SIGTERM then SIGKILL after the grace when the time passes or the file goes, restart of a child that exits while valid, SIGTERM forwarded to the child
- [x] 3.2 Write `compactor-gate.sh` as a packaged resource, calling every busybox applet explicitly

## 4. The compactor Deployment

- [x] 4.1 Port `CompactorTaskDefinitionTest`'s retention and deletion locks to `CompactorManifestBuilderTest`, and add tests for the pod shape: init container with `busybox:1.37.0-musl`, lease container, four gated containers, pod network, `Recreate`, termination grace, no limits, no readiness probe on gated containers, scratch host paths, SHA-256 jar path, bucket-region environment
- [x] 4.2 Implement `configuration/compactor/CompactorManifestBuilder` with fabric8, moving the Mimir and Loki compactor args and the config-file loading from `CompactorTaskDefinition` (through `TemplateService`)
- [x] 4.3 Implement `CompactorAgentInstaller`: create `/mnt/db1/compactor/{mimir,loki,tempo,agent}`, give Loki's and Tempo's directories to user 10001, upload the jar to its SHA-256 path unless it exists
- [x] 4.4 Add the compactor as a stage in `ObservabilityStackService.buildStages()` for every cluster, redirect clusters included, with the jar upload inside `deploy()`; add its ConfigMap to `ConfigHashAnnotator`'s known ConfigMaps
- [x] 4.5 Add the builder to `K8sServiceIntegrationTest.collectAllResources()`, with an apply test and the image pull test for the JRE, busybox-musl and Grafana images
- [x] 4.6 Rework `CompactorTaskIntegrationTest` to run the new pod spec's containers and keep the "Mimir blocks compacted by the account compactor keep every sample" check; add a two-agent lease failover scenario against LocalStack

## 5. IAM and backend guards

- [x] 5.1 Update `AWSPolicy`'s instance-role delete deny to `grafana/` and `reports/` only, and the bucket policy's deny to the EMR roles only; update `AWSpolicyTest`
- [x] 5.2 Add `block_retention: 876000h` to the cluster `tempo.yaml`, with a test that locks it; update the `loki.yaml` comment that names the IAM deny

## 6. CLI

- [x] 6.1 Remove `observability compactor start` and `stop`; rewrite `status` as a read-only lease print with the bucket's region; rewrite `CompactorCommandsTest`

## 7. Remove the Fargate path

- [x] 7.1 Remove the compactor call from `AccountBucketSetup` and the compactor test from `UpTest`
- [x] 7.2 Remove `stopCompactorIfLastCluster` and its wiring from `Down.kt`, and the `AccountCompactor` tests from `DownCommandTest`
- [x] 7.3 Delete `CompactorService`, `CompactorStatusReader`, `CompactorTaskRegistrar`, `CompactorIam`, `CompactorNetwork`, `ClusterCensus`, `CompactorTaskDefinition`, `RegionalClients`, the ECS retry helpers and imports in `RetryUtil` and `RetryWrappers`, and their tests
- [x] 7.4 Delete the compactor IAM trust, managed and inline policies, `iam-policy-compactor.json`, its `UserIAM` entry and the `ShowIamPolicies` filter
- [x] 7.5 Delete `InfrastructureConfig.forCompactor`, `TeardownTypes.isCompactorVpc`, the compactor VPC skip in `AwsInfrastructureService`, `Constants.Vpc.COMPACTOR_VPC_NAME` and the ECS members of `Constants.Compactor`; trim their tests
- [x] 7.6 Delete every `Event.Compactor.*` event and `Event.Infra.CompactorVpcSkipping`, and the Koin wiring in `AWSModule` and `TestModules`
- [x] 7.7 Remove `awssdk-ecs` and `awssdk-cloudwatchlogs` from `gradle/libs.versions.toml` and the bundle; confirm the repo has no ECS SDK import, `CompactorTaskDefinition` or `CompactorTaskRegistrar`

## 8. Docs and agent docs

- [x] 8.1 Rewrite `docs/user-guide/compactor.md`; update `docs/SUMMARY.md`, the Mimir, Loki and monitoring pages, `docs/getting-started/setup.md` (policy list), `docs/reference/commands.md` and `docs/reference/pyroscope-configuration.md`
- [x] 8.2 Update `bin/set-policies` and `bin/set-policies.test.sh` for four policies
- [x] 8.3 Update the `services/aws`, `configuration`, `commands`, `events` and `providers` CLAUDE.md files, and the `observability-stack` skill

## 8a. Larger subnets and the public IP fallback

- [x] 8a.1 `CidrBlock.subnetCidr` splits the VPC into eight equal subnets of the VPC prefix + 3 with 32-bit address math; index 0 to 7, a ninth zone fails fast; the VPC prefix must be `/25` or larger
- [x] 8a.2 Remove `Constants.Vpc.subnetCidr`; Packer uses `CidrBlock.DEFAULT.subnetCidr(0)`; `init --azs` rejects more than `Constants.Vpc.MAX_AVAILABILITY_ZONES`
- [x] 8a.3 Update `CidrBlockTest`, `InfrastructureConfigTest`, `InitTest`, `PlatformCniTest`, `CiliumInspectionServiceTest` and the LocalStack VPC integration tests for `/19` subnets
- [x] 8a.4 `DefaultExternalIpService` asks `checkip.amazonaws.com`, trims the answer, and falls back to `api.ipify.org` on a failure or a non-IPv4 answer; the fallback decisions are unit-tested with an injected fetch
- [x] 8a.5 Update `docs/user-guide/network-connectivity.md` and `docs/reference/commands.md`

## 9. Verification

- [x] 9.1 Run `./gradlew check` (JDK 21) with detekt and ktlint clean, with no new `@Suppress`, threshold change or baseline entry
- [ ] 9.2 Before QA, stop the live Fargate compactor service with the previous build's `observability compactor stop`
- [ ] 9.3 Real-AWS QA with two small clusters on one account, with the off-hours shutdown opt-out tag: start cluster 1 and confirm with `observability compactor status` and the compactor logs that it holds the lease and compacts; start cluster 2 and confirm its compactor pod is Ready and idle (no compactor processes, standby logs); stop cluster 1 and confirm cluster 2 takes the lease after the TTL and starts compacting (Mimir and Tempo first, Loki after about 10 minutes); then `down` both clusters
