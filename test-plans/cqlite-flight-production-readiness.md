# cqlite-flight — Production-Readiness Test Plan

Comprehensive, **selectable** scenario catalog for validating `cqlite-flight` + the
`trino-cqlite` connector against a live Cassandra cluster on real AWS. Covers 10
production-readiness dimensions. Every scenario is independently runnable and tagged
`[G]` (gate — fast per-round GO/NO-GO) or `[E]` (extended — fuller audit).

> Coordination: this plan supersedes the ad-hoc round checklists. Per-round results are
> posted to the active cqlite tracker issue (currently **pmcfadin/cqlite#2367**) and saved
> to `clusters/RUN-FINDINGS-<round>.md`. easy-db-lab-side defects are **comment-only
> upstream** on `rustyrazorblade/easy-db-lab` (never fixed in the cqlite tree).

---

## How to run a subset

Tell the agent in plain language what to run; it maps to sections and executes against **one
shared prelude cluster** (the prelude runs once — selecting 3 scenarios does not mean 3
cluster builds):

- `run the gate` → the prelude (P0) + all `[G]` scenarios + GO/NO-GO verdict.
- `run gate + dimension:cassandra-dynamics` → gate plus all D10 scenarios.
- `run scenario 4.2` → prelude + that one scenario.
- `run full audit` → prelude + all scenarios (`[G]` and `[E]`).
- `run dimension:resilience` → prelude + all D5 scenarios.

**Ordering rule:** scenarios that mutate cluster state (`needs: live-mode`, `fresh-cluster`,
or any D10 scenario) run **last** in a session, or on a fresh prelude, so they don't corrupt
the baseline other scenarios rely on. The agent orders selected scenarios accordingly.

---

## Pinned assets (fill per round — do not substitute)

| Asset | Value |
|---|---|
| cqlite-flight image | `ghcr.io/pmcfadin/cqlite-flight:<round>@sha256:<INDEX-digest>` (multi-arch INDEX digest, never a child manifest) |
| Trino connector | `in.mcfad:cqlite-trino:<version>` (verify live on Maven Central) |
| Trino | image tag `481` (SPI pin) |
| Cluster shape | 3× `i4i.xlarge` db + 1× `m5.xlarge` app (stress/loadtest), Cassandra 5.0.x |

**Digest discipline:** resolve the multi-arch **INDEX** digest, never an arch child
(`sha256:289f97e0…`-style arm64 children cause `exec format error` on amd64 i4i nodes).
Verify with `docker buildx imagetools inspect` or a GHCR index-accept `curl`.

---

## Environment prerequisites (verified findings, not guesses)

- **AWS SSO:** `aws sso login --sso-session ehc-embark` (profile `edl`, acct 417835013894).
  Clusters run in **`us-west-2`** though the profile default is `us-west-1` — pass
  `--region us-west-2` on raw `aws` CLI calls. The token often **expires mid-`down`**; if
  teardown orphans the VPC, re-login then clean up directly with the AWS CLI (the tool
  caches the stale token). See the Teardown section.
- **JDK 21:** `export JAVA_HOME=/Users/pmcfadin/.sdkman/candidates/java/21.0.11-tem`
  (bare `java` is a broken Salesforce shim). The workspace wrapper sets this automatically.
- **Local jar:** build once with `./gradlew installDist` on branch `fix/735-jdbc-socks-proxy`
  so the SOCKS-JDBC (#735) and Grafana core-plugin fixes are baked in — otherwise the
  per-run `JAVA_TOOL_OPTIONS` SOCKS export and Grafana hotfix are needed.
- **SOCKS for kit commands:** `export ALL_PROXY=socks5h://localhost:1080
  HTTPS_PROXY=socks5h://localhost:1080 HTTP_PROXY=socks5h://localhost:1080` for all
  `helm`/`kubectl` and raw `kubectl`. SSH-based `cassandra` commands don't need it.
- **Shell cap:** long `easy-db-lab`/`kubectl rollout` commands exceed a 2-min shell cap —
  background them.
- **`count(*)` full-ring** on the big table is slow (super-linear cold parse, #2385) — use
  bounded `LIMIT` for multi-node checks unless the scenario explicitly needs the full ring.

---

## P0 — Prelude (shared, one-time bring-up)

Every scenario with `needs: none` assumes the prelude has completed and left the cluster in
the **baseline state** defined at the end of this section.

### P0.1 Workspace + provision (~8–10 min)

```bash
CLUSTER_DIR="clusters/cqlite-flight-<round>-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
export JAVA_HOME=/Users/pmcfadin/.sdkman/candidates/java/21.0.11-tem AWS_PROFILE=edl
"$EDB" init cqlite-flight-<round> --db 3 --app 1 --instance i4i.xlarge --stress-instance m5.xlarge --up
```

### P0.2 Tunnels + verify

SOCKS (`-D 1080`) is started by `up`; add the Grafana tunnel and export proxy vars:

```bash
SSH="$CLUSTER_DIR/sshConfig"
ssh -F "$SSH" -N -o ServerAliveInterval=30 -o ServerAliveCountMax=1000 -o ExitOnForwardFailure=yes -L 3000:localhost:3000 control0 &
export KUBECONFIG="$CLUSTER_DIR/kubeconfig" ALL_PROXY=socks5h://localhost:1080 HTTPS_PROXY=socks5h://localhost:1080 HTTP_PROXY=socks5h://localhost:1080
kubectl get nodes                       # all 5 Ready
kubectl get pods | grep grafana         # Running 2/2, 0 restarts (validates core-plugin fix)
```

### P0.3 Cassandra 5.0 (3× UN)

```bash
"$EDB" cassandra use 5.0
"$EDB" cassandra update-config
"$EDB" cassandra start                   # run as its OWN step; may need a second start
"$EDB" cassandra nt status | grep -E '^UN' | awk '{print $2}' | sort -u   # 3 unique IPs
```

### P0.4 Write load + discriminator tables

```bash
KS=cassandra_easy_stress
"$EDB" cassandra stress start --name cqlite-write-baseline --tags "phase=write" -- KeyValue -d 12m --threads 50
sleep 25
"$EDB" cassandra cql "CREATE TABLE IF NOT EXISTS ${KS}.tiny (key text PRIMARY KEY, value text)"
for v in 1 2 3; do "$EDB" cassandra cql "INSERT INTO ${KS}.tiny (key,value) VALUES ('$v','$v')"; done
"$EDB" cassandra cql "CREATE TABLE IF NOT EXISTS ${KS}.mixed (key text PRIMARY KEY, label text, t time)"
"$EDB" cassandra cql "INSERT INTO ${KS}.mixed (key,label,t) VALUES ('a','alpha','08:30:00')"
"$EDB" cassandra cql "INSERT INTO ${KS}.mixed (key,label,t) VALUES ('b','bravo','14:45:15')"
"$EDB" cassandra nt flush "$KS"
# let the write build volume, then stop + final flush
"$EDB" cassandra stress stop cqlite-write-baseline --force
"$EDB" cassandra nt flush "$KS"
"$EDB" cassandra nt tablestats "${KS}.keyvalue" | grep -iE 'SSTable count|Number of partitions'
```

### P0.5 Trino 481 (+ #2116 web-ui workaround)

```bash
"$EDB" kit install trino --version 481
# #2116: replace the two web-ui.* lines under additionalConfigProperties with an empty list
perl -0pi -e 's/additionalConfigProperties:\n(  - .*\n)+/additionalConfigProperties: []\n/' "$CLUSTER_DIR/trino/values.yaml"
"$EDB" trino start                        # both coordinator + worker must reach 1/1 Ready
```

### P0.6 cqlite-flight (digest-pinned) + overlay + add-opens

```bash
"$EDB" kit source add cqlite /Users/pmcfadin/projects/cqlite/easy-db-lab-kits
"$EDB" kit install cqlite-flight --tag "dev@sha256:<INDEX-digest>" --flight-port 8815
"$EDB" cqlite-flight start
# Verify one pod per db node AND every imageID ends in <INDEX-digest> (see 8.1)

DB0_IP=$("$EDB" ip db0 --private | tail -1)
"$EDB" kit install trino-cqlite --connector-version <version> --flight-port 8815 \
  --sidecar-uri "http://${DB0_IP}:9043" --trino-image-tag 481 --read-mode snapshot
"$EDB" cqlite start
# Wait for coordinator rollout to settle; SHOW CATALOGS must list cqlite (self-heals).

# add-opens (kit lacks #2290): patch BOTH configmaps AFTER cqlite start settles, then restart.
FLAG='--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED'
for cm in trino-coordinator trino-worker; do
  cur=$(kubectl get cm "$cm" -o jsonpath='{.data.jvm\.config}')
  case "$cur" in *"$FLAG"*) : ;; *) kubectl patch cm "$cm" --type merge -p "$(jq -n --arg v "${cur}"$'\n'"${FLAG}" '{data:{"jvm.config":$v}}')";; esac
done
kubectl rollout restart deployment/trino-coordinator deployment/trino-worker
# Confirm: flag in running pod's /etc/trino/jvm.config AND cqlite catalog still present.
```

> When the kit ships #2290 (`--add-opens` baked in) drop the manual patch — verify the flag
> is present without it.

### Baseline state left by the prelude (the prelude contract)

- 3× UN Cassandra, RF=3 (`SimpleStrategy`, native default for `cassandra_easy_stress`)
- `cassandra_easy_stress.keyvalue`: ~1M+ partitions/node, ≥2 SSTables, complete components
- `cassandra_easy_stress.tiny`: exactly 3 rows; `cassandra_easy_stress.mixed`: 2 rows w/ TIME
- flight DaemonSet: one Running pod per db node, all on the pinned INDEX digest
- Trino 481, `cqlite` catalog registered, `--add-opens` live, **snapshot** read mode
- no `cqlite-*` snapshots lingering

---

## Metrics appendix (referenced by scenarios)

Standard set captured/reported every round (grouped by what they protect). Scenarios cite
these by letter.

**A. Correctness (gate)**
- A1 per-scenario **completes? + rows correct?** (both snapshot + live where noted)
- A2 **row-count parity**: Trino count vs Cassandra `nt tablestats` partition estimate
- A3 **access path**: `cqlite_read_partition_lookup_total{route,result}` + `cqlite_query_rows_scanned_total` — PK-equality must be a bounded lookup, never full scan

**B. Hang / liveness**
- B1 **query wall time**, cold vs warm, per shape
- B2 **`cqlite_sstable_index_parses_total` delta per query** — MUST be ≤ #generations and flat on warm (direct #2383 re-parse-storm regression guard)
- B3 **`cqlite_rpc_phase_active_ratio{phase}`** during a scan — which phase stands, and that it clears
- B4 **cancellation reclaim time**: seconds from kill → pod CPU & memory back to baseline, WITHOUT restart
- B5 **flight pod CPU + memory trajectory** during a scan — flat vs unbounded growth

**C. Throughput / scale**
- C1 **qps, rows/s, p50/p99 latency, error rate** under a fixed loadtest (same query set each round)
- C2 **cross-node work distribution**: CPU across all flight pods under load — all N participating vs single-node-bound
- C3 **cold-parse cost**: first-query-per-table wall time + `index_parses` (#2385 baseline)

**D. Hygiene**
- D1 **snapshot cleanup**: `nt listsnapshots | grep cqlite-` == 0 on ALL nodes after queries
- D2 **`cqlite_errors_total`** by category (should stay 0; note lazy registration)
- D3 **digest pin verified**: every flight pod's imageID == the round's INDEX digest

> **Latency reporting note:** `easy-db-lab trino sql` is prod-representative for the query
> path (JDBC → coordinator → connector → Flight → core) but spins a fresh JVM/JDBC connection
> per invocation (~2s floor, measurable via `SELECT 1`). Report warm latency **minus that
> floor** for a prod-ish figure, or measure via the loadtest driver's persistent connection.
> A JDBC-based concurrent loadtest (vs the current Python-client driver) would be the fully
> prod-representative throughput path — noted as a future improvement.

---

## Scenario catalog

Format per scenario: **tags** (`[G]`/`[E]`, `dimension:`, `needs:`), **preconditions**,
**steps**, **pass/fail bar**, **on-fail capture**. `$EDB`, `$KS`, `$CLUSTER_DIR` as set in
the prelude. All read scenarios run in **snapshot** mode unless they say `needs: live-mode`.

### D1 — Correctness / data integrity

#### 1.1 [G] Row-count parity — no silent 0/short
- **dimension:** d1 · **needs:** none
- **Steps:** `$EDB trino sql "SELECT count(*) FROM cqlite.$KS.keyvalue"` (or a bounded
  `SELECT count(*) FROM (SELECT * FROM ... LIMIT N)` if the full ring is impractical — note
  which). Compare to `nt tablestats` partition estimate × nodes.
- **Pass:** Trino count matches Cassandra within expected bounds; non-zero; no short count.
- **On fail:** the query, both counts, the ticket's token range, flight logs on the split's host. (A1, A2)

#### 1.2 [G] tiny decode + complete SELECT *
- **dimension:** d1 · **needs:** none
- **Steps:** `$EDB trino sql "SELECT * FROM cqlite.$KS.tiny ORDER BY key"`;
  `$EDB trino sql "SELECT * FROM cqlite.$KS.keyvalue LIMIT 1000"`.
- **Pass:** tiny returns exactly 3 rows decoded; LIMIT 1000 returns 1000 rows (no silent 0).
- **On fail:** server ERROR line (`RUST_LOG=info`), coordinator arrow-java version, client stack trace. (A1)

#### 1.3 [G] Type mapping (TIME + common types)
- **dimension:** d1 · **needs:** none
- **Steps:** `$EDB trino sql "DESCRIBE cqlite.$KS.mixed"`;
  `$EDB trino sql "SELECT * FROM cqlite.$KS.mixed ORDER BY key"`.
- **Pass:** `t` maps to `time(9)` with correct values; `key`/`label` return; table not poisoned.
- **On fail:** `DESCRIBE` output, coordinator error, which column, whether TIME mapped. (A1)

#### 1.4 [E] Wide-row / large-partition fidelity
- **dimension:** d1 · **needs:** none (create a clustered wide-row table in prep)
- **Steps:** create a table with a partition holding many clustering rows; write, flush;
  read the full partition + a clustering-range slice; compare to a direct `cqlite cql` read.
- **Pass:** all clustering rows returned in order; range slice bounds honored; values exact.
- **On fail:** row counts (Trino vs CQL), the slice predicate, flight logs. (A1)

### D2 — Read-path liveness

#### 2.1 [G] LIMIT completes (cold + warm) — the hang guard
- **dimension:** d2 · **needs:** none
- **Steps:** cold `time $EDB trino sql "SELECT * FROM cqlite.$KS.keyvalue LIMIT 5"`; then a
  second (warm) run. Watch `cqlite_rpc_phase_active_ratio{cqlite_rpc_phase="resolve"}`.
- **Pass:** both complete with 5 correct rows; cold is a bounded parse (≈ minutes at ~1M
  partitions per #2385), warm is fast (seconds); resolve ratio clears. **NOT a hang.**
- **On fail:** the R7/R8 signature — capture flight pod CPU/mem, `perf` sample, thread
  states, `RUST_LOG=cqlite_core=debug` log showing what resolve iterates, phase metrics. (B1,B3,B5)

#### 2.2 [G] index_parses flat on warm — re-parse-storm guard
- **dimension:** d2 · **needs:** none (run after 2.1)
- **Steps:** capture `cqlite_sstable_index_parses_total` before and after a warm LIMIT/point-read.
- **Pass:** delta ≤ #generations; stays flat across repeated warm queries (no storm).
- **On fail:** per-query parse count, `RUST_LOG=cqlite_core=debug` `load_index` lines. (B2)

#### 2.3 [G] Cancellation reclaims without restart
- **dimension:** d2 · **needs:** none
- **Steps:** launch a full-ring `count(*)`; while `phase_active_ratio{resolve}`>0, kill via
  `CALL system.runtime.kill_query(...)`. Watch busiest flight pod CPU + memory.
- **Pass:** within a bounded window (report the seconds), CPU→~baseline and memory drops,
  **without** a DaemonSet restart. (R8 failed this — stayed pinned 3+ min.)
- **On fail:** CPU/mem trajectory post-kill, whether only a restart reclaims. (B4)

#### 2.4 [E] count(*) full-ring completes (bounded)
- **dimension:** d2 · **needs:** none
- **Steps:** `time $EDB trino sql "SELECT count(*) FROM cqlite.$KS.keyvalue"`; sample
  `cqlite_rpc_rows_total` to confirm progress.
- **Pass:** completes with the correct count; progresses (rows advance), cancellable; slow is
  acceptable (#2385/#2366) — record the wall time as the baseline.
- **On fail:** rows frozen + no progress = hang → the 2.1 capture bundle. (B1,C3)

### D3 — Latency

#### 3.1 [G] Warm latency (point-read + LIMIT), floor-adjusted
- **dimension:** d3 · **needs:** none
- **Steps:** `time $EDB trino sql "SELECT 1"` (floor); ≥5 warm point-reads on distinct real
  keys; ≥4 warm `LIMIT 5` and one `LIMIT 100`.
- **Pass:** report distribution **minus the SELECT-1 floor**. Warm point-read ≈ floor (read
  cost ~0); flag any large fixed per-query overhead on LIMIT (was ~4–5s in R9) as a finding.
- **On fail / regression:** n/a (measurement) — record numbers vs the previous round. (B1)

#### 3.2 [E] Cold vs warm split per shape
- **dimension:** d3 · **needs:** none
- **Steps:** for point-read, LIMIT, count — measure first (cold) then warmed.
- **Pass:** cold reflects the bounded #2385 parse; warm is fast. Record both. (B1,C3)

### D4 — Throughput / scale

#### 4.1 [G] Concurrent throughput
- **dimension:** d4 · **needs:** none
- **Steps:** `$EDB kit install trino-loadtest --target trino`; run with a fixed **warm** query
  set (point-read + LIMIT 5/100 + tiny — **exclude full count(*)**, it dominates), e.g.
  `--threads 8 --duration 180 --interval 30 --queries-file <abs-path>`.
- **Pass:** report qps, rows/s, p50/p99, error rate. Bar = **0 errors** and stable qps
  (no collapse). Same query set every round for comparability.
- **On fail:** driver interval log, flight pod errors, any query state stuck RUNNING. (C1)

#### 4.2 [G] Cross-node fan-out
- **dimension:** d4 · **needs:** none (observe during 4.1)
- **Steps:** during the 4.1 loadtest, `kubectl top pod -l easydblab.com/kit=cqlite-flight`.
- **Pass:** all 3 flight pods do work. **Single-node-bound (one busy, others idle) = a
  finding** (R9 hit this) — report it; not necessarily a NO-GO but flag prominently.
- **On fail/finding:** per-pod CPU samples through the run. (C2)

#### 4.3 [E] Scale with nodes
- **dimension:** d4 · **needs:** node-headroom
- **Steps:** baseline throughput at 3 db nodes; add a node (see 10.2); re-run 4.1.
- **Pass:** throughput rises (or is explained). Documents scale-out behavior. (C1,C2)

### D5 — Resilience / failure modes

#### 5.1 [G] Killed flight pod → failover (#2241)
- **dimension:** d5 · **needs:** none
- **Steps:** kill exactly one flight pod (or exclude a node via DaemonSet nodeSelector so it
  isn't recreated instantly), then run a full-ring query.
- **Pass:** query succeeds via failover to another replica; loud failure only if all replicas down.
- **On fail:** coordinator error naming the dead endpoint, Ready-state of other pods, keyspace RF. (A1)

#### 5.2 [E] Sidecar unavailable → clean failure
- **dimension:** d5 · **needs:** none
- **Steps:** make the Sidecar endpoint unreachable (stop it / block the port); run a snapshot-mode query.
- **Pass:** clear, prompt error (not a hang); recovers when Sidecar returns.
- **On fail:** error text/timeout behavior, flight logs, whether it hung. (B1)

#### 5.3 [E] Compaction race during live-mode scan
- **dimension:** d5 · **needs:** live-mode
- **Steps:** reinstall overlay `--read-mode live`; start a read loadtest; concurrently drive
  write churn + forced `nt flush`/compaction.
- **Pass:** no errors/resets; results stay correct; no hang.
- **On fail:** flight logs (`--since=30m`), `cqlite_errors_total` by category, Tempo trace, ticket JSON. (A1,D2)

### D6 — Resource behavior

#### 6.1 [G] Memory bounded under load
- **dimension:** d6 · **needs:** none (observe during 4.1)
- **Steps:** sample `kubectl top pod` for flight pods across the 4.1 loadtest.
- **Pass:** memory plateaus; no unbounded growth, no OOM/restart. (R7 leaked to 5.5GB — guard.)
- **On fail:** memory trajectory samples, restart counts, Pyroscope alloc flame graph. (B5)

#### 6.2 [E] Disk-access mode behavior
- **dimension:** d6 · **needs:** none
- **Steps:** inspect the resolved `DiskAccessMode` (auto/buffered/mmap/direct) for the served
  SSTables given their size vs `direct_io_memory_fraction`; optionally pin each mode via config
  and confirm reads still correct.
- **Pass:** mode matches the documented heuristic (small→buffered, mid→mmap, >fraction→direct);
  reads correct in every pinned mode.
- **On fail:** config values, file sizes, observed mode, any read error per mode. (A1)

### D7 — Observability

#### 7.1 [G] Metrics present & moving
- **dimension:** d7 · **needs:** none
- **Steps:** `curl` VictoriaMetrics for `cqlite_rpc_*`, `cqlite_rpc_phase_active_ratio`,
  `cqlite_sstable_index_parses_total`, `cqlite_errors_total`.
- **Pass:** the `cqlite_rpc_*` family present; rows/bytes climb during a scan; phase ratios
  move; `errors_total` sane (0 or lazily absent). (B2,B3,D2)
- **On fail:** which metric absent/stuck, the OTel collector config/logs.

#### 7.2 [E] Traces + dashboards
- **dimension:** d7 · **needs:** none
- **Steps:** run reads with `--traceparent`; check Tempo for a client→flight→core span; open
  the CQLite Flight RPC dashboard in Grafana.
- **Pass:** trace spans the full path, `span.rpc.method` filterable; dashboard panels populate.
- **On fail:** missing span layer, empty panels + their queries.

### D8 — Operability

#### 8.1 [G] Digest pin + catalog + add-opens
- **dimension:** d8 · **needs:** none
- **Steps:** `kubectl get pods -l easydblab.com/kit=cqlite-flight -o jsonpath=...imageID`;
  `SHOW CATALOGS`; `grep add-opens /etc/trino/jvm.config` in the running coordinator.
- **Pass:** every flight pod imageID == the round INDEX digest; `cqlite` catalog present;
  add-opens flag live. **Any pod on a non-pinned digest voids all verdicts.**
- **On fail:** the offending imageID, catalog list, jvm.config contents. (D3)

#### 8.2 [E] Deploy / upgrade / rollback + fail-fast
- **dimension:** d8 · **needs:** none
- **Steps:** reinstall the overlay at a different `--read-mode`; confirm clean rollout;
  (if testing #2290) remove add-opens and confirm the connector fails fast at plugin init
  with an actionable message rather than a cryptic per-query error.
- **Pass:** rollouts converge; fail-fast message names the exact missing flag + remedy.
- **On fail:** rollout state, the actual error message shape.

### D9 — Data lifecycle

#### 9.1 [G] Snapshot cleanup on all nodes
- **dimension:** d9 · **needs:** none (run after read scenarios)
- **Steps:** after queries stop, `$EDB cassandra nt listsnapshots | grep -c cqlite-`.
- **Pass:** 0 `cqlite-*` snapshots on **all 3** nodes (Trino's cleanupQuery hook fired everywhere).
- **On fail:** which node(s) leaked, snapshot names/ages, per-node listsnapshots. (D1)

#### 9.2 [E] Live-mode compaction safety
- **dimension:** d9 · **needs:** live-mode
- **Steps:** in live mode, read a table while forcing compaction that deletes/merges its SSTables.
- **Pass:** no stale or resurrected data; reads reflect the post-compaction truth.
- **On fail:** row diffs, SSTable file set at mismatch time, snapshot name, the query. (A1)

### D10 — Cassandra cluster dynamics

> The distinctive dimension: cqlite-flight reads SSTables from under a **live, mutating**
> Cassandra cluster. These scenarios mutate cluster state — run them **last** or on a fresh
> prelude. Each verifies reads stay correct through the operation.

#### 10.1 [G] Deletes / tombstones
- **dimension:** d10 · **needs:** none (mutates data — run after correctness scenarios)
- **Steps:** on a known key set: row delete, partition delete, a range delete (clustered
  table), and a short-TTL insert allowed to expire; `nt flush`. Read each back through Trino;
  compare to a direct `cqlite cql` read.
- **Pass:** deleted rows/partitions **absent** from reads; TTL-expired data gone; **no
  resurrected data**; read-time reconciliation matches Cassandra's own view.
- **On fail:** the delete, Trino vs CQL result, tombstone state (`sstabledump`/`nt`),
  whether purgeable tombstones were mishandled. (A1)

#### 10.2 [E] Add node (bootstrap)
- **dimension:** d10 · **needs:** node-headroom
- **Steps:** capture baseline full-ring count; bootstrap a new db node (token ranges
  redistribute, data streams); after `UN`, re-run the full-ring read and check snapshot
  fan-out (#2227) now includes the new node.
- **Pass:** reads complete + correct **during and after** bootstrap; snapshots fan to the new
  node; count matches (accounting for any concurrent writes).
- **On fail:** count before/during/after, `nt status` transitions, which nodes hold snapshots,
  flight logs on the new node. (A1,A2)

#### 10.3 [E] Remove / replace node
- **dimension:** d10 · **needs:** node-headroom
- **Steps:** `nodetool decommission` one node (and/or `removenode`, and/or host replacement);
  after topology settles, re-run the full-ring read.
- **Pass:** reads correct + complete after the topology change; no missing/duplicated data.
- **On fail:** `nt status`, count vs baseline, snapshot distribution, flight logs. (A1,A2)

#### 10.4 [E] Repair
- **dimension:** d10 · **needs:** none
- **Steps:** `nodetool repair` on the stress keyspace (streams data, rewrites SSTables);
  read the table back.
- **Pass:** reads correct after repair; no stale/duplicated rows from the streamed SSTables.
- **On fail:** count vs baseline, SSTable generations before/after, flight logs. (A1)

---

## Teardown (every run)

```bash
"$EDB" cassandra stress stop --all --force
"$EDB" cqlite uninstall; "$EDB" cqlite-flight stop; "$EDB" trino stop; "$EDB" cassandra stop
"$EDB" down --auto-approve
```

**Verify AWS clean** (profile default is us-west-1, clusters are us-west-2):

```bash
aws ec2 describe-instances --region us-west-2 --profile edl \
  --filters "Name=tag:Name,Values=*cqlite-flight-<round>*" \
  "Name=instance-state-name,Values=running,pending,stopping,stopped" \
  --query 'length(Reservations[].Instances[])' --output text     # expect 0
aws ec2 describe-vpcs --region us-west-2 --profile edl \
  --filters "Name=tag:Name,Values=cqlite-flight-<round>" --query 'length(Vpcs)' --output text   # expect 0
```

**Token-expiry fallback (known gotcha):** if `down` fails mid-teardown with "Token is
expired" and orphans the VPC, re-login (`aws sso login --sso-session ehc-embark`), then clean
up **directly with the AWS CLI** (the easy-db-lab JVM caches the stale token so re-`down`
keeps failing): `terminate-instances` → `wait instance-terminated` (releases ENIs) →
detach+delete IGW → delete subnets → delete non-default SG → `delete-vpc`. Always
`--region us-west-2`.

---

## Output per run

1. **Verdict table** for the selected scenarios (id, verdict, evidence/wall-time).
2. **Metrics captured** (the cited A/B/C/D metrics).
3. **GO/NO-GO** — emitted only when the gate subset ran; **NO-GO if any `[G]` fails**.
4. **Failure capture bundles** attached for any fail.
5. Posted to the round tracker issue (currently #2367) + saved to `clusters/RUN-FINDINGS-<round>.md`.
