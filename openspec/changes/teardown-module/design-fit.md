## Design, part 2

This file continues [`design.md`](design.md) with D7 to D10, the declined findings, the risks and the owner decisions.  The tests are in [`test-plan.md`](test-plan.md).

### D7. Boundary rules, ports, Koin

The new rules go into `architecture/PackageBoundaryTest`, next to the SDK rules:
- No class in `services.teardown..`, and not `commands.Down`, depends on `providers.aws..` or `services.aws..`.
- Only classes in `services.teardown..` call `InfrastructureTeardown.discover` or construct a `TeardownScope`.
- Only `TeardownConfirmation` calls the `ConfirmedPlan` constructor.

Each rule has a fixture in a test package the rule selects:
- `com.rustyrazorblade.easydblab.services.teardown.fixtures.NamesAwsInventory` for the first rule;
- `com.rustyrazorblade.easydblab.commands.fixtures.CallsDiscover` and `BuildsConfirmedPlan` for the other two.

The main scan uses `DoNotIncludeTests`, so the fixtures stay out of it.  `Init` keeps its other AWS dependencies, so the first rule does not cover `Init`; the second rule stops `Init` from resolving a scope of its own.

`S3DataBucketTeardown` (renamed from `DataBucketTeardown`) implements the neutral `BucketTeardown` port:

```kotlin
interface BucketTeardown {
    fun stopMetrics(bucket: String): MetricsStop
    fun deleteEmptyBuckets(names: List<String>): DataBucketSweep   // only the planned names; a non-empty bucket is kept
}
```

`plan` lists `easy-db-lab-data-<clusterId>` for each planned network that has a `clusterId` and is not `GONE`.  The account-wide `findDataBuckets()` sweep is removed from `down`, so `down --all` no longer deletes the empty data bucket of a cluster it did not plan.  `MetricsStop` and `DataBucketSweep` move to the port file.

The VPC teardown retry becomes `VpcTeardownRetryPolicy(maxAttempts, baseInterval)`, a constructor parameter of `EC2VpcService` and `EC2SecurityGroupService` (like `pollInterval`).  Its default comes from the existing `Constants.Retry` values.  The retry stays; a test passes a short policy.

Koin:
- in `AwsServicesModule`: `single<InfrastructureTeardown> { AwsInfrastructureTeardown(...) }`, with `region()` from the same `User.region` the EC2 client is built with (`AwsClientsModule.kt:54`), and `factory<BucketTeardown> { S3DataBucketTeardown(...) }`;
- in `ServicesModule`: `ClusterTeardown`, `WorkspaceTeardown` and `TeardownConfirmation`.

`AwsInfrastructureService` loses `emrService`, `openSearchService` and `@Suppress("TooManyFunctions")`.  `Init` loses `infrastructureService`.  `EMRService.findClustersInVpc` and `OpenSearchService.findDomainsInVpc` are deleted (D3).

### D8. An existing Kubernetes cluster

The port is neutral, and the scope rule is not yet.  `TeardownScopes.resolve` reads `state.vpcId` and `state.vpcSupplied`, which are AWS-shaped fields of `InfrastructureState`.  An existing cluster (EKS, where the tool made no network) records no VPC id, so `plan(CurrentCluster)` returns `NothingRecorded(NO_NETWORK_RECORDED)` or `DeviceOnly`, and no adapter's `discover` is called.

A later change needs a recorded "no network of ours" state, and a plan entry for it that, like `GONE`, is previewed, confirmed and gets only the workspace step.  The `GONE` path built here is that shape.  The reshape of `InfrastructureState` belongs to #1062, and nothing for it is built here.

### D9. How this fits the related issues

- **#1056 (ClusterNetwork module):** when it lands, `ClusterNetwork.teardown(network)` takes phase 3 out of `AwsInfrastructureTeardown.remove`, which then calls it.  The port, the plan and `Down` do not change.  `Down` calls `ClusterTeardown` and never `ClusterNetwork`; task 6.4 posts this on #1056.
- **#398 (parallel VPC teardown):**
  - Its dependency queue replaces `executeVpcTeardown` inside the adapter.  The plan already holds every id by kind, which is the queue's input.
  - The per-network loop is in `ClusterTeardown.execute`, and `TeardownReport.results` already keeps one result per network, in plan order.
  - #398 must give phase 1 and that loop a dedicated, bounded executor.  Today phase 1 runs on the common ForkJoinPool (`AwsInfrastructureService.kt:592`), which blocking waits would starve.
  - #398 can start the other networks while the workspace saves its tail, if "a failed save removes nothing" is scoped to the workspace's network.
- **#1171 (ByTags):** its `VpcOwnership` decision stays in `discover(ByTags)`.
- **#1062 (multi-cloud):** the port is neutral and the adapter carries the provider name.  D8 names the state reshape that remains.

### D10. Scale

One discovery per `down` instead of two.  One discovery of one VPC is `9 + E + D` calls: 7 EC2 calls, plus `ListClusters` and one `DescribeCluster` per active EMR cluster in the account, plus `ListDomainNames` and one `DescribeDomain` per OpenSearch domain.  Today a `down` makes `2 x (9 + E + D)`.

For `--all` over K VPCs it was `2K x (9 + E + D)`.  It is now `6K + 3 + E + D`: one tag search that returns the ids, names and tags, 6 EC2 describes per VPC, and the EMR and OpenSearch maps fetched once.

`TerminateInstances` takes the whole instance list in one call.  At 100 nodes the plan holds about 110 ids, in memory only.  Execute stays sequential per network (#398).

`down <own-vpc>` and a `down --all` that includes the workspace now run the tail save first.  The save's worst case is about 23 minutes:
- the senders stop in up to 120 s;
- Loki and Mimir each shut down in up to 600 s and scale down in up to 660 s;
- Tempo's 300 s drain runs in parallel with them.

A save usually finishes in a few minutes.  The total is the save plus the sum of the network removals.

## Declined findings

- **Debt m10 (move the tail-save gate out of `Down`):** declined.  #1242 says `Down` keeps the gate, and moving it is a separate change.  For later: `abortEvent` and `unsavedAbort` could sit beside `TeardownBackupService`.
- **Tests T13 (`remove makes no describe call`):** dropped, per the lead decision.  `remove` must describe (the ENI waits, the security group describe).  Instead, `AwsInfrastructureTeardownIntegrationTest` asserts what survives, and T23's event count shows that discovery runs once.

## Risks / Trade-offs

- An `OWNED` network with a late resource now fails instead of deleting it (D4).  That is the fix, and a re-run finishes the teardown.
- The pure move of about 500 lines into the adapter is large.  It lands as its own commit (task 2.1), and the moved tests keep their assertions.
- A recorded region that differs from the client's now fails `plan` (D1).  Before, the lookup in the wrong region returned NotFound and could reset a live workspace.

## Owner decisions

None yet.  Open questions are in the hand-back.  The working assumption for the late resource is D4.
