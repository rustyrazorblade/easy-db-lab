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

### D7b. The bucket port

`S3DataBucketTeardown` (renamed from `DataBucketTeardown`) implements the neutral `BucketTeardown` port:

```kotlin
interface BucketTeardown {
    fun stopMetrics(state: ClusterState): MetricsStop                 // needs dataBucket and metricsConfigId()
    fun deleteEmptyBuckets(clusterIds: List<String>): DataBucketSweep // the adapter builds each bucket name
}
```

- Neutral code passes cluster ids, never bucket names.  The S3 adapter builds `easy-db-lab-data-<clusterId>` (`Constants.S3.DATA_BUCKET_PREFIX`).
- `plan` fills `Networks.sweepClusterIds` only for `AllTagged`, from each planned network that has a `clusterId` and is not `GONE` (D1).  A plain `down`, `down <vpc-id>` and `--packer` delete no bucket, as today.
- `--all` deletes only the empty data buckets of the clusters it planned.  The account-wide `findDataBuckets()` sweep is removed, so `down --all` no longer deletes the empty data bucket of a cluster it did not plan.
- A bucket S3 reports as missing (`NoSuchBucket`) is nothing to do: the adapter emits no `DataBucketDeleting` and no `DataBucketKept` for it, and it is in neither list of the sweep.  A cluster made before data buckets existed, or whose bucket the owner deleted, has none.  Any other S3 error keeps today's `DataBucketKept(bucket, reason)`.
- Deleted with this change (task 1.3): `S3BucketAdmin.findDataBuckets` (the interface, the `AwsS3BucketService` implementation and their tests), `Event.Teardown.DataBucketDiscoveryFailed` and its line in `events/CLAUDE.md`.
- `MetricsStop` and `DataBucketSweep` move to the port file.

The VPC teardown retry becomes `VpcTeardownRetryPolicy(maxAttempts, baseInterval)`, a constructor parameter of `EC2VpcService` and `EC2SecurityGroupService` (like `pollInterval`).  Its default comes from the existing `Constants.Retry` values.  The retry stays; a test passes a short policy.

Koin:
- in `AwsServicesModule`: `single<InfrastructureTeardown> { AwsInfrastructureTeardown(...) }`, with `location()` built from the same `User.region` the EC2 client is built with (`AwsClientsModule.kt:54`) and the account from `AccountIdentity`, and `factory<BucketTeardown> { S3DataBucketTeardown(...) }`;
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
  - Under #398, a throw marks the thrower and every network not yet started as `NotAttempted`, as the sequential loop does (D1); a network already running finishes and records its own result.
- **#1171 (ByTags):** its `VpcOwnership` decision stays in `discover(ByTags)`.
- **#1062 (multi-cloud):** the port is neutral and the adapter carries the provider name.  D8 names the state reshape that remains.

### D10. Scale

One discovery per `down` instead of two.  One discovery of one named VPC is up to `10 + E + D` calls:
- up to 8 EC2 calls: one `DescribeVpcs` for the name, tags, `ClusterId` and the `GONE` mapping (D3), 6 inventory describes, and the gateway-route `DescribeRouteTables` for a kept network only;
- `ListClusters` and one `DescribeCluster` per active EMR cluster in the account;
- `ListDomainNames` and one `DescribeDomain` per OpenSearch domain.

Today a `down` makes two discoveries, each with separate name and tag reads.

For `--all` over K VPCs it is now `6K + 3 + E + D`: one tag search that returns the ids, names and tags (so no per-VPC `DescribeVpcs`), 6 inventory describes per VPC, and the EMR and OpenSearch maps fetched once.  Every `--all` network is tagged, so none needs the kept-network route describe.  The account id is read once per process (`AccountIdentity` caches it).

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
- A recorded region or account that differs from the client's now fails `plan` (D1).  Before, the lookup in the wrong region or account returned NotFound and could reset a live workspace.
- A state written before this change records no account id, so its `down` skips the account check and checks the region only.  Such a workspace pointed at the wrong account with the right region can still hit the NotFound path; for its own recorded network that is now `GONE`, previewed and confirmed before any reset.  Clusters are ephemeral, so this lasts only until the next `up`.

## Owner decisions

- **Account check (lead decision, round 3; the owner can override at approval):** `up` records the account id, `plan` checks it with the region, and a state with no recorded account skips the account check (D1, Risks).
- Open questions are in the hand-back.  The working assumption for the late resource is D4.
