# Lab Plan: SOCKS5 Proxy Validation (No Tailscale)

## Objective

Validate that the new detached-SSH SOCKS5 proxy implementation works correctly when Tailscale is disabled. The cluster must come up, Cassandra 5.0 must start, and a CQL query must succeed — all routed through the SOCKS proxy with no Tailscale involvement.

## Cluster Name

socks5-validation

## Datacenters

single

## Environment

Single DC, 3 database nodes on `i4i.xlarge` (local NVMe, no EBS required), 0 app nodes. Tailscale disabled via `--no-tailscale`.

## Steps

### 1. Provision the cluster

Provision a 3-node cluster with Tailscale explicitly disabled.

```bash
$EDB init socks5-validation --db 3 --instance i4i.xlarge --no-tailscale --up
```

### 2. Select Cassandra 5.0

```bash
$EDB cassandra use 5.0
```

### 3. Start Cassandra

```bash
$EDB cassandra start
```

### 4. Verify cluster health via nodetool

All 3 nodes should show UN (Up/Normal). This command routes through the SOCKS proxy.

```bash
$EDB cassandra nt status
```

### 5. Run a CQL query over the SOCKS proxy

The result should show `5.0.x`. This confirms the detached-SSH SOCKS5 proxy is routing traffic correctly.

```bash
$EDB cassandra cql "SELECT release_version FROM system.local"
```

### 6. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- The entire test runs with Tailscale disabled. All cluster access (CQL, nodetool) routes through the detached-SSH SOCKS5 proxy introduced in this branch.
- If step 5 returns `No node was available`, wait 30 seconds and retry — the sidecar may not be fully initialized yet.
- This validates the minimal happy path for the new proxy implementation. Reconnect behavior and proxy restart edge cases are out of scope.
