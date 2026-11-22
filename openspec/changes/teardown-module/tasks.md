Each task is marked **[M]** mechanical (Sonnet) or **[J]** judgement (Opus).  Paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/` unless they start with `src/`.

Work test first: write the test, see it fail, then write the code.  Run every test's control (`test-plan.md`) and record its failing run in the PR.  Read exit codes through `CommandExecutor` only.

## 0. The bugs, seen failing on main

- [ ] 0.1 [J] On `main`'s code, before any other task, add the five tests in test-plan.md section 0 to `src/test/.../commands/DownTest.kt`, using `main`'s mocks: bug A, bug B (twice), bug C, and the dead VPC id.  Run them, record the five failures in the PR, and commit them on their own.  Task 4.3 ports them to the fakes.

## 1. Neutral types and the scope rule

- [ ] 1.1 [M] Create `services/teardown/TeardownTypes.kt` with the types in design D1 and D3:
  - `TeardownTarget` (with `from`), `TeardownScope`, `AllTagged` and `Packer` (each implements both interfaces);
  - `TeardownPlan`, `ExecutablePlan`, `NothingRecorded`, `NothingReason`, `DeviceOnly`, `Networks` (`includesWorkspace`);
  - `ConfirmedPlan` (`internal` constructor);
  - `PlannedNetwork`, `NetworkOwnership`, `ResourceGroup` (`live`), `ResourceKind` (with `ROUTE_ASSOCIATION`);
  - `NetworkRemoval` and `TeardownReport`.

  Give each type its KDoc.  Write `PlannedNetworkTest` and `TeardownTargetTest` (test-plan R2, R3).
- [ ] 1.2 [M] Create `services/teardown/TeardownScopes.kt`, with `resolve(target, state)` and one branch per row of the D2 table.  Write `TeardownScopesTest`, with one case per row plus the blank device id.
- [ ] 1.3 [M] Create `services/teardown/InfrastructureTeardown.kt` (the port) and `BucketTeardown.kt` (the port; `MetricsStop` and `DataBucketSweep` move here).
- [ ] 1.4 [M] Write the fakes and `TeardownLog`, as test-plan "Fakes" describes:
  - `src/test/.../services/teardown/FakeInfrastructureTeardown.kt`, with the dependency rule and a `remove` that reads only its argument;
  - `FakeBucketTeardown.kt`;
  - `src/test/.../services/FakeTeardownBackupService.kt`, with a scripted `Result`, `unsavedSignals` and an `onSave` hook.

## 2. The AWS adapter

- [ ] 2.1 [M] Make a pure move as its own commit, so that `git diff -M` shows it as a move:
  - `AwsInfrastructureService` lines 330-825 go to `services/aws/AwsInfrastructureTeardown.kt`;
  - `DiscoveredResources` goes to `services/aws/AwsNetworkInventory.kt` (`internal`);
  - `DataBucketTeardown` is renamed `S3DataBucketTeardown`.

  Then remove `emrService`, `openSearchService` and `@Suppress("TooManyFunctions")` from `AwsInfrastructureService`.  Update `AwsServicesModule.kt:75-80` and the `AwsBuildSites` lambda (`:185`).  Fix the constructors in `AwsInfrastructureServiceTest`, `AwsInfrastructureServiceIntegrationTest`, `AwsBuildSitesIntegrationTest`, `UpTestFixture`, `UpVpcCidrTest`, `SetupProfileTest`, `SetupProfileIntegrationTest` and `DataBucketTeardownTest` (renamed `S3DataBucketTeardownTest`).
- [ ] 2.2 [M] Add `AwsNetworkInventory.toPlanned()` and `fromPlanned()`; route tables and route associations are `live = false`.  Delete `providers/aws/TeardownTypes.kt` (`TeardownMode` and `TeardownResult` go with it).  Write `AwsNetworkInventoryTest`: every field filled with a distinct id, `vpcName` set, each ownership, and a case with a null gateway.
- [ ] 2.3 [J] `AwsInfrastructureTeardown` implements the port (design D3):
  - `discover(scope)` dispatches per scope and emits the `Infra.*` events once.
  - For `AllTagged`, it reads the VPC name first and skips the packer VPC before its inventory, and it fetches the EMR and OpenSearch subnet maps once.  This needs `EMRService` and `OpenSearchService` methods that return the maps, so that `findClustersInVpc` and `findDomainsInVpc` become filters over them.
  - A NotFound VPC returns no network.
  - `remove` emits `VpcKept` for `KEPT_UNTAGGED` only and runs `executeVpcTeardown(fromPlanned(network))`.  It returns a `NetworkRemoval` whose `networkGone` is true when the VPC delete succeeded or the network was kept.
- [ ] 2.4 [J] Plan the route associations:
  - `findRouteTablesInVpc` and `findOwnedResources` return each table's non-main association ids from the describe call they already make (`VpcService.kt:257`, `ClusterVpcResources`).
  - `VpcService.deleteRouteTable(routeTableId, associationIds)` disassociates only those ids.
  - Update `EC2VpcServiceTest`.
- [ ] 2.5 [M] Add `VpcTeardownRetryPolicy(maxAttempts, baseInterval)`: `RetryUtil.createVpcTeardownRetryConfig(policy)` and `withVpcTeardownRetry(policy, ...)`.  It is a constructor parameter of `EC2VpcService` and `EC2SecurityGroupService`, defaulting to `Constants.Retry.MAX_VPC_TEARDOWN_RETRIES` and `VPC_TEARDOWN_BACKOFF_BASE_MS`.  Update the `providers/CLAUDE.md` retry table.
- [ ] 2.6 [M] Move the teardown cases of `AwsInfrastructureServiceTest` to `AwsInfrastructureTeardownTest`.  Each case calls `discover` and then `remove`; keep every assertion and the `inOrder` test.  Add the R1 and R7 adapter tests from test-plan.md.
- [ ] 2.7 [M] Koin, in `AwsServicesModule`: `single<InfrastructureTeardown>` and `factory<BucketTeardown>`.
- [ ] 2.8 [M] Integration tests:
  - Move the `discoverResources` cases of `src/integrationTest/.../services/aws/AwsInfrastructureServiceIntegrationTest.kt` (lines 235-305) to `AwsInfrastructureTeardownIntegrationTest`.
  - Add the three R1 integration cases there.
  - First check whether LocalStack's `DeleteVpc` refuses a VPC that holds a subnet, and record the answer in the PR.

## 3. The module

- [ ] 3.1 [J] Create `services/teardown/WorkspaceTeardown.kt` (design D5):
  - `beforeRemoval()` unpublishes the proxy port.
  - `after(workspaceRemoval)` always stops the tunnel.  On the workspace network's own success, it removes the device, deletes the auth key, stops the bucket metrics and marks the state down through `update`.  It returns its errors.
  - It takes over `Down.cleanupSocks5Proxy`, `updateClusterState`, `removeTailscaleDevice` and `reportClusterStateFailure`.

  Add `SocksProxyService.unpublishPort()`.  It clears only `Constants.Proxy.PORT_PROPERTY` and never touches `socksProxyHost` or `socksProxyPort`.  Delete the six `DownTest` cases at lines 158-314 and write `WorkspaceTeardownTest` over a real `ClusterStateManager` on `@TempDir` (test-plan R4).  Move the `clearProxySystemProperties` case to `ProcessSocksProxyServiceTest`.
- [ ] 3.2 [J] Create `services/teardown/ClusterTeardown.kt`.  `plan` loads the state, resolves, discovers once and emits no `Teardown.*` event.  `execute` follows design D1:
  - `beforeRemoval`;
  - the loop inside `try`, with a result per network;
  - `after` in the `finally`;
  - the sweep last, for `AllTagged` on full success.

  Write `ClusterTeardownTest` with every `ClusterTeardownTest` case in test-plan.md.
- [ ] 3.3 [M] Rename `services/TeardownPlanner.kt` to `services/teardown/TeardownConfirmation.kt`:
  - drop `modeFor` and `TeardownOptions`;
  - `review(plan, ReviewOptions(dryRun, autoApprove, forceSkips))` follows the D6 table and emits `ForceSkipsTail` after the summary and before the dry-run stop;
  - `DryRunDeviceRemovalPreview` and the `No*Found` choice move here.

  Move `TeardownPlannerTest` to `TeardownConfirmationTest` and add one case per table cell.
- [ ] 3.4 [M] Koin: put `ClusterTeardown`, `WorkspaceTeardown` and `TeardownConfirmation` in `ServicesModule`.  `src/test/.../TestModules.kt:286` binds the fakes and drops `TeardownPlanner`.

## 4. Commands

- [ ] 4.1 [J] Rewrite `commands/Down.kt` per design D6:
  - emit `Starting`, then `plan`, then emit the plan's reason events, then `review`;
  - run the tail-save gate when `includesWorkspace` and the plan is not empty;
  - `execute`, then report;
  - every failure exit is a `CommandFailedException` thrown after its event.

  Remove `TeardownOutcome`, `VpcScope`, `exitCode`, `call()` and `@Suppress("TooManyFunctions")`.  Leave no `providers.aws` or `services.aws` import.
- [ ] 4.2 [M] `commands/Init.kt`: `refuseLiveInfrastructure` calls `clusterTeardown.plan(CurrentCluster)` and refuses when a planned network `holdsResources()`.  `CleanRefusedLiveInfrastructure` gets the network id, the `INSTANCE` ids and `nonInstanceResourceCount()`.  Remove the `infrastructureService` and `liveInfrastructure` fields.  Delete `services/LiveInfrastructureLookup.kt` and move `LiveInfrastructureLookupTest` into `InitTest`, with the two new cases.
- [ ] 4.3 [M] Move the `Down*Test` files, the task 0 tests and `DownS3NoExpiryIntegrationTest` to the fakes, and remove every Mockito stub of a `Result`.  Move every exit-code assertion to `CommandExecutor`.  Rewrite these cases to the bug C behaviour:
  - `DownBackupTest.kt:575`, as `down --all that does not include the workspace network never runs the save`;
  - `:687`, which now saves the tail first.

  Keep every other assertion.
- [ ] 4.4 [J] Add the remaining `DownTest`, `DownCommandTest` and `DownInitScopeTest` cases from test-plan R1, R2, R4 and R6, and `src/integrationTest/.../commands/DownLateResourceIntegrationTest`.
- [ ] 4.5 [M] Add the three teardown rules to `architecture/PackageBoundaryTest`, with fixtures in `src/test/.../services/teardown/fixtures/` and `src/test/.../commands/fixtures/` (design D7).  Each fixture's own test is its control.

## 5. Docs

- [ ] 5.1 [M] `docs/reference/commands.md` (`down`):
  - the preview is the plan that is removed;
  - a resource created after the preview is not removed and can make `down` fail, possibly after several minutes;
  - `down <vpc-id>` of the workspace's network and a `down --all` that includes it save the tail first and reset the workspace;
  - `down` of another VPC, `--packer`, a declined prompt and "nothing to tear down" leave the workspace and the tunnel alone.

  Fix "a VPC with nothing left in it" in the save paragraph.
- [ ] 5.2 [M] Update the CLAUDE.md files:
  - `providers/CLAUDE.md`: remove `TeardownTypes.kt`, add the retry policy.
  - `services/aws/CLAUDE.md`: rewrite line 85 (`guarded` errors become `NetworkRemoval` errors).  Add `AwsInfrastructureTeardown`, `AwsNetworkInventory` and `S3DataBucketTeardown`, and note that `AwsInfrastructureService` now only creates.
  - `services/CLAUDE.md`: a short section on `services/teardown/` covering the ports, the fakes, where the scope rule lives, and the ArchUnit rules.
  - `commands/CLAUDE.md`: in the services table, `ClusterTeardown` replaces `TeardownPlanner`.

## 6. Gates and real AWS

- [ ] 6.1 [M] Run `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21, through the build-engineer on Sonnet.  Add no `@Suppress`, threshold change or baseline entry.
- [ ] 6.2 [J] Real AWS L2 (test-plan "Real AWS"): own VPC and supplied VPC, on `main` and on the branch, with the describe-before and describe-after records, the comparison and the cleanup.
- [ ] 6.3 [J] Real AWS L1: on `main` (the control) and on the branch, using the fifo, `jq` and the secondary block steps.  Put the commands and the output of 6.2 and 6.3 in the PR, with no account numbers.
- [ ] 6.4 [M] Comment on #1056: `Down` calls `ClusterTeardown`, and `AwsInfrastructureTeardown.remove` calls `ClusterNetwork.teardown` (design D9).
