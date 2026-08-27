# Lab Plan: Runtime-controlled async-profiler (#880)

## Objective

Validate that issue #880's runtime-controlled profiling works end to end on a real Cassandra 5.0
cluster: every verb of `cassandra profile` behaves as specified, profiles reach Pyroscope and are
independently selectable, and `jfrconv` output is readable with thread-pool attribution.

Success is: Cassandra starts with no profiler in its JVM options; the cluster is profiling
`-e cpu --alloc 512k --lock 10ms` with no operator action, so **all four** profiling dashboard panels
populate from cluster-up; the CPU profile contains **real symbolised data** rather than merely files
on disk; the reconciler converges rather than thrashing; a downloaded flame graph shows Cassandra
thread-pool names; retention pruning fires without destroying unshipped data; profiling resumes on
its own after a Cassandra restart; and the three failure modes the review panel found — a stopped
database, a dead reconciler timer, and an unreadable config — are each visible and correctly
attributed.

Those three were open findings when this plan was first written. They are now **fixed**, so the
steps that probe them verify the fix rather than confirm the defect; their expected outcomes are
inverted from the original draft.

## Cluster Name

profiling-880

## Datacenters

single

## Environment

- 3 db nodes, `m6id.xlarge` — 4 vCPU, 16 GiB, local NVMe, Intel Ice Lake. No EBS needed; Cassandra's
  data directory and the profile directory share the instance store, which is deliberate — the
  byte-ceiling bound exists because that is one volume.
- 1 app node, `m6id.xlarge`, running `cassandra-easy-stress` on k3s.
- Cassandra 5.0, which selects **Java 11** on these AMIs.
- Observability: Pyroscope (4040), VictoriaMetrics (8428), VictoriaLogs (9428), Grafana (3000) on the
  control node.
- `AWS_PROFILE=sandbox-admin`.
- **Both AMIs must be rebuilt from this branch.** Base adds `jq` to its package list; the Cassandra
  image carries the reconciler script, its systemd units, and the modified `cassandra.in.sh`. Base
  also supplies `yq` (mikefarah v4) and async-profiler 4.5.

All observability URLs use the control node's **private** IP (Tailscale); public IPs will not work.

## Steps

### 1. Provision the cluster

```bash
$EDB init --db.count 3 --app.count 1 \
  --db.instance-type m6id.xlarge --app.instance-type m6id.xlarge \
  --up profiling-880
```

Capture what later steps need. `$EDB` is the workspace wrapper, so its directory is the workspace:

```bash
CLUSTER_DIR=$(dirname "$EDB")
CONTROL_IP=$($EDB ip --private control0)
SSH="ssh -F $CLUSTER_DIR/sshConfig"
VM="http://$CONTROL_IP:8428/api/v1/query"
echo "control=$CONTROL_IP"
```

The Pyroscope `cluster` label is **not** `profiling-880` — it is `<name>-<clusterId>`. Read it from
the node's own profiling config, which `init --up` seeds as part of cluster setup:

```bash
CLUSTER_LABEL=$($SSH db0 "yq -p=json -r '.clusterName' /etc/easy-db-lab/profiling.json")
echo "cluster_label=$CLUSTER_LABEL"
```

Do **not** read the workspace's `state.json` for this or anything else — it is the tool's internal
state and is not a supported interface.

The wrapper `cd`s into the workspace before exec'ing the CLI, so `profiles/` from `fetch` and
`flamegraph` lands under `$CLUSTER_DIR`.

### 2. Select Cassandra 5.0 and start it

Node starts are spaced ~120s apart and wait for UP/NORMAL, so this takes roughly four minutes.

```bash
$EDB cassandra use 5.0
$EDB cassandra start
$EDB cassandra nt status
```

**Check:** all three nodes `UN`.

### 3. Confirm the AMI is right, the agent is gone, and the prerequisites hold

First acceptance check, not last: moving capture out of JVM startup removes a whole failure class,
so a clean start is what proves the removal was correct.

```bash
for h in db0 db1 db2; do
  echo "=== $h"
  $SSH $h "grep -ci pyroscope /usr/local/cassandra/current/bin/cassandra.in.sh || true"
  $SSH $h "pgrep -af CassandraDaemon | tr ' ' '\n' | grep -E 'javaagent|agentpath' || echo 'NO PROFILER AGENT IN JVM OPTS'"
  $SSH $h "sysctl kernel.perf_event_paranoid kernel.kptr_restrict; getcap \$(readlink -f \$(which java))"
  $SSH $h "/usr/local/async-profiler/bin/asprof --version && ls /usr/local/async-profiler/lib/libasyncProfiler.so"
done
```

**Expect:** `0` pyroscope references (proves the new AMI); `NO PROFILER AGENT IN JVM OPTS`;
`perf_event_paranoid = 1`, `kptr_restrict = 0`, and `cap_perfmon,cap_sys_ptrace,cap_syslog=ep`. The
sysctls are set at `up` with plain `sysctl` and no `/etc/sysctl.d` drop-in, so they hold on a running
cluster but do not survive a reboot — verify rather than assume.

**`asprof` must report 4.5**, and `libasyncProfiler.so` must exist. The installer builds its download
URL from a version string, and a 404 there leaves the directory absent or stale while the AMI still
builds successfully — so this is the step that catches a bad version bump, not the profiling steps
further down, which would fail confusingly instead.

### 4. Confirm profiling auto-started at cluster-up

No profiling command has been run yet.

```bash
$EDB cassandra profile status
$SSH db0 "systemctl list-timers edl-profiling-reconcile.timer --no-pager; systemctl show -p NRestarts,Result edl-profiling-reconcile.service"
```

**Check, per node:**
- `your args:  -e cpu --alloc 512k --lock 10ms` — the restored default. CPU only would mean the
  regression this run exists to catch has come back.
- `attached: yes`, a non-zero `age:`, and `updated:` a few seconds ago with no STALE or CONFIG banner.
- `chunks:   0 pending, N shipped, 0 rejected` — pending is **0** on a healthy node; the open chunk
  is excluded from the count, though `ls` still shows it.
- `pruned:   0 for age, 0 for size, 0 never shipped`.
- Timer shows a recent trigger and a future `NEXT`; `Result=success`.

**Expected and not a fault:** `edl_jfr_attach_skipped_total` is already non-zero on every node.
Profiling is seeded during `up` while Cassandra is still stopped, so every pass between `up` and
`cassandra start` logs `jfr_attach_skipped` and increments that counter.

**Also expected and not a fault:** `edl_jfr_attach_deferred_total` is non-zero on every node, for the
neighbouring reason — the passes between `cassandra start` and the database accepting client
connections defer the attach instead of signalling a JVM that would die on the signal. It stops
rising once the node is up; `edl_jfr_attach_failures_total` must still be **0**.

### 5. Generate load

```bash
$EDB cassandra stress start --name profiling-load --tags "run=profiling-880" \
  -- KeyValue -d 3h --threads 20 -p 50m
$EDB cassandra stress status
```

**Note the wall-clock time now** — steps 9 and 10 have a timing floor that depends on it. `-p 50m`
widens the dataset so SSTable read and compaction frames appear.

### 6. Verify chunks are produced, rotated, and shipped — on every node

Wait a few minutes after load starts.

```bash
for h in db0 db1 db2; do echo "=== $h"; $SSH $h "ls -la /mnt/db1/cassandra/profiles/"; done
$EDB cassandra profile status
```

**Check:** shipped chunks carry an **infix** — `cassandra-<pid>-<ts>-shipped.jfr` — not a new
extension, so they still match `*.jfr` and stay retrievable by `fetch`. One or two un-shipped `.jfr`
files on disk is the normal steady state (the grace window is 70s against a 60s timer), while
`status` reports `0 pending` in that same state — the open chunk is excluded from the count but not
from the directory. `rejected` stays 0.

### 7. Verify all four profile types in Pyroscope

Open `http://$CONTROL_IP:4040`, service `cassandra`, filter `cluster="$CLUSTER_LABEL"`.

**This is the highest-value new check in the plan.** Three of these four panels were permanently
empty before the default event set was restored, so all four must populate with **no operator
action**. Profile type IDs, exactly as `dashboards/profiling.json` uses them:

- `process_cpu:cpu:nanoseconds:cpu:nanoseconds`
- `memory:alloc_in_new_tlab_bytes:bytes:space:bytes`
- `block:contentions:count:block:count`
- `mutex:delay:nanoseconds:mutex:count`

**Also check:** recognisable Cassandra frames in the CPU graph (Netty decode, CQL execute,
MutationStage, ReadStage); all three hostnames present. A 1-minute window on 4 vCPU cannot exceed
240 CPU-seconds — substantially more means sample weights are being inflated.

### 8. Confirm the reconciler is converging, not thrashing

```bash
curl -sG "$VM" --data-urlencode 'query=edl_jfr_session_starts_total' | jq .
```

**Expect exactly 1 per node.** Use the absolute counter, not a rate: the cluster-up attach is one
legitimate increment, so `rate(...) == 0` can never hold unless ten minutes have already passed. The
sequence is seeded at `up` → `no_process` until `cassandra start` → one `start`. **Any node above 1
here is thrashing** — the reconciler decides by fingerprint comparison, and a fingerprint that never
matches re-attaches a profiler to the live database JVM every 60 seconds forever while every other
symptom still looks healthy.

### 9. Fetch raw JFR chunks

**Do not run until at least 12 minutes after step 5**, or several of the ten chunks predate the load.

```bash
$EDB cassandra profile fetch --last 10
ls -la "$CLUSTER_DIR"/profiles/*/
```

**Check:** ten chunks per node. Shipped chunks stay retrievable, or a node shipping every 60s would
have almost nothing left.

### 10. Produce a flame graph with thread-pool attribution

```bash
$EDB cassandra profile flamegraph --last 10 --hosts db0 -- --threads
```

**Check:** HTML flame graph in `$CLUSTER_DIR/profiles/db0/`, stacks split by thread showing
`ReadStage-*`, `MutationStage-*`, `Native-Transport-Requests-*`, `CompactionExecutor:*`,
`Messaging-EventLoop-3-*`, `MemtableFlushWriter-*`. `GC Thread#*` and `G1 Conc#*` will be prominent
— heap is ~4 GB on a 16 GiB node with G1 on Java 11. Expected, not a fault.

### 11. Prove the profile contains real data, not just files

Everything above passes even if the profiler produces structurally valid but empty output.

```bash
$EDB cassandra profile flamegraph --last 3 --hosts db0 --format collapsed
COLLAPSED=$(ls -t "$CLUSTER_DIR"/profiles/db0/flame-db0-*.collapsed | head -1)

# Collapsed format is one line per UNIQUE STACK with the sample count last,
# so every number must be weighted by $NF — line counts measure the wrong thing.
awk '{s+=$NF} END {print "samples:", s}' "$COLLAPSED"
awk '/unknown/{u+=$NF} {t+=$NF} END {printf "unknown: %.2f%%\n", 100*u/t}' "$COLLAPSED"
grep -c '_\[k\]' "$COLLAPSED"

$SSH db0 "cat /mnt/db1/cassandra/profiles/metrics.prom"
```

**Check.** *Samples* — `-e cpu` defaults to 10ms, so three busy cores for 60s is roughly 18,000
samples per chunk; a few hundred means the event never armed. *Unknown fraction* — small single
digits is healthy; the criterion is that Java frames are symbolised. *Kernel frames* —
async-profiler marks every kernel frame `_[k]`, so a zero count means the step 3 sysctls are not in
effect.

**These figures still measure CPU only.** The chunk now carries alloc and lock events too, but
`jfrconv` converts execution samples unless given `--alloc`/`--lock`, so the sample count and the
`_[k]` ratio are uncontaminated.

`metrics.prom` carries `asprof metrics` output plus the `edl_jfr_*` series, written every pass. Read
it rather than attaching as root — the reconciler deliberately uses no `sudo`, since it runs as
`cassandra`, which owns both the JVM and the profile directory. `jps` will not work here (Cassandra
ships `-XX:+PerfDisableSharedMem`); use `cassandra-pid`.

### 12. Switch the event set, and prove the switch actually applied

```bash
$EDB cassandra profile start --retention 600 --max-bytes 4294967296 -- -e cpu --alloc 2m
$EDB cassandra profile status
```

This **narrows** the running session — it drops `--lock` — which is what makes it falsifiable.
Non-default bounds are set here so step 19 can test that `stop` preserves them.

**Check:**
- `edl_jfr_session_starts_total` goes 1 → **2** per node. Exactly one restart, not more.
- The outgoing session's in-flight chunk is finalized and ships rather than being lost.
- After a few minutes, **`block` and `mutex` stop receiving new samples** while `process_cpu` and
  `memory` keep advancing. That is the observable consequence of dropping `--lock`, and it proves the
  reconciler applies what was asked rather than reading its own wish back. Merely seeing
  `process_cpu` and `memory` populated proves nothing — both have been live since step 7.

### 13. Switch to wall-clock in its own session

```bash
$EDB cassandra profile start --retention 600 --max-bytes 4294967296 -- -e wall -i 100ms
$EDB cassandra profile status
```

**Check:** `wall` populated in Pyroscope after a few minutes; `session_starts` goes 2 → 3. Do **not**
combine this with a cpu event in one session — that is the upstream `jfr-parser` defect, and it
corrupts CPU weights silently.

### 14. Verify profiling survives a Cassandra restart

```bash
$EDB cassandra profile start --retention 600 --max-bytes 4294967296 -- -e cpu --alloc 512k --lock 10ms
$EDB cassandra restart --hosts db0
$EDB cassandra profile status --hosts db0
```

The argument list here restores the shipped default deliberately, so the rest of the run exercises
what a real cluster actually runs.

**Check:** within about one reconcile interval db0 reports attached again with those args, against a
**new** PID, with no command run. New chunks appear afterwards. `session_starts` on db0 rises twice
here — once for the args change, once for the restart.

**This step is the hazard the readiness gate exists for, so check the gate here too.** Restarting a
node with profiling active is exactly the case where a pass used to attach to a JVM that had not yet
installed its `SIGQUIT` handler and kill it. The expected behaviour now:

- db0 comes back and **stays** up. A node that dies within a minute of starting, with
  `code=dumped, status=3/QUIT` in `journalctl -u cassandra`, is this bug and nothing else.
- While db0 is starting, its status reports `WAITING` and
  `attached: no (waiting for the database to become ready)` — not a failed attach.
- `edl_jfr_attach_deferred_total` on db0 rises by one or two and then stops.
  `edl_jfr_attach_failures_total` does **not** move.
- Attach follows on the first pass after the native transport starts listening, so it can take up to
  one interval longer than the reattach itself.
- The attach line says which readiness answer allowed it: `ready=listening` is correct.
  `ready=uptime` means the socket check is not finding the native transport and the ten-minute
  backstop is carrying the node — profiling still works, ten minutes late on every restart.

```bash
$SSH db0 "journalctl -u cassandra -n 20 --no-pager | grep -i quit || echo 'no SIGQUIT death: correct'"
$SSH db0 "grep -E 'edl_jfr_attach_(deferred|failures)' /mnt/db1/cassandra/profiles/metrics.prom"
$SSH db0 "cassandra-ready \$(cassandra-pid); echo exit=\$?"   # expect a 'listening:' line, not 'uptime:'
$SSH db0 "journalctl -u edl-profiling-reconcile.service --no-pager -n 50 | grep jfr_session_started"
```

Expect a side effect: `restart` bounces the sidecar DaemonSet on all three nodes regardless of
`--hosts`, so `cassandra cql` is briefly unavailable. It does not affect profiling.

### 15. Exercise retention pruning

```bash
$EDB cassandra profile start --retention 5 --max-bytes 4294967296 -- -e cpu --alloc 512k --lock 10ms
# wait ~8 minutes
curl -sG "$VM" --data-urlencode 'query=edl_jfr_pruned_for_age_total' | jq .
curl -sG "$VM" --data-urlencode 'query=edl_jfr_pruned_unshipped_total' | jq .
$EDB cassandra profile status --hosts db0
$SSH db0 "ls -la /mnt/db1/cassandra/profiles/"
```

**Check:** `pruned_for_age_total` rises. **`pruned_unshipped_total` must stay 0** — everything older
than five minutes has long since shipped, so a non-zero value means the pruner is destroying data
that was still shippable. `status` shows the new `pruned:` line with a non-zero age count.

**Not a restart.** Only the bounds moved; the argument fingerprint is unchanged from step 14, so
`age:` keeps growing and `session_starts` does **not** move. A converged node here is correct.

Restore the wider window before the destructive steps, or step 18 will destroy the backlog it
creates:

```bash
$EDB cassandra profile start --retention 600 --max-bytes 4294967296 -- -e cpu --alloc 512k --lock 10ms
```

### 16. Verify the metric inventory and the node's tooling

```bash
$SSH db0 "command -v yq && yq --version && command -v jq && jq --version"
curl -s "http://$CONTROL_IP:8428/api/v1/label/__name__/values" | tr ',' '\n' | grep edl_jfr
$SSH db0 "journalctl -u edl-profiling-reconcile.service --no-pager -n 50 | grep -i jfr_metrics_export_failed || echo 'no metrics export failures'"
```

**Expect:** `yq` present and reporting **mikefarah v4** (the python `yq` wrapper is a different tool
and would fail every JSON operation); `jq` present, which is what confirms the base AMI was rebuilt
from this branch; **fifteen** `edl_jfr_*` series present, including `edl_jfr_config_unreadable` and
`edl_jfr_ship_truncated`; no `jfr_metrics_export_failed`.

The reconciler itself uses `yq` for every JSON operation — it already reads the desired-state
document with it — so a missing `jq` would not break profiling. It is checked here as an AMI
provenance signal, not a runtime dependency.

### 17. Validate the CLI's own guards — no cluster time, nothing destructive

Every one of these is rejected in `execute()` before any SSH, so no node is contacted.

```bash
$EDB cassandra profile start --loop 1h                     # 3600 < 3670 → refused, names both flags
$EDB cassandra profile start --retention 1                 # 60 < 130 → refused
$EDB cassandra profile start --max-bytes 1048575           # below 1 MiB → refused
$EDB cassandra profile start --max-bytes 1048576           # accepted (boundary)
$EDB cassandra profile start --retention 0                 # refused
$EDB cassandra profile start --loop 02:30:00               # refused, explains time-of-day
$EDB cassandra profile fetch --last 0                      # refused
$EDB cassandra profile flamegraph --last -1                # refused
$EDB cassandra profile status                              # nothing changed on any node
```

`--loop 1h` against the default retention is worth naming: that exact configuration silently shipped
nothing at all before the relational check existed.

### 18. Verify a stopped database is visible without the CLI

Stop only the JVM — `cassandra stop --hosts db0` also undeploys the sidecar cluster-wide.

```bash
$SSH db0 "sudo systemctl stop cassandra"
```

Wait two reconcile intervals, then:

```bash
curl -sG "$VM" --data-urlencode 'query=edl_jfr_attach_skipped_total' | jq .
curl -sG "$VM" --data-urlencode 'query=edl_jfr_attach_deferred_total' | jq .
curl -sG "$VM" --data-urlencode 'query=edl_jfr_profiling_desired' | jq .
curl -sG "$VM" --data-urlencode 'query=edl_jfr_session_attached' | jq .
curl -sG "$VM" --data-urlencode 'query=edl_jfr_profiling_desired == 1 and edl_jfr_session_attached == 0' | jq .
$SSH db0 "journalctl -u edl-profiling-reconcile.service --no-pager -n 20 | grep jfr_attach_skipped"
$EDB cassandra profile status --hosts db0
```

**Expect — this verifies a fix, it is no longer a finding:**
- `attach_skipped_total` rises, one per pass. `attach_failures_total` deliberately does **not** move
  — a refused jattach and a stopped database are diagnosed in different places, so the counters are
  kept apart. `attach_deferred_total` does not move either while the database is stopped: there is no
  process to wait for. It rises again during the restore below, and then stops.
- `profiling_desired` stays 1, `session_attached` drops to 0, and the documented alert expression
  **returns a series for db0**. That expression silently resolved before this fix, because an absent
  series makes the `and` empty.
- One `<4>level=warn event=jfr_attach_skipped reason=no_database_process ... hint=is_cassandra_running`
  line per pass.
- `status` reports `desired: enabled`, `attached: no`, `pid: 0`, with an `AttachFailed` event.

Restore, and wait for `attached: yes` before continuing. db0 reports `WAITING` for a pass or two
first, and must come back up rather than dying on the attach:

```bash
$SSH db0 "sudo systemctl start cassandra"
$EDB cassandra profile status --hosts db0
```

### 19. Verify a dead timer is reported as stale — and the ship budget

**Only proceed once step 18's restore shows `attached: yes`.** Retention must be back at 600 from
step 15, or the backlog this creates is pruned before it can ship.

```bash
$SSH db0 "sudo systemctl mask --now edl-profiling-reconcile.timer"
```

**Wait at least 10 minutes** — the staleness threshold is `5 × 60s = 300s`, so the banner cannot
appear sooner than 5, and a 10-minute mask also builds the ~10-chunk backlog the ship budget needs.

```bash
$EDB cassandra profile status --hosts db0
$SSH db0 "cat /mnt/db1/cassandra/profiles/effective-state.json"
```

**Expect:**
- A two-line `STALE:` banner **above everything else**, naming the age, the 60s interval, and
  `edl-profiling-reconcile.timer` as the thing to check.
- `updated:` reads `Xm ago (stale)`; an `Event.Profiling.StateStale` event is emitted.
- The banner says **STALE and not CONFIG** — `configError` is empty here. That is one half of the
  discriminator between the two banners; step 20 is the other.

Unmask, then watch the first pass drain the backlog:

```bash
$SSH db0 "sudo systemctl unmask edl-profiling-reconcile.timer && sudo systemctl start edl-profiling-reconcile.timer"
$SSH db0 "journalctl -u edl-profiling-reconcile.service --no-pager -n 30 | grep jfr_ship_truncated"
curl -sG "$VM" --data-urlencode 'query=max_over_time(edl_jfr_ship_truncated[15m])' | jq .
```

**Expect:** the first pass uploads exactly **6** chunks (`SHIP_MAX_CHUNKS_PER_PASS`) and logs
`<4>level=warn event=jfr_ship_truncated uploaded=6 budget=6 remaining=N hint=pyroscope_slow_or_backlog`;
`max_over_time(edl_jfr_ship_truncated[15m])` is 1; later passes drain the remainder. The unit is
**not** killed at `TimeoutStartSec=300`, which is what the budget exists to prevent. With retention
at 600, `pruned_unshipped_total` stays 0.

### 20. Verify an unreadable config is reported as its own condition

The headline change of the fix round, and the one behaviour no earlier draft covered. db0 only.

```bash
$SSH db0 "sudo cp /etc/easy-db-lab/profiling.json /tmp/profiling.json.bak"
$SSH db0 "sudo sh -c 'printf \"{\" > /etc/easy-db-lab/profiling.json'"
# wait two reconcile intervals
$EDB cassandra profile status --hosts db0
$SSH db0 "cat /mnt/db1/cassandra/profiles/effective-state.json"
curl -sG "$VM" --data-urlencode 'query=edl_jfr_config_unreadable' | jq .
curl -sG "$VM" --data-urlencode 'query=edl_jfr_profiling_desired' | jq .
```

**Expect — every one of these was broken before the fix round:**
- `status` prints a `CONFIG:` banner naming `/etc/easy-db-lab/profiling.json`, **not** STALE, and it
  must **not** name the timer as the thing to check. The timer is healthy and firing every 60s.
- `Event.Profiling.NodeConfigUnreadable` is emitted and `StateStale` is **not** — they are mutually
  exclusive.
- `attached: yes`, **same pid**, `age:` still growing. Only the attach/detach decision is refused;
  the session is untouched.
- `chunksShipped` keeps rising and `ship_last_success_timestamp_seconds` keeps advancing — shipping
  continues rather than going dark.
- `effective-state.json` still carries `retentionMinutes: 600` and the real `pyroscopeUrl` — bounds
  are recovered field-by-field from the last good state, not reset to defaults.
- `edl_jfr_config_unreadable == 1`, and `profiling_desired` **still == 1** so the alert keeps firing.
- `updatedAt` advances every pass.

Restore and confirm the banner clears:

```bash
$EDB cassandra profile start --retention 600 --max-bytes 4294967296 -- -e cpu --alloc 512k --lock 10ms
$EDB cassandra profile status --hosts db0
```

### 21. Stop profiling and confirm a clean shutdown

```bash
$EDB cassandra profile stop
```

**Wait about three minutes before verifying.** `stop` writes the desired-state document; it does not
touch the JVM. `status` reads the *effective* state, which changes only when the reconciler next
runs — and shipping the final chunk takes two passes, since pass 1 finalizes it and it is eligible
only after clearing the 70s grace window.

```bash
$EDB cassandra profile status
$SSH db0 "ls -la /mnt/db1/cassandra/profiles/; cat /etc/easy-db-lab/profiling.json"
```

**Check:** status reports disabled; the final chunk of the stopped session ships rather than being
stranded (the skip-the-newest rule must not apply once the session is stopped); and the
desired-state document still carries `retentionMinutes: 600` / `maxBytes: 4294967296` — `stop`
copies the existing document and flips `enabled` rather than rebuilding it.

### 22. Record results and tear down

Capture Pyroscope screenshots of all four profile types, the flame graphs, the step 11 numbers, and
the outcome of steps 18-20 into the run journal before destroying anything.

```bash
$EDB cassandra stress stop profiling-load
$EDB down --auto-approve
```

## Notes

- **Only the Cassandra AMI needs rebuilding from this branch.** The reconciler, its systemd units and
  the modified `cassandra.in.sh` live there. The base AMI is unchanged from `main` — it supplies `yq`
  (mikefarah v4) and async-profiler 4.5.
- **`edl_jfr_session_starts_total` counts deliberate mode switches too**, not just thrash. Expected
  absolute values: after step 8, 1 per node. After step 14, db0 = 5, db1/db2 = 4 wait — count them
  from the steps themselves rather than memorising: up, step 12, step 13, step 14 args, and on db0
  also the step 14 restart and the step 18 database restore. Read the absolute counter and compare
  against what the plan actually did; a value moving between two readings with nothing being changed
  is the real signal.
- **Steps 9 to 11 must run before the step 12 switch.** `jfrconv` merges all input chunks, so a flame
  graph taken after a wall switch blends wall samples into a graph presented as CPU.
- **Do not combine a cpu event with `--wall` in one session.** Steps 12 and 13 are separate runs
  deliberately. Combined, CPU weights inflate by up to three orders of magnitude and the graph looks
  entirely plausible while being wrong. The tool does not reject this — by design, it does not police
  async-profiler's parameters — so it is on the operator.
- **Collapsed output is one line per unique stack**, with the sample count last. Weight every count
  by `$NF`.
- **Steps 18 to 20 deliberately break things.** Each has an explicit restore, and step 19 depends on
  step 18's restore having taken effect. Run them in order.
- **Retention must be back at 600 before step 19.** A 10-minute mask under `--retention 5` guarantees
  the unshipped backlog is pruned on unmask, which would both destroy data and contradict step 15's
  own assertion.
- **The non-ASCII cluster-name path is not exercised here.** `profiling-880` is ASCII, and covering it
  would need a second cluster. The bash tier covers it; do not add a cluster for it.
- Nothing sets `-XX:+DebugNonSafepoints`, so leaf and inlined frames carry safepoint bias. Odd leaves
  are not a profiler defect.
- The stress job's driver gets one contact point (db0's private IP). An already-connected driver
  keeps serving from db1/db2 during step 18, but a pod restart while db0 is down cannot bootstrap. If
  the job dies there, restart it after the restore.
- If `cassandra cql` returns `No node was available` while `nt status` shows all `UN`, the sidecar is
  not ready; wait 30s and retry.
