## Context

Issue #1242 (Teardown module: plan once, delete exactly the confirmed plan).  Paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/`.  This file holds D1 to D6.  D7 to D10, the declined findings and the risks are in [`design-fit.md`](design-fit.md), and the tests are in [`test-plan.md`](test-plan.md).

Today `down` makes two separate calls into `AwsInfrastructureService`, a preview (`dryRun = true`) and a delete (`dryRun = false`), and each call discovers the VPC again.  The tail save runs between the two and can take minutes.  The scope rule is written three times: `Down.teardownCurrentCluster` (line 388), `Down.scopeForNamedVpc` (359) and `LiveInfrastructureLookup.find` (29).  `Down` is 630 lines, carries `@Suppress("TooManyFunctions")`, and decides the exit code in two places.

Bugs this change fixes (see the proposal):
- **A:** `down <other-vpc>` resets the workspace (`Down.kt:191`).
- **B:** the tunnel stops for every `Finished` outcome (`Down.kt:188`): a declined prompt, `--packer`, an empty `--all`, and the "nothing recorded" paths.
- **C:** `down <own-vpc-id>`, and a `down --all` that finds the workspace's VPC, skip the tail save.
- **Dead VPC id:** a removal that deletes the VPC but records an error (an ENI wait timeout, `AwsInfrastructureService.kt:702`) leaves `vpcId` in the state.  The next `down` gets `InvalidVpcID.NotFound` from `getVpcName` and cannot finish.

## Goals / Non-Goals

Goals:
- One plan per `down`, and `execute` changes only what the plan holds.
- The scope rule written once.
- Provider types behind a neutral port.
- The local work after a teardown in one step.
- One exit path.

Non-goals:
- Changing the delete order or the AWS calls (#398 does that).
- The `ClusterNetwork` module (#1056).
- The tail save itself.
- A second provider adapter.

## Decisions

### D1. The module: `services/teardown/`

```kotlin
class ClusterTeardown(stateManager, infrastructure: InfrastructureTeardown, workspace: WorkspaceTeardown, buckets: BucketTeardown) {
    fun plan(target: TeardownTarget): TeardownPlan        // discovers once; emits no Teardown.* event
    fun execute(confirmed: ConfirmedPlan): TeardownReport // discovers nothing
}
sealed interface TeardownTarget { companion object { fun from(vpcId: String?, all: Boolean, packer: Boolean): TeardownTarget } }
data object CurrentCluster : TeardownTarget
data class Network(val id: String) : TeardownTarget
data class AllTagged(val includePacker: Boolean) : TeardownTarget, TeardownScope
data object Packer : TeardownTarget, TeardownScope

sealed interface TeardownPlan
data class NothingRecorded(val reason: NothingReason, val clusterName: String?) : TeardownPlan
sealed interface ExecutablePlan : TeardownPlan
data class DeviceOnly(val deviceId: String) : ExecutablePlan
data class Networks(val target: TeardownTarget, val networks: List<PlannedNetwork>, val workspace: WorkspacePlan?,
                    val sweepClusterIds: List<String>) : ExecutablePlan   // non-empty only for AllTagged
data class WorkspacePlan(val networkId: String, val clusterName: String, val deviceId: String?)
enum class NothingReason { NO_CLUSTER_STATE, NO_NETWORK_RECORDED }
class ConfirmedPlan internal constructor(val plan: ExecutablePlan)

sealed interface NetworkOutcome {
    data class Removed(val errors: List<String>, val networkDeleted: Boolean) : NetworkOutcome
    data object Gone : NetworkOutcome          // planned as GONE, confirmed, no remove call
    data object NotAttempted : NetworkOutcome  // its remove threw, or an earlier network's did
}
data class NetworkResult(val networkId: String, val outcome: NetworkOutcome)
data class TeardownReport(val results: List<NetworkResult>, val workspaceErrors: List<String>) {
    fun outcomeFor(id: String): NetworkOutcome = results.first { it.networkId == id }.outcome   // a planned id always has one
    val errors: List<String>                   // every Removed error, plus workspaceErrors
    val success get() = errors.isEmpty() && results.none { it.outcome == NetworkOutcome.NotAttempted }
}
data class ProviderLocation(val region: String, val account: String)
class TeardownTargetMismatch(val recorded: RecordedLocation, val current: ProviderLocation) : RuntimeException()
data class RecordedLocation(val region: String?, val account: String?)
```

`workspace` is non-null exactly when the plan includes the workspace's own cluster (D5).  `TeardownTarget.from` holds today's flag precedence: a VPC id beats `--all`, and `--all` beats `--packer`.

`plan` returns its reasons, and `Down` emits the events from them (`NoClusterState`, `NoVpcId`, `PreparingVpc`, `FindingPackerVpc`).  So `init --clean` emits no `Teardown.*` event; it still gets the adapter's own `Infra.*` discovery events, as it does today.

Before any discovery, `plan` compares what the state recorded with `infrastructure.location()`, the region and account the port's adapter talks to:
- the region: `infrastructure.region`, else `initConfig.region`;
- the account: `infrastructure.accountId`, a new nullable field of `InfrastructureState` that `up` writes from `AccountIdentity.getAccountId()` in `finalizeInfrastructureState` (`Up.kt:640-655`).

If either recorded value is present and differs, `plan` throws `TeardownTargetMismatch(recorded, current)`.  A value the state does not record (a state written before the field existed) is skipped, never treated as a mismatch.  `Down` turns the exception into `Teardown.TargetMismatch` (both values) and exits non-zero.  `init --clean` turns it into `Setup.CleanTargetMismatch` and deletes nothing.  This is a configuration check made before anything happens, not a verify check.  It runs only when the resolution reaches discovery, so `NothingRecorded` and `DeviceOnly` never call `location()`.

`ConfirmedPlan` takes only an `ExecutablePlan`, so `execute` has no unreachable branch.  Only `TeardownConfirmation` constructs one, which an ArchUnit rule enforces (design-fit D7).

`execute` runs these steps in order:
1. `workspace.beforeRemoval()`, when `workspace != null`.
2. Inside a `try`, each planned network in order:
   - a `GONE` network records `Gone`, with no remove call;
   - any other network gets `remove`, and its result is recorded as `Removed`.
   If one throws, that network and every network after it record `NotAttempted`.  `execute` keeps the throwable in a local; the report does not carry it.
3. In the `finally`: `WorkspaceTeardown.after(report.outcomeFor(workspace.networkId))` when `workspace != null`.  A `DeviceOnly` plan calls `after(NetworkOutcome.Gone)`: the network is already gone, and today's device-only `down` removes the device and marks the state down (`Down.kt:404-405`, then `:192-193`).
4. Only when `report.success` and `sweepClusterIds` is not empty: `buckets.deleteEmptyBuckets(sweepClusterIds)` (design-fit D7b).  It runs after the workspace step, so `stopMetrics` never targets a bucket the sweep just deleted.
5. After the `finally`, the kept throwable is rethrown.

`plan` fills `sweepClusterIds` only for `AllTagged`, from each planned network's `clusterId`, because today's `--all` is the only sweep (`Down.kt:486-488`).  Every other target gets an empty list, so a plain `down` deletes no bucket.

`Event.Teardown.Starting` stays the first event `down` emits.

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

Every row matches today's code (`Down.kt:359-415`).  `workspaceNetworkId` is `state.vpcId`.

The plan includes the workspace (`Networks.workspace != null`) in three cases:
- the target is `CurrentCluster`;
- the target is `Network(id)` with `id == workspaceNetworkId`;
- the target is `AllTagged` and a planned network's id is `workspaceNetworkId`.

It never does for `Packer`.  `--all` searches by the legacy tag, so it never finds a supplied VPC.

`TeardownScopes.resolve` passes `workspaceNetworkId` to `discover` for every scope, so the adapter applies the `GONE` rule (D3) under `ByTags` too: `down <own-vpc-id>` of a non-supplied own network resolves to `ByTags` (r3 adv N12).

`init --clean` calls `plan(CurrentCluster)`.  It is refused when a planned network `holdsResources()` (`CleanRefusedLiveInfrastructure`, as today).  It is also refused when the workspace network is `GONE` (`Setup.CleanRefusedNetworkNotFound(vpcId, region)`, which says to run `down`), and on a `TeardownTargetMismatch`.  `NothingRecorded`, `DeviceOnly` and a planned network with no live resources let it proceed.  A thrown `plan` fails it, and it deletes nothing.  `LiveInfrastructureLookup` is removed.

### D3. The port: `InfrastructureTeardown`

```kotlin
interface InfrastructureTeardown {
    fun location(): ProviderLocation      // the configured region; the account from AccountIdentity (cached)
    fun discover(scope: TeardownScope, workspaceNetworkId: String?): List<PlannedNetwork>
    fun remove(network: PlannedNetwork): NetworkOutcome.Removed      // a step failure is an error, never a throw
}
data class PlannedNetwork(val id: String, val name: String?, val kindLabel: String, val ownership: NetworkOwnership,
                          val clusterId: String?, val groups: List<ResourceGroup>) {
    fun holdsResources() = groups.any { it.live && it.ids.isNotEmpty() }
    fun nonInstanceResourceCount() = groups.filter { it.live && it.kind != ResourceKind.INSTANCE }.sumOf { it.ids.size }
    fun summary(): String
}
enum class NetworkOwnership { OWNED, KEPT_UNTAGGED, KEPT_SUPPLIED, GONE }
data class ResourceGroup(val kind: ResourceKind, val label: String?, val ids: List<String>, val live: Boolean)
enum class ResourceKind { INSTANCE, SPARK_CLUSTER, SEARCH_DOMAIN, FIREWALL_GROUP, SUBNET, ROUTE_TABLE,
                          ROUTE_ASSOCIATION, GATEWAY_ROUTE, NAT_GATEWAY, INTERNET_GATEWAY }
```

The plan holds the full inventory as neutral ids, grouped by a neutral kind.  Neutral code reads only `INSTANCE` (for `init --clean`) and `live`.  Every other kind exists so the adapter can rebuild its inventory and tests can read ids; the `ResourceKind` KDoc says so.

The adapter supplies everything that carries a provider word or a provider rule:
- `kindLabel` ("VPC") and each group's `label`, so `summary()` prints today's text: `"$kindLabel: $id ($name)"`, then " (not created by easy-db-lab, kept)" for both kept values, then each labelled non-empty group.
- `live`, which is false for route tables, route associations and gateway routes.  A group with a null `label` is not printed.
- `clusterId`: from the scope for `ClusterInSuppliedNetwork`, because a supplied VPC carries no `ClusterId` tag; from the VPC's `ClusterId` tag otherwise (r3 perf M2).

A `GONE` network has no groups.  Its summary line reads `"$kindLabel: $id no longer exists in region <r>"`.

**The adapter.**  `services/aws/AwsInfrastructureTeardown` takes the teardown half of `AwsInfrastructureService` (lines 330-825) as a pure move.  `remove` rebuilds the inventory (`AwsNetworkInventory.fromPlanned`, the inverse of `toPlanned`) and runs `executeVpcTeardown` on it, with the same order.  `ownsVpc` is `ownership == OWNED`.  `networkDeleted` is true only when the network is `OWNED` and `deleteVpc` succeeded; a kept network is never deleted.  For `KEPT_UNTAGGED` only, `remove` emits `Infra.VpcKept` first, as today.  `DiscoveredResources` becomes the `internal` class `AwsNetworkInventory`.  `TeardownMode`, `TeardownResult` and `providers/aws/TeardownTypes.kt` are deleted.

**Only planned resources change.**  `remove` reads, but never changes, unplanned state: the ENI waits and the security group describe.
- **Route associations:** discovery records each planned route table's non-main association ids in `ROUTE_ASSOCIATION`, each encoded `<rtb>|<association>`, so `fromPlanned` rebuilds the per-table lists.  It gets them from the `DescribeRouteTables` that `findRouteTablesInVpc` and `findOwnedResources` already make.  `VpcService.deleteRouteTable(routeTableId, associationIds)` disassociates only that table's planned ids.
- **Gateway routes:** for a kept network, discovery records each route to the planned gateway as `GATEWAY_ROUTE`, encoded `<rtb>|v4|<cidr>` or `<rtb>|v6|<cidr>`, because `DeleteRoute` takes the IPv4 or the IPv6 destination field.  It uses the describe that `deleteRoutesToInternetGateway` makes today.  The preview counts them as "Routes to the gateway: N".  `VpcService.deleteRoutes(planned)` deletes only those routes; a route added after the plan stays, as a blackhole route once the gateway is gone.

**Discovery cost.**
- `discover` fetches the EMR cluster-to-subnet map and the OpenSearch domain-to-subnet map once per call, for every scope, and filters them in the adapter.  `findClustersInVpc` and `findDomainsInVpc` are deleted, and their tests move to the map methods.
- `VpcService.describeVpc(id): TaggedVpc` is one `DescribeVpcs` per named VPC.  It returns the name and the tags (so the `ClusterId`), replaces the separate `getVpcName` and `getVpcTags` reads in discovery, and is where NotFound is mapped, for every scope.  It runs before the tag-filtered describes, which return empty for a missing VPC.
- `findVpcsByTag` returns `TaggedVpc(id, name, tags)` from the one tag search.  `--all` skips the packer VPC on that name, before reading any inventory, and passes the name and `ClusterId` on.  `commands/aws/Vpcs.kt` uses the tags it now gets, instead of calling `getVpcTags` once per VPC.

**A network that no longer exists.**  The adapter matches only the error code `InvalidVpcID.NotFound`, and only for the workspace's own recorded network (`id == workspaceNetworkId`) under `WholeNetwork`, `ClusterInSuppliedNetwork` or `ByTags`.  There it returns that network with `ownership = GONE`.  Every other case keeps today's failure, so `plan` throws, `down` exits non-zero and nothing changes:
- `NotFound` for any other id: `down <typo-id>` fails as it does today.
- `UnauthorizedOperation` or any other code.
- An empty `vpcs()` list from `describeVpc`.

Rejected alternatives:
- An opaque inventory: it fails only at run time.
- A closure: it hides the data.
- A `keepsNetwork` Boolean: it cannot carry `VpcKept`.
- Planning a gone network as "no network": that resets a workspace without a prompt (r2 adv N1).

### D4. A resource that appears after the plan

`execute` removes only planned ids and changes no unplanned resource (D3).

A late resource that touches nothing planned is left alone, in every ownership, and the removal succeeds.  A late resource that depends on a planned one blocks that delete, in every ownership, and the removal fails.  Examples are a late instance in a planned subnet or security group, or a late subnet associated with a planned route table.

The added time before `down` exits non-zero:

| late resource | blocked deletes | about |
|---|---|---|
| a subnet in an `OWNED` network (L1) | the VPC delete | 75 s |
| a subnet associated with a planned route table | the route table delete and the VPC delete | 150 s |
| an instance in a planned subnet | the ENI wait (5 min), then each dependent delete at about 75 s | 10 to 15 min |

When the removal fails, `down` reports the errors and exits non-zero, and the state is not marked down (D5).  A re-run plans again and shows the late resource before it removes anything.  No pre-delete drift check is added, because that would be a verify check.

A planned id that is gone at execute time, because another `down` ran meanwhile, fails its delete with NotFound.  For an instance, that is a fatal phase 1 failure: that network's phases 2 and 3 do not run, and a re-run finishes it.

### D5. The workspace step: `WorkspaceTeardown`

When `Networks.workspace != null`:
- `Down` runs the tail save before `execute`, when the workspace network is not `GONE`.  This fixes bug C.
- `beforeRemoval()` runs `SocksProxyService.unpublishPort()`, before the first `remove`.  It clears only `Constants.Proxy.PORT_PROPERTY` and never `socksProxyHost` or `socksProxyPort`.
- `after(outcome)` always stops the tunnel.  It runs the rest only when the outcome is `Gone`, or `Removed` with no errors or with `networkDeleted`:
  - remove the device;
  - delete the auth key;
  - stop the bucket metrics;
  - mark the state down through `ClusterStateManager.update`, so a write made meanwhile is kept.
  - A failed device removal adds to `workspaceErrors`, as today.
- `NotAttempted` never resets the state.
- `Removed(errors non-empty, networkDeleted = true)` is an `OWNED` network whose VPC was deleted while another step recorded an error (the ENI wait timeout).  `after` resets the workspace, and `down` still reports the errors and exits non-zero.  `after` keys on `networkDeleted`, never on `errors.isEmpty()` alone.

So a failed removal in a supplied or untagged network keeps the state and its `vpcId`, and so does a throw on the workspace network or on an earlier `--all` network.  A failure in another `--all` network does not block the workspace's reset, but `down` still exits non-zero.  This fixes the dead VPC id.

When `workspace` is null, nothing here runs, which fixes bugs A and B.

### D6. What stays in `Down`

`Down` keeps the flag parsing, `TeardownConfirmation` (renamed from `TeardownPlanner`) and the tail-save gate (`saveTailBeforeTeardown`, `stateToSave`, `abortEvent`, `unsavedAbort`).  `reportForceSkipsTail` is deleted.  When `--force` is set and the workspace network is not `GONE`, `Down` computes `forceSkips = teardownBackupService.unsavedSignals(state)`.  `review` is the only code that emits `ForceSkipsTail`.

`review(plan, ReviewOptions(dryRun, autoApprove, forceSkips))` returns one of `Proceed(ConfirmedPlan)`, `DryRun`, `Declined` or `NothingToDo(reason)`.

"Empty" means `networks.isEmpty()`.  A planned network always needs confirmation, `GONE` included.  When the plan includes the workspace, the summary ends with one more line: "This workspace (`<name>`): cluster state marked down, tailnet device `<id>` removed, auth key deleted, data bucket metrics stopped".  When `sweepClusterIds` is not empty (`--all` only), a "Data buckets deleted if empty: N" line follows.  `--dry-run` wins over `--auto-approve`, as today.

| plan | `--dry-run` | `--auto-approve` | no flag, answer yes | no flag, answer no |
|---|---|---|---|---|
| NothingRecorded | reason, `CompletedWithErrors`, exit 1 | same | same | same |
| DeviceOnly | `DryRunDeviceRemovalPreview`, exit 0 | Proceed, no prompt | Proceed, no prompt | (no prompt) |
| Networks([]) | `No*Found`, exit 0 | `No*Found`, nothing changes, exit 0 | same | (no prompt) |
| Networks(non-empty, `GONE` included) | `ForceSkipsTail`?, `DryRunPreview`, exit 0 | `ForceSkipsTail`?, Proceed | `ForceSkipsTail`?, prompt, Proceed | `ForceSkipsTail`?, prompt, `CancelledByUser`, exit 1 |

- `No*Found` is `NoResourcesFound` for `CurrentCluster` and `Network`, `NoTaggedVpcsFound` for `AllTagged`, and `NoPackerVpcFound` for `Packer`.
- `NothingRecorded` keeps today's `CompletedWithErrors([reason])`.
- `ForceSkipsTail` comes after the summary and before the dry-run stop or the prompt.
- A dry run changes nothing in any row.
- An empty plan changes nothing: a gone workspace network is a `GONE` entry, never an empty plan.

```text
emit Starting
plan = teardown.plan(target)                        // one discovery; a region or account mismatch throws
emit NoClusterState | NoVpcId | PreparingVpc | FindingPackerVpc   (from plan)
when (review(plan, options))
  NothingToDo -> emit CompletedWithErrors; throw CommandFailedException
  DryRun      -> return
  Declined    -> throw CommandFailedException        // CancelledByUser already emitted
  Proceed     -> if (workspace != null && workspace network not GONE && !force && !saveTail()) throw
                 report = teardown.execute(confirmed)
                 emit CompletedSuccessfully, or CompletedWithErrors(report.errors) and throw
```

`TeardownOutcome`, `VpcScope`, `exitCode`, `call()`, `reportForceSkipsTail` and `@Suppress("TooManyFunctions")` are removed.  Every failure exit is a `CommandFailedException` thrown after its typed event, and the executor prints nothing more.
