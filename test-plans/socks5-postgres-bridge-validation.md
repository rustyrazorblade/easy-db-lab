# Lab Plan: SOCKS5 Postgres Bridge Validation (No Tailscale)

## Objective

Validate the #744 in-process SOCKS→loopback TCP bridge end-to-end on a real cluster.
With Tailscale disabled, the postgres kit's JDBC endpoint (`:30432`) is reachable only
through the `ssh -D` SOCKS5 tunnel. The success criterion is that `postgres sql "SELECT 1"`
returns `1` — routed through the in-process loopback bridge (`KitJdbcSqlService` →
`SocksTcpBridge`) over the existing tunnel. This is the JDBC path that #744 adds; it is
distinct from the Cassandra CQL path (which uses `SocksProxyNettyOptions`).

## Cluster Name

socks5-pg-validation

## Datacenters

single

## Environment

Single DC, 1 database node on `i4i.xlarge` (local NVMe, no EBS required), 0 app nodes.
Tailscale disabled via `--no-tailscale`, so all cluster access routes through the
detached-SSH SOCKS5 proxy. Binary under test: branch `740-sql-over-socks`
(`build/install/easy-db-lab/bin/easy-db-lab`).

## Steps

### 1. Provision the cluster

Provision a 1-node cluster with Tailscale explicitly disabled.

```bash
$EDB init socks5-pg-validation --db 1 --instance i4i.xlarge --no-tailscale --up
```

### 2. Install the postgres kit

```bash
$EDB kit install postgres
```

### 3. Start postgres

```bash
$EDB postgres start
```

### 4. Verify postgres is running

```bash
$EDB postgres status
```

### 5. Run SQL over the SOCKS bridge — THE #744 VALIDATION

Must return `1`. This connects to the JDBC endpoint's private IP, which is reachable
only via the tunnel, so a success proves the in-process loopback bridge routed the
raw-TCP pgjdbc connection through the SOCKS5 tunnel.

```bash
$EDB postgres sql "SELECT 1"
```

### 6. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- The entire test runs with Tailscale disabled. `postgres sql` is the *only* step that
  exercises the #744 bridge; the earlier Cassandra SOCKS plan does not touch it.
- Do NOT tear down on failure — leave the cluster up for debugging (per this run's
  instruction). Step 6 runs only on a clean pass, or is skipped in favor of exploration.
- If step 5 fails, capture the exact error and check: is `easydblab.socks5Port` published
  (proxy up)? did `@RequiresProxy` establish the tunnel? is the bridge listener bound on
  127.0.0.1? Does a direct connect fail the same way (confirming the private IP is only
  reachable via SOCKS)?
