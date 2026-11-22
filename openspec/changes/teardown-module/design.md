## Context

Issue #1242 (Teardown module: plan once, delete exactly the confirmed plan).  Paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/`.

Today `down` runs two independent calls into `AwsInfrastructureService`, a preview (`dryRun = true`) and a delete (`dryRun = false`).  Each call discovers the VPC again (`teardownVpc`, `teardownOwned`, `teardownAllTagged`, `teardownPackerInfrastructure`).  The tail save runs between them and can take minutes.  The scope rule is in `Down.teardownCurrentCluster` (line 413), in `Down.scopeForNamedVpc` (359) and again in `LiveInfrastructureLookup.find` (29).  `Down` (630 lines, `@Suppress("TooManyFunctions")`) holds the sequence and decides the exit code in two places.

Two more bugs found while reading (fixed here; see proposal):
- **Bug A:** `SpecificVpc` mode runs `removeTailscaleDevice` and `updateClusterState` for any VPC id, so `down <other-vpc>` resets the workspace.
- **Bug B:** `cleanupSocks5Proxy` runs for every `Finished` outcome, including a declined prompt.
- **Bug C:** `down <own-vpc-id>` and `down --all` (when it finds the workspace's VPC) destroy the workspace's cluster without the tail save.  The `Cluster Teardown` requirement says the save runs "before any infrastructure is torn down"; only the current-cluster mode does it.

## Goals / Non-Goals

Goals: one plan per `down`, and `execute` deletes exactly that plan; the scope rule in one place; provider types behind a neutral port; the local work after a teardown in one step; one exit path.

Non-goals: the delete order and the AWS calls (unchanged; #398 changes them); the `ClusterNetwork` module (#1056); the tail save itself (`TeardownBackupService` is unchanged); a second provider adapter.

## Decisions

### D1. The module: `services/teardown/`

```kotlin
class ClusterTeardown(                       // the deep module
    stateManager: ClusterStateManager,
    infrastructure: InfrastructureTeardown,  // port, D3
    workspace: WorkspaceTeardown,            // D5
    buckets: BucketTeardown,                 // port over DataBucketTeardown
    eventBus: EventBus,
) {
    fun plan(target: TeardownTarget): TeardownPlan
    fun execute(plan: ConfirmedPlan): TeardownReport
}

sealed interface TeardownTarget {            // what the flags ask for; Down builds it
    data object CurrentCluster; data class Network(val id: String)
    data class AllTagged(val includePacker: Boolean); data object Packer
}

sealed interface TeardownPlan {
    data class Nothing(val reason: String) : TeardownPlan          // no state, no VPC id
    data class DeviceOnly(val deviceId: String) : TeardownPlan     // VPC gone, device left
    data class Networks(
        val target: TeardownTarget,
        val networks: List<PlannedNetwork>,
        val includesWorkspace: Boolean,      // D5
    ) : TeardownPlan
}
data class ConfirmedPlan(val plan: TeardownPlan)                   // DeviceOnly or Networks
data class TeardownReport(val removed: List<String>, val errors: List<String>) { val success get() = errors.isEmpty() }
```

`plan` discovers once and emits the discovery events (`PreparingVpc`, `FindingPackerVpc`, `NoClusterState`, `NoVpcId`, and the adapter's `Infra.*` events).  `execute` discovers nothing: it removes each `PlannedNetwork` through the port, sweeps empty data buckets for `AllTagged` on success, and then runs the workspace step when `includesWorkspace` (or for `DeviceOnly`).

`ConfirmedPlan` is made only by `TeardownConfirmation.review` (D6).  It is not a security barrier, but `execute(ConfirmedPlan)` makes "this plan was the one shown" the type of the argument, and a reader sees that `execute` cannot take a fresh plan.

### D2. The scope rule: `TeardownScopes.resolve`, one pure function

```kotlin
sealed interface TeardownScope {
    data class WholeNetwork(val id: String); data class ByTags(val id: String)
    data class ClusterInSuppliedNetwork(val id: String, val clusterId: String)
    data class AllTagged(val includePacker: Boolean); data object Packer
}
fun resolve(target: TeardownTarget, state: ClusterState?): Resolution  // Nothing | DeviceOnly | Scope(scope, workspaceNetworkId)
```

| target | state | scope |
|---|---|---|
| CurrentCluster | none | Nothing("No cluster state found") |
| CurrentCluster | no VPC id, device recorded | DeviceOnly |
| CurrentCluster | no VPC id, no device | Nothing("No VPC ID in cluster state") |
| CurrentCluster | VPC, `vpcSupplied` | ClusterInSuppliedNetwork(vpc, clusterId) |
| CurrentCluster | VPC, not supplied | WholeNetwork(vpc) |
| Network(id) | `vpcId == id`, `vpcSupplied` | ClusterInSuppliedNetwork(id, clusterId) |
| Network(id) | anything else | ByTags(id) |
| AllTagged / Packer | any | AllTagged(includePacker) / Packer |

This is today's behaviour, row for row (#1171 introduced `ByTags`).  `Network(id)` with the workspace's own non-supplied VPC stays `ByTags`, which removes an easy-db-lab VPC whole, as today.  `init --clean` calls `plan(CurrentCluster)` and is refused when any planned network `holdsResources()` (the rule `DiscoveredResources.hasResources` has today).  `LiveInfrastructureLookup` is removed.

### D3. The port: `InfrastructureTeardown`

```kotlin
interface InfrastructureTeardown {
    fun discover(scope: TeardownScope): List<PlannedNetwork>
    fun remove(network: PlannedNetwork): NetworkRemoval            // errors, never throws for a step failure
}
data class PlannedNetwork(val id: String, val name: String?, val keepsNetwork: Boolean, val groups: List<ResourceGroup>)
data class ResourceGroup(val kind: ResourceKind, val label: String, val ids: List<String>)
enum class ResourceKind { INSTANCE, SPARK_CLUSTER, SEARCH_DOMAIN, FIREWALL_GROUP, SUBNET, ROUTE_TABLE, NAT_GATEWAY, INTERNET_GATEWAY }
```

The plan holds the full inventory as neutral ids, grouped by a neutral kind.  The adapter supplies each group's display `label` ("EC2 Instances", "EMR Clusters"), so `PlannedNetwork.summary()` prints the same text as `DiscoveredResources.summary()` today and no provider word sits in neutral code.

One adapter: `services/aws/Ec2InfrastructureTeardown`.  It takes the teardown half of `AwsInfrastructureService` (lines 330-783: discovery, the three phases, `guarded`, `TeardownStepResult`, `Phase1Result`) as a pure move.  `remove` turns the `PlannedNetwork` back into a `DiscoveredResources` (`Ec2Inventory.fromPlanned`, the inverse of `toPlanned`) and runs `executeVpcTeardown` on it, with the same order and the same `ownsVpc` branches.  `DiscoveredResources` moves to `services/aws` and is `internal` to the adapter.  `TeardownMode` and `TeardownResult` are deleted; `providers/aws/TeardownTypes.kt` goes.

Rejected, an opaque inventory (`PlannedNetwork.inventory: Any` cast back by the adapter): it fails only at run time, and a test of "deleted equals confirmed" could not read the ids.  Rejected, a closure in the plan (`remove: () -> NetworkRemoval`): it hides the data and breaks equality.

### D4. A resource that appears after the plan

`execute` removes only planned ids.  A late resource in a scope that keeps the network (`ByTags` on an untagged VPC, `ClusterInSuppliedNetwork`) is left alone and nothing else changes.  A late resource in `WholeNetwork` blocks the dependent deletes: the ENI wait times out (recorded, non-fatal, as today), the security group, subnet or VPC delete fails with `DependencyViolation` after `createVpcTeardownRetryConfig`'s 5 attempts, `down` reports the errors and exits non-zero, and the state is not marked down.  A re-run plans again and shows the late resource before it is deleted.  That is the intended result: nothing is deleted that the operator did not see.

A planned id that is already gone at execute time (another `down` ran meanwhile) fails its delete with a NotFound error, which is recorded the same way.  Today's window is the same; only its length changes.

### D5. The workspace step: `WorkspaceTeardown`

`includesWorkspace` is true when the plan removes the workspace's own cluster: `CurrentCluster`, `Network(id)` with `id == state.vpcId`, and `AllTagged` when a discovered network's id is `state.vpcId`.  `Packer` never includes it.

When `includesWorkspace` is true:
- `Down` runs the tail save before `execute` (fixes bug C for `down <own-vpc>` and `down --all`).
- `execute` unpublishes the proxy port before the first removal (`SocksProxyService.unpublishPort()`, which moves `Down.clearProxySystemProperties` behind the proxy service; it touches only `Constants.Proxy.PORT_PROPERTY`).
- After the removals, `WorkspaceTeardown.after(report)` stops the tunnel (whatever the result, as today), and only on success removes the tailnet device, deletes the auth key, stops the data bucket metrics and marks the state down.  A failed device removal fails the report, as today.

When it is false, none of these run (fixes bugs A and B).  The state is re-read inside `after`, through `ClusterStateManager.update`, so a write another command made meanwhile is kept.

### D6. What stays in `Down`

`Down` keeps flag parsing (flags to `TeardownTarget`), the confirmation (`TeardownConfirmation`, the renamed `TeardownPlanner`: `review(plan, options)` returns `Proceed(ConfirmedPlan) | DryRun | Declined | Nothing`), and the tail-save gate (`saveTailBeforeTeardown`, `stateToSave`, `abortEvent`, `unsavedAbort`, `reportForceSkipsTail`, unchanged).

```text
plan = teardown.plan(target)                       // one discovery
review = confirmation.review(plan, options)        // empty: NoResourcesFound etc., then Proceed with no prompt
Nothing -> emit, throw CommandFailedException      // dry run too: exits non-zero, as today
DryRun -> return                                   // nothing touched
Declined -> emit CancelledByUser, throw
Proceed -> if (includesWorkspace && holdsResources && !force && !saveTail()) throw
           report = teardown.execute(confirmed)
           emit CompletedSuccessfully, or CompletedWithErrors and throw
```

An empty plan proceeds with no prompt and no tail save, then runs the workspace step, as today ("a VPC with nothing left in it stops nothing").  `--force` with `includesWorkspace` still lists the unsaved signals before the prompt.

`TeardownOutcome`, `VpcScope`, the mutable `exitCode` and the `call()` override go.  Every failure exit is a `CommandFailedException` thrown after its typed event; the executor returns the error code and prints nothing more.  `@Suppress("TooManyFunctions")` comes off.

### D7. Boundary rule

`architecture/TeardownBoundaryTest` (ArchUnit): no class in `services.teardown..` (which holds `TeardownConfirmation`) or `commands.Down` depends on a class in `providers.aws..` or `services.aws..`.  A fixture class that names `DiscoveredResources` proves the rule fails.  `Init` keeps its other AWS dependencies (instance types, data disks), so the rule does not cover `Init`; its clean path goes through `ClusterTeardown`, and the R2 shared-scope test fails if it builds its own scope.

`DataBucketTeardown` implements the neutral `BucketTeardown` port; `MetricsStop` and `DataBucketSweep` move to the port file.  Koin: `single<InfrastructureTeardown> { Ec2InfrastructureTeardown(...) }` and `single<BucketTeardown>` in `AwsServicesModule`; `ClusterTeardown`, `WorkspaceTeardown` and `TeardownConfirmation` in `ServicesModule`.

### D8. Another target

A target is chosen by the `InfrastructureTeardown` binding.  An existing Kubernetes cluster (the tool made no network) binds an adapter whose `discover` returns no networks.  `plan` then returns `Networks(networks = [])`, `review` reports `NoResourcesFound` and proceeds with no prompt, and `execute` removes nothing and runs only the workspace step.  No such adapter is built here; the empty-plan path is covered by T4.4 with the fake.

### D9. How this fits the related issues

- **#1056 (ClusterNetwork module):** deferred by its own amendment.  When it lands, `ClusterNetwork.teardown(network)` takes phase 3 (security groups, route tables, subnets, gateway, VPC) out of `Ec2InfrastructureTeardown.remove`, which then calls it.  `InfrastructureTeardown`, the plan and `Down` do not change.  The issue named `ClusterTeardown` as a later candidate; this is it.
- **#398 (parallel VPC teardown):** the dependency queue replaces `executeVpcTeardown` inside the adapter.  The plan already holds every id by kind, which is the queue's input, and per-network `remove` calls in `execute` can run in parallel there.  Nothing here makes it harder.
- **#1171 (ByTags):** its `VpcOwnership` decision stays in the adapter's `discover(ByTags)`; the scope rule that routes to it is now in `TeardownScopes`.
- **#1062 (multi-cloud):** the port is provider-neutral, the adapter carries the provider name, and `TeardownTarget` and `TeardownScope` name networks, not VPCs.

### D10. Scale

One discovery per `down` instead of two: about 9 describe calls saved per VPC (by `--all`, per VPC).  `TerminateInstances` takes the whole instance list in one call, as today.  At 100 nodes the plan holds about 110 ids; it is held in memory only.  Execute stays sequential per network for `--all`, as today (#398).

## Test plan

Tiers: unit (`./gradlew test`), integration (`./gradlew integrationTest`, LocalStack EC2), real AWS.  CI runs unit and integration.  Each control is: make the stated break, see the test fail, revert.

**Fake:** `FakeInfrastructureTeardown` (`src/test/.../services/teardown/`): an in-memory map of networks; `add(networkId, kind, id)` at any time; `discover` reads the map; `remove` deletes only the ids passed and records them; a scripted error per network.

### R1. down deletes exactly the confirmed plan

| Test | Tier | Control |
|---|---|---|
| `ClusterTeardownTest.execute removes only the planned resources when one appears after the plan` | unit | make `execute` call `plan(plan.target)` and remove that: fails, the late id is removed |
| `DownTest.a resource that appears during the tail save is not removed` (save fake adds `i-late`) | unit | same break |
| `DownTest.--all leaves a network that appears after the preview` | unit | same break |
| `Ec2InfrastructureTeardownTest.remove makes no describe call` (mocked `VpcService`, `EMRService`, `OpenSearchService`: AWS side effects) | unit | call `discoverResources` in `remove` |
| `Ec2InventoryTest.toPlanned and fromPlanned round-trip every field` | unit | drop `natGatewayIds` from `fromPlanned` |
| `Ec2InfrastructureTeardownIntegrationTest.a subnet created after discover survives remove` | integration | rediscover in `remove` |
| Real AWS L1: at the `down` prompt, create a subnet in the cluster VPC; answer yes | AWS | none; the result is the evidence: the subnet survives, the VPC delete fails, exit non-zero, state not marked down; a second `down` removes it |

### R2. The scope rule is in one place

| Test | Tier | Control |
|---|---|---|
| `TeardownScopesTest`, one case per table row of D2 | unit | swap the `vpcSupplied` branch; drop the `vpcId == id` check |
| `ClusterTeardownTest.down and init --clean get the same scope` (both call `plan(CurrentCluster)` on a supplied-VPC state; the fake records the scope) | unit | have `Init` build its own scope |
| `InitTest` clean cases, moved to the fake (refused with live resources, proceeds when empty or gone, a supplied VPC is searched by cluster tag, a discovery failure propagates and deletes nothing) | unit | treat a thrown discover as "nothing live" |

### R3. No provider type in neutral code

| Test | Tier | Control |
|---|---|---|
| `TeardownBoundaryTest.neutral teardown code names no AWS type` | unit | the rule fails on fixture `NamesDiscoveredResources` (a test of its own) |

### R4. The workspace step runs only for the workspace's cluster

| Test | Tier | Control |
|---|---|---|
| `DownTest.down of another VPC leaves the workspace state, device and tunnel` | unit | set `includesWorkspace = true` for `Network` |
| `DownTest.a declined prompt does not stop the tunnel` | unit | stop the tunnel before the review |
| `DownTest.down --packer leaves the workspace alone` | unit | `includesWorkspace = true` for `Packer` |
| `DownTest.down of the workspace VPC by id saves the tail, then marks the state down` | unit | `saveTail` only for `CurrentCluster` |
| `DownTest.down --all that finds the workspace VPC saves the tail first` | unit | compute `includesWorkspace` from the target only |
| `DownTailscaleDeviceTest` (six cases, moved to the fake) | unit | skip `removeDevice` on success |
| `DownCommandTest.a failed teardown keeps the state for a retry` | unit | mark down on failure |

### R5. The `down` rules stay

`DownBackupTest` (every case, moved to the fake): a failed save removes nothing (the fake's removed set is empty), `--force` skips the save, a failed removal after a save restores nothing.  Control: run `execute` before the save gate; the "removes nothing" cases fail.  `DownDryRunTest` (all cases, moved to the fake; the fake records no removal and the tunnel and state are untouched).  Control: let `DryRun` fall through to `execute`.  `DownS3NoExpiryIntegrationTest` (integration, moved to the fake; LocalStack S3 is unchanged).

### R6. One exit path

`DownCommandTest`: `a teardown that completes with errors exits non-zero`, `a declined prompt exits non-zero`, `no cluster state exits non-zero`, `a successful teardown exits zero` (all through `CommandExecutor`).  Control: return instead of throwing after `CompletedWithErrors`.

### R7. Delete order and resources unchanged

| Test | Tier | Control |
|---|---|---|
| `Ec2InfrastructureTeardownTest.should delete resources in correct order` (moved, `inOrder`) | unit | swap subnets and route tables |
| The moved `AwsInfrastructureServiceTest` teardown cases (supplied VPC, ByTags, `--all`, packer, error collection) | unit | each keeps its existing assertion |
| Real AWS L2: `init --up`, `down` on main, and the same on the branch; own VPC and `init --vpc` | AWS | compare the deleted-resource list and the `Infra.*` event order from both runs; they match |

Real AWS L1 and L2 run on a real cluster (tag `embark_offhours=off`), with the output in the PR.

## Risks / Trade-offs

- A `WholeNetwork` teardown with a late resource now fails instead of deleting it (D4).  That is the fix, and a re-run finishes it.
- The pure move of about 450 lines into the adapter is large.  It is mechanical, and the moved tests keep their assertions.

## Owner decisions

None yet.  Open questions are in the hand-back.
