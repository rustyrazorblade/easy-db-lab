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
- [x] 2.7 Review round 5: `LeaseLoop` catches `Exception` only; an `InterruptedException` closes the gate and propagates with the flag set, and `runUntilInterrupted` ends `LeaseAgentMain` cleanly. A renewal that throws or answers `VersionMismatch` reads the lease back once, in the same round, and adopts its own write (it never reopens a closed or overdue gate). Acquiring clears the unparsed-version memo. Comments on the first gate window and on the wall-clock `valid-until`. `InMemoryConditionalObjects` gains `loseNextReplaceAnswer` and content-derived versions

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

## 8b. 1,000 nodes, pre-launch capacity checks, and `down --dry-run`

- [x] 8b.1 `waitForInstanceStatusOk` asks DescribeInstanceStatus in batches of 100; `describeInstances` asks in batches of 100
- [x] 8b.2 `ClusterProvisioningService` records each RunInstances call's instances as soon as it returns, and again with the final IPs (`mergeByAlias`)
- [x] 8b.3 `findInstancesByClusterId` reads every DescribeInstances page and counts pending instances; `up` discovers once and passes the result to the instance specs
- [x] 8b.4 `waitForInstancesRunning` waits for every ID under a deadline, tolerates `InvalidInstanceID.NotFound`, and asks only about the instances not yet running
- [x] 8b.5 K3s agents carry the node type and ordinal labels in their config (`easydblab.com/node-ordinal`, app nodes included); `up` waits for every node to register (`K8sService.waitForNodesRegistered`) instead of labeling per node
- [x] 8b.6 K3s runs with `--cluster-cidr=10.224.0.0/12`, `--service-cidr=10.240.0.0/16` and `node-cidr-mask-size=24`; `init --cidr` refuses and VPC auto-selection skips a block overlapping them
- [x] 8b.7 `BoundedParallel` caps per-host work at `Constants.Scale.HOST_CONCURRENCY` (50) in `HostOperationsService` and K3s `setupAgents`; db instance setup, AxonOps and registry TLS run in parallel
- [x] 8b.8 `SshReadinessWait` probes in parallel, re-probes only the hosts not ready, within one time budget; `CiliumNodeImageCheck` checks in parallel
- [x] 8b.9 RunInstances launches one call per subnet with `minCount = maxCount = n`, chunked at 100, with a client token and a `RequestLimitExceeded` retry; aliases are `<serverType><ordinal>`; `Name` tags go on after launch with CreateTags
- [x] 8b.10 The observability pod readiness wait lists once per poll under one deadline; the rollout deadline grows with the node count
- [x] 8b.11 `services/aws/LaunchCapacityPreflight` checks, for the new instances only, the zone count, the zone offerings, the On-Demand standard vCPU quota, the network interface quota and the free subnet addresses, then a RunInstances dry run per server type once the subnets and security group exist; `Ec2AccountCapacity` reads them from EC2 and Service Quotas
- [x] 8b.12 Add `awssdk-servicequotas`; grant `ec2:DescribeInstanceTypes`, `ec2:DescribeInstanceTypeOfferings`, `ec2:DescribeNetworkInterfaces` and `servicequotas:GetServiceQuota` in `iam-policy-ec2.json`
- [x] 8b.13 `down --dry-run` changes nothing in any mode, the no-resources path included: no state update, no tunnel stop, no Tailscale removal, exit 0 (`TeardownOutcome.DryRun`)
- [x] 8b.14 Document the capacity checks and large clusters in `docs/user-guide/large-clusters.md`, `docs/SUMMARY.md` and `docs/reference/commands.md`; update the package CLAUDE.md files
- [x] 8b.15 Review round 1: `up` launches the ordinals missing from the existing aliases (`missingOrdinals`), in the instance specs, the launch and the capacity plan alike, and keeps hosts in ordinal order (`mergeByAlias`)
- [x] 8b.16 Review round 1: each RunInstances call tags its instances with its first ordinal and stride; `onLaunched` runs before naming; discovery names an instance without a `Name` tag from those tags and its launch index
- [x] 8b.17 Review round 1: the pod readiness wait ends at once on a terminal container state (sealed `PodsPoll`), and logs the PVCs only on a timeout
- [x] 8b.18 Review round 1: `down --dry-run` with the VPC gone and a tailnet device recorded only previews the device removal (`Teardown.DryRunDeviceRemovalPreview`)
- [x] 8b.19 Review round 1: the RunInstances dry run runs under the EC2 throttle retry
- [x] 8b.20 Review round 2: `up` waits for every discovered instance that is pending or has no public IP (running wait and status check) and keeps it with the IPs the wait read
- [x] 8b.21 Review round 2: the RunInstances dry run asks about the launch's first call (`RunInstancesRequests.launchPlan`), in its subnet with its ordinal tags; the LocalStack dry-run test names an instance profile it creates, and the refused-subnet LocalStack test is removed (LocalStack answers `DryRunOperation` before checking the subnet; `Ec2AccountCapacityTest` covers the refusal mapping)
- [x] 8b.22 Review round 2: multi-digit ordinals sort numerically (gap test holds db0, db2, db10 and launches db1, db9, db11; the 250-node chunk test asserts db0..db249 in order); `docs/user-guide/tutorial.md` gives the `--azs` default as `a,b,c`, at most 8
- [x] 8b.23 Review round 3: the running wait fails at the first poll that finds an instance shutting-down, terminated, stopping or stopped, naming its ID, alias and state, and says a re-run of `up` replaces it
- [x] 8b.24 Review round 3: `down --dry-run` with no cluster state, or with no VPC and no tailnet device, ends through `TeardownOutcome.DryRun` (carrying the failure): it exits non-zero as before and never stops the tunnel
- [x] 8b.25 Review round 3: the dry-run test asserts the first call's `minCount`/`maxCount`; the `up` not-ready wait test covers a running instance with a null or blank public IP
- [x] 8b.26 Review round 4: the running wait keys ended instances by ID, so a look retried after an exception names each once, and names one with no `Name` tag by the alias its ordinal launch tags derive (the derivation discovery uses); `docs/reference/commands.md` says `down --dry-run` with nothing to tear down exits non-zero, and `docs/user-guide/large-clusters.md` and `services/aws/CLAUDE.md` describe the ended-instance failure

## 9. Verification

- [x] 9.1 Run `./gradlew check` (JDK 21) with detekt and ktlint clean, with no new `@Suppress`, threshold change or baseline entry
- [x] 9.2 Before QA, stop the live Fargate compactor service with the previous build's `observability compactor stop`
- [x] 9.3 Real-AWS QA with two small clusters on one account, with the off-hours shutdown opt-out tag: start cluster 1 and confirm with `observability compactor status` and the compactor logs that it holds the lease and compacts; start cluster 2 and confirm its compactor pod is Ready and idle (no compactor processes, standby logs); stop cluster 1 and confirm cluster 2 takes the lease after the TTL and starts compacting (Mimir and Tempo first, Loki after about 10 minutes); then `down` both clusters
- [x] 9.4 Batch A of the PR 1071 fixes: remove the conditional writes from `ObjectStore`, so `ConditionalObjects` is the only port and its contract runs against `S3ConditionalObjects` on LocalStack; lease times are `kotlin.time.Instant` with the built-in serializer and an unchanged wire format; `CompactorLease.Unreadable`, which `compactor status` reports with the recovery step; on replace, a 404 is a lost race only for `NoSuchKey`; `GateFile.grant` removes its temp file on failure; a failed revoke is logged and survived; `HexFormat` and `DigestInputStream` for digests; the dead `compactor-gate.sh` state and the `DockerPod` volume lookups are gone; the stale VPC KDoc and the shared-compactor-version docs are fixed
- [x] 9.5 Batch B simplifications: `CidrBlock` delegates its address math to `Ipv4Cidr`; one `withEc2ThrottleRetry`; capped name lists use `joinToString(limit, truncated)` with `Constants.Scale.MAX_NAMED_IN_ERROR`; `Math.ceilDiv`; `describeInstanceType` on the paginator; list overloads on `HostOperationsService` and `Up.allNodes()`; `ServerType.ordinalOf`; explicit `missingIndexes`; `InitConfig.nodeGroups()`; one `Down` preview helper; `toInfrastructureContext()`; `createPollUntilRetryConfig(retryExceptions)` replaces `PendingHostsRetry`; dead code removed (`LEASES_ROOT`, `checkForPodFailure`, duplicate Koin tests); fabric8 `Readiness.isPodReady`; one `SharedLocalStack` builder helper; the Pending-timeout case moved to K3s; one Gradle `subprojects` block for the JVM target and detekt gate
