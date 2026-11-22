Each task is marked **[M]** mechanical (Sonnet) or **[J]** judgement (Opus).  Paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/` unless they start with `src/`.  Work test first: write the test, see it fail, then write the code.  Run every test's control (design.md, "Test plan") and note it in the PR.

## 1. Neutral types and the scope rule

- [ ] 1.1 [M] `services/teardown/TeardownTypes.kt`: `TeardownTarget`, `TeardownScope`, `TeardownPlan` (`Nothing`, `DeviceOnly`, `Networks`), `ConfirmedPlan`, `PlannedNetwork` (with `holdsResources()` and `summary()`), `ResourceGroup`, `ResourceKind`, `NetworkRemoval`, `TeardownReport`.  KDoc on each.  Unit test `PlannedNetworkTest`: `summary()` text for an owned and a kept network (same text as `DiscoveredResources.summary()` today), `holdsResources()` is false for route tables only (today's rule)
- [ ] 1.2 [M] `services/teardown/TeardownScopes.kt`: `resolve(target, state)`, one branch per row of design D2.  `TeardownScopesTest`, one case per row, with the two controls
- [ ] 1.3 [M] `services/teardown/InfrastructureTeardown.kt` (port) and `BucketTeardown.kt` (port; `MetricsStop` and `DataBucketSweep` move here from `services/aws/DataBucketTeardown.kt`)
- [ ] 1.4 [M] `src/test/.../services/teardown/FakeInfrastructureTeardown.kt` and `FakeBucketTeardown.kt` as design.md describes.  No test of the fakes themselves

## 2. The EC2 adapter (pure move)

- [ ] 2.1 [M] Move `AwsInfrastructureService` lines 330-783 (discovery, `teardown*`, `executeVpcTeardown` and its phases, `guarded`, `logError`, `TeardownStepResult`, `Phase1Result`) into `services/aws/Ec2InfrastructureTeardown.kt`.  No behaviour change.  `AwsInfrastructureService` keeps only creation; drop its `emrService`/`openSearchService` dependencies if nothing else uses them, and update `AwsBuildSites`' `infrastructureFor` lambda
- [ ] 2.2 [M] Move `DiscoveredResources` into `services/aws/Ec2Inventory.kt` (`internal`), with `toPlanned()` and `fromPlanned()`.  Delete `providers/aws/TeardownTypes.kt` (`TeardownMode`, `TeardownResult` are gone).  `Ec2InventoryTest` round-trip, with its control
- [ ] 2.3 [J] `Ec2InfrastructureTeardown` implements the port: `discover(scope)` dispatches to the existing discovery per scope (`WholeNetwork`, `ByTags` with `VpcOwnership`, `ClusterInSuppliedNetwork`, `AllTagged` with the packer skip, `Packer`) and emits the same `Infra.*` events once.  `Infra.VpcKept` is emitted by `remove` for a `ByTags` network that is kept, before anything is removed, as today.  `remove(network)` runs `executeVpcTeardown(fromPlanned(network))` and never discovers
- [ ] 2.4 [M] Move the teardown cases of `AwsInfrastructureServiceTest` to `Ec2InfrastructureTeardownTest` (call `discover` then `remove` where they called `teardown*(dryRun = false)`; keep every assertion and the `inOrder` order test).  Add `remove makes no describe call` with its control
- [ ] 2.5 [M] `DataBucketTeardown` implements `BucketTeardown`.  Koin in `AwsServicesModule`: `single<InfrastructureTeardown>`, `factory<BucketTeardown>`
- [ ] 2.6 [M] `src/integrationTest/.../services/aws/Ec2InfrastructureTeardownIntegrationTest`: on `SharedLocalStack`, discover a VPC, add a subnet, remove; the new subnet is still there.  Scope every assertion to its own VPC (shared backend)

## 3. The module

- [ ] 3.1 [J] `services/teardown/WorkspaceTeardown.kt`: `beforeRemoval()` (unpublish the proxy port) and `after(report)` (tunnel stop always; on success device removal, auth key, bucket metrics, state reset through `ClusterStateManager.update`; a failed device removal fails the report).  Moves `Down.cleanupSocks5Proxy`, `updateClusterState`, `removeTailscaleDevice`, `reportClusterStateFailure`.  Add `SocksProxyService.unpublishPort()` (clears only `Constants.Proxy.PORT_PROPERTY`; never touch `socksProxyHost`/`socksProxyPort`) and move `DownTest`'s `clearProxySystemProperties` and `cleanupSocks5Proxy` cases to `WorkspaceTeardownTest` and `ProcessSocksProxyServiceTest`
- [ ] 3.2 [J] `services/teardown/ClusterTeardown.kt`: `plan(target)` (load state, `TeardownScopes.resolve`, `discover` once, compute `includesWorkspace` per design D5, emit `NoClusterState`/`NoVpcId`/`PreparingVpc`/`FindingPackerVpc`), `execute(confirmed)` (workspace `beforeRemoval` when it applies, `remove` each planned network in order, collect errors, sweep empty buckets for `AllTagged` on success, then the workspace step).  `ClusterTeardownTest` over the fakes: the late-resource test and its control, the shared-scope test, `includesWorkspace` for each target, the empty plan (design D8) removes nothing and runs only the workspace step, `DeviceOnly` runs only the workspace step
- [ ] 3.3 [M] Rename `services/TeardownPlanner.kt` to `services/teardown/TeardownConfirmation.kt`: drop `modeFor`; `review(plan, options)` returns `Proceed(ConfirmedPlan)`, `DryRun`, `Declined`, `Nothing`; the summary is `PlannedNetwork.summary()` joined by newline; the dry-run device preview (`DryRunDeviceRemovalPreview`) moves here.  Move `TeardownPlannerTest` to `TeardownConfirmationTest`
- [ ] 3.4 [M] Koin: `ClusterTeardown`, `WorkspaceTeardown` and `TeardownConfirmation` in `ServicesModule`; update `src/test/.../TestModules.kt` to bind the fakes

## 4. Commands

- [ ] 4.1 [J] Rewrite `commands/Down.kt` per design D6: flags to `TeardownTarget`, `plan`, `review`, the tail-save gate when `includesWorkspace` and the plan holds resources, `execute`, report; every failure is `CommandFailedException` after its event.  Remove `TeardownOutcome`, `VpcScope`, `exitCode`, `call()`, `@Suppress("TooManyFunctions")`.  No `providers.aws` or `services.aws` import
- [ ] 4.2 [M] `commands/Init.kt`: `refuseLiveInfrastructure` calls `clusterTeardown.plan(CurrentCluster)` and refuses when a planned network `holdsResources()`; `CleanRefusedLiveInfrastructure` gets the network id, the `INSTANCE` group's ids and the count of the rest.  Delete `services/LiveInfrastructureLookup.kt` and move `LiveInfrastructureLookupTest`'s cases into `InitTest` over the fake
- [ ] 4.3 [M] Move the six `Down*Test` files and `DownS3NoExpiryIntegrationTest` from mocks of `AwsInfrastructureService` to `FakeInfrastructureTeardown`.  Keep every behaviour each test checks; drop assertions on `teardown*(dryRun)` calls
- [ ] 4.4 [J] New `DownTest` cases for R1, R4 and R6 (design.md test plan), each with its control
- [ ] 4.5 [M] `src/test/.../architecture/TeardownBoundaryTest.kt` and its fixture, with the fixture test as the control

## 5. Docs

- [ ] 5.1 [M] `docs/reference/commands.md` (`down`): the preview is the plan that is removed; a resource created after the preview is not removed and can make `down` fail; `down <vpc-id>` of the workspace's network and `down --all` save the tail; `down` of another VPC, `--packer` and a declined prompt leave the workspace alone
- [ ] 5.2 [M] `providers/CLAUDE.md` (remove `TeardownTypes.kt`), `services/aws/CLAUDE.md` (`Ec2InfrastructureTeardown`, `Ec2Inventory`; `AwsInfrastructureService` is creation only), `commands/CLAUDE.md` (services table: `ClusterTeardown`), root `CLAUDE.md` if a new rule is needed

## 6. Gates and real AWS

- [ ] 6.1 [M] `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (build-engineer on Sonnet).  No new `@Suppress`, threshold or baseline entry
- [ ] 6.2 [J] Real AWS L2 (design.md R7): on `main` and on the branch, `init --up` then `down --auto-approve`, once with its own VPC and once with `init --vpc`.  Tag `embark_offhours=off`.  Compare the resources removed and the `Infra.*` event order
- [ ] 6.3 [J] Real AWS L1 (design.md R1): at the `down` prompt create a subnet in the cluster VPC, then confirm; the subnet survives, `down` exits non-zero, the state is not marked down; a second `down` lists and removes it.  Put the commands and output of 6.2 and 6.3 in the PR
