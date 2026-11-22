## Why

Issue #1242 (Teardown module: plan once, delete exactly the confirmed plan).  Teardown has no module of its own, and it has a safety bug.

- **What is deleted is not the preview the operator confirmed.**  `Down` calls `teardown*(…, dryRun = true)` for the preview, then `teardown*(…, dryRun = false)` after the prompt and the tail save.  Each call discovers the VPC again, and the tail save between them can take minutes.  A resource that appears in that window is deleted without the operator ever seeing it in the summary they approved.  No test can catch this, because the preview and the delete are two separate calls into a mock.
- **The scope rule is written twice**, in `Down.teardownCurrentCluster` and in `LiveInfrastructureLookup.find` (which `init --clean` uses).
- **`Down` holds the whole sequence**: mode, scope, preview, review, tail save, proxy unpublish, delete, tunnel stop, tailnet device and auth key, bucket metrics, and state reset.  It decides the exit code in two places (`TeardownOutcome` and a mutable `exitCode`).
- **The provider interface is wide and controlled by flags**: five public `teardown*(…, dryRun)` methods, plus `includePackerVpc`.  `TeardownPlanner`, `LiveInfrastructureLookup` and `Down` import provider types (`DiscoveredResources`, `TeardownMode`, `TeardownResult`).

Reading the code turned up four more bugs.  This change fixes them all:

- **`down <vpc-id>` resets the workspace when it names another VPC.**  It removes the workspace's tailnet device, deletes its auth key, stops its bucket metrics and marks its state down.
- **`down` stops the SOCKS tunnel when it tears nothing down.**  That happens on a declined prompt, on `down --packer`, on `down` of an unrelated VPC, on an empty `--all`, and when there is nothing recorded to tear down.
- **`down <own-vpc-id>` and a `down --all` that finds the workspace's VPC skip the tail save.**  The data still on the control node is lost.
- **A teardown that deletes the VPC but records an error keeps the dead VPC id in the state.**  An ENI wait timeout is one such error.  The next `down` then fails with a NotFound error from AWS, so the workspace can no longer be torn down.

## What Changes

- **A new provider-neutral module, `services/teardown/ClusterTeardown`**, with two operations, `plan(target)` and `execute(confirmed)`.  `execute` changes only the resources in the confirmed plan and discovers nothing.
- **The scope rule is resolved once, in `TeardownScopes`.**  The scopes are: the whole network, by tags, one cluster in a supplied network, all tagged, and packer.  `down` and `init --clean` both get the scope from `plan`.
- **A neutral port, `InfrastructureTeardown` (`discover`, `remove`), with one adapter, `services/aws/AwsInfrastructureTeardown`.**
  - The teardown half of `AwsInfrastructureService` moves into the adapter unchanged, delete order included.
  - The plan also records each route table's subnet associations and, in a kept network, each route to the gateway, so `remove` changes only planned ones.
  - Only the AWS error code `InvalidVpcID.NotFound` for the workspace's own network plans that network as gone.
  - `--all` fetches the EMR and OpenSearch maps once and skips the packer VPC before reading its inventory.
  - The VPC teardown retry takes a policy that a test can shorten.
- **The local work after a teardown becomes one step, `WorkspaceTeardown`.**  That work is the tunnel stop, the tailnet device, the auth key, the bucket metrics and the state reset.
  - The step runs only when the plan includes the workspace's own network.
  - The step depends on that network's own result, not on the whole run.
  - The tail save runs under the same condition.
- **`Down` keeps the flag parsing, the confirmation prompt and the tail-save gate.**  The prompt is `TeardownConfirmation`, renamed from `TeardownPlanner`.  `Down` fails through `CommandFailedException`, so the exit code is decided in one place.
- **`up` records the account id, and `plan` fails when the recorded region or account differs from the configured one**, before it discovers anything.  A state that records no account (written before this change) checks the region only.
- **Only `down --all` deletes data buckets, and only the empty buckets of the clusters it planned**, not every empty data bucket in the account.  A plain `down` deletes none, as today.  A cluster with no data bucket is nothing to do and is not reported as kept.
- **`LiveInfrastructureLookup` is removed.**  `init --clean` asks `ClusterTeardown.plan(CurrentCluster)` instead and emits no teardown event.
- **New ArchUnit rules in `PackageBoundaryTest`:**
  - neutral teardown code names no AWS type;
  - only the module discovers;
  - only the confirmation builds a confirmed plan.

## Capabilities

### New Capabilities

- `cluster-teardown`: "down changes only the confirmed plan" and "Teardown of the workspace's own cluster".  At archive, "Cluster Teardown" moves here from `cluster-lifecycle` (task 7.1), so every teardown rule lives in one capability.

### Modified Capabilities

- `cluster-lifecycle`:
  - "Cluster Teardown" runs the pre-teardown save only for a teardown that includes the workspace's own cluster, and gains the scenario "A teardown of another network runs no save".  Its "Teardown of all clusters" scenario now deletes only the empty data buckets of the removed clusters.
  - "Cluster Initialization": `init --clean` refuses when the recorded VPC is not found or the recorded region or account differs, instead of proceeding on a VPC "AWS shows gone".  "When the lookup itself fails, `init --clean` MUST fail and delete nothing" is kept.
- `networking`: "SOCKS Proxy" and "SOCKS tunnel reconnects on its own" say every `down` stops the tunnel.  Now only a `down` that includes the workspace's own cluster stops it; any other `down` leaves it running.

## Impact

- **Code:**
  - `commands/Down.kt`, `commands/Init.kt`, and the Koin modules.
  - `services/TeardownPlanner.kt`, which becomes `TeardownConfirmation`.
  - `services/LiveInfrastructureLookup.kt`, which is removed.
  - `services/aws/AwsInfrastructureService.kt`: the teardown half moves out, along with its EMR and OpenSearch dependencies.
  - `providers/aws/TeardownTypes.kt`, which is removed.
  - `services/aws/DataBucketTeardown.kt`, which becomes `S3DataBucketTeardown`; `S3BucketAdmin.findDataBuckets` and `Event.Teardown.DataBucketDiscoveryFailed` are removed.
  - `configuration/ClusterState.kt` (`InfrastructureState.accountId`) and `commands/Up.kt`, which records it.
  - `services/aws/EC2VpcService.kt`: planned associations and the retry policy.
  - `EMRService` and `OpenSearchService`, which get the subnet maps.
  - `proxy/SocksProxyService.kt`, which gets `unpublishPort`.
  - The new `services/teardown/` package.
- **Tests:**
  - The `Down*Test` files, `DownS3NoExpiryIntegrationTest`, `InitTest`, `LiveInfrastructureLookupTest`, `TeardownPlannerTest`, and the teardown cases of `AwsInfrastructureServiceTest` and its integration test move to in-memory fakes or to the adapter.
  - Six `DownTest` cases that never ran `Down` are replaced by `WorkspaceTeardownTest`.
  - The bug tests are first written and seen failing on main.
- **Behaviour changes:**
  - One discovery per `down` instead of two, so the discovery events print once.
  - A resource created after the preview is not removed.  In a network easy-db-lab created, it makes `down` fail and keep the state, after the existing retries (about 75 s for a subnet, up to about 15 min for an instance).  A re-run lists the resource and removes it.
  - `down <own-vpc-id>` saves the tail and resets the workspace.
  - A `down --all` that includes the workspace's network saves the tail first and resets the workspace.  The extra time is the save: usually a few minutes, about 23 minutes at worst (design-fit D10).  Today `--all` never touches the workspace.
  - `down <other-vpc-id>`, `down --packer`, a declined prompt and "nothing to tear down" no longer stop the tunnel or change the workspace.
  - A declined prompt emits `CancelledByUser` only, not also `CompletedWithErrors(["Teardown cancelled by user"])`.  It still exits non-zero.
  - Under `--all`, a failure in another network no longer keeps the workspace's state pointing at its deleted VPC; `down` still exits non-zero.
  - A recorded VPC that AWS no longer has is shown in the preview as gone and must be confirmed; then `down` finishes the workspace step instead of failing.  `init --clean` refuses on it.
  - A recorded region or account that differs from the configured one fails `down` and `init --clean`, and changes nothing.
  - `down --all` no longer deletes the empty data bucket of a cluster it did not plan.
  - Under `--all`, the workspace step runs before the empty-bucket sweep.
- **Docs:** `docs/reference/commands.md` (`down`), `providers/CLAUDE.md`, `services/CLAUDE.md`, `services/aws/CLAUDE.md` and `commands/CLAUDE.md`.
- **Related:** #1056 (ClusterNetwork module), #398 (parallel VPC teardown), #1171 (ByTags scope) and #1062 (multi-cloud epic).  `design-fit.md` D9 explains how each one fits.
