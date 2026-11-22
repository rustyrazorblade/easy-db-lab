# Test plan: teardown module (#1242)

Tiers:
- unit: `./gradlew test`;
- integration: `./gradlew integrationTest`, on LocalStack;
- real AWS.

CI runs unit and integration.

A control is a break in the code.  Make it, see the test fail, then revert it.  Every control below can fail its test; the PR records each control's failing run.

Paths are under `src/test/kotlin/com/rustyrazorblade/easydblab/` unless they start with `src/integrationTest/`.

## Fixtures

Both fixtures below are built in task 0.1, on `main`, so the task 0 tests use them before any refactor.

- **`commands/DownExecutorFixture`.**  Every exit-code check and every "no `Command.ExecutionError`" check runs `Down` (and `Init`, in `DownInitScopeTest`) through a real `DefaultCommandExecutor`, never through `Down().call()`, `assertThatThrownBy` or `testCommandExecutorModule` (which returns `command.call()`, `TestModules.kt:342-350`).  It is built as `StatusTest.kt:584-600` builds it, with all eight parameters:
  - `DefaultCommandExecutor(context, clusterStateManager, requirementCheckDeps = RequirementCheckDeps(userConfigProvider, dockerClientProvider), resourceManager, eventBus, proxyPreflight = ProxyPreflight(clusterStateManager, socksProxyService, WorkspaceShellTools(ToolWrapperInstaller(), EnvScriptWriter(DirectSshRoute(22))), workingDirectory), proxyAvailability = DefaultProxyAvailability(), profileSetupProvider = ProfileSetupCommandProvider { error("profile setup must not run here") })`;
  - the Koin binding `single { CommandFailureReporter(get(), emptyList()) }` (`StatusTest.kt:152`), which the executor's lazy `failureReporter` injects;
  - `userConfigProvider.isSetup()` returns true, because `Down` and `Init` have `@RequireProfileSetup`;
  - `socksProxyService` is `FakeSocksProxyService`.
- **`services/FakeSocksProxyService`.**  It implements every `SocksProxyService` method, plus `unpublishPort`, and logs each call to `TeardownLog`.  Its `unpublishPort` really clears `Constants.Proxy.PORT_PROPERTY` (and nothing else), so the port test reads the real property.
- **`commands/DownSaveFixture`.**  A state that reaches the tail save: `UP`, one control host, no telemetry redirect, `networkMode = NetworkMode.Direct` (so `needsTunnel` is false and no Tailscale step runs), and `FakeSocksProxyService`.  Without it `saveTailBeforeTeardown` (`Down.kt:217-282`) returns before the save, and an `onSave` hook never runs.  Its state records `infrastructure.region` equal to the fake's `location().region`; `DownLateResourceIntegrationTest` records `SharedLocalStack.region()`.  No fixture state records an account id unless its test is about the account check.
- **`services/teardown/FakeInfrastructureTeardown`.**  An in-memory map of network id to `(ownership, clusterId, kind -> ids)`.
  - `add(networkId, kind, id)` works at any time, even during the save hook or the prompt.
  - `location()` returns a settable `ProviderLocation`, `us-west-2` and a fixed fake account by default.
  - `discover(scope, workspaceNetworkId)` reads the map and records the scope.  A network marked `gone` is returned with `ownership = GONE` only when its id is `workspaceNetworkId`, under any of `WholeNetwork`, `ClusterInSuppliedNetwork` and `ByTags`; otherwise `discover` throws, as the adapter does.  For `ClusterInSuppliedNetwork` it returns the scope's `clusterId`.
  - `remove(network)` reads ids **only from its argument**, never from the map, deletes them from the map and records them in `removed`.  Calling it on a `GONE` network fails the test.
  - The dependency rule: when the map still holds ids in the network that the argument did not list, it returns errors `["network <id> still holds <ids>"]` in every ownership.  `networkDeleted` is true only when the network is `OWNED` and the map then holds nothing in it.  A kept network is never deleted.
  - A scripted error, a scripted throw and an `onRemove` hook can be set per network.  A scripted error carries its own `networkDeleted` (default `false`), so a test can script the ENI-timeout case: errors and `networkDeleted = true`.
- **`services/teardown/FakeBucketTeardown`.**  It records `stopMetrics` and the cluster ids given to `deleteEmptyBuckets`.
- **`services/FakeTeardownBackupService`.**  It returns a scripted `Result` (Mockito cannot stub a Kotlin `Result`) and a scripted `unsavedSignals`, and it runs an `onSave` hook.
- **Shared log.**  All the fakes and `FakeSocksProxyService` write to one `TeardownLog`, so the order of calls can be asserted from one list.

No test of the fakes themselves.

## 0. The bugs, on main first (task 0)

Each test below is written on `main`'s code with its mocks, before any refactor, and run there.  The PR records the failing run on main; after that, each test is ported to the fakes (task 4.3).  The control is `main` itself.

| Test (on main) | Fails on main because |
|---|---|
| `DownTest.down of another VPC leaves the workspace state, device and tunnel` (A) | `removeTailscaleDevice` and `updateClusterState` run (`Down.kt:191-194`) |
| `DownTest.a declined prompt does not stop the tunnel` (B) | `cleanupSocks5Proxy` runs (`Down.kt:188`) |
| `DownTest.down with no cluster state does not stop the tunnel` (B) | same line |
| `DownTest.down of the workspace VPC by id saves the tail before removing anything` (C), on the `DownSaveFixture` state with a mocked backup service that records the call | `saveTail = false` (`Down.kt:350`), so the save is never called |
| `DownTest.a recorded VPC that AWS no longer has can be torn down` (dead VPC id): a real `AwsInfrastructureService` whose mocked `VpcService.getVpcName` throws an `Ec2Exception` with error code `InvalidVpcID.NotFound`; asserts exit 0 through `DownExecutorFixture` and the state marked down | the exception propagates out of discovery and `down` exits non-zero |

On the branch, the dead-VPC-id test asserts that the preview shows the network as gone, the prompt is asked, and only a "yes" marks the state down.

## R1. down changes only the confirmed plan

| Test | Tier | Control |
|---|---|---|
| `ClusterTeardownTest.a late resource in a kept network that touches nothing planned survives and the removal succeeds` (`KEPT_SUPPLIED`) | unit | `execute` calls `plan(plan.target)` again and removes that |
| `ClusterTeardownTest.a late resource in an owned network fails the removal` | unit | same break: the late id is removed and the removal succeeds |
| `DownTest.a subnet created during the tail save fails down, keeps the state, and the next plan lists it` (owned, `DownSaveFixture`) | unit | let the workspace step run on a `Removed` with errors and `networkDeleted = false` |
| `DownTest.a supplied VPC whose removal fails keeps the state UP and its vpcId` (`DownSaveFixture`, a scripted subnet error) | unit | in `WorkspaceTeardown.after`, run the reset on any `Removed` whose network is not `OWNED` |
| `DownTest.a resource created during the tail save in a supplied VPC survives` (`DownSaveFixture`) | unit | rediscover in `execute` |
| `DownTest.--all leaves a network that appears after the preview` (the `readAnswer` lambda of `TeardownConfirmation` adds the network to the fake and returns "yes") | unit | rediscover in `execute` |
| `AwsInfrastructureTeardownTest.remove deletes only the planned ids when discovery would now return more` | unit | call `discoverResources` in `remove` |
| `AwsInfrastructureTeardownTest.discover then remove emits ResourceDiscovering once` | unit | rediscover in `remove` |
| `AwsInfrastructureTeardownTest` `networkDeleted` cases, with mocked `VpcService` and the `inOrder` fixture: the ENI wait times out and `deleteVpc` succeeds (errors, `networkDeleted = true`); `deleteVpc` fails (`false`); a kept network with a failed subnet delete (`false`) | unit | return `errors.isEmpty()`; return true for a kept network |
| `AwsNetworkInventoryTest.toPlanned and fromPlanned round-trip every field` | unit | drop `natGatewayIds` from `fromPlanned` |
| `AwsInfrastructureTeardownIntegrationTest.a subnet created after discover survives remove` (supplied VPC) | integration | rediscover in `remove` |
| `AwsInfrastructureTeardownIntegrationTest.a late subnet's association with a planned route table survives` | integration | disassociate every non-main association again |
| `AwsInfrastructureTeardownIntegrationTest.a late gateway route in a kept VPC survives remove` | integration | delete every non-local route to the gateway, as `deleteRoutesToInternetGateway` does today |
| `AwsInfrastructureTeardownIntegrationTest.an owned network with a late subnet fails its VPC delete` | integration | rediscover in `remove` |
| `DownLateResourceIntegrationTest.down --auto-approve on a supplied VPC leaves a subnet made during the save` | integration | rediscover in `remove` |

What the R1 tests assert:
- Each one asserts that `fake.removed` `containsExactlyInAnyOrderElementsOf` the planned ids, and that the late id is still in the fake's map.
- The `DownTest` owned and supplied failure cases also assert, through `DownExecutorFixture`:
  - the exit is non-zero;
  - `state.json` is still `UP` with its `vpcId`;
  - the tunnel was stopped;
  - a second `plan(CurrentCluster)` lists the late id (owned case).

The integration tests run on `SharedLocalStack`, with EMR and OpenSearch mocked, as `AwsInfrastructureServiceIntegrationTest` does today.  Every assertion is scoped to the test's own VPC.  The owned case uses a `VpcTeardownRetryPolicy(2, 10ms)`, so it takes well under a second.  Before that case is written, the implementer checks that LocalStack's `DeleteVpc` refuses a VPC that still holds a subnet; if it does not, the owned case stays in L1 only, and the PR says so.

`DownLateResourceIntegrationTest` runs `Down` through `DownExecutorFixture`, with the real `AwsInfrastructureTeardown` on LocalStack, on the `DownSaveFixture` state.  `FakeTeardownBackupService.onSave` creates a subnet tagged with the cluster's `ClusterId`.  The test first asserts that `onSave` ran, so a missing save fails it for the right reason.  It then asserts that the planned subnet is gone and the late subnet exists.

## R2. The scope rule is in one place

| Test | Tier | Control |
|---|---|---|
| `TeardownScopesTest`, one case per row of D2, plus a device id `""` | unit | swap the `vpcSupplied` branch; drop the `vpcId == id` check; use `isNull` for the device |
| `TeardownTargetTest`: a VPC id beats `--all`, `--all` beats `--packer` (moved from `TeardownPlannerTest:52,59`) | unit | swap the first two branches |
| `ClusterTeardownTest.workspace is set for each target` (own and other VPC id, `--all` with and without the workspace VPC, `--packer`) | unit | compute it from the target only |
| `ClusterTeardownTest.plan fails when the recorded region differs from the client region` (state `us-east-1`, fake `us-west-2`; `discover` is never called) | unit | skip the region check |
| `ClusterTeardownTest.plan fails when the recorded account differs from the client account` (same region; `discover` is never called) | unit | compare the region only |
| `ClusterTeardownTest.plan with no recorded region skips the region check`, and `plan with no recorded account skips the account check` (each plans the network) | unit | treat a null recorded value as a mismatch |
| `UpTest.finalizeInfrastructureState records the account id` (`AccountIdentity` stubbed; the saved `state.json` holds it) | unit | leave `accountId` null |
| `DownTest.a target mismatch exits non-zero and leaves state.json unchanged` (`DownExecutorFixture`, a region case and an account case) | unit | skip the check |
| `DownInitScopeTest.init --clean and down --dry-run get the same scope` (both through `DownExecutorFixture` on one supplied-VPC state; the fake records `ClusterInSuppliedNetwork(vpc, clusterId)` for both) | unit | have `Init` resolve its own `WholeNetwork` scope |
| `InitTest` clean cases on the fake: refused with live resources; refused when the workspace network is `GONE` (`CleanRefusedNetworkNotFound`); refused on a target mismatch; proceeds when the network is empty, `NothingRecorded` or `DeviceOnly`; a discovery failure propagates and deletes nothing | unit | treat `GONE` as empty; treat a thrown `discover` as "nothing live" |
| `InitTest.init --clean in a fresh workspace emits no teardown event` | unit | emit `NoClusterState` in `plan` |
| `PackageBoundaryTest.only services.teardown calls discover` (fixture `CallsDiscover`) | unit | the fixture's own test fails without the rule |

## R3. No provider type in neutral code

| Test | Tier | Control |
|---|---|---|
| `PackageBoundaryTest.neutral teardown code names no AWS type` | unit | the rule run on the fixture `services.teardown.fixtures.NamesAwsInventory` fails |
| `PackageBoundaryTest.only TeardownConfirmation builds a ConfirmedPlan` | unit | the rule run on the fixture `commands.fixtures.BuildsConfirmedPlan` fails |
| `PlannedNetworkTest.summary` equals a literal of today's text, for an owned, a kept-untagged and a supplied network, and the gone line for `GONE` | unit | print `kindLabel` without the name |
| `PlannedNetworkTest.holdsResources is false for a network with only non-live groups` | unit | ignore `live` |

## R4. The workspace step runs only for the workspace's cluster

| Test | Tier | Control |
|---|---|---|
| Ported task 0 tests (A, B, C, dead VPC id) | unit | set `workspace` for `Network(other)`; run `after` on `Declined` and on `NothingToDo`; save the tail only for `CurrentCluster`; key `after` on `report.success` |
| `DownTest.down --packer leaves the workspace alone` | unit | set `workspace` for `Packer` |
| `DownTest.down --all that finds the workspace VPC saves the tail, marks the state down and removes the device` (`DownSaveFixture`) | unit | set `workspace` only for `CurrentCluster` and `Network` |
| `DownTest.down --all that does not find the workspace VPC leaves the workspace alone` (and its supplied-VPC variant) | unit | `AllTagged` always sets `workspace` |
| `ClusterTeardownTest.--all: the workspace network succeeds, another fails: state marked down, exit non-zero` | unit | key `after` on the whole report |
| `ClusterTeardownTest.--all: a throw on an earlier network leaves state.json byte-for-byte unchanged` (the workspace network is second; its outcome is `NotAttempted`) | unit | treat `NotAttempted` like `Removed` with no errors |
| `ClusterTeardownTest.a GONE workspace network gets no remove call and the workspace step runs` | unit | call `remove` on a `GONE` network |
| `ClusterTeardownTest.the workspace step runs before the bucket sweep` (order in `TeardownLog`) | unit | sweep before `after` |
| `ClusterTeardownTest.the sweep gets only the planned cluster ids` (`--all` with two planned networks; a third cluster's id is not passed) | unit | pass every cluster id the fake knows |
| `ClusterTeardownTest.a down of the current cluster sweeps no bucket`, and the same for `Network(id)` and `Packer` (`sweepClusterIds` is empty; `deleteEmptyBuckets` is never called) | unit | fill `sweepClusterIds` for every target |
| `ClusterTeardownTest.a failed --all removal sweeps no bucket` | unit | always sweep |
| `S3DataBucketTeardownIntegrationTest.a missing bucket is nothing to do` (LocalStack; one cluster id has a bucket, one has none: the sweep deletes the first and emits no `DataBucketKept` and no `DataBucketDeleting` for the second, which is in neither list) | integration | emit `DataBucketKept` for `NoSuchBucket` as for any other error |
| `S3DataBucketTeardownIntegrationTest.the bucket name is built from the cluster id` (the bucket `easy-db-lab-data-<id>` is the one deleted) | integration | use the cluster id as the name |
| `DownTest.a GONE plan never calls save` (`Down` on `DownSaveFixture` with the workspace network gone; `FakeTeardownBackupService` records no call) | unit | run the save gate whenever `workspace != null` |
| `ClusterTeardownTest.the only network throws: it records NotAttempted, state.json is byte-for-byte unchanged, the throwable is rethrown` | unit | record nothing for the thrower and let `outcomeFor` default to `Removed(emptyList(), false)` |
| `ClusterTeardownTest.a DeviceOnly plan removes the device and marks the state down` (`after(Gone)`, through `WorkspaceTeardown` over a real `ClusterStateManager`) | unit | call `after(NotAttempted)` for `DeviceOnly` |
| `ClusterTeardownTest.a thrown remove still stops the tunnel` | unit | drop the `finally` |
| `ClusterTeardownTest.the proxy port is unpublished before the first remove` (the fake records `System.getProperty(PORT_PROPERTY)` in `onRemove`; `socksProxyHost` and `socksProxyPort` are unchanged) | unit | unpublish after the loop |
| `AwsInfrastructureTeardownTest.discover maps InvalidVpcID.NotFound to GONE for the workspace network only`, from `describeVpc` throwing that code under `WholeNetwork`, `ClusterInSuppliedNetwork` and `ByTags`; `UnauthorizedOperation`, an empty `vpcs()` list and NotFound for another id all throw | unit | match on the message instead of the code; map every `Ec2Exception` to `GONE`; drop `ByTags` from the rule |
| `AwsInfrastructureTeardownTest.discover makes one DescribeVpcs per named VPC` (mocked `Ec2Client`, `times(1)`) | unit | call `getVpcName` and `getVpcTags` separately again |
| `AwsInfrastructureTeardownTest.ClusterInSuppliedNetwork takes clusterId from the scope` (the VPC carries no `ClusterId` tag; the plan's `clusterId` is the scope's) | unit | read only the VPC tag |
| `AwsNetworkInventoryTest.GATEWAY_ROUTE round-trips an IPv4 and an IPv6 route`, and `ROUTE_ASSOCIATION ids rebuild per route table` (two tables) | unit | encode the destination without `v4` / `v6`; key associations by id alone |
| `AwsInfrastructureTeardownIntegrationTest.discover of a VPC that does not exist`: `WholeNetwork("vpc-0000000000000000f")`, `ClusterInSuppliedNetwork` and `ByTags` of that id as the workspace network each return one `GONE` network; `ByTags` of it as another id throws | integration | map NotFound to `GONE` for every id; call `getVpcName` after the tag-filtered describes and skip it when they are empty |
| `AwsInfrastructureTeardownIntegrationTest.a late IPv6 gateway route in a kept VPC survives remove`, and a planned IPv6 route is deleted (fixture: an IPv6 CIDR block on the VPC and a `::/0` route) | integration | delete IPv6 routes through the IPv4 field |
| `DownTest.down <own-id> of a gone VPC previews it as gone and a yes marks the state down` (non-supplied, so `ByTags`) | unit | drop `ByTags` from the fake's and the adapter's `GONE` rule |
| `WorkspaceTeardownTest` over a real `ClusterStateManager` on `@TempDir`: `after(Removed, no errors)` and `after(Gone)` mark DOWN and clear hosts, EMR, OpenSearch, infrastructure and the auth key id, and keep `clusterId`, `createdAt` and `nodes`; `after(Removed(errors non-empty, networkDeleted = true))` marks DOWN too; `after(Removed with errors, networkDeleted = false)` and `after(NotAttempted)` leave the file byte-for-byte unchanged and stop the tunnel; a field written between `plan` and `after` survives | unit | replace `update` with `replace(loadedEarlier)`; run the cleanup on failure; key `after` on `errors.isEmpty()` |
| `DownTest.an owned network deleted with an ENI-wait error marks the state DOWN and exits non-zero` (`DownExecutorFixture`; the fake's scripted error has `networkDeleted = true`; a second `down` then finds `NothingRecorded`, not a NotFound failure) | unit | key `after` on `errors.isEmpty()` |
| `DownTailscaleDeviceTest` (six cases, on the fake) | unit | skip `removeDevice` on success |

`WorkspaceTeardownTest` replaces the six `DownTest` cases at lines 158-314.  Those cases change a `ClusterState` by hand and never run `Down`, so they are deleted rather than moved.

## R5. The down rules stay

- **`TeardownConfirmationTest`, one case per cell of the D6 table.**  It covers all four plans with dry-run, auto-approve, yes and no, and dry-run with auto-approve (the dry run wins).  Every dry-run case asserts that nothing else was called on any fake.  Extra cases:
  - a `Networks` plan whose only network has all-empty groups still prompts;
  - a `GONE` network prompts, and its dry run prints the gone line and the workspace line, which is what a real run would do;
  - the summary of a plan with `workspace` ends with the workspace line, and a kept network with gateway routes shows "Routes to the gateway: N".

  Controls: define empty as "no `holdsResources`"; let `DryRun` on `Networks([])` fall through to `Proceed`; let `--auto-approve` beat `--dry-run`; skip the prompt for `GONE`; drop the workspace line.
- **`TeardownConfirmationTest.ForceSkipsTail comes after the summary and before the dry-run stop and the prompt`.**  Control: emit it after the prompt.
- **`DownBackupTest`, every case moved to `FakeTeardownBackupService` and `DownSaveFixture`.**
  - A failed save removes nothing: `fake.removed` is empty.
  - `--force` skips the save.
  - A failed removal after a save restores nothing.
  - `the backup runs after the preview and before the teardown` asserts the order on `TeardownLog`.
  - Control: run `execute` before the save gate; the "removes nothing" cases fail.
- **`DownBackupTest.kt:575` is rewritten to the bug C behaviour.**  The new case is `down --all that does not include the workspace network never runs the save`.  `:687`, which names the supplied VPC on the command line, now asserts that the save runs first.
- **`DownDryRunTest`, every case on the fakes.**  Nothing is removed, and the tunnel and the state are untouched.  Control: let `DryRun` fall through to `execute`.
- **`DownS3NoExpiryIntegrationTest`.**  It moves to the fake infrastructure; LocalStack S3 is unchanged.

## R6. One exit path

`DownCommandTest`, every case through `DownExecutorFixture`:
- a teardown that completes with errors exits non-zero;
- a declined prompt exits non-zero;
- no cluster state exits non-zero, and emits `CompletedWithErrors` once;
- `down <typo-id>` (NotFound for an id that is not the workspace's) exits non-zero and changes nothing;
- a successful teardown exits zero.

The error cases also assert that the output holds `CompletedWithErrors` at most once and no `Command.ExecutionError`.

Control: return instead of throwing after `CompletedWithErrors`; map NotFound to an empty plan for every id.

## R7. The delete order and the resources removed are unchanged

| Test | Tier | Control |
|---|---|---|
| `AwsInfrastructureTeardownTest.should delete resources in correct order` (moved, `inOrder`) | unit | swap subnets and route tables |
| The moved `AwsInfrastructureServiceTest` teardown cases (supplied VPC, ByTags, `--all`, packer, error collection) | unit | each keeps its assertion; the moved test fails when its moved code is deleted |
| `AwsInfrastructureTeardownTest.VpcKept is emitted for KEPT_UNTAGGED only` (one case per ownership) | unit | emit it for every kept network |
| `AwsInfrastructureTeardownTest.--all fetches the EMR and OpenSearch maps once` and `skips the packer VPC by the tag search's name, before its inventory` | unit | fetch them per VPC; call `getVpcName` per VPC |
| The moved `EMRService` and `OpenSearchService` finder tests, now on the map methods | unit | each keeps its assertion |
| The moved `AwsInfrastructureServiceIntegrationTest` discovery cases (lines 235-305) | integration | each keeps its assertion |

## Real AWS

See [`test-plan-aws.md`](test-plan-aws.md) for L1 (a late subnet) and L2 (same resources removed).
