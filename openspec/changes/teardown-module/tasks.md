Each task is marked **[M]** mechanical (Sonnet) or **[J]** judgement (Opus).  Paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/` unless they start with `src/`.

Work test first: write the test, see it fail, then write the code.  Run every test's control (`test-plan.md`) and record its failing run in the PR.  Read exit codes through `DownExecutorFixture` only.

## 0. The bugs, seen failing on main

- [ ] 0.1 [J] On `main`'s code, before any other task, add the five tests in test-plan.md section 0 to `src/test/.../commands/DownTest.kt`, using `main`'s mocks: bug A, bug B (twice), bug C on a state that reaches the save, and the dead VPC id with `getVpcName` throwing `InvalidVpcID.NotFound` through a real `AwsInfrastructureService`.  Run them, record the five failures in the PR, and commit them on their own.  Task 4.3 ports them to the fakes.

## 1. Neutral types and the scope rule

- [ ] 1.1 [M] Create `services/teardown/TeardownTypes.kt` with the types in design D1 and D3:
  - `TeardownTarget` (with `from`), `TeardownScope`, `AllTagged` and `Packer` (each implements both interfaces);
  - `TeardownPlan`, `ExecutablePlan`, `NothingRecorded`, `NothingReason`, `DeviceOnly`, `Networks` (no `scope` field; `workspace: WorkspacePlan?`, `dataBuckets`), `WorkspacePlan`;
  - `ConfirmedPlan` (`internal` constructor);
  - `PlannedNetwork` (with `clusterId`), `NetworkOwnership` (with `GONE`), `ResourceGroup` (`live`), `ResourceKind` (with `ROUTE_ASSOCIATION` and `GATEWAY_ROUTE`; its KDoc says neutral code reads only `INSTANCE`);
  - the sealed `NetworkOutcome` (`Removed`, `Gone`, `NotAttempted`, `NotPlanned`), `NetworkResult`, `TeardownReport` (a `List` of results) and `TeardownRegionMismatch`.

  Give each type its KDoc.  Write `PlannedNetworkTest` and `TeardownTargetTest` (test-plan R2, R3).
- [ ] 1.2 [M] Create `services/teardown/TeardownScopes.kt`, with `resolve(target, state)` and one branch per row of the D2 table.  Write `TeardownScopesTest`, with one case per row plus the blank device id.
- [ ] 1.3 [M] Create `services/teardown/InfrastructureTeardown.kt` (the port, with `region()`) and `BucketTeardown.kt` (the port, `deleteEmptyBuckets(names)`; `MetricsStop` and `DataBucketSweep` move here).
- [ ] 1.4 [M] Write the fixtures and fakes in test-plan "Fixtures":
  - `src/test/.../commands/DownExecutorFixture.kt`, a real `DefaultCommandExecutor` built as `StatusTest.kt:584` builds it, with `userConfigProvider.isSetup()` true.  Every exit-code check and every "no `Command.ExecutionError`" check uses it;
  - `src/test/.../commands/DownSaveFixture.kt`, a state that reaches the tail save;
  - `src/test/.../services/teardown/FakeInfrastructureTeardown.kt`, with the dependency rule, `networkDeleted` only for `OWNED`, the `GONE` rule and a `remove` that reads only its argument;
  - `FakeBucketTeardown.kt` and `TeardownLog`;
  - `src/test/.../services/FakeTeardownBackupService.kt`, with a scripted `Result`, `unsavedSignals` and an `onSave` hook.

## 2. The AWS adapter

- [ ] 2.1 [M] Make a pure move as its own commit, so that `git diff -M` shows it as a move:
  - `AwsInfrastructureService` lines 330-825 go to `services/aws/AwsInfrastructureTeardown.kt`;
  - `DiscoveredResources` goes to `services/aws/AwsNetworkInventory.kt` (`internal`);
  - `DataBucketTeardown` is renamed `S3DataBucketTeardown`.

  Then remove `emrService`, `openSearchService` and `@Suppress("TooManyFunctions")` from `AwsInfrastructureService`.  Update `AwsServicesModule.kt:75-80` and the `AwsBuildSites` lambda (`:185`).  Fix the constructors in `AwsInfrastructureServiceTest`, `AwsInfrastructureServiceIntegrationTest`, `AwsBuildSitesIntegrationTest`, `UpTestFixture`, `UpVpcCidrTest`, `SetupProfileTest`, `SetupProfileIntegrationTest` and `DataBucketTeardownTest` (renamed `S3DataBucketTeardownTest`).
- [ ] 2.2 [M] Add `AwsNetworkInventory.toPlanned()` and `fromPlanned()`; route tables, route associations and gateway routes are `live = false`.  Delete `providers/aws/TeardownTypes.kt` (`TeardownMode` and `TeardownResult` go with it).  Write `AwsNetworkInventoryTest`: every field filled with a distinct id, `vpcName` set, each ownership, and a case with a null gateway.
- [ ] 2.3 [J] `AwsInfrastructureTeardown` implements the port (design D3):
  - `region()` returns the region the EC2 client is built with.
  - `discover(scope, workspaceNetworkId)` dispatches per scope and emits the `Infra.*` events once.  It fetches the EMR and OpenSearch subnet maps once for every scope, through new `EMRService` and `OpenSearchService` map methods; delete `findClustersInVpc` and `findDomainsInVpc` and move their tests to the map methods.
  - `VpcService.findVpcsByTag` returns `TaggedVpc(id, name, tags)`.  `--all` skips the packer VPC on that name before its inventory.  Update `commands/aws/Vpcs.kt` to use the returned tags.
  - Only `InvalidVpcID.NotFound` (read with `awsErrorDetails()?.errorCode()`, as `EC2SecurityGroupService.kt:131` does), from `getVpcName` or `getVpcTags`, for the workspace network under `WholeNetwork` or `ClusterInSuppliedNetwork`, returns a `GONE` network.  Every other failure throws.
  - `remove` emits `VpcKept` for `KEPT_UNTAGGED` only and runs `executeVpcTeardown(fromPlanned(network))`.  It returns `Removed` whose `networkDeleted` is true only when the network is `OWNED` and `deleteVpc` succeeded.
- [ ] 2.4 [J] Plan the route associations and gateway routes:
  - `findRouteTablesInVpc` and `findOwnedResources` return each table's non-main association ids, and for a kept network each route to the planned gateway, from the describe calls they already make (`VpcService.kt:257`, `ClusterVpcResources`).
  - `VpcService.deleteRouteTable(routeTableId, associationIds)` disassociates only those ids.  `VpcService.deleteRoutes(planned)` replaces `deleteRoutesToInternetGateway` and deletes only the planned routes.
  - Update `EC2VpcServiceTest`.
- [ ] 2.5 [M] Add `VpcTeardownRetryPolicy(maxAttempts, baseInterval)`: `RetryUtil.createVpcTeardownRetryConfig(policy)` and `withVpcTeardownRetry(policy, ...)`.  It is a constructor parameter of `EC2VpcService` and `EC2SecurityGroupService`, defaulting to `Constants.Retry.MAX_VPC_TEARDOWN_RETRIES` and `VPC_TEARDOWN_BACKOFF_BASE_MS`.  Update the `providers/CLAUDE.md` retry table.
- [ ] 2.6 [M] Move the teardown cases of `AwsInfrastructureServiceTest` to `AwsInfrastructureTeardownTest`.  Each case calls `discover` and then `remove`; keep every assertion and the `inOrder` test.  Add the R1, R4 and R7 adapter tests from test-plan.md, including the three `networkDeleted` cases and the NotFound cases for both `getVpcName` and `getVpcTags`.
- [ ] 2.7 [M] Koin, in `AwsServicesModule`: `single<InfrastructureTeardown>` and `factory<BucketTeardown>`.
- [ ] 2.8 [M] Integration tests:
  - Move the `discoverResources` cases of `src/integrationTest/.../services/aws/AwsInfrastructureServiceIntegrationTest.kt` (lines 235-305) to `AwsInfrastructureTeardownIntegrationTest`.
  - Add the R1 integration cases (late subnet, late association, late gateway route, owned late subnet) and the R4 NotFound case there.
  - First check whether LocalStack's `DeleteVpc` refuses a VPC that holds a subnet, and record the answer in the PR.

## 3. The module

- [ ] 3.1 [J] Create `services/teardown/WorkspaceTeardown.kt` (design D5):
  - `beforeRemoval()` unpublishes the proxy port.
  - `after(outcome)` always stops the tunnel.  On `Gone`, or `Removed` with no errors or with `networkDeleted`, it removes the device, deletes the auth key, stops the bucket metrics and marks the state down through `update`.  It returns its errors.
  - It takes over `Down.cleanupSocks5Proxy`, `updateClusterState`, `removeTailscaleDevice` and `reportClusterStateFailure`.

  Add `SocksProxyService.unpublishPort()`.  It clears only `Constants.Proxy.PORT_PROPERTY` and never touches `socksProxyHost` or `socksProxyPort`.  Delete the six `DownTest` cases at lines 158-314 and write `WorkspaceTeardownTest` over a real `ClusterStateManager` on `@TempDir` (test-plan R4).  Move the `clearProxySystemProperties` case to `ProcessSocksProxyServiceTest`.
- [ ] 3.2 [J] Create `services/teardown/ClusterTeardown.kt`.  `plan` loads the state, checks the region, resolves, discovers once, lists the planned data buckets and emits no `Teardown.*` event.  `execute` follows design D1:
  - `beforeRemoval`;
  - the loop inside `try`, with one `NetworkResult` per network (`Gone` without a remove call, `NotAttempted` after a throw);
  - `after` in the `finally`;
  - the sweep of the planned buckets last, on full success.

  Write `ClusterTeardownTest` with every `ClusterTeardownTest` case in test-plan.md.
- [ ] 3.3 [M] Rename `services/TeardownPlanner.kt` to `services/teardown/TeardownConfirmation.kt`:
  - drop `modeFor` and `TeardownOptions`;
  - `review(plan, ReviewOptions(dryRun, autoApprove, forceSkips))` follows the D6 table: `--dry-run` wins over `--auto-approve`, `GONE` prompts, the summary ends with the workspace line and the bucket line, and `ForceSkipsTail` comes after the summary and before the dry-run stop;
  - `DryRunDeviceRemovalPreview` and the `No*Found` choice move here.

  Move `TeardownPlannerTest` to `TeardownConfirmationTest` and add one case per table cell.
- [ ] 3.4 [M] Koin: put `ClusterTeardown`, `WorkspaceTeardown` and `TeardownConfirmation` in `ServicesModule`.  `src/test/.../TestModules.kt:286` binds the fakes and drops `TeardownPlanner`.

## 4. Commands

- [ ] 4.1 [J] Rewrite `commands/Down.kt` per design D6:
  - emit `Starting`, then `plan`, then emit the plan's reason events, then `review`;
  - turn `TeardownRegionMismatch` into `Teardown.RegionMismatch` (a new typed event) and a non-zero exit;
  - run the tail-save gate when `workspace != null` and its network is not `GONE`;
  - `execute`, then report;
  - every failure exit is a `CommandFailedException` thrown after its event.

  Remove `TeardownOutcome`, `VpcScope`, `exitCode`, `call()`, `reportForceSkipsTail` and `@Suppress("TooManyFunctions")`.  Leave no `providers.aws` or `services.aws` import.
- [ ] 4.2 [M] `commands/Init.kt`: `refuseLiveInfrastructure` calls `clusterTeardown.plan(CurrentCluster)`.  It refuses when a planned network `holdsResources()` (`CleanRefusedLiveInfrastructure` with the network id, the `INSTANCE` ids and `nonInstanceResourceCount()`), when the workspace network is `GONE` (new `Setup.CleanRefusedNetworkNotFound(vpcId, region)`), and on a region mismatch (new `Setup.CleanRegionMismatch`).  Remove the `infrastructureService` and `liveInfrastructure` fields.  Delete `services/LiveInfrastructureLookup.kt` and move `LiveInfrastructureLookupTest` into `InitTest`, with the new cases.
- [ ] 4.3 [M] Move the `Down*Test` files, the task 0 tests and `DownS3NoExpiryIntegrationTest` to the fakes and fixtures, and remove every Mockito stub of a `Result`.  Move every exit-code assertion to `DownExecutorFixture`.  Rewrite these cases to the bug C behaviour:
  - `DownBackupTest.kt:575`, as `down --all that does not include the workspace network never runs the save`;
  - `:687`, which now saves the tail first.

  Keep every other assertion.
- [ ] 4.4 [J] Add the remaining `DownTest`, `DownCommandTest` and `DownInitScopeTest` cases from test-plan R1, R2, R4 and R6, and `src/integrationTest/.../commands/DownLateResourceIntegrationTest` on `DownSaveFixture`.
- [ ] 4.5 [M] Add the three teardown rules to `architecture/PackageBoundaryTest`, with fixtures in `src/test/.../services/teardown/fixtures/` and `src/test/.../commands/fixtures/` (design-fit D7).  Each fixture's own test is its control.

## 5. Docs

- [ ] 5.1 [M] `docs/reference/commands.md` (`down`):
  - the preview is the plan that is removed, including the routes to the gateway and the workspace reset;
  - a resource created after the preview is not removed and can make `down` fail, possibly after several minutes;
  - `down <vpc-id>` of the workspace's network and a `down --all` that includes it save the tail first (worst case about 23 minutes) and reset the workspace;
  - a recorded VPC that no longer exists is shown as gone and confirmed;
  - a recorded region that differs from the profile's fails `down` and `init --clean`;
  - `down --all` deletes only the empty data buckets of the clusters it removed;
  - `down` of another VPC, `--packer`, a declined prompt and "nothing to tear down" leave the workspace and the tunnel alone.

  Fix "a VPC with nothing left in it" in the save paragraph.
- [ ] 5.2 [M] Update the CLAUDE.md files:
  - `providers/CLAUDE.md`: remove `TeardownTypes.kt`, add the retry policy.
  - `services/aws/CLAUDE.md`: rewrite line 85 (`guarded` errors become `Removed` errors).  Add `AwsInfrastructureTeardown`, `AwsNetworkInventory` and `S3DataBucketTeardown`, and note that `AwsInfrastructureService` now only creates.
  - `services/CLAUDE.md`: a short section on `services/teardown/` covering the ports, the fakes and fixtures, where the scope rule lives, and the ArchUnit rules.
  - `commands/CLAUDE.md`: in the services table, `ClusterTeardown` replaces `TeardownPlanner`.

## 6. Gates and real AWS

- [ ] 6.1 [M] Run `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21, through the build-engineer on Sonnet.  Add no `@Suppress`, threshold change or baseline entry.
- [ ] 6.2 [J] Real AWS L2 (`test-plan-aws.md`): own VPC and supplied VPC, on `main` and on the branch, with the masked describe-before and describe-after records, the comparison and the cleanup.
- [ ] 6.3 [J] Real AWS L1 (`test-plan-aws.md`): on `main` (the control) and on the branch, with the fifo held open on fd 3.  Put the masked commands and output of 6.2 and 6.3 in the PR, with no account numbers.
- [ ] 6.4 [M] Comment on #1056: `Down` calls `ClusterTeardown`, and `AwsInfrastructureTeardown.remove` calls `ClusterNetwork.teardown` (design-fit D9).

## 7. Archive

- [ ] 7.1 [M] At archive, run `openspec-consolidate-specs` and move the "Cluster Teardown" requirement from `cluster-lifecycle` into `cluster-teardown`, so every teardown rule lives in one capability.
