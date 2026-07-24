# cqlite-flight Production Readiness

End-to-end validation of the cqlite offline SSTable read path on a real AWS cluster:
`cqlite-flight` (Arrow Flight data plane, one pod per db node) → `cqlite-trino` (registers
the `cqlite` Trino catalog) → `trino-loadtest` (concurrent read-load driver). All three
kits are **built in** to easy-db-lab — this plan installs them directly with
`kit install`, with **no external kit-source registration** and no dependency on any
out-of-tree cqlite checkout.

The read path is **offline, read-only, flushed-SSTables-only, and eventually stale**: it
reads SSTable files on disk via Arrow Flight, never the live read/write path. Writes not
yet flushed from memtables are invisible; compaction and new flushes change the on-disk
file set over time, so results reflect the files present when a query ran — not a single
consistent cluster snapshot. Flush the target keyspace (`nodetool flush`) before
correctness or perf runs that need recent data.

## Cluster Configuration

| Node type | Count | Instance | Role |
|-----------|-------|----------|------|
| control   | 1     | (default) | K3s control plane, observability stack, Trino coordinator |
| db        | 3     | i4i.xlarge | Cassandra + one `cqlite-flight` pod each |
| app       | 1     | (default) | Trino workers |
| stress    | 1     | m5.xlarge | `cassandra-easy-stress` load generator |

RF=3 across the 3 db nodes gives a real fan-out and failover surface.

## Prerequisites

- AWS SSO logged in (`aws sso login --sso-session ehc-embark`; profile `edl`). Clusters run
  in **us-west-2** even though the profile default is us-west-1 — pass `--region us-west-2`
  on any raw `aws` CLI call.
- JDK 21 (the workspace wrapper sets `JAVA_HOME` automatically; a bare `java` may be a
  broken shim).
- Project built: `./gradlew installDist`.

---

## Durable setup guidance (read before running)

These are the hard-won, non-version-specific practices that keep a cqlite lab run stable.
They are inlined here so this plan stands alone.

### Tunnels with keepalive

Stale SOCKS proxies cause flaky `kubectl`/`helm`. Open both tunnels with aggressive
keepalive and leave them running for the whole session (replace `<sshConfig>` with the
generated `$CLUSTER_DIR/sshConfig`):

```bash
ssh -F "$CLUSTER_DIR/sshConfig" -N -o ServerAliveInterval=20 -o ServerAliveCountMax=1000 -D 1080 control0 &
ssh -F "$CLUSTER_DIR/sshConfig" -N -o ServerAliveInterval=30 -o ServerAliveCountMax=1000 -L 3000:localhost:3000 control0 &
```

Kit commands that shell out to `helm`/`kubectl` need the SOCKS env
(`ALL_PROXY`/`HTTPS_PROXY`/`HTTP_PROXY=socks5h://localhost:1080`); SSH-based `cassandra`
commands do not. Long `easy-db-lab` commands can hit a ~2-minute shell cap — background
them.

### Cassandra step ordering

Bring Cassandra up as discrete, verified steps — do **not** collapse them into one
backgrounded line (that has historically started only db0):

```bash
$EDB cassandra use 5.0
$EDB cassandra update-config
$EDB cassandra start
```

Then verify **3× `UN`** in `nodetool status` before proceeding. Flush the target keyspace
so the offline read path can see the data: `nodetool flush`.

### Image digest discipline

When pinning a `cqlite-flight` image `--tag`, pin the **INDEX** digest, never an
architecture child. arm64 children cause `exec format error` on amd64 (i4i) nodes. After
`cqlite-flight start`, verify each pod's `imageID` equals the pinned INDEX digest. Resolve
an index digest with `docker buildx imagetools inspect`.

---

## Steps

### 1. Set up cluster workspace

```bash
CLUSTER_DIR="clusters/cqlite-flight-prod-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
```

### 2. Initialize and bring up the cluster

```bash
$EDB init --name cqlite-flight-prod --db 3 --app 1 --instance i4i.xlarge --stress-instance m5.xlarge
./gradlew installDist
$EDB up
```

Expect ~8–10 min. Open the tunnels (see "Tunnels with keepalive" above) once `up`
completes.

### 3. Start Cassandra and load data

```bash
$EDB cassandra use 5.0
$EDB cassandra update-config
$EDB cassandra start
```

Verify 3× `UN`. Load a keyspace with `cassandra-easy-stress` (the stress node), then
**flush** so SSTables exist on disk:

```bash
$EDB cassandra stress start   # writes cassandra_easy_stress.keyvalue (RF=3)
# after the write phase, flush every db node so the offline path sees the data
```

### 4. Start Trino

Edit `trino/values.yaml` **before** the first `trino start` to set
`additionalConfigProperties: []` (removes the `web-ui.*` lines that crashloop workers),
then:

```bash
$EDB kit install trino
$EDB trino start
```

Wait for `kubectl rollout status deployment/trino-coordinator`.

### 5. Install and start cqlite-flight (built-in — no external kit source)

```bash
$EDB kit install cqlite-flight --flight-port 8815
$EDB cqlite-flight start
```

Confirm one Flight pod per db node (`DaemonSet`, `nodeSelector: type=db`) and that each
pod's `imageID` matches the pinned INDEX digest.

### 6. Register the cqlite catalog (built-in cqlite-trino overlay)

```bash
$EDB kit install cqlite-trino \
  --flight-port 8815 \
  --sidecar-uri "http://$($EDB ip db0 --private):9043" \
  --read-mode snapshot
$EDB cqlite start
```

If `SHOW CATALOGS` lacks `cqlite`, wait for the coordinator rollout to finish and re-check
(self-heals). Confirm `cqlite` appears **in addition to** the existing `cassandra`
catalog.

### 7. Correctness read

```bash
$EDB trino sql "SHOW CATALOGS"
$EDB trino sql "SELECT * FROM cqlite.cassandra_easy_stress.keyvalue LIMIT 5"
$EDB trino sql "SELECT count(*) FROM cqlite.cassandra_easy_stress.keyvalue"
```

`count(*)` is a full-ring scan — bound your expectations on run time. Verify rows come back
and fan out across all 3 Flight pods.

### 8. Throughput / latency (built-in trino-loadtest)

```bash
$EDB kit install trino-loadtest --target trino
$EDB trino-loadtest-trino start \
  --ks cassandra_easy_stress --tbl keyvalue \
  --threads 8 --duration 180 --interval 10
```

Per-interval stats (qps, rows/s, p50/p99, errors/s) stream to the terminal and push to
VictoriaMetrics. Measure with discipline: never report latency from a single query — take
≥10 samples per query shape, split warm vs cold, use multiple distinct keys, and run in
isolation (no competing `count(*)`).

### 9. Snapshot hygiene (D12)

The snapshot-mode read path takes a transient per-query Cassandra snapshot and must clean
it up. After the loadtest, assert zero `cqlite-` snapshots remain on every db node — pass
the raw `nodetool listsnapshots` output through the driver's `--snapshot-check-cmd` (the
driver does the `cqlite-` matching itself; do not pipe through `grep`).

### 10. Tear down

```bash
$EDB trino-loadtest-trino stop
$EDB cqlite stop
$EDB cqlite-flight stop
$EDB trino stop
$EDB cassandra stop
$EDB down --auto-approve
```

If teardown orphans the VPC (SSO token can expire mid-`down`), re-login and re-run
`$EDB down --auto-approve` (idempotent), then verify AWS is clean.

---

## Validation Checklist

- [ ] All three cqlite kits install with **no external kit-source registration** (built-in discovery)
- [ ] `cqlite-flight start` runs one Flight pod per db node with the data dir mounted
- [ ] `cqlite start` registers the `cqlite` catalog additively — `cassandra` catalog remains
- [ ] `SELECT * FROM cqlite.<ks>.<tbl> LIMIT 5` returns rows read from SSTables
- [ ] `count(*)` fans out across all 3 Flight pods and returns a plausible ring total
- [ ] `trino-loadtest-trino start` reports throughput/latency and pushes metrics
- [ ] D12: zero `cqlite-` snapshots remain on every db node after the run
- [ ] `cqlite stop` deregisters the catalog; `cassandra` catalog still present
- [ ] `down --auto-approve` terminates all EC2 instances; AWS clean

## Notes

- The read path only sees **flushed** SSTables — flush before every correctness/perf run
  that needs recent writes.
- `count(*)` on `cassandra_easy_stress.keyvalue` is a slow full scan — bound it.
- To exercise #2241 failover, keep one Flight pod down (label the other db nodes and patch
  the DaemonSet `nodeSelector` to exclude the victim, keeping the `type=db` key so the
  selector still matches), then re-run the full-ring `count(*)` and confirm identical rows
  with 0 errors.
