## Context

The `server` command starts a long-lived process that exposes MCP and REST endpoints for cluster management. It is often started in automated or unattended workflows (e.g., running alongside an AI assistant session). When the underlying AWS infrastructure — specifically the VPC — is torn down (via `easy-db-lab down` or external deletion), the server process has no meaningful work to do but continues running indefinitely.

The VPC is used as the sentinel because it is the root of the cluster's AWS infrastructure. If the VPC is gone, all associated EC2 instances, subnets, and security groups are also gone — the cluster no longer exists.

The VPC ID for the current cluster is stored in `ClusterState` and accessible via `ClusterStateManager`.

## Goals / Non-Goals

**Goals:**
- Provide an opt-in `--auto-shutdown` flag on the `server` command
- Check whether the cluster's VPC still exists on each status refresh cycle (`--refresh`)
- Emit a domain event and exit cleanly when the VPC is no longer found

**Non-Goals:**
- Automatic shutdown without the flag (opt-in only)
- Monitoring resources other than the VPC (instances, subnets, etc.)
- Reacting to partial infrastructure removal (only full VPC deletion triggers shutdown)
- Persistent state or restart behavior after shutdown

## Decisions

### Decision 1: Integrate VPC check into `StatusCache` refresh cycle

The VPC existence check runs inside `StatusCache` on each refresh, reusing the existing `--refresh` interval. When `autoShutdown` is enabled and the VPC is not found, `StatusCache` emits `Event.Server.InfrastructureGone` and calls `exitProcess(0)`. The `Server` command passes `autoShutdown` and the cluster VPC name directly to `StatusCache`.

**Alternative considered**: A separate background service on its own timer. Rejected — `StatusCache` already polls AWS on a schedule; a second thread for VPC checks is redundant overhead with no benefit.

### Decision 2: Opt-in via `--auto-shutdown` flag, not default behavior

Auto-shutdown should not happen unexpectedly during interactive use. An explicit flag makes the intent clear and prevents surprises.

**Alternative considered**: Always-on with a `--no-auto-shutdown` flag. Rejected — fail-fast-on-default would surprise users who run the server interactively.

### Decision 3: Use `VpcDiscoveryOperations.findVpcByName()` as the existence check

`findVpcByName()` returns `null` when the VPC doesn't exist. This is already implemented in `VpcService` / `VpcInfrastructure`. The cluster's VPC name is derivable from `ClusterState`.

**Alternative considered**: Describe VPC by ID. Also valid, but the name-based lookup already has the right semantics (null = not found) and is used elsewhere.

### Decision 4: Single consecutive miss triggers shutdown (no retry dampening)

If the VPC is gone, it is gone. VPC deletion in AWS is not transient. There is no need to wait for N consecutive failures before shutting down.

**Alternative considered**: Require 2–3 consecutive misses. Rejected — AWS VPC existence checks are reliable; the complexity of dampening is not warranted.

### Decision 5: Emit a domain event, then exit the JVM

The watchdog emits `Event.Server.InfrastructureGone` and then calls `exitProcess(0)`. This ensures MCP clients and REST consumers see a structured event before the process ends.

## Risks / Trade-offs

- **False positive on transient AWS API error** → The check should distinguish "not found" (null) from an AWS API exception. API exceptions should be logged and the watchdog should continue polling rather than triggering shutdown. Only a confirmed null result (VPC not found) causes shutdown.
- **VPC ID not present in ClusterState** → If the cluster was provisioned before VPC tracking was introduced, `ClusterState.vpcId` may be null. The watchdog should skip polling and log a warning rather than crashing or shutting down.
- **No clean shutdown hook** → The `exitProcess(0)` approach bypasses Ktor's graceful shutdown. This is acceptable for an infra-gone scenario since the cluster is already gone. A future improvement could use a coroutine cancellation signal instead.

## Open Questions

- Should the watchdog also check instance existence as a secondary signal, or is VPC-only sufficient? (Current proposal: VPC-only)
