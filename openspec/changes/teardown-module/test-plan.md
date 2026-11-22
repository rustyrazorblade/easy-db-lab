# Test plan: teardown module (#1242)

Tiers:
- unit: `./gradlew test`;
- integration: `./gradlew integrationTest`, on LocalStack;
- real AWS.

CI runs unit and integration.

A control is a break in the code.  Make it, see the test fail, then revert it.  Every control below can fail its test; the PR records each control's failing run.

Exit codes are always read through `CommandExecutor`, never from `Down().call()` and never through `assertThatThrownBy`.  Paths are under `src/test/kotlin/com/rustyrazorblade/easydblab/` unless they start with `src/integrationTest/`.

## Fakes

- **`services/teardown/FakeInfrastructureTeardown`.**  An in-memory map of network id to `(ownership, kind -> ids)`.
  - `add(networkId, kind, id)` works at any time, even during the save hook.
  - `discover(scope)` reads the map and records the scope it was given.
  - `remove(network)` reads ids **only from its argument**, never from the map.  It deletes those ids from the map and records them in `removed`.
  - The dependency rule: when the network is `OWNED` and the map still holds ids in it that the argument did not list, it returns `NetworkRemoval(errors = ["network <id> still holds <ids>"], networkGone = false)`.  Otherwise it deletes the network and returns `networkGone = true`.
  - A scripted error, a scripted throw, and an `onRemove` hook can be set per network.
- **`services/teardown/FakeBucketTeardown`.**  It records `stopMetrics` and `deleteEmptyBuckets` calls.
- **`services/FakeTeardownBackupService`.**  It returns a scripted `Result` (Mockito cannot stub a Kotlin `Result`) and a scripted `unsavedSignals`, and it runs an `onSave` hook.
- **Shared log.**  All three fakes and a `FakeSocksProxyService` write to one `TeardownLog`, so the order of calls can be asserted from one list.

No test of the fakes themselves.

## 0. The bugs, on main first (task 0)

Each test below is written on `main`'s code with its mocks, before any refactor, and run there.  The PR records the failing run on main; after that, each test is ported to the fakes (task 4.3).  The control is `main` itself.

| Test (on main) | Fails on main because |
|---|---|
| `DownTest.down of another VPC leaves the workspace state, device and tunnel` (A) | `removeTailscaleDevice` and `updateClusterState` run (`Down.kt:191-194`) |
| `DownTest.a declined prompt does not stop the tunnel` (B) | `cleanupSocks5Proxy` runs (`Down.kt:188`) |
| `DownTest.down with no cluster state does not stop the tunnel` (B) | same line |
| `DownTest.down of the workspace VPC by id saves the tail before removing anything` (C) | `saveTail = false` (`Down.kt:350`) |
| `DownTest.a removal that deletes the VPC with an ENI wait error marks the state down` (dead VPC id) | the result fails, so `updateClusterState` is skipped |

## R1. down changes only the confirmed plan

| Test | Tier | Control |
|---|---|---|
| `ClusterTeardownTest.a late resource in a kept network survives and the removal succeeds` (`KEPT_SUPPLIED`) | unit | `execute` calls `plan(plan.target)` again and removes that |
| `ClusterTeardownTest.a late resource in an owned network fails the removal` | unit | same break: the late id is removed and the removal succeeds |
| `DownTest.a subnet created during the tail save fails down, keeps the state, and the next plan lists it` (owned) | unit | let the workspace step run on a failed workspace removal |
| `DownTest.a resource created during the tail save in a supplied VPC survives` | unit | rediscover in `execute` |
| `DownTest.--all leaves a network that appears after the preview` | unit | rediscover in `execute` |
| `AwsInfrastructureTeardownTest.remove deletes only the planned ids when discovery would now return more` | unit | call `discoverResources` in `remove` |
| `AwsInfrastructureTeardownTest.discover then remove emits ResourceDiscovering once` | unit | rediscover in `remove` |
| `AwsNetworkInventoryTest.toPlanned and fromPlanned round-trip every field` | unit | drop `natGatewayIds` from `fromPlanned` |
| `AwsInfrastructureTeardownIntegrationTest.a subnet created after discover survives remove` (supplied VPC) | integration | rediscover in `remove` |
| `AwsInfrastructureTeardownIntegrationTest.a late subnet's association with a planned route table survives` | integration | disassociate every non-main association again |
| `AwsInfrastructureTeardownIntegrationTest.an owned network with a late subnet fails its VPC delete` | integration | rediscover in `remove` |
| `DownLateResourceIntegrationTest.down --auto-approve on a supplied VPC leaves a subnet made during the save` | integration | rediscover in `remove` |

What the R1 tests assert:
- Each one asserts that `fake.removed` `containsExactlyInAnyOrderElementsOf` the planned ids, and that the late id is still in the fake's map.
- The `DownTest` owned case also asserts:
  - the exit is non-zero;
  - the state is still UP with its VPC id;
  - the tunnel was stopped;
  - a second `plan(CurrentCluster)` lists the late id.

The integration tests run on `SharedLocalStack`, with EMR and OpenSearch mocked, as `AwsInfrastructureServiceIntegrationTest` does today.  Every assertion is scoped to the test's own VPC.  The owned case uses a `VpcTeardownRetryPolicy(2, 10ms)`, so it takes well under a second.  Before that case is written, the implementer checks that LocalStack's `DeleteVpc` refuses a VPC that still holds a subnet; if it does not, the owned case stays in L1 only, and the PR says so.

`DownLateResourceIntegrationTest` runs `Down` through Koin with the real `AwsInfrastructureTeardown` on LocalStack.  `FakeTeardownBackupService.onSave` creates a subnet tagged with the cluster's `ClusterId`.  The test asserts that the planned subnet is gone and the late subnet exists.

## R2. The scope rule is in one place

| Test | Tier | Control |
|---|---|---|
| `TeardownScopesTest`, one case per row of D2, plus a device id `""` | unit | swap the `vpcSupplied` branch; drop the `vpcId == id` check; use `isNull` for the device |
| `TeardownTargetTest`: a VPC id beats `--all`, `--all` beats `--packer` (moved from `TeardownPlannerTest:52,59`) | unit | swap the first two branches |
| `ClusterTeardownTest.includesWorkspace` for each target (own and other VPC id, `--all` with and without the workspace VPC, `--packer`) | unit | compute it from the target only |
| `DownInitScopeTest.init --clean and down --dry-run get the same scope` (both through `CommandExecutor` on one supplied-VPC state; the fake records `ClusterInSuppliedNetwork(vpc, clusterId)` for both) | unit | have `Init` resolve its own `WholeNetwork` scope |
| `InitTest` clean cases on the fake: refused with live resources; proceeds when empty, gone, `NothingRecorded` or `DeviceOnly`; a discovery failure propagates and deletes nothing | unit | treat a thrown `discover` as "nothing live" |
| `InitTest.init --clean in a fresh workspace emits no teardown event` | unit | emit `NoClusterState` in `plan` |
| `PackageBoundaryTest.only services.teardown calls discover` (fixture `CallsDiscover`) | unit | the fixture's own test fails without the rule |

## R3. No provider type in neutral code

| Test | Tier | Control |
|---|---|---|
| `PackageBoundaryTest.neutral teardown code names no AWS type` | unit | the rule run on the fixture `services.teardown.fixtures.NamesAwsInventory` fails |
| `PackageBoundaryTest.only TeardownConfirmation builds a ConfirmedPlan` | unit | the rule run on the fixture `commands.fixtures.BuildsConfirmedPlan` fails |
| `PlannedNetworkTest.summary` equals a literal of today's text, for an owned, a kept-untagged and a supplied network | unit | print `kindLabel` without the name |
| `PlannedNetworkTest.holdsResources is false for a network with only non-live groups` | unit | ignore `live` |

## R4. The workspace step runs only for the workspace's cluster

| Test | Tier | Control |
|---|---|---|
| Ported task 0 tests (A, B, C, dead VPC id) | unit | set `includesWorkspace = true` for `Network`; run `after` on `Declined` and on `NothingToDo`; save the tail only for `CurrentCluster`; key `after` on `report.success` |
| `DownTest.down --packer leaves the workspace alone` | unit | `includesWorkspace = true` for `Packer` |
| `DownTest.down --all that finds the workspace VPC saves the tail, marks the state down and removes the device` | unit | `includesWorkspace` true only for `CurrentCluster` and `Network` |
| `DownTest.down --all that does not find the workspace VPC leaves the workspace alone` (and its supplied-VPC variant) | unit | `AllTagged` always includes the workspace |
| `ClusterTeardownTest.--all: the workspace network succeeds, another fails: state marked down, exit non-zero` | unit | key `after` on the whole report |
| `ClusterTeardownTest.the workspace step runs before the bucket sweep` (order in `TeardownLog`) | unit | sweep before `after` |
| `ClusterTeardownTest.a failed --all removal sweeps no bucket` | unit | always sweep |
| `ClusterTeardownTest.a thrown remove still stops the tunnel` | unit | drop the `finally` |
| `ClusterTeardownTest.the proxy port is unpublished before the first remove` (the fake records `System.getProperty(PORT_PROPERTY)` in `onRemove`; `socksProxyHost` and `socksProxyPort` are unchanged) | unit | unpublish after the loop |
| `ClusterTeardownTest.an empty plan for the workspace runs only the workspace step` | unit | skip `after` when the plan is empty |
| `AwsInfrastructureTeardownTest.discover of a VPC that does not exist returns no network` | unit | let NotFound propagate |
| `WorkspaceTeardownTest` over a real `ClusterStateManager` on `@TempDir`: `after(success)` marks DOWN and clears hosts, EMR, OpenSearch, infrastructure and the auth key id, and keeps `clusterId`, `createdAt` and `nodes`; `after(failure)` leaves the file byte-for-byte unchanged and stops the tunnel; a field written between `plan` and `after` survives | unit | replace `update` with `replace(loadedEarlier)`; run the cleanup on failure |
| `DownTailscaleDeviceTest` (six cases, on the fake) | unit | skip `removeDevice` on success |
| `DownCommandTest.a failed teardown keeps the state and stops the tunnel` | unit | mark down on failure; skip the tunnel stop on failure |

`WorkspaceTeardownTest` replaces the six `DownTest` cases at lines 158-314.  Those cases change a `ClusterState` by hand and never run `Down`, so they are deleted rather than moved.

## R5. The down rules stay

- **`TeardownConfirmationTest`, one case per cell of the D6 table.**  It covers all four plans with dry-run, auto-approve, yes and no.  Every dry-run case asserts that nothing else was called on any fake.  There is also a case for a `Networks` plan whose only network has all-empty groups: it still prompts.  Controls:
  - define empty as "no `holdsResources`";
  - let `DryRun` on `Networks([])` fall through to `Proceed`.
- **`TeardownConfirmationTest.ForceSkipsTail comes after the summary and before the dry-run stop and the prompt`.**  Control: emit it after the prompt.
- **`DownBackupTest`, every case moved to `FakeTeardownBackupService`.**
  - A failed save removes nothing: `fake.removed` is empty.
  - `--force` skips the save.
  - A failed removal after a save restores nothing.
  - `the backup runs after the preview and before the teardown` asserts the order on `TeardownLog`.
  - Control: run `execute` before the save gate; the "removes nothing" cases fail.
- **`DownBackupTest.kt:575` is rewritten to the bug C behaviour.**  The new case is `down --all that does not include the workspace network never runs the save`.  `:687`, which names the supplied VPC on the command line, now asserts that the save runs first.
- **`DownDryRunTest`, every case on the fakes.**  Nothing is removed, and the tunnel and the state are untouched.  Control: let `DryRun` fall through to `execute`.
- **`DownS3NoExpiryIntegrationTest`.**  It moves to the fake infrastructure; LocalStack S3 is unchanged.

## R6. One exit path

`DownCommandTest`, every case through `CommandExecutor`:
- a teardown that completes with errors exits non-zero;
- a declined prompt exits non-zero;
- no cluster state exits non-zero;
- a successful teardown exits zero.

The error case also asserts that the output holds `CompletedWithErrors` once and no `Command.ExecutionError`.

Control: return instead of throwing after `CompletedWithErrors`.

## R7. The delete order and the resources removed are unchanged

| Test | Tier | Control |
|---|---|---|
| `AwsInfrastructureTeardownTest.should delete resources in correct order` (moved, `inOrder`) | unit | swap subnets and route tables |
| The moved `AwsInfrastructureServiceTest` teardown cases (supplied VPC, ByTags, `--all`, packer, error collection) | unit | each keeps its assertion; the moved test fails when its moved code is deleted |
| `AwsInfrastructureTeardownTest.VpcKept is emitted for KEPT_UNTAGGED only` (one case per ownership) | unit | emit it for every kept network |
| `AwsInfrastructureTeardownTest.--all fetches the EMR and OpenSearch maps once` and `skips the packer VPC before its inventory` | unit | fetch them per VPC; read the inventory first |
| The moved `AwsInfrastructureServiceIntegrationTest` discovery cases (lines 235-305) | integration | each keeps its assertion |

## Real AWS

Both runs use `AWS_PROFILE=easy-db-lab` and `--tag embark_offhours=off`, each in its own workspace under `clusters/`.  Names below are placeholders; the PR records the real output without account numbers.

**L1, a late subnet (R1).**  It runs once on `main`, as the control, and once on the branch.
1. `easy-db-lab init l1 --up --tag embark_offhours=off`.
2. `mkfifo answer`, then `easy-db-lab down < answer > down.log 2>&1 &`.
3. Wait until `down.log` shows the confirmation prompt.
4. Read the network: `VPC=$(jq -r .vpcId state.json)`, and its block with `aws ec2 describe-vpcs --vpc-ids "$VPC" --query 'Vpcs[0].CidrBlock'`.
5. Pick an address range the cluster does not use.  The eight /19 subnets fill a /16, so first add a secondary block: `aws ec2 associate-vpc-cidr-block --vpc-id "$VPC" --cidr-block 100.64.0.0/24`.
6. Create the late subnet: `aws ec2 create-subnet --vpc-id "$VPC" --cidr-block 100.64.0.0/24`.  Record its id.
7. Answer the prompt: `echo yes > answer`, and wait for `down` to exit.

| | on main (control) | on the branch |
|---|---|---|
| exit code | 0 | non-zero |
| `aws ec2 describe-subnets --subnet-ids <late>` | NotFound (deleted, never shown) | the subnet exists |
| `jq .infrastructureStatus state.json` | `"DOWN"` | `"UP"`, `vpcId` kept |
| a second `down --auto-approve` | (not needed) | lists the late subnet, removes it and the VPC, exits 0 |

**L2, same resources removed (R7).**  It runs on `main` and on the branch, for an own VPC and for a supplied VPC.
- **Supplied VPC setup:**
  - `aws ec2 create-vpc --cidr-block 10.77.0.0/16 --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=l2-supplied}]'`;
  - `aws ec2 create-subnet --vpc-id <id> --cidr-block 10.77.255.0/24`, a pre-existing user resource;
  - then `easy-db-lab init l2s --vpc <id> --up --tag embark_offhours=off`.  `up` splits the VPC's own block into eight /19 subnets and uses the first one per zone (three by default), so the user's subnet in the eighth /19 does not collide.
- **Before `down --auto-approve`**, record the network with `describe-instances`, `describe-subnets`, `describe-security-groups`, `describe-route-tables` and `describe-internet-gateways`, each with `--filters Name=vpc-id,Values=<vpc>`.  Save the output to `before.json`.
- **After `down`:**
  - The own-VPC run: every recorded resource and the VPC are gone.
  - The supplied-VPC run: every resource tagged with the cluster's `ClusterId` is gone, and the VPC, the `10.77.255.0/24` subnet and the main route table remain.
- **Compare main and branch** by kind and count of resources removed.  Compare the console lines from the first `Tearing down VPC:` to the end; they must match.  The discovery lines print once on the branch and twice on main; that is the expected difference.
- **Cleanup:**
  - `aws ec2 delete-subnet` for the `10.77.255.0/24` subnet;
  - `aws ec2 delete-vpc` for the supplied VPC;
  - `describe-vpcs` with the `Name` filter returns nothing.
