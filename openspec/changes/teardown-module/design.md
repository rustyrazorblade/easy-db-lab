## Context

Issue #1242 (Teardown module: plan once, delete exactly the confirmed plan).  Paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/`.

Today `down` makes two separate calls into `AwsInfrastructureService`: a preview (`dryRun = true`) and a delete (`dryRun = false`).  Each call discovers the VPC again (`teardownVpc`, `teardownOwned`, `teardownAllTagged`, `teardownPackerInfrastructure`).  The tail save runs between the two calls and can take minutes.  The scope rule appears three times: in `Down.teardownCurrentCluster` (line 388), in `Down.scopeForNamedVpc` (359) and in `LiveInfrastructureLookup.find` (29).  `Down` is 630 lines with `@Suppress("TooManyFunctions")`, holds the whole sequence, and decides the exit code in two places.

Bugs found while reading the code.  This change fixes all of them (see proposal):
- **A:** `SpecificVpc` mode runs `removeTailscaleDevice` and `updateClusterState` for any VPC id (`Down.kt:191`), so `down <other-vpc>` resets the workspace.
- **B:** `cleanupSocks5Proxy` runs for every `Finished` outcome (`Down.kt:188`).  That includes a declined prompt, `--packer`, an empty `--all`, and the "nothing recorded" paths.
- **C:** `down <own-vpc-id>` and a `down --all` that finds the workspace's VPC destroy the workspace's cluster without the tail save.
- **Dead VPC id:** a removal that records an error but still deletes the VPC (an ENI wait timeout, `AwsInfrastructureService.kt:702`) leaves `vpcId` in the state.  The next `down` then calls `getVpcName` on a VPC that no longer exists.  `DescribeVpcs` answers `InvalidVpcID.NotFound`, so `plan` throws, and the workspace cannot be torn down.

## Goals / Non-Goals

Goals:
- One plan per `down`, and `execute` changes only what that plan holds.
- The scope rule in one place.
- Provider types behind a neutral port.
- The local work after a teardown in one step.
- One exit path.

Non-goals:
- The delete order and the AWS calls (unchanged; #398 changes them).
- The `ClusterNetwork` module (#1056).
- The tail save itself (`TeardownBackupService` is unchanged).
- A second provider adapter.

## Decisions

### D1. The module: `services/teardown/`

```kotlin
class ClusterTeardown(stateManager, infrastructure: InfrastructureTeardown, workspace: WorkspaceTeardown,
                      buckets: BucketTeardown) {
    fun plan(target: TeardownTarget): TeardownPlan        // discovers once; emits no Teardown.* event
    fun execute(confirmed: ConfirmedPlan): TeardownReport // discovers nothing
}
sealed interface TeardownTarget { companion object { fun from(vpcId: String?, all: Boolean, packer: Boolean): TeardownTarget } }
data object CurrentCluster : TeardownTarget
data class Network(val id: String) : TeardownTarget
data class AllTagged(val includePacker: Boolean) : TeardownTarget, TeardownScope   // one type, both roles
data object Packer : TeardownTarget, TeardownScope

sealed interface TeardownPlan
data class NothingRecorded(val reason: NothingReason, val clusterName: String?) : TeardownPlan
sealed interface ExecutablePlan : TeardownPlan
data class DeviceOnly(val deviceId: String) : ExecutablePlan
data class Networks(val target: TeardownTarget, val scope: TeardownScope, val networks: List<PlannedNetwork>,
                    val workspaceNetworkId: String?) : ExecutablePlan { val includesWorkspace: Boolean }
enum class NothingReason { NO_CLUSTER_STATE, NO_NETWORK_RECORDED }
class ConfirmedPlan internal constructor(val plan: ExecutablePlan)
data class NetworkRemoval(val networkId: String, val errors: List<String>, val networkGone: Boolean)
data class TeardownReport(val removals: Map<String, NetworkRemoval>, val workspaceErrors: List<String>) {
    val errors get() = removals.values.flatMap { it.errors } + workspaceErrors
    val success get() = errors.isEmpty()
}
```

`TeardownTarget.from` holds the flag precedence that `TeardownPlanner.modeFor` holds today: a VPC id wins over `--all`, and `--all` wins over `--packer`.

`plan` returns its reasons, and `Down` emits the events from them: `NoClusterState`, `NoVpcId(clusterName)`, `PreparingVpc(scope id)` and `FindingPackerVpc`.  So `init --clean` emits no `Teardown.*` event.  The adapter still emits its own `Infra.*` discovery events, which `init --clean` emits today too.

`ConfirmedPlan` takes an `ExecutablePlan`, so `execute` has no unreachable `NothingRecorded` branch.  Only `TeardownConfirmation` may call its constructor; an ArchUnit rule enforces that (D7), because `internal` alone does not stop `Down`, which is in the same Gradle module.

`execute` runs in this order:
1. When `includesWorkspace`, unpublish the proxy port.
2. Inside `try`, call `remove` on each planned network, in order, and keep one `NetworkRemoval` per network id.
3. In the `finally`, when `includesWorkspace` (or the plan is `DeviceOnly`), run `WorkspaceTeardown.after(workspaceRemoval)`.  A thrown defect therefore still stops the tunnel.
4. Last, for `AllTagged` and only when every removal succeeded, sweep the empty data buckets.  The sweep runs after the workspace step, so `stopMetrics` never targets a bucket the sweep has just deleted.

`Event.Teardown.Starting` is still the first event `down` emits, before `plan`.

### D2. The scope rule: `TeardownScopes.resolve`, one pure function

```kotlin
sealed interface TeardownScope
data class WholeNetwork(val id: String) : TeardownScope
data class ByTags(val id: String) : TeardownScope
data class ClusterInSuppliedNetwork(val id: String, val clusterId: String) : TeardownScope
fun resolve(target: TeardownTarget, state: ClusterState?): Resolution   // NothingRecorded | DeviceOnly | Scoped(scope, workspaceNetworkId)
```

| target | state | result |
|---|---|---|
| CurrentCluster | none | NothingRecorded(NO_CLUSTER_STATE) |
| CurrentCluster | no VPC id, device id not blank | DeviceOnly |
| CurrentCluster | no VPC id, device id null or blank | NothingRecorded(NO_NETWORK_RECORDED) |
| CurrentCluster | VPC, `vpcSupplied` | ClusterInSuppliedNetwork(vpc, clusterId) |
| CurrentCluster | VPC, not supplied | WholeNetwork(vpc) |
| Network(id) | `vpcId == id`, `vpcSupplied` | ClusterInSuppliedNetwork(id, clusterId) |
| Network(id) | anything else | ByTags(id) |
| AllTagged / Packer | any | the target itself |

Every row matches today's code (`Down.kt:359-415`; #1171 introduced `ByTags`).  `workspaceNetworkId` is `state.vpcId` whenever a state exists.

`includesWorkspace` is true in these cases:
- the target is `CurrentCluster`;
- the target is `Network(id)` with `id == workspaceNetworkId`;
- the target is `AllTagged` and a planned network's id is `workspaceNetworkId`.

It is never true for `Packer`.  `--all` searches by the legacy tag, so it never finds a supplied VPC, and a supplied-VPC workspace is not included.

`init --clean` calls `plan(CurrentCluster)`.  It is refused when a planned network `holdsResources()`.  `NothingRecorded`, `DeviceOnly` and an empty `Networks` let it proceed, as `LiveInfrastructureLookup` does today.  `LiveInfrastructureLookup` is removed.

### D3. The port: `InfrastructureTeardown`

```kotlin
interface InfrastructureTeardown {
    fun discover(scope: TeardownScope): List<PlannedNetwork>
    fun remove(network: PlannedNetwork): NetworkRemoval       // a step failure is an error, never a throw
}
data class PlannedNetwork(val id: String, val name: String?, val kindLabel: String, val ownership: NetworkOwnership,
                          val groups: List<ResourceGroup>) {
    fun holdsResources() = groups.any { it.live && it.ids.isNotEmpty() }
    fun nonInstanceResourceCount() = groups.filter { it.live && it.kind != ResourceKind.INSTANCE }.sumOf { it.ids.size }
    fun summary(): String
}
enum class NetworkOwnership { OWNED, KEPT_UNTAGGED, KEPT_SUPPLIED }
data class ResourceGroup(val kind: ResourceKind, val label: String?, val ids: List<String>, val live: Boolean)
enum class ResourceKind { INSTANCE, SPARK_CLUSTER, SEARCH_DOMAIN, FIREWALL_GROUP, SUBNET, ROUTE_TABLE,
                          ROUTE_ASSOCIATION, NAT_GATEWAY, INTERNET_GATEWAY }
```

The plan holds the full inventory as neutral ids, grouped by a neutral kind.  The adapter supplies everything that carries a provider word or a provider rule:
- `kindLabel` ("VPC") and each group's `label` ("EC2 Instances"), so `summary()` prints today's text exactly: `"$kindLabel: $id ($name)"`, then " (not created by easy-db-lab, kept)" for both kept values, then each labelled non-empty group.
- `live`, which the adapter sets false for route tables and route associations.  AWS gives every VPC a main route table, so a route table alone does not make a network live.  A group with a null `label` is not printed.

One adapter, `services/aws/AwsInfrastructureTeardown`.  It takes the teardown half of `AwsInfrastructureService` (lines 330-825: discovery, the three phases, `guarded`, `logError`, `TeardownStepResult` and `Phase1Result`) as a pure move.  `remove` turns the plan back into an inventory (`AwsNetworkInventory.fromPlanned`, the inverse of `toPlanned`) and runs `executeVpcTeardown` on it, with the same order and the same `ownsVpc` branches.  `ownsVpc` is `ownership == OWNED`.

For `KEPT_UNTAGGED` only, `remove` emits `Infra.VpcKept` before it removes anything, as `teardownVpcByTags` does today; a supplied VPC does not get the event, also as today.  `DiscoveredResources` becomes `internal` `AwsNetworkInventory`.  `TeardownMode`, `TeardownResult` and `providers/aws/TeardownTypes.kt` are deleted.

**Route associations.**  Today `deleteRouteTable` describes the table and disassociates every non-main association (`EC2VpcService.kt:963-1011`), including that of a subnet created after the plan.  Discovery now records each planned route table's association ids in `ROUTE_ASSOCIATION`.  `VpcService.deleteRouteTable(routeTableId, associationIds)` disassociates only the listed ids that it still finds.  `findRouteTablesInVpc` and `findOwnedResources` return the association ids from the describe call they already make, so no call is added.

**What `remove` still reads.**  It reads, but does not change, unplanned state:
- the ENI wait covers the whole VPC;
- `deleteRoutesToInternetGateway` describes the VPC's route tables;
- `revokeSecurityGroupRules` describes the group.

It removes a route only when that route points at a planned gateway that is being deleted.  The requirement says exactly this.

**Discovery cost (P1, P2).**  `discover(AllTagged)` fetches the EMR cluster-to-subnet map and the OpenSearch domain-to-subnet map once, then matches each network's subnets against them.  It reads the VPC name first and skips the packer VPC before any inventory call.

**A network that no longer exists.**  When `getVpcName` or `getVpcTags` gets `InvalidVpcID.NotFound`, `discover` returns no network for that id.  The plan is then an empty `Networks`.  For the workspace's own network, the workspace step runs and marks the state down.  This frees a workspace that an earlier release left with a dead VPC id.

Rejected alternatives:
- An opaque inventory (`inventory: Any`, which the adapter casts back): it fails only at run time, and a test could not read the ids.
- A closure (`remove: () -> NetworkRemoval`): it hides the data and breaks equality.
- `keepsNetwork: Boolean`: it cannot tell `KEPT_UNTAGGED` from `KEPT_SUPPLIED`, so `VpcKept` could not be emitted as it is today.

### D4. A resource that appears after the plan

`execute` removes only planned ids and changes no unplanned resource (D3).

- **Kept network** (`KEPT_UNTAGGED` or `KEPT_SUPPLIED`): a late resource is left alone, and the removal succeeds.
- **`OWNED` network:** a late resource blocks the dependent deletes.
  - The ENI wait times out after 5 minutes when the late resource has an ENI.  This is recorded as an error, as today.
  - Each blocked delete (security group, subnet, IGW detach, VPC) fails with `DependencyViolation` after `createVpcTeardownRetryConfig`'s 5 attempts, about 75 s each.
  - A late subnet (the L1 case) blocks only the VPC delete, so it costs about 75 s.  A late instance can cost about 5 min plus 75 s per blocked delete, 10 to 15 min in all.
  - `down` then reports the errors and exits non-zero, and the state is not marked down.  A re-run plans again and shows the late resource before it removes it.

Nothing is deleted that the operator did not see.  There is no pre-delete drift check: that would be a verify check, which `down` must not have.

A planned id that is gone at execute time (another `down` ran meanwhile) fails its delete with NotFound.  For an instance, that is a fatal phase 1 failure: phases 2 and 3 do not run for that network, and a re-run finishes it.  `TerminateInstances` on an instance terminated in the last hour succeeds, so only an instance that has aged out hits this.  A second concurrent `down` is operator error.

### D5. The workspace step: `WorkspaceTeardown`

When `includesWorkspace` is true:
- `Down` runs the tail save before `execute` when the plan holds a network.  This fixes bug C.
- `execute` calls `beforeRemoval()`, which runs `SocksProxyService.unpublishPort()`.  That method replaces `Down.clearProxySystemProperties` and clears only `Constants.Proxy.PORT_PROPERTY`.
- `after(workspaceRemoval)` always stops the tunnel.  It then runs the rest only when the workspace network's own removal succeeded (`errors.isEmpty() || networkGone`), or when the workspace network is not in the plan (it is gone):
  1. remove the tailnet device;
  2. delete the auth key;
  3. stop the data bucket metrics;
  4. mark the state down, clearing hosts, EMR, OpenSearch, infrastructure and the auth key id.

  A failed device removal adds to `workspaceErrors`, as it does today.  The step depends only on the workspace's own network.  Under `--all`, a failure in another network does not keep the workspace state pointing at a deleted VPC, but `down` still exits non-zero.  This fixes the dead-VPC-id bug.

`after` re-reads the state through `ClusterStateManager.update`, so a write another command made meanwhile is kept.  When `includesWorkspace` is false, none of these steps runs, which fixes bugs A and B.

### D6. What stays in `Down`

`Down` keeps the flag parsing (`TeardownTarget.from`), the confirmation (`TeardownConfirmation`, renamed from `TeardownPlanner`) and the tail-save gate (`saveTailBeforeTeardown`, `stateToSave`, `abortEvent`, `unsavedAbort`).  `review(plan, ReviewOptions(dryRun, autoApprove, forceSkips))` returns one of `Proceed(ConfirmedPlan)`, `DryRun`, `Declined` or `NothingToDo(reason)`.  `forceSkips` holds the signals that will not be saved.  `Down` computes it, through `reportForceSkipsTail`'s logic, when `--force`, `includesWorkspace` and the plan is not empty; otherwise it is null.

"Empty" means `Networks.networks.isEmpty()`, and nothing else.  A planned network always counts as a removal, whatever its groups hold.  What `review` does for each plan:

| plan | `--dry-run` | `--auto-approve` | neither, answer yes | neither, answer no |
|---|---|---|---|---|
| NothingRecorded | NothingToDo, exit 1 | NothingToDo, exit 1 | NothingToDo, exit 1 | NothingToDo, exit 1 |
| DeviceOnly | `DryRunDeviceRemovalPreview`, DryRun, exit 0 | Proceed, no prompt | Proceed, no prompt | (no prompt) |
| Networks([]) | `No*Found`, DryRun, exit 0 | `No*Found`, Proceed, no prompt | `No*Found`, Proceed, no prompt | (no prompt) |
| Networks(non-empty) | `ForceSkipsTail`?, `DryRunPreview`, DryRun, exit 0 | `ForceSkipsTail`?, Proceed | `ForceSkipsTail`?, prompt, Proceed | `ForceSkipsTail`?, prompt, `CancelledByUser`, Declined, exit 1 |

- `No*Found` is `NoResourcesFound` for `CurrentCluster` and `Network`, `NoTaggedVpcsFound` for `AllTagged`, and `NoPackerVpcFound` for `Packer`.
- `ForceSkipsTail`? is emitted when `forceSkips` is not null, after the summary is built and before the dry-run stop or the prompt, as today.
- A dry run changes nothing in any row.
- An empty `Networks` with `includesWorkspace` runs only the workspace step: the network is already gone.  Otherwise an empty plan changes nothing.

```text
emit Starting
plan = teardown.plan(target)                        // one discovery
emit NoClusterState | NoVpcId | PreparingVpc | FindingPackerVpc   (from plan)
when (review(plan, options))
  NothingToDo -> throw CommandFailedException        // dry run too, as today
  DryRun      -> return
  Declined    -> throw CommandFailedException        // CancelledByUser already emitted
  Proceed     -> if (includesWorkspace && networks not empty && !force && !saveTail()) throw
                 report = teardown.execute(confirmed)
                 emit CompletedSuccessfully, or CompletedWithErrors(report.errors) and throw
```

`TeardownOutcome`, `VpcScope`, the mutable `exitCode`, the `call()` override and `@Suppress("TooManyFunctions")` are removed.  Every failure exit is a `CommandFailedException` thrown after its typed event.  The executor returns the error code and prints nothing more.

### D7. Boundary rules, ports, Koin

The new rules go into `architecture/PackageBoundaryTest`, next to the SDK rules:
- No class in `services.teardown..`, and not `commands.Down`, depends on `providers.aws..` or `services.aws..`.
- Only classes in `services.teardown..` call `InfrastructureTeardown.discover` or construct a `TeardownScope`.
- Only `TeardownConfirmation` calls the `ConfirmedPlan` constructor.

Each rule has a fixture in a test package the rule selects:
- `com.rustyrazorblade.easydblab.services.teardown.fixtures.NamesAwsInventory` for the first rule;
- `com.rustyrazorblade.easydblab.commands.fixtures.CallsDiscover` and `BuildsConfirmedPlan` for the other two.

The main scan uses `DoNotIncludeTests`, so the fixtures stay out of it.  `Init` keeps its other AWS dependencies, so the first rule does not cover `Init`; the second rule stops `Init` from resolving a scope of its own.

`S3DataBucketTeardown` (renamed from `DataBucketTeardown`) implements the neutral `BucketTeardown` port.  `MetricsStop` and `DataBucketSweep` move to the port file.

The VPC teardown retry becomes `VpcTeardownRetryPolicy(maxAttempts, baseInterval)`, a constructor parameter of `EC2VpcService` and `EC2SecurityGroupService` (like `pollInterval`).  Its default comes from the existing `Constants.Retry` values.  The retry stays; a test passes a short policy.

Koin:
- in `AwsServicesModule`: `single<InfrastructureTeardown> { AwsInfrastructureTeardown(...) }` and `factory<BucketTeardown> { S3DataBucketTeardown(...) }` (`factory`, as `DataBucketTeardown` is bound today);
- in `ServicesModule`: `ClusterTeardown`, `WorkspaceTeardown` and `TeardownConfirmation`.

`AwsInfrastructureService` loses `emrService`, `openSearchService` and `@Suppress("TooManyFunctions")`, and `Init` loses `infrastructureService`.

### D8. An existing Kubernetes cluster

The port is neutral, and the scope rule is not yet.  `TeardownScopes.resolve` reads `state.vpcId` and `state.vpcSupplied`, which are AWS-shaped fields of `InfrastructureState`.  An existing cluster (EKS, where the tool made no network) records no VPC id, so `plan(CurrentCluster)` returns `NothingRecorded(NO_NETWORK_RECORDED)` or `DeviceOnly` today, and no adapter's `discover` is called.

The one later change that path needs is a recorded "no network of ours" state that resolves to a scope whose `discover` returns no networks.  `plan` then returns an empty `Networks` with `includesWorkspace`, and `execute` runs only the workspace step, which already works (D6 table, row 3).  The reshape of `InfrastructureState` belongs to #1062.  Nothing for it is built here.

### D9. How this fits the related issues

- **#1056 (ClusterNetwork module):** when it lands, `ClusterNetwork.teardown(network)` takes phase 3 out of `AwsInfrastructureTeardown.remove`, which then calls it.  The port, the plan and `Down` do not change.  `Down` calls `ClusterTeardown` and never `ClusterNetwork`; task 6.4 posts this on #1056.
- **#398 (parallel VPC teardown):**
  - Its dependency queue replaces `executeVpcTeardown` inside the adapter.  The plan already holds every id by kind, which is the queue's input.
  - The per-network loop is in `ClusterTeardown.execute`, and `TeardownReport` already keeps one result per network.
  - #398 must give phase 1 and that loop a dedicated, bounded executor.  Today phase 1 runs on the common ForkJoinPool (`AwsInfrastructureService.kt:592`), which blocking waits would starve.
  - #398 can start the other networks while the workspace saves its tail, if "a failed save removes nothing" is scoped to the workspace's network.
- **#1171 (ByTags):** its `VpcOwnership` decision stays in `discover(ByTags)`.
- **#1062 (multi-cloud):** the port is neutral and the adapter carries the provider name.  D8 names the state reshape that remains.

### D10. Scale

One discovery per `down` instead of two.  One discovery is `9 + E + D` calls per VPC: 7 EC2 calls, plus `ListClusters` and one `DescribeCluster` per active EMR cluster in the account, plus `ListDomainNames` and one `DescribeDomain` per OpenSearch domain.  Today a `down` makes `2 x (9 + E + D)`.  For `--all` over K VPCs it was `2K x (9 + E + D)`; it is now about `8K + 2 + E + D`, through P1 and P2.

`TerminateInstances` takes the whole instance list in one call.  At 100 nodes the plan holds about 110 ids, in memory only.  Execute stays sequential per network (#398).

`down <own-vpc>` and a `down --all` that includes the workspace now run the tail save first: up to the Tempo drain's 5 minutes, usually less.  The total is the save plus the sum of the network removals.

## Test plan

The full test plan, with a control for each test, is in [`test-plan.md`](test-plan.md).

## Declined findings

- **Debt m10 (move the tail-save gate out of `Down`):** declined.  #1242 says `Down` keeps the gate, and moving it is a separate change.  For later: `abortEvent` and `unsavedAbort` could sit beside `TeardownBackupService`.
- **Tests T13 (`remove makes no describe call`):** dropped, per the lead decision.  `remove` must describe (the waits, the IGW routes).  Instead, `AwsInfrastructureTeardownIntegrationTest` asserts what survives, and T23's event count shows that discovery runs once.

## Risks / Trade-offs

- An `OWNED` network with a late resource now fails instead of deleting it (D4).  That is the fix, and a re-run finishes the teardown.
- The pure move of about 500 lines into the adapter is large.  It lands as its own commit (task 2.1), and the moved tests keep their assertions.

## Owner decisions

None yet.  Open questions are in the hand-back.  The working assumption for the late resource is D4.
