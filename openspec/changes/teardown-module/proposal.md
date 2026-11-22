## Why

Issue #1242 (Teardown module: plan once, delete exactly the confirmed plan).  Teardown has no module of its own, and it has a safety bug.

- **The confirmed preview is not what is deleted.**  `Down` calls `teardown*(…, dryRun = true)` for the preview and then `teardown*(…, dryRun = false)` after the prompt and the tail save.  Each call discovers the VPC again.  The tail save can take minutes.  A resource that appears in that window is deleted, and the operator never saw it in the summary they approved.  No test can catch this, because the preview and the delete are two separate calls into a mock.
- **The scope rule is written twice**: in `Down.teardownCurrentCluster` and in `LiveInfrastructureLookup.find` (which `init --clean` uses).
- **`Down` holds the whole sequence**: mode, scope, preview, review, tail save, proxy unpublish, delete, tunnel stop, tailnet device and auth key, bucket metrics, state reset.  The exit code is decided in two places (`TeardownOutcome` and a mutable `exitCode`).
- **The provider interface is wide and boolean-flagged**: five public `teardown*(…, dryRun)` methods on `AwsInfrastructureService`, plus `includePackerVpc`.  `TeardownPlanner`, `LiveInfrastructureLookup` and `Down` import `providers.aws.DiscoveredResources`, `TeardownMode` and `TeardownResult`.

While reading the code for this plan, two more bugs turned up.  Both are fixed in this change:

- **`down <vpc-id>` resets the workspace when it names another VPC.**  In `SpecificVpc` mode, `Down` removes the workspace's tailnet device, deletes its auth key, stops its data bucket metrics and marks its state down, whatever VPC was named.
- **A declined prompt stops the SOCKS tunnel.**  `cleanupSocks5Proxy` runs for every finished outcome, including "Teardown cancelled by user", `down --packer` and `down` of an unrelated VPC.

## What Changes

- A new provider-neutral module, `services/teardown/ClusterTeardown`, with two operations: `plan(target)` and `execute(plan)`.  `execute` deletes exactly the resources in the plan and discovers nothing.
- The scope rule (whole network, by tags, one cluster in a supplied network, all tagged, packer) is resolved once, in `TeardownScopes`.  `down` and `init --clean` both get it from `plan`.
- A provider-neutral port, `InfrastructureTeardown` (`discover(scope)`, `remove(network)`), with one adapter, `services/aws/Ec2InfrastructureTeardown`.  The teardown half of `AwsInfrastructureService` moves into the adapter unchanged, delete order included.  `DiscoveredResources` stays inside the adapter.  `TeardownMode` and `TeardownResult` are removed.
- The work after a successful teardown (tunnel stop, tailnet device, auth key, data bucket metrics, state reset) becomes one step, `WorkspaceTeardown`.  It runs only when the confirmed plan includes the workspace's own network.  The tail save runs under the same condition.
- `Down` keeps flag parsing, the confirmation prompt (`TeardownConfirmation`, renamed from `TeardownPlanner`) and the tail-save gate.  It fails through `CommandFailedException`, so the exit code is decided in one place.
- `LiveInfrastructureLookup` is removed.  `init --clean` asks `ClusterTeardown.plan(CurrentCluster)` and refuses when a planned network still holds resources.
- An ArchUnit rule: no class in `services.teardown..`, and neither `Down` nor `Init`'s teardown path, depends on `providers.aws..` or `services.aws..`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cluster-lifecycle`: two added requirements.  "down deletes exactly the confirmed plan".  "Teardown of the workspace's own cluster": the tail save and the local cleanup run when the plan includes the workspace's network, and only then.

## Impact

- Code: `commands/Down.kt`, `commands/Init.kt`, `services/TeardownPlanner.kt` (becomes `TeardownConfirmation`), `services/LiveInfrastructureLookup.kt` (removed), `services/aws/AwsInfrastructureService.kt` (the teardown half moves out), `providers/aws/TeardownTypes.kt` (removed; `DiscoveredResources` moves to `services/aws`), `services/aws/DataBucketTeardown.kt` (implements a neutral port), `proxy/SocksProxyService.kt` (`unpublishPort`), the new `services/teardown/` package, and Koin modules.
- Tests: the six `Down*Test` files, `DownS3NoExpiryIntegrationTest`, `InitTest`, `LiveInfrastructureLookupTest`, `TeardownPlannerTest` and the teardown cases of `AwsInfrastructureServiceTest` move from mocks of `AwsInfrastructureService` to an in-memory `FakeInfrastructureTeardown`.
- Behaviour: one discovery per `down` instead of two, so the discovery events (`Infra.ResourceDiscovering`, the `--all` and `--packer` search events) are printed once.  `down <own-vpc-id>` now saves the tail.  `down <other-vpc-id>`, `down --packer` and a declined prompt leave the workspace alone.
- Docs: `docs/reference/commands.md` (`down`), `providers/CLAUDE.md`, `services/aws/CLAUDE.md`, `commands/CLAUDE.md` (services table).
- Related: #1056 (ClusterNetwork module), #398 (parallel VPC teardown), #1171 (ByTags scope), #1062 (multi-cloud epic).  How each one fits is in `design.md`.
