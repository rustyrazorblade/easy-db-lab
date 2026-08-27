## Context

Cassandra nodes profile continuously via the Pyroscope Java agent (`pyroscope.jar` v2.3.0), injected
into `JVM_OPTS` by an easy-db-lab snippet appended to every install's `bin/cassandra.in.sh`. It is
gated on `PYROSCOPE_SERVER_ADDRESS` being present in `/etc/default/cassandra`, which `SetupInstance`
writes at cluster-up. The agent takes one primary event (`cpu` XOR `wall`) fixed for the JVM's
lifetime.

async-profiler 4.5 is installed on every node at `/usr/local/async-profiler` by
`packer/base/install/install_async_profiler.sh`, and every `java` binary under `/usr/lib/jvm/`
already carries `cap_perfmon,cap_sys_ptrace,cap_syslog=ep` via `setup_instance.sh`, with
`kernel.perf_event_paranoid=1` and `kernel.kptr_restrict=0` set. Kernel-stack CPU profiling therefore
already works for the unprivileged `cassandra` user without root.

Three facts, each verified in upstream source, constrain the design and are the reason it looks the
way it does.

**One async-profiler session per JVM.** `Profiler` is a singleton with a single state machine —
`profiler.cpp:867` returns `"Profiler already started"` when `_state > IDLE` — one `FlightRecorder
_jfr` member, and one `loop=` clock folded into a single `_loop_time` field. async-profiler 4.x added
multi-*event* recording into one file (`--all`, `-e cpu --wall 10ms`), not multi-recording. Two
simultaneous independent sessions are impossible, and cpu and wall cannot be given different `loop=`
periods.

**Pyroscope's JFR parser corrupts CPU sample weights in mixed cpu+wall recordings.** Pyroscope
delegates to `github.com/grafana/jfr-parser` (pinned at v0.18.0). In `pprof/parser.go:56` the sample
value buffer is declared once, outside the event loop:

```go
var values = [2]int64{1, 0}
```

`T_WALL_CLOCK_SAMPLE` assigns `values[0] = parser.WallClockSample.Samples` (line 83) and never
restores it; `T_EXECUTION_SAMPLE` passes `values[:1]` without re-initialising. After the first wall
sample, every subsequent CPU sample carries that wall event's batch count as its weight. Batching
coalesces *idle* threads up to `MAX_IDLE_BATCH = 1000` (`wallClock.cpp:32`), and Cassandra runs
hundreds of mostly-idle pool threads, so the error reaches three orders of magnitude. The
contamination is scoped to one `parse()` call, which is one upload — separate recordings uploaded
separately are each clean.

`--nobatch` is not a mitigation, and the CLI rejects it. It does not merely disable coalescing: it
changes the emitted event type. `wallClock.cpp:162` selects `WALL_LEGACY` under `--nobatch`, whose
signal handler emits `ExecutionEvent` rather than `WallClockEvent`, so wall samples arrive as
`jdk.ExecutionSample` — identical in type to CPU samples — and Pyroscope merges them all into
`process_cpu`. It causes precisely the failure it appears to prevent. `asprof` itself rejected the
flag at 4.3 and accepts it at 4.5, so the guard has to be ours; see the 4.5 notes in Domain Facts.

**Runtime attach works on these nodes.** The `setcap` makes execve a secureexec, setting
`AT_SECURE=1` and clearing the dumpable flag, which re-owns `/proc/<pid>` to root and gates
`/proc/<pid>/root` behind `ptrace_may_access`. That breaks `jcmd`/`jstack`/`jmap` on JDK 9–22, which
resolve the attach socket through `/proc/<pid>/root/<tmpdir>` (OpenJDK **JDK-8226919**, fixed in 23).
async-profiler's own `jattach` is unaffected: `psutil.c`'s `get_tmp_path()` falls back to `/tmp` when
`stat("/proc/<pid>/root/tmp")` fails, `jattach_hotspot.c` falls back to `/tmp` for the
`.attach_pid` trigger, and `jattach.c` calls `setegid`/`seteuid` to the target's credentials before
connecting — which also satisfies JDK 8's stricter `st_uid == geteuid()` peer check that a root-run
`jcmd` would fail. `cassandra.service` sets no `PrivateTmp` and nothing overrides `java.io.tmpdir`,
so both fallback paths resolve to the same directory the JVM used.

The `setcap` is the enabler here, not an obstacle: `libasyncProfiler.so` runs *inside* the Cassandra
JVM and inherits that process's `cap_perfmon`.

## Goals / Non-Goals

**Goals:**
- Change what is being profiled without restarting Cassandra.
- Pass async-profiler's own parameters through untouched, so the tool never has to track
  async-profiler's option surface as it evolves.
- Keep profiling running across Cassandra restarts with no operator action and no CLI process
  resident.
- Never ship a corrupted profile: make the known-bad combination avoidable and documented.
- Never let profiling output fill `/mnt/db1`, which would take the node down.
- One command surface for profiling, not two (absorbing #872).

**Non-Goals:**
- Simultaneous cpu + wall + alloc in one session. Impossible per the constraints above; the design
  gives cpu+alloc in one session and wall in another.
- Preserving per-thread attribution through Pyroscope. Structurally impossible (see Domain Facts);
  the `flamegraph` path is where thread-pool attribution lives.
- Migrating `cassandra-sidecar`, stress jobs, or Spark/EMR off the Pyroscope Java agent.
- Touching Grafana Alloy's `pyroscope.ebpf` sampler.
- Validating or interpreting async-profiler's profiling parameters beyond the reserved output set.

## Decisions

### Runtime attach, not `-agentpath` at JVM startup

`-agentpath` fixes the profiler configuration for the JVM's lifetime, and `loop=` re-runs the same
arguments each rotation. Changing the event set would mean restarting Cassandra, discarding page
cache and compaction state on a rig that exists to measure exactly those. Attach-based control makes
the event set a runtime property.

The cost, accepted: attach-based profiling dies with the JVM, so something must re-establish it —
hence the reconciler below. A second cost: a bad `-agentpath` string aborts JVM startup outright,
whereas an attach failure leaves Cassandra running. Moving capture out of the startup path removes
that failure mode entirely, which is a side benefit worth naming.

### One mode per session; switch by stop/start

Since one session per JVM is a hard constraint and mixed cpu+wall is corrupting, the design runs one
mode at a time. Switching is `stop` then `start` with different arguments. `asprof stop` finalizes
the in-flight chunk, so a mode switch loses no data — unlike a `kill -9`, which loses it because
constant pools are written at chunk close.

Combining cpu and wall remains *expressible*, because arguments pass through unvalidated. It is
handled by documentation in three places rather than by a guard: the user guide, `profile start
--help`, and a source comment on the reserved-set constant citing `jfr-parser` v0.18.0
`pprof/parser.go:56`. The source comment matters most — it is what stops a future contributor from
"helpfully" adding a combined mode, and the failure it prevents is silent.

### Pass async-profiler's parameters through; reserve only output plumbing

The tool supplies `-o jfr`, `--loop`, and `-f <dir>/cassandra-%p-%t.jfr`; everything else is the
user's. Tool arguments are appended last, so `status` shows the user's parameters first and the
plumbing clearly separated. Ordering is defence in depth only — async-profiler's precedence is not
relied upon; the validator is the guarantee.

The reserved set spans **both of async-profiler's spelling systems**, because `asprof` translates CLI
flags into a comma-separated agent option string:

| Concept | CLI form | Agent form | Why reserved |
|---|---|---|---|
| Output file | `-f FILE` | `file=FILE` | Shipper needs a known path with `%p`/`%t` |
| Output format | `-o FORMAT` | bare token: `jfr`, `collapsed`, `flamegraph`, `tree`, `otlp`, `traces[=N]`, `flat[=N]` | Shipper needs JFR |
| Rotation | `--loop TIME` | `loop=TIME` | The completeness rule below depends on it |
| Auto-stop | `-d N`, `--timeout N` | `timeout=N` | Ends the session; the reconciler restarts it, thrashing forever |

Two subtleties that a naive check misses. `-o` is not the only way to set format — a **bare format
word** sets it directly. And because values are joined with commas, a comma inside any user *value*
smuggles a reserved agent-form option: `-e 'cpu,file=/tmp/elsewhere.jfr'` silently redirects output
so the shipper sees nothing and Pyroscope stays empty with no error anywhere. So every value is split
on commas and each fragment re-checked in agent form.

Deliberately **not** reserved, so the boundary reads as drawn rather than forgotten:
`--chunksize`/`--chunktime` set JFR-internal chunk boundaries within a file rather than file
rotation, and Pyroscope parses multi-chunk JFR; `--jfrsync` takes a settings-profile name, not a
path; `--jfropts`, `--log`, `--loglevel` do not touch the output path.

Rejection happens in Kotlin at the CLI, before any SSH round-trip, per the repo's fail-fast
preference. The error names the flag, the owner, and the reason, and points at `--loop` as the
supported way to set rotation.

**Known limit, stated rather than smoothed over:** the bare-format-token rule fires only in "flag
position" (the preceding token does not start with `-`). Distinguishing perfectly would require
knowing which async-profiler flags consume values, i.e. enumerating the option surface — the thing
this design exists to avoid. `--include collapsed` is correctly allowed; a hypothetical `--quiet
collapsed` would be wrongly allowed. The tool appending `-o jfr` last is the backstop.

### Store arguments as an array; expand as argv

Desired state stores `asprofArgs` as a JSON array of strings, not one string. An array preserves the
user's exact tokenization; a single string would force the node to re-split it, and re-splitting is
where quoting bugs live.

The reconciler reads it NUL-delimited via `yq -0` into a bash array and expands `"${USER_ARGS[@]}"`
directly as argv. Nothing is re-parsed on the node, so the injection surface is **structurally
absent** rather than escaped correctly. NUL delimiting also survives a newline inside an argument,
which `mapfile -t` would not; arguments containing newline or NUL are rejected at the CLI anyway, to
keep the node-side contract simple.

`yq` rather than `jq`: both are on the base image, and either would serve. `yq` (mikefarah v4, Go)
already parses JSON in this repo — `SetupInstance` pipes IMDS JSON through it — and it supports
`-0`/`--nul-output`, so the reconciler needs one JSON tool rather than two. This is a tidiness call,
not a safety or availability one.

### The live JVM is the sole authority on liveness

The reconciler asks `asprof status <pid>` whether a session is running. It never trusts a file for
that. async-profiler's session state lives inside the target process and dies with it, which is what
makes the whole design safe against stale records: any effective-state record whose `pid` differs
from the current `cassandra-pid` describes a dead JVM and is discarded before the decision is read.

`cassandra-pid` already exists on nodes as `systemctl show --property MainPID --value cassandra` —
authoritative, returns 0 when stopped, no pgrep heuristics.

The decision table per pass: desired absent → idle; desired corrupt → log and **leave the running
session untouched**; `enabled == false` → stop if running; not running → start; running with
matching spec → no-op; running with differing or unknown spec → stop, then start. The "unknown spec"
case (an operator attached by hand, or the record was lost) converges in one pass at a cost of at
most one loop interval, which is correct — guessing would leave the node profiling something nobody
asked for.

Spec comparison is element-wise array equality, so changing an interval triggers a switch as surely
as changing the event. `["-e","wall"]` and `["-e=wall"]` are equal in effect but unequal as arrays,
causing one spurious restart if a user retypes the same intent differently. Accepted: normalising
would mean parsing async-profiler's option surface.

### One reconciler doing three jobs, not three units

Re-attach, ship, and prune run in the same pass because all three need the same PID lookup and the
same directory listing. Splitting them would race — pruning could delete a chunk mid-upload. This is
one responsibility ("reconcile this node's profiling state"), not three.

A `Type=oneshot` unit plus a timer, rather than a long-running service with a sleep loop: the timer
gives scheduling, catch-up, and restart semantics for free, and it makes the script a single pure
pass over a directory, which is directly testable against a fixture with a stubbed `asprof` and
`curl`. It runs as `cassandra`, the user owning the JFR files — no root, no new capability grant.

### Chunk completion: skip the newest file *while a session is live*

Under `loop=`, the newest unshipped file by mtime is by definition the one being written and every
older one is closed and complete. This is exact, not heuristic — strictly better than an mtime-age
threshold, which races on a slow flush. A secondary grace guard (mtime at least one loop interval
plus 10s in the past) covers rotation edge cases where two files land in the same second.

The "skip the newest" rule is conditional on a session actually running, and that qualifier is
load-bearing. After `profile stop` the reconciler detaches, which finalizes the in-flight chunk;
nothing will ever be newer than it, so an unconditional rule would strand the final minute of every
run until retention deleted it — unshippable, unfetchable, and unconvertible. With no session live
the grace guard alone is sufficient, because nothing is writing.

Upload outcomes are branched by status class, and **failures and rejections are counted separately
because they mean different things**: 5xx or network → leave the chunk as it is, retry next pass, a
rising count means Pyroscope or the network is unwell; 4xx → rename `.jfr.rejected`, never retry, a
rising count means chunks are being produced Pyroscope cannot parse, usually truncation after an
unclean shutdown. Never retrying a 4xx is what stops one malformed chunk wedging the queue — and
truncated chunks are expected, not hypothetical, since a `kill -9` leaves one behind.

A shipped chunk is marked with a `-shipped.jfr` **infix** rather than a `.jfr.shipped` suffix. The
suffix form was wrong: `fetch` and `flamegraph` list the node with `ls *.jfr` and feed the results to
`jfrconv`, which dispatches on the extension, so a suffix rename hid every already-uploaded chunk
from the CLI. Since the reconciler ships on a 60s timer, that is nearly the entire directory —
`--last 20` would have returned at most one chunk. The infix keeps a shipped chunk answering to
`*.jfr` while still excluding it from the shipping queue, which is expressed as `*.jfr` minus
`*-shipped.jfr`.

### Two pruning bounds

Age (default 60 minutes) and total bytes (default 2 GiB), pruning oldest-first and `-shipped.jfr`
before `.jfr.rejected` before unshipped. Unshipped chunks are age-pruned too, so an unreachable
Pyroscope cannot fill the disk.

Both bounds exist because the failure mode is not "lost profiles" but "dead node": filling `/mnt/db1`
takes Cassandra down via `disk_failure_policy`/`commit_failure_policy`. At the expected volume the
byte ceiling should never engage, which is exactly why it is set generously — if it engages, that is
itself the signal, surfaced by `edl_jfr_bytes_on_disk` in VictoriaMetrics.

JFR chunks land in a new `/mnt/db1/cassandra/profiles`, not the existing `artifacts/`, which
`setup_instance.sh` chmods to 777 and where operators hand-drop heap dumps and jstacks. Pointing an
auto-pruning process at that directory would silently delete a user's manual capture.

### Failure observability is pull-based

The event bus is a Kotlin object in the CLI JVM; the reconciler runs on the node, in bash, hours
after the CLI exited. No node-resident process can push a typed domain event at failure time. Rather
than pretend otherwise, the reconciler writes structured logfmt lines to journald — which already
flows through Fluent Bit to VictoriaLogs and is queryable in Grafana within seconds, with no new
infrastructure. `cassandra profile status` reads node state and emits typed `Event.Profiling.*`
when run, so an MCP client or Redis subscriber still sees structured typed data.

Every failure line carries the underlying tool's own message, bounded to 300 characters and stripped
of quotes. A hard-coded hint ("check PrivateTmp") narrows the search; only asprof's or curl's actual
words end it, and discarding them turns "profiling is broken" into an unanswerable question.

Counters travel a different route from the logs. The reconciler writes a Prometheus textfile for
whoever is already on the node, but **nothing on a node reads that file** — there is no
node_exporter, no textfile collector, and the OTel collector's receivers are `hostmetrics`, `otlp`,
and HTTP `prometheus` scrape jobs, none of which read files. So the counters are additionally pushed
as OTLP to `localhost:4318/v1/metrics`. The collector is a hostNetwork DaemonSet, so that endpoint
exists on every node, and its existing `metrics/otlp` pipeline stamps `cluster` and `host.name` and
remote-writes to VictoriaMetrics — no collector configuration change at all. Push rather than scrape
because the alternative was standing up an HTTP server on a node just to expose six numbers a bash
script already has in variables.

The payload is built with `yq`'s JSON constructor, not string concatenation, so a cluster name
containing a quote produces valid JSON rather than a malformed request. Every value crosses into the
document through `strenv()`, which yq treats as an opaque string rather than as part of the
expression — exactly the property `jq --arg` gives. Either tool would be safe here; the reconciler
uses `yq` throughout only because it already reads the desired-state document with it.

`status` itself is a read-only display command and prints with `println()` rather than being modelled
as an event, per the repo rule.

### Convert flame graphs on the node

`jfrconv` is at `/usr/local/async-profiler/bin/jfrconv` on every node and is not installed on the
developer's machine; `commands/CLAUDE.md` is explicit that cluster tooling runs remotely rather than
via local process invocation; and HTML is far smaller than raw JFR, so the transfer is cheaper.

`jfrconv` arguments pass through too, for consistency with the asprof decision and for the same
reason — not reimplementing another tool's option surface. Its grammar makes the reserved rule far
simpler than async-profiler's: inputs and output are **positional** and `-o/--output` selects the
*format*, so the rule is "no positional arguments, and `-o`/`--output` is reserved" — no enumeration,
no false-positive problem. `--last` selects completed chunks by mtime and passes all of them to one
conversion, since `jfrconv` accepts multiple inputs; a single one-minute chunk is rarely enough to
read.

### `--` separates tool options from passthrough

`cassandra profile start --loop 30s -- -e wall -i 10ms`. Conventional and unambiguous, and a typo
in a tool option (`--lop 30s`) fails at the CLI. The alternative, picocli's
`unmatchedOptionsArePositionalParams = true`, forwards that typo to the node where `asprof` rejects
it after an SSH round-trip — worse under fail-fast.

## Alternatives Considered

Presented by the `architect` agent at the design stop unless noted. The owner's choice is recorded
against each.

**Where the JFR shipper lives.** Options: (A) systemd oneshot + timer, packer-baked; (B) a
long-running systemd service with a sleep loop; (C) a Kotlin service in the CLI; (D) a K8s DaemonSet
with hostPath; (E) Grafana Alloy. **Chosen: A**, the architect's recommendation. C was rejected on
the facts — the CLI is run-and-exit while clusters live for hours, so it would ship JFR only during
the seconds a command happens to be running. D's selling point (config changes without an AMI
rebuild) is illusory here since the change requires a Cassandra AMI rebuild regardless, and it places
a container on the database node under benchmark. E was eliminated on capability grounds, not
preference: Alloy's component set has no JFR-file source, and its `pyroscope.java` component exposes
no wall-clock option at all. B loses to A because a timer gives scheduling and restart semantics free
and makes the script a single testable pass.

**Capture mechanism.** The original issue specified `-agentpath` at JVM startup with a combined
`event=cpu,wall=...,alloc=...` loop session. **Owner override — rejected after research.** One
session per JVM plus the jfr-parser weight bug means a combined cpu+wall session yields a corrupted
CPU profile, and a startup-fixed configuration cannot be changed without restarting Cassandra. The
owner directed runtime control instead. This voided two of the issue's original acceptance criteria
and is the largest single deviation from the issue as filed.

**Handling the jfr-parser bug.** Options weighed with the owner: `--nobatch` in one combined session;
separate time-alternated recordings; dropping wall from the default set; splitting each JFR chunk in
the shipper; accepting the corruption. **Chosen: one mode per session, switched at runtime** — which
subsumes the "separate recordings" option and needs no JFR splitter. `--nobatch` was investigated and
**withdrawn as actively harmful**; it changes the emitted event type and causes the merge it appears
to prevent. Accepting the corruption was rejected: a misleading flame graph is worse than an absent
one on a benchmarking tool.

**Mode representation.** The architect proposed a Kotlin enum (`--event cpu|wall|alloc|cpu-alloc`)
mapping to asprof arguments, which would have made cpu+wall structurally unrepresentable. **Owner
override — rejected** in favour of passing async-profiler's own parameters through. The owner's
reasoning is that the tool should not reimplement asprof's interface; the consequence accepted is
that cpu+wall becomes expressible again and is handled by documentation rather than by a guard. A
side benefit: every async-profiler event, including `lock` and anything future versions add, is
available with no code change.

**Reserved-parameter handling.** Options: silently override user-supplied `-f`/`-o`; supply defaults
the user may override; reject. **Owner directive: reject.** The override option's failure mode is the
worst available — profiling appears to work, chunks land where the shipper never looks, and Pyroscope
stays silently empty with no error anywhere.

**AC #6 (typed event on shipping failure).** Options: pull-based via journald + metrics + typed
events at `status` read time; keep the criterion as written and satisfy it at read time; push to a
REST endpoint on `easy-db-lab server`. **Chosen: pull-based, with the criterion rewritten** to
describe it honestly. Push was rejected because the server is optional and usually not running, and
it would make every database node depend on a process on the operator's machine.

**Auto-start at cluster-up.** Options: auto-start with cpu; start nothing until explicitly asked;
auto-start with an `init` flag to disable. **Chosen: auto-start with `-e cpu`**, seeded by
`SetupInstance` rather than defaulted by the reconciler — the CLI must write the config file anyway
(only it knows the Pyroscope URL and cluster name), so seeding the mode in the same write adds no new
failure point.

**Restart behaviour.** Options: node-resident supervisor re-attaches; profiling stops until manually
restarted; stops but surfaces loudly. **Chosen: supervisor.** This is the PID-tracking supervisor the
original issue set out to avoid, now justified — runtime control requires attach, and attach dies
with the JVM.

**#872's relationship.** Options: fold into one command surface; keep separate; keep separate but
build a foundation. **Chosen: fold in.** Note the owner was asked this twice and answered differently
each time, correctly: under the original always-on design the two were genuinely independent, since
#872 produced local HTML via the existing `flamegraph` script and never touched JFR or Pyroscope.
Once #880 became runtime-controlled remote asprof invocation, they became the same command surface.

**`jq` vs `yq` on the node.** The architect first recommended adding `jq` (following CLAUDE.md
literally, and for clean NUL-delimited output), then withdrew it on discovering `yq` supports
`-0`/`--nul-output`. Both are now on the base image at the owner's direction, so availability is not
a factor; the reconciler uses `yq` because it already reads the desired-state document with it.

## Domain Facts

Supplied by the `cassandra-expert` agent, and by source research into async-profiler, OpenJDK, and
Grafana's jfr-parser. Recorded because several are non-obvious and shaped decisions above.

- **CPU sampling cost is bounded by core count; wall sampling cost by thread count.** A perf_events
  CPU sample only fires on a running thread — at most ~400 samples/s on 4 vCPU at a 10ms interval,
  regardless of thread count. A wall tick targets threads regardless of run state, so hundreds of
  Cassandra pool threads at the same interval is a far higher sample rate. This asymmetry is why wall
  is treated as its own mode rather than something to leave on by default.
- **`alloc=<n>` is a sampling interval in bytes, not a size filter.** HotSpot implements it by
  shrinking the TLAB so the next allocation crosses the sample boundary; an interval below the
  thread's natural TLAB forces extra slow-path refills on the allocation fast path.
- **`jdk.ExecutionSample` and `profiler.WallClockSample` are distinct JFR types** as of
  async-profiler 4.0 (release note #1007). `jfrMetadata.cpp` gives `profiler.WallClockSample` two
  extra fields, `samples` and `timeSpan`. Grafana's parser maps them to `process_cpu` and `wall`
  respectively, and the TLAB allocation types to `memory`, so a session's events do separate cleanly
  into independently selectable Pyroscope profile types. The corruption described above is a value
  bug, not a type-routing bug.
- **Thread identity does not survive Pyroscope ingest.** The JFR carries `sampledThread` and
  jfr-parser decodes it (`parser/types/thread.go` holds real `JavaName`/`OsName`), but the pprof
  converter never reads it — `addStacktrace()` has no thread parameter, and series labels come from
  the Pyroscope Java agent's dynamic context labels, which do not apply here. The obvious workaround
  is closed off: `profiler.cpp:927` sets `_add_thread_frame = args._threads && args._output !=
  OUTPUT_JFR`, deliberately putting thread identity in the constant pool in JFR mode. So `-t` buys
  nothing through Pyroscope, and `jfrconv --threads` on a fetched chunk is the only route to
  Cassandra thread-pool attribution (`ReadStage-3`, `CompactionExecutor:4`) — the strongest reason
  the `flamegraph` path exists alongside continuous shipping.
- **Native `-agentpath` agents load during VM initialization, before any `-javaagent` premain.** No
  ordering constraint exists relative to Cassandra's other flags. Not directly used by this design
  (capture moved out of startup) but it establishes that the AxonOps and MAAC Java agents already
  injected by `cassandra.in.sh` do not conflict with async-profiler.
- **No supported Cassandra version starts JFR by default** — the flight-recorder lines in
  `jvm.options`/`jvm-server.options` ship commented out. async-profiler does not use the JVM's
  FlightRecorder subsystem anyway; with `-o jfr` it writes JFR-format chunks from its own buffers.
  Nothing collides.
- **async-profiler still supports profiling JDK 8.** The "JDK 11+" line in its README is a
  *build* requirement; the CI matrix tests profiled JVMs on Java 8 through 25, and 27 as of v4.5.
  Allocation profiling on Java 8 additionally requires a JDK with `libjvm.so` debug symbols — already
  satisfied here, since `install_jdks.sh` installs `openjdk-8-dbg` through `openjdk-21-dbg`.
- **JFR write volume is small** — stack traces are interned in constant pools, so a steady-state
  sample costs roughly 10–20 bytes. Disk *bandwidth* is a non-issue against a Cassandra benchmark;
  disk *capacity* is the real hazard, hence bounded retention.
- **`--loop` requires a timestamp pattern in the filename** or each rotation overwrites the previous
  chunk. `%p` is added alongside `%t` so a restart within the same wall-clock second cannot collide.
- **`asprof status <pid>` and `asprof metrics <pid>` exist**, the latter emitting Prometheus format
  directly — so profiler-side numbers need no hand-rolled counters.
- **The `setcap` blocks HotSpot dynamic attach for `jcmd`/`jstack`/`jmap`** on JDK 9–22, as described
  in Context. Worth recording because it means those tools are unavailable against Cassandra on these
  nodes, independent of this change.

### What async-profiler 4.5 changes

The facts above were verified against 4.3. The nodes now install 4.5. Everything this design leans on
is unchanged across the two: the release-asset naming, the extracted directory name, the internal
layout (`bin/asprof`, `bin/jfrconv`, `lib/libasyncProfiler.so`), `asprof status`/`metrics`, `--loop`
parsing, the `jdk.ExecutionSample` and `profiler.WallClockSample` type names, and jattach's `/tmp`
fallback. The `src/jattach/` subtree is byte-identical between the two releases. Four things do
change, and all four matter here.

- **Wall-clock samples are now flushed on dump and on JFR chunk rotation** (upstream #1746, adding
  `WallClock::flush()` at `src/wallClock.cpp:192-202`). This is a real accuracy gain for this design,
  not a cosmetic fix. In `WALL_BATCH` mode an idle thread buffers up to `MAX_IDLE_BATCH` (1000)
  samples before emitting an event, and at 4.3 those buffers were flushed only when `timerLoop()`
  exited. Every `--loop` rotation could therefore drop up to 1000 buffered idle samples per thread,
  and rotation is exactly how this design ships data. Wall profiles from 4.3 undercounted idle time;
  4.5 does not.
- **The long time-to-safepoint pause on attach is fixed** (upstream #1406, commit `589e9c8`), by
  parsing only essential libraries at load time. It materially shortens the synchronous work jattach
  does on the target's attach-listener thread. **It does not bound it.** `read_response()` at
  `src/jattach/jattach_hotspot.c:125` is still a bare blocking `read()` with no `SO_RCVTIMEO`, and
  jattach's only timeout covers waiting for the attach socket file to appear, not waiting for a
  response. The reconciler's EXIT/TERM trap, paired with the unit's `TimeoutStartSec`, remains the
  only bound on a wedged attach. **Do not read the upstream fix as a reason to remove that trap.**
- **`--nobatch` is now an accepted `asprof` flag.** At 4.3 it was absent from the CLI parser and
  `asprof` exited 1; 4.5 adds it to the usage string and the pass-through list (`src/main/main.cpp`).
  The design had been relying on that rejection without knowing it, so easy-db-lab now reserves
  `--nobatch` itself, in `AsprofArgValidator`, on the same ground the output-format flags are
  reserved: it changes which JFR event types reach the shipper.
- **Two `--cstack` values changed meaning in 4.4.** `--cstack dwarf` is now an alias for `vm` on
  HotSpot — `src/profiler.cpp:938-940` promotes both `CSTACK_DEFAULT` and `CSTACK_DWARF` to
  `CSTACK_VM` whenever VMStructs are available, with no error and no warning; real DWARF requires
  `features=agct`. Upstream's `docs/StackWalkingModes.md` is stale and still promises otherwise, so
  do not cite it. `--cstack lbr` was removed, and `src/arguments.cpp:335-337` falls through to
  `_cstack = CSTACK_NO` for any unrecognised value, so passing `lbr` now silently disables native
  stack collection rather than failing. Both are documented as traps in the user guide. Neither is
  policed: this is a passthrough tool and users own their own flags.

One naming correction while recording all this: async-profiler's event-type enum members are
`EXECUTION_SAMPLE` and `WALL_CLOCK_SAMPLE` (`src/event.h:17-18`). `EC_CPU` and `EC_WALL` do not exist
upstream.

## Risks / Trade-offs

**A future contributor adds a combined cpu+wall mode** → The failure is silent and up to three orders
of magnitude. Mitigated by a source comment on the reserved-set constant and in the reconciler script
citing `jfr-parser` v0.18.0 `pprof/parser.go:56`, plus the user guide and `--help` text. With the
mode enum rejected, these comments are the only signpost, so they matter more, not less.

**Attach depends on jattach's `/tmp` fallback** → Adding `PrivateTmp=yes` or `RootDirectory=` to
`cassandra.service`, or setting `-Djava.io.tmpdir`, would break attach at a distance, long after
whoever made the change moved on. Mitigated by a comment in `cassandra.service` recording the
dependency, and by the reconciler reporting a distinguishable error rather than a generic failure.

**Reconcile latency** → Worst case is roughly one reconcile interval with no profiling after a
Cassandra restart or a mode switch. Accepted as the price of attach-based capture; documented so it
is not reported as a bug.

**`kill -9` or OOM loses the in-flight chunk** → async-profiler finalizes constant pools at chunk
close, so an unclean death loses the current chunk — the minutes before an OOM being exactly the ones
most wanted. Partially mitigated by a short default loop interval; a shorter interval trades against
per-chunk overhead, since each chunk re-emits its own constant pool. Tunable in one field. Note this
is a regression against the current agent's ~10s HTTP push, and it is accepted knowingly. Mode
switches do *not* have this hole, since `asprof stop` finalizes cleanly.

**Disk pressure kills the node** → Called out separately from other risks because the failure mode is
Cassandra death, not lost profiles. Mitigated by two-bound pruning including unshipped chunks, and by
`edl_jfr_bytes_on_disk` making the approach visible before it bites.

**The bare-format-token validator is imperfect** → Documented above as a known limit rather than
presented as airtight; `-o jfr` appended last is the backstop.

**Two profiling mechanisms coexist in the fleet** → Cassandra moves to runtime-controlled
async-profiler while the sidecar, stress, Presto, and Spark/EMR keep the Pyroscope Java agent. The
user guide must document both. Resolved only by a follow-up covering the remaining JVM workloads.

**A reserved-parameter rejection frustrates a user with a legitimate need** → The one knob users
actually want from the reserved set is rotation, and it is available as `--loop` on the command
itself. Every profiling parameter async-profiler offers remains reachable.
