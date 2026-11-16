# Lab Plan: Telemetry Redirect (Two Datacenters)

## Objective

Validate the life-of-cluster telemetry-redirect mode (issue #937).  DC1 runs the full local observability stack.  DC2 runs in redirect mode and ships all four telemetry signals to DC1.  The plan proves that every DC2 signal — metrics, logs, traces, and profiles — lands in DC1's backends carrying DC2's cluster name as its origin identifier, and that DC2 stands up no local Grafana and no local backends.

## Cluster Name

telemetry-redirect-dc1 (DC1), telemetry-redirect-dc2 (DC2)

## Datacenters

multi

## Environment

Two single-node DCs, each with 1 database node on `i4i.xlarge` and 0 app nodes.

- **DC1** stands up the full local stack: Mimir (9009), Loki (3100), Tempo (OTLP 4320, query 3200), the Pyroscope server (4040), and Grafana (3000).
- Both DCs use the default observability tenant, `default`, so DC2's signals land in the tenant DC1 reads.  Every direct backend query sends it in `X-Scope-OrgID`; without it Mimir, Loki, Tempo and Pyroscope return nothing.
- **DC2** runs with `--redirect-telemetry <DC1 control private IP>`.  It stands up no local backends and no local Grafana.  Its collectors and profiling agents send to DC1.

## Prerequisites

```admonish warning
DC2's nodes must reach DC1's control node on ports 9009, 3100, 4320, and 4040.
The two DCs must share network reachability — the same VPC, a peered VPC, or a
common Tailscale network — and DC1's control node security group must allow
inbound from DC2's node CIDR on those four ports.  Without this reachability the
DC2 collectors fail to send and no signal reaches DC1.  Confirm reachability
before you start.  This is a deployment precondition, not part of the feature
under test.
```

## Steps

### 1. Provision DC1 with the full local stack

```bash
$EDB_DC1 init telemetry-redirect-dc1 --db 1 --instance i4i.xlarge --cidr 10.0.0.0/16 --up
```

### 2. Capture DC1's control node private IP

DC2 sends telemetry to this address.  Read it from DC1's `state.json`.

```bash
DC1_DIR=$(dirname "$EDB_DC1")
DC1_CONTROL_IP=$(jq -r '.hosts.Control[0].privateIp' "$DC1_DIR/state.json")
echo "DC1 control private IP: $DC1_CONTROL_IP"
```

### 3. Provision DC2 in redirect mode

Point DC2 at DC1's control node.  The tool derives the four signal endpoints from this host.

```bash
$EDB_DC2 init telemetry-redirect-dc2 --db 1 --instance i4i.xlarge --redirect-telemetry "$DC1_CONTROL_IP" --cidr 10.1.0.0/16 --up
```

### 4. Confirm DC2 stands up no local backends

DC2 must not run Grafana, Mimir, Loki, Tempo, or the Pyroscope server.  The collector and profiling DaemonSets do run — they send to DC1.

```bash
DC2_DIR=$(dirname "$EDB_DC2")
ssh -F "$DC2_DIR/sshConfig" control0 \
  'sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get pods -A'
```

Expected: no `grafana`, `mimir`, `loki`, `tempo`, or `pyroscope` (server Deployment) pods.  The `otel-collector`, `pyroscope-ebpf`, `beyla`, and `ebpf-exporter` DaemonSets are present.

### 5. Confirm the guarded commands refuse on DC2

Each local-stack command must refuse and name the reason.

```bash
$EDB_DC2 grafana update-config
$EDB_DC2 logs query
```

Expected: each command stops with a message that it is unavailable on a telemetry-redirect cluster.  No command touches a backend.

### 6. Start the database on both DCs

This generates all four signal types on each DC.  The database exports metrics and traces, writes logs, and runs under the profiling agents.

```bash
$EDB_DC1 cassandra start
$EDB_DC2 cassandra start
```

### 7. Generate load on DC2

Drive traffic on DC2 so its telemetry has volume to ship.  Let it run for a few minutes.

```bash
$EDB_DC2 cassandra nt status
$EDB_DC2 cassandra cql "SELECT release_version FROM system.local"
```

### 8. Verify DC2 metrics land in DC1

Query DC1's Mimir for the values of the `cluster` label.  DC2's name must appear.

```bash
ssh -F "$DC1_DIR/sshConfig" control0 \
  'curl -s -H "X-Scope-OrgID: default" http://localhost:9009/prometheus/api/v1/label/cluster/values'
```

Expected: the value list contains a value beginning `telemetry-redirect-dc2` (the label is `<name>-<clusterId>`).

### 9. Verify DC2 logs land in DC1

DC1's local stack holds the logs.  Query DC1's Loki, filtered to DC2's `cluster` label.  A raw `-q` query reads every cluster in the tenant, so it can select DC2's.

```bash
$EDB_DC1 logs query -q '{cluster=~"telemetry-redirect-dc2.*"}' --since 1h --limit 20
```

Expected: log lines from DC2's node.  An empty result means DC2's logs did not reach DC1.

### 10. Verify DC2 traces land in DC1

Search DC1's Tempo for traces tagged with DC2's cluster.

```bash
ssh -F "$DC1_DIR/sshConfig" control0 \
  'curl -sG -H "X-Scope-OrgID: default" "http://localhost:3200/api/search" --data-urlencode "q={resource.cluster=~\"telemetry-redirect-dc2.*\"}" --data-urlencode "limit=5"'
```

Expected: a non-empty `traces` array in the JSON response.

### 11. Verify DC2 profiles land in DC1

Query DC1's Pyroscope for the values of the `cluster` label.  DC2's name must appear.

```bash
ssh -F "$DC1_DIR/sshConfig" control0 \
  'curl -s -H "X-Scope-OrgID: default" "http://localhost:4040/pyroscope/label-values?label=cluster&from=now-1h"'
```

Expected: the response contains a value beginning `telemetry-redirect-dc2`.  If the API path differs on this Pyroscope version, open DC1's Grafana (port 3000) and confirm the `cluster` label carries `telemetry-redirect-dc2` in the Pyroscope datasource under Explore.

### 12. Confirm there is only one Grafana

DC1 runs the single Grafana.  DC2 runs none.  Re-confirm DC2 has no Grafana pod.

```bash
ssh -F "$DC2_DIR/sshConfig" control0 \
  'sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get pods -A -l app.kubernetes.io/name=grafana'
```

Expected: `No resources found`.

### 13. Tear down both DCs

```bash
$EDB_DC2 down --auto-approve
$EDB_DC1 down --auto-approve
```

## Notes

- Redirect mode is chosen once, at `init`.  You cannot switch a running cluster between local and redirect mode.  All four signals move together.
- The traces endpoint DC2 sends to is DC1's OTLP gRPC receiver on port 4320, not Tempo's query port 3200.  The verification in step 10 reads from the query port 3200, which is correct for search.
- Steps 8 through 11 read DC1's backends on the DC1 control node over SSH because there is no `metrics`, `traces` or `profiles` query command.  Step 9 uses `logs query` against DC1, which is allowed — DC1 is a local-stack cluster.
- If a signal is missing, first re-check the reachability precondition, then check the DC2 collector logs: `ssh -F "$DC2_DIR/sshConfig" control0 'sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl logs -l app.kubernetes.io/name=otel-collector -n default --tail=100'`.
- This plan is authored for `/easy-db-lab:run`.  Do not execute it by hand and do not scaffold the workspace — `run` creates the two DC workspaces and sets `$EDB_DC1` and `$EDB_DC2`.
