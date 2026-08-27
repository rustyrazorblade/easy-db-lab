# Profiling

Continuous profiling is provided by [Grafana Pyroscope](https://grafana.com/oss/pyroscope/), deployed automatically as part of the observability stack.

There are **two capture mechanisms** in the fleet, and which one applies depends on the workload:

- **Cassandra** uses **runtime-controlled async-profiler**, attached to the already-running JVM and driven by the `cassandra profile` command group. Nothing profiling-related is injected at JVM startup.
- **Every other JVM workload** — stress jobs, Presto/Trino, Spark/EMR, the Cassandra Sidecar — still uses the **Pyroscope Java agent**, loaded with `-javaagent` at startup.
- **Everything else** (ClickHouse, TiDB/TiKV/PD, and all host processes) is covered by the **Grafana Alloy eBPF profiler**.

## Architecture

Profiling data is collected from these sources and sent to the Pyroscope server on the control node (port 4040):

- **async-profiler, attached at runtime (Cassandra)** — A systemd timer on every Cassandra node runs `edl-profiling-reconcile`, which attaches async-profiler to the live Cassandra JVM, writes rotating JFR chunks to `/mnt/db1/cassandra/profiles`, uploads completed chunks to Pyroscope's `/ingest`, and prunes the directory. What is being profiled changes without restarting Cassandra.
- **Pyroscope Java agent (Stress jobs)** — Runs as a `-javaagent` inside cassandra-easy-stress K8s Jobs. Collects CPU, allocation, and lock profiles. The agent JAR is mounted from the host via a hostPath volume.
- **Pyroscope Java agent (Presto)** — Runs as a `-javaagent` inside both the Presto coordinator and worker JVMs, injected via `JAVA_TOOL_OPTIONS` during `presto start`. Profiles appear under `service_name=presto` with `component=coordinator` or `component=worker`.
- **Pyroscope Java agent (Spark/EMR)** — Runs as a `-javaagent` on Spark driver and executor JVMs, installed via EMR bootstrap action to `/opt/pyroscope/pyroscope.jar`. Profiles appear under `service_name=spark-<job-name>`.
- **Grafana Alloy eBPF profiler** — Runs as a DaemonSet on all nodes via [Grafana Alloy](https://grafana.com/docs/alloy/latest/). Profiles all processes at the system level using eBPF, including kernel stack frames. Pod processes are attributed per pod/container/service_name; see the eBPF Agent section below.

## Cassandra profiling at runtime

A cluster is profiling as soon as it comes up — `setup-instances` seeds each Cassandra node with profiling enabled and `-e cpu --alloc 512k --lock 10ms`. Nothing needs to be run to get CPU, allocation and lock-contention flame graphs. Only wall-clock sampling is exclusive of a CPU event, so all three of these run in one recording.

### Why it is not a startup agent

The Pyroscope Java agent takes exactly one primary `profiler.event`, fixed for the JVM's lifetime. Changing what was being profiled therefore meant restarting Cassandra, which discards page cache and compaction state — precisely what an operator on a benchmarking rig is trying to observe. Attaching at runtime makes the event set a runtime property instead. It also takes profiling out of the startup path entirely, so a bad profiler configuration can no longer abort Cassandra's start.

The cost, worth knowing: attach-based profiling dies with the JVM, so a node-resident reconciler re-establishes it. After a Cassandra restart or a mode switch, expect up to one reconcile interval (60s by default) with no profiling. That is expected behaviour, not a bug.

### Switching modes

There is one async-profiler session per JVM, so one mode at a time. Switch by stopping and starting:

```bash
# what is running right now, per node
easy-db-lab cassandra profile status

# switch the whole cluster to wall-clock profiling
easy-db-lab cassandra profile stop
easy-db-lab cassandra profile start -- -e wall -i 10ms

# back to CPU, with allocation sampling alongside it
easy-db-lab cassandra profile stop
easy-db-lab cassandra profile start -- -e cpu --alloc 512k

# only two nodes, rotating every 30 seconds
easy-db-lab cassandra profile start --hosts db0,db1 --loop 30s -- -e cpu
```

`stop` finalizes the in-flight JFR chunk before detaching, so a mode switch loses no data — the outgoing chunk still ships, and `fetch` and `flamegraph` can reach it once nothing is writing to it. `stop` also leaves your retention and byte ceiling exactly as `start` set them; it only turns profiling off. If a node's configuration document cannot be read — truncated by an interrupted write, say — `stop` has nothing to preserve and falls back to the defaults, and it says so with a `Profiling.DesiredStateUnreadable` event naming the node and the bounds it is applying, rather than resetting them silently.

Every profiling command applies to all Cassandra nodes by default; `--hosts` narrows it to a subset.

```admonish warning title="Never combine a CPU event with wall-clock sampling"
Do not run `-e cpu --wall 10ms` (or any equivalent single recording that captures both). The
resulting CPU profile is silently corrupted — see [The cpu+wall hazard](#the-cpuwall-hazard) below.
Run one mode at a time and switch with `stop`/`start`.
```

### async-profiler's own parameters pass through

Anything after `--` is handed to `asprof` byte-identically. easy-db-lab does not model, enumerate, or re-parse async-profiler's option surface, so options added by newer async-profiler releases work with no change here.

```bash
easy-db-lab cassandra profile start -- -e wall -i 10ms
easy-db-lab cassandra profile start -- -e cpu --lock 10ms --alloc 512k
```

```admonish info title="Two --cstack values changed in async-profiler 4.4"
The nodes run async-profiler 4.5, and you own the flags you pass. Both of these changed silently:

- `--cstack dwarf` no longer yields DWARF unwinding on HotSpot. async-profiler promotes it to `vm` whenever VMStructs are available, with no error and no warning. Forcing real DWARF now needs `features=agct`. Upstream's own `docs/StackWalkingModes.md` is stale on this point.
- `--cstack lbr` was **removed**. An unrecognised `--cstack` value falls through to "no C stacks", so passing `lbr` silently turns off native stack collection rather than failing.
```

### Reserved parameters

Five parameters are **rejected**, at the CLI, before any node is contacted. Four of them easy-db-lab supplies itself, because the node-side JFR shipper depends on them; the fifth changes which JFR event types reach that shipper at all:

| Concept | Rejected spellings | Why |
|---|---|---|
| Output file | `-f`, `--file`, `file=` | The shipper needs a known path carrying `%p`/`%t` |
| Output format | `-o`, `--output`, and bare format words (`jfr`, `collapsed`, `flamegraph`, `tree`, `otlp`, `flat`, `traces`) | The shipper needs JFR |
| Rotation | `--loop`, `loop=` | Chunk-completion detection depends on it |
| Auto-stop | `-d`, `--duration`, `--timeout`, `timeout=` | A self-terminating session would be restarted by the reconciler, thrashing forever |
| Wall-clock batching | `--nobatch`, `nobatch` | It makes wall samples arrive as `jdk.ExecutionSample`, indistinguishable from CPU samples, and Pyroscope merges them into `process_cpu` — see [The cpu+wall hazard](#the-cpuwall-hazard) |

Rejection covers both of async-profiler's spelling systems (CLI flags and the comma-separated agent-option form) and reserved options smuggled inside another value after a comma, such as `-e cpu,file=/tmp/elsewhere.jfr`. That last case is the reason the check exists at all: without it, profiling would appear to work while chunks landed somewhere the shipper never looks and Pyroscope stayed silently empty.

**The first four remove no capability.** Every profiling parameter async-profiler offers remains reachable, and the one knob from that set people actually want — rotation — is a first-class option on the command itself:

```bash
easy-db-lab cassandra profile start --loop 30s -- -e cpu
```

### Retention on the node

Chunks are bounded by both age and total size, and pruning covers unshipped chunks too, so an unreachable Pyroscope cannot fill `/mnt/db1` and take the node down.

```bash
easy-db-lab cassandra profile start --retention 30 --max-bytes 1073741824 -- -e cpu
```

| Option | Default | Meaning |
|---|---|---|
| `--loop` | `1m` | JFR rotation interval: a whole number of seconds (`30`) or a number with a unit (`30s`, `5m`, `1h`). Anything else is refused before any node is contacted. async-profiler's `hh:mm:ss` time-of-day form is deliberately **not** accepted — it rotates once a day, and because easy-db-lab ships every completed chunk continuously and sizes the completion and upload windows from a fixed interval, a daily rotation yields no usable profiles. The interval also has a floor of **10s**: a node ships at most 6 chunks per 60-second pass, so anything faster produces chunks quicker than they can be drained and the queue grows until retention deletes them unshipped. That floor is refused before any node is contacted too. |
| `--retention` | `60` | Minutes of profile data kept on each node. Must be long enough to hold the rotation: a chunk is not shippable until one `--loop` interval plus 10s have passed and the node only looks every 60s, so a window shorter than that prunes every chunk before it can ship. That combination is refused before any node is contacted, naming both flags. |
| `--max-bytes` | `2 GiB` | Byte ceiling for the profile directory, pruned oldest-first. Must be at least 1 MiB, for the same reason: a ceiling below one JFR chunk deletes each chunk as it lands. |

Profiles live in `/mnt/db1/cassandra/profiles`, deliberately **not** `artifacts/` — that directory is world-writable and holds operator-dropped heap dumps and jstacks, which automatic pruning must never delete.

### Fetching chunks and building flame graphs

```bash
# download the 5 most recent completed chunks from every node into ./profiles/<host>/
easy-db-lab cassandra profile fetch

easy-db-lab cassandra profile fetch --last 20 --hosts db0

# convert the 10 most recent chunks into a flame graph on the node and download it
easy-db-lab cassandra profile flamegraph --last 10

# split stacks by thread — see below
easy-db-lab cassandra profile flamegraph --last 10 -- --threads
```

Conversion runs on the node, because `jfrconv` is installed there and not on your machine, and because the rendered HTML is far smaller than the raw JFR.

`jfrconv` arguments pass through after `--` the same way async-profiler's do. Only its input and output are reserved — positional arguments and `-o`/`--output` are rejected, because easy-db-lab supplies the input chunks (`--last`) and the output destination (`--format`).

The chunk currently being written is never offered by `fetch` or `flamegraph`: async-profiler finalizes constant pools when a chunk closes, so an unfinalized chunk is unreadable by any tool. Everything else on the node is offered, including chunks already shipped to Pyroscope — shipping marks a chunk with a `-shipped.jfr` infix rather than moving it aside, so `--last 20` really does give you twenty chunks. Once you stop profiling, the final chunk of the run becomes available too, since nothing is writing to it any more.

### Thread-pool attribution

**`jfrconv --threads` on a fetched chunk is the only way to see Cassandra thread-pool attribution** (`ReadStage-3`, `CompactionExecutor:4`, `MutationStage-7`).

Pyroscope's JFR ingest discards thread identity: the JFR carries `sampledThread` and Grafana's parser decodes it, but the pprof converter never reads it. Passing `-t` to async-profiler does not help either — in JFR output mode it deliberately puts thread identity in the constant pool rather than into the stack. So the continuous-profiling path structurally cannot show you which pool a stack came from, and the `flamegraph` path exists alongside it precisely for this:

```bash
easy-db-lab cassandra profile flamegraph --last 20 -- --threads
```

### Status

```bash
easy-db-lab cassandra profile status
```

Reports per node: what you asked for (`desired`) and what is actually attached to the JVM (`attached`), reported separately, the process being profiled, how long the session has been running, **your arguments verbatim**, the **full command line as actually invoked** (including the `-o jfr --loop ... -f ...` the tool appended), chunks pending/shipped/rejected, bytes held on disk, and the most recent shipping and attach errors.

It also reports `pruned:` — how many chunks the node reclaimed for age, for size, and how many it destroyed before they ever reached Pyroscope. That last number is the only one that means lost data rather than reclaimed disk, and a non-zero value emits a typed `Profiling.ChunksLost` event.

The report is a snapshot the node's reconciler left behind, not a live reading, so `status` also prints `updated:` — how long ago that node last completed a pass. If the reconciler has stopped running, the report is marked `STALE` above everything else in it and names the unit to check, and `status` emits a typed `Profiling.StateStale` event. Without that, a node whose timer is masked keeps reporting `attached: yes` with a session age that grows convincingly against the current clock while nothing is running.

A node whose configuration document cannot be read is marked `CONFIG` instead, never `STALE`, and emits `Profiling.NodeConfigUnreadable`. The two are opposite diagnoses. A pass that cannot read `/etc/easy-db-lab/profiling.json` refuses only the attach/detach decision — it keeps shipping and pruning under the bounds the last good pass recorded, and keeps reporting every metric — so the reconciler is running perfectly and the file it reads is what is wrong. Run `cassandra profile start` to rewrite it.

`desired` and `attached` are two lines rather than one because they answer different questions. A node showing `desired: enabled` with `attached: no` is a node whose profiler will not attach — usually `PrivateTmp` hiding the attach socket, a `java.io.tmpdir` mismatch, or `perf_event_paranoid` — and `status` emits a typed `Profiling.AttachFailed` event with the reconciler's captured error, rather than letting it look like a deliberate stop.

A node that has just started is marked `WAITING` instead, and reports `attached: no (waiting for the database to become ready)`. The reconciler will not attach to a database whose native transport is not yet listening, because async-profiler attaches with jattach, jattach signals `SIGQUIT`, and a process that has not installed a handler for that signal yet is killed by it — which is how attaching to a starting node used to kill it. This state clears itself on the first pass after the database is up, so it emits a typed `Profiling.AttachDeferred` event, which is **not** an error, and no `Profiling.AttachFailed`. If it does not clear, see `edl_jfr_attach_deferred_total` below.

Shipping and attach problems are also visible without the CLI. The reconciler writes structured logfmt lines to journald, which Fluent Bit ships to VictoriaLogs, and exports counters to the node's OTel collector, which forwards them to VictoriaMetrics:

| Metric | Meaning |
|---|---|
| `edl_jfr_ship_failures_total` | Transient upload failures (5xx or network). Rising means Pyroscope or the network is unwell; those chunks are retried. |
| `edl_jfr_ship_rejected_total` | Chunks Pyroscope refused (4xx). Rising means chunks are being produced it cannot parse, usually truncation after an unclean shutdown. These are **never** retried, so one bad chunk cannot wedge the queue. |
| `edl_jfr_profiling_desired` / `edl_jfr_session_attached` | What was asked for and what the JVM actually has, as `1`/`0`. Together they separate the three states that otherwise look identical: `0/0` is profiling deliberately off, `1/1` is profiling running, and `1/0` is a node that wants to profile and is not — the condition to alert on. |
| `edl_jfr_attach_failures_total` | Failed profiler attaches. Rising means asprof refused: usually `PrivateTmp` hiding the attach socket, a `java.io.tmpdir` mismatch, or `perf_event_paranoid`. The journald line carries asprof's own message. |
| `edl_jfr_attach_skipped_total` | Passes that wanted to attach and found no database process — a stopped database, a missing `cassandra-pid`, or the reconciler running as the wrong user. Counted apart from a refused attach because the two are fixed in completely different places. |
| `edl_jfr_attach_deferred_total` | Passes that wanted to attach, detach or replace a session against a database that was present but not ready to be signalled. Every node restart moves this once or twice and then stops; a rate that never returns to zero means the database is not coming up, or its native transport is on a port the node's `cassandra-ready` probe was not told about. The journald line carries the reason: `reason=database_not_ready` for the ordinary case, `reason=readiness_probe_missing` if the node image is missing `cassandra-ready` altogether. Counted apart from both neighbours above because all three are fixed in different places. |
| `edl_jfr_attach_deferred` | `1` when the last pass deferred for that reason. Pair it with `edl_jfr_profiling_desired == 1 and edl_jfr_session_attached == 0` so a node that has just restarted does not fire that alert. |
| `edl_jfr_session_starts_total` | Profiler attaches that succeeded, counting every restart. A steady rate here means the node is tearing the session down and re-attaching every pass — samples are lost across each detach — rather than profiling continuously. |
| `edl_jfr_pruned_unshipped_total` | Chunks deleted before they ever reached Pyroscope, under either bound, counting only chunks that could still have shipped — a chunk Pyroscope rejected is counted above instead. **This is lost data**, and rising here is the answer to "my profiles never showed up", usually shipping failing for longer than the retention window. |
| `edl_jfr_pruned_for_age_total` / `edl_jfr_pruned_for_size_total` | Chunks reclaimed by the retention window and by the byte ceiling. Which one is moving says which bound to raise. |
| `edl_jfr_chunks_pending` | Chunks the shipper could have shipped and did not. Both the chunk currently being written and any chunk still inside its completion window are excluded — neither has had its chance yet — so on a healthy node this sits at `0` and any non-zero value is a real backlog. |
| `edl_jfr_config_unreadable` | `1` when the node could not read its desired-state document on the last pass. The pass still ships, prunes and reports; it will not change what it profiles until the document is rewritten. Pair this with `edl_jfr_profiling_desired`, which keeps reporting the last known answer rather than dropping out — a series that stops being written silently *resolves* an alert instead of firing it. |
| `edl_jfr_ship_truncated` | `1` when a pass hit its per-pass upload budget with chunks still queued. A backlog draining normally sets this on a few consecutive passes and then clears it, so the reconciler is not unwell. Watch it together with `edl_jfr_chunks_pending`: if this stays at `1` while pending keeps climbing, the node is producing faster than it can ship and nothing will ever drain — check `edl_jfr_pruned_unshipped_total`, which is the data already lost to it. |
| `edl_jfr_bytes_on_disk` | Size of the profile directory. If the byte ceiling ever engages, this is the signal. |
| `edl_jfr_ship_last_success_timestamp_seconds` | When a chunk last shipped successfully. |

Each successful attach also logs `event=jfr_session_started ... ready=<code>`, naming which readiness answer allowed it. `ready=listening` is the healthy answer. A node — or a whole cluster — attaching on `ready=uptime` is profiling ten minutes late every time: the socket check is not finding the native transport, and the ten-minute backstop is doing work it was only meant to do for a transport that is deliberately off.

Every series carries the node's `host_name` and the `cluster` label, the same as the rest of the stack.

## The cpu+wall hazard

Combining a CPU event and wall-clock sampling **in one recording** corrupts the CPU sample weights, silently, by up to three orders of magnitude.

The defect is upstream, in Pyroscope's JFR ingest. Pyroscope delegates to `github.com/grafana/jfr-parser` (pinned at v0.18.0), whose `pprof/parser.go:56` declares the sample value buffer once, outside the event loop:

```go
var values = [2]int64{1, 0}
```

The wall-clock branch assigns `values[0] = WallClockSample.Samples` and never restores it, and the execution-sample branch reuses the buffer without re-initialising it. After the first wall sample, every subsequent CPU sample carries that wall event's **batch count** as its weight. Wall sampling coalesces idle threads into batches of up to 1000, and Cassandra runs hundreds of mostly-idle pool threads — hence the magnitude.

Three things follow:

- **The corruption is scoped to a single upload.** Separate sessions are uploaded separately and are each clean. Switching modes with `stop`/`start` is the supported approach and is not affected.
- **`--nobatch` is not a mitigation, and easy-db-lab rejects it.** It does not merely disable coalescing: it changes the emitted event type, so wall samples arrive as `jdk.ExecutionSample` — indistinguishable from CPU samples — and Pyroscope merges them all into `process_cpu`. It causes exactly the failure it appears to prevent. async-profiler itself refused the flag until 4.5 accepted it, so the guard now lives in easy-db-lab.
- **Getting CPU, wall, and allocation profiles requires more than one session, in sequence.** cpu+alloc in one session is fine; wall belongs in its own.

Note also that CPU and wall sampling have very different costs. A CPU sample only fires on a *running* thread, so its rate is bounded by core count. A wall tick targets threads regardless of run state, so its rate is bounded by *thread* count — far higher on a Cassandra node. That is why wall is treated as its own mode rather than something left on by default.

## Accessing profiles

### Profiling dashboard

1. Open Grafana (port 3000)
2. Navigate to **Dashboards** and select the **Profiling** dashboard
3. Use the **Service** dropdown to select a service (e.g. `cassandra`, `cassandra-easy-stress`, `clickhouse-server`)
4. Use the **Hostname** dropdown to filter by specific nodes
5. Select a time range

The dashboard includes panels for:
- **CPU Flame Graph** — CPU time spent in each method
- **Wall Clock Flame Graph** — Wall-clock time, for finding I/O and blocking
- **Memory Allocation Flame Graph** — Heap allocation hotspots
- **Lock Contention Flame Graph** — Time spent waiting for monitors
- **Mutex Contention Flame Graph** — Mutex delay analysis

```admonish info title="The Cassandra panels are mutually exclusive by design"
One async-profiler session runs per JVM, so only one mode runs at a time. Which panels carry data
therefore depends on the session that is running, and the panels for the other mode are empty. That
is expected, and it is not a broken dashboard or a broken pipeline.

- The **default** session, seeded at cluster up as `-e cpu --alloc 512k --lock 10ms`, fills CPU,
  Memory Allocation, Lock Contention and Mutex Contention. Wall Clock is empty.
- A **wall-clock** session, `-e wall -i 100ms`, fills Wall Clock. Memory Allocation, Lock Contention
  and Mutex Contention are empty. CPU keeps data, because Pyroscope routes runnable-thread wall
  samples into `process_cpu` as well.

Run `cassandra profile status` to see what each node is actually running, and switch modes with
`stop`/`start` as shown in [Switching modes](#switching-modes). The dashboard repeats this in a text
panel at the top.

This applies only to Cassandra. Workloads profiled by the Pyroscope Java agent or the eBPF agent are
not affected.
```

### Grafana Explore

1. Open Grafana (port 3000) and navigate to **Explore**
2. Select the **Pyroscope** datasource
3. Choose a profile type (e.g. `process_cpu`, `memory`, `mutex`, `wall`)
4. Filter by labels: `service_name`, `hostname`, `cluster`

Cassandra profiles arrive under `service_name=cassandra` with `hostname` and `cluster` labels.

## Profile types

### Cassandra (runtime async-profiler)

Which types are populated depends on the session that is running, since one session runs at a time.

| Profile | Produced by | Description |
|---------|-------------|-------------|
| `process_cpu` | `-e cpu` | CPU time spent in each method |
| `memory` | `--alloc <n>` | Allocation by method. `<n>` is a sampling interval in bytes, not a size filter — setting it below a thread's natural TLAB forces extra slow-path refills |
| `mutex` | `--lock <t>` | Lock contention |
| `wall` | `-e wall` | Wall-clock time, for finding I/O and blocking. Own session only |

Thread identity is **not** available through this path; use `flamegraph -- --threads` (see above).

### Java agent (stress jobs, Presto, Spark, sidecar)

| Profile | Description |
|---------|-------------|
| `cpu` | CPU time spent in each method |
| `alloc` | Memory allocation by method |
| `lock` | Lock contention |

### eBPF agent (all processes)

| Profile | Description |
|---------|-------------|
| `process_cpu` | CPU usage by process, including kernel frames |

The eBPF agent profiles **all processes** on every node, including ClickHouse and other kit databases (TiDB, TiKV, PD). Since these are written in C++/Go, only CPU profiles are available.

Processes running inside Kubernetes pods are attributed to their pod: they carry `namespace`, `pod`, `container`, and a `service_name` derived as `<namespace>/<container>` (for example `tidb-cluster/tikv`). Host processes that don't run in a pod are still profiled, just without pod labels.

## Stress job profiling

Stress jobs are automatically profiled via the Pyroscope Java agent. No configuration is needed — when you start a stress job, the agent is mounted from the host node and configured to send profiles to the Pyroscope server. Profiles appear under `service_name=cassandra-easy-stress` with `cluster` and `job_name` labels.

## Configuration

### Cassandra runtime profiling

| Location | Purpose |
|---|---|
| `/etc/easy-db-lab/profiling.json` | Desired state, written atomically by the CLI, read by the reconciler each pass |
| `/mnt/db1/cassandra/profiles/` | JFR chunks, plus `effective-state.json` and `metrics.prom` (the textfile is a convenience when you are already on the node; the counters that reach Grafana are pushed to the node's OTel collector) |
| `/usr/local/bin/edl-profiling-reconcile` | The reconciler |
| `/usr/local/bin/cassandra-ready` | The readiness gate. Exits 0 once the database is safe to signal, and prints a reason code saying which answer it gave: `listening` (something is listening on port 9042 — the normal path), `uptime` (nothing is listening but the process has been up ten minutes, which covers a native transport deliberately turned off), `starting`, or `no-process`. It reads the socket table with `ss`, never a connection to an assumed address — the native transport binds to `rpc_address`, the node's private IP, so a loopback connect answers "closed" on every healthy node. Run it by hand as `cassandra-ready $(cassandra-pid)`. |
| `edl-profiling-reconcile.timer` | systemd timer, 60s interval, runs as the `cassandra` user |

You should not need to edit these by hand; `cassandra profile start`/`stop` own the desired-state document, and stopping is recorded as an explicit disabled state rather than by deleting the file.

```admonish danger title="Do not add PrivateTmp to cassandra.service"
Attaching depends on async-profiler's `jattach` finding the HotSpot attach socket by falling back
to `/tmp`. Adding `PrivateTmp=yes` or `RootDirectory=` to `cassandra.service`, or setting
`-Djava.io.tmpdir`, silently breaks attach. There is a comment in the unit file recording this.
```

### eBPF agent

The eBPF profiler runs as a privileged Grafana Alloy DaemonSet (`pyroscope-ebpf`) and profiles all processes on each node. Configuration is in the `pyroscope-ebpf-config` ConfigMap (Alloy River format). It uses `discovery.kubernetes` to discover the pods on each node, `discovery.process` (joined to those pods by container id) to discover host processes, and `pyroscope.ebpf` to collect CPU profiles. The DaemonSet runs under the `pyroscope-ebpf` ServiceAccount, whose ClusterRole grants read access to pods so samples can be attributed to a pod/container/service_name.

### Pyroscope server

The Pyroscope server runs on the control node with data stored in S3 (`s3://<account-bucket>/clusters/<name>-<id>/pyroscope/`). Configuration is in the `pyroscope-config` ConfigMap.

## Data flow

Cassandra is the one path where nothing is injected into the JVM at startup and nothing pushes from inside the process — the reconciler on each node reads finished chunks off disk and uploads them.

```
Cassandra JVM
    │ (async-profiler attached at runtime, writes JFR chunks)
    ▼
/mnt/db1/cassandra/profiles/*.jfr
    │ (edl-profiling-reconcile.timer, every 60s: ship completed chunks, prune)
    ├──────────────► Pyroscope Server (:4040) ──┐
    │                                            │
    └──(fetch / flamegraph)──► your workspace    │
                                                 │
Stress Jobs   ──(Java agent)────────────────────►│
Presto JVMs   ──(Java agent)────────────────────►│
Spark JVMs    ──(Java agent)────────────────────►│
All Processes ──(eBPF agent)────────────────────►│
                                                 │
                                                 ▼
                                            S3 storage
                                          Grafana (:3000)
                                     Pyroscope datasource
                                    + Profiling dashboard
```
