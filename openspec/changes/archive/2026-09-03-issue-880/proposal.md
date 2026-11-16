## Why

Cassandra's continuous profiling runs through the Pyroscope Java agent, injected into `JVM_OPTS` by
`packer/cassandra/cassandra.in.sh` at JVM startup. That agent accepts exactly one primary
`profiler.event` at a time — cpu XOR wall — and the event is fixed for the JVM's lifetime, so
changing what is being profiled means restarting Cassandra. On a benchmarking rig that is the wrong
trade: a restart discards page cache and compaction state, which is precisely what an operator is
trying to observe.

The fix is to stop profiling at JVM startup and control async-profiler at runtime instead. The
profiler attaches to the running JVM on demand, and the event set is changed by stopping and
restarting the profiler rather than the database.

## What Changes

- **Remove the Pyroscope Java agent injection** from `packer/cassandra/cassandra.in.sh`. Nothing
  profiling-related remains in Cassandra's JVM startup path.
- **Add a `cassandra profile` command group** — `start`, `stop`, `status`, `fetch`, `flamegraph` —
  scoped by `HostsMixin` (all Cassandra nodes by default, `--hosts` for a subset).
- **async-profiler's own parameters pass through untouched.** `cassandra profile start -- -e wall
  -i 10ms` sends exactly those arguments to `asprof`. The tool does not model, enumerate, or
  reimplement async-profiler's option surface. `jfrconv` arguments pass through the same way for
  `flamegraph`.
- **Reject the output-plumbing parameters the tool owns**, at the CLI, before any SSH round-trip:
  the output-file family (`-f`/`--file`/`file=`), the output-format family (`-o`/`--output` and bare
  format tokens such as `jfr`/`collapsed`/`flamegraph`), rotation (`--loop`/`loop=`), and auto-stop
  (`-d`/`--duration`/`--timeout`/`timeout=`). Rejection covers both of async-profiler's spelling
  systems and values that smuggle a reserved option after a comma. This rejects conflicting input;
  it removes no capability — rotation remains available as a first-class `--loop` on the command
  itself.
- **Add a node-resident reconciler** (systemd timer) that on each pass re-attaches the profiler if
  the desired arguments are not running against the current Cassandra PID, ships completed JFR chunks
  to Pyroscope's `/ingest`, and prunes local chunks under both an age bound and a byte bound.
- **Profiling survives a Cassandra restart** without operator action and without a CLI process
  running, because the reconciler re-establishes it.
- **Auto-start at cluster-up.** `SetupInstance` seeds the desired state with `-e cpu` enabled, so a
  cluster is profiling CPU as soon as it is up.
- **BREAKING**: `-Dpyroscope.*` JVM options and the `PYROSCOPE_PROFILER_EVENT` environment variable
  no longer have any effect for Cassandra. Clusters are ephemeral, so no migration path is needed.
- **Absorbs #872** (`cassandra asprof` on-demand flame graphs) as the `fetch` and `flamegraph` verbs,
  rather than building a second remote-asprof invocation path.
- **Documentation**: `docs/user-guide/profiling.md` is rewritten — it currently documents the
  Java-agent path and states that cpu and wall are mutually exclusive, both of which become false.
  It must also explain that combining a cpu event with `--wall` in one recording corrupts CPU sample
  weights, and direct the reader to switch modes instead.

## Capabilities

### New Capabilities
- `profiling`: Runtime-controlled continuous profiling of Cassandra nodes — attaching and detaching
  async-profiler against the running JVM, passing async-profiler's own parameters through while
  reserving output plumbing, reconciling desired against actual profiling state on each node,
  shipping JFR chunks to Pyroscope, bounded local retention, and on-demand flame-graph conversion.

### Modified Capabilities
<!-- None. -->

No existing capability's requirements change. Two committed specs cover Pyroscope and both remain
true: `trino` REQ-TRN-005 patches Trino deployments with the Pyroscope Java agent, and `sidecar-otel`
instruments the Cassandra Sidecar with it. Both are out of scope here and are untouched — the
sidecar reads its own environment file, not `/etc/default/cassandra`. `observability` covers OTel,
Grafana, and Cilium and says nothing about profiling, which is why this is a new capability rather
than an amendment.

## Impact

**Removed from the startup path**: the Pyroscope block in `packer/cassandra/cassandra.in.sh`. That
file is ~150 lines of untested shell that runs on every JVM start and can abort startup, so it gets
shorter and no more complex.

**New on the node**: a reconciler script under `packer/cassandra/bin/`, plus a systemd service and
timer under `packer/cassandra/services/`. Desired state at `/etc/easy-db-lab/profiling.json`;
effective state and JFR chunks under a new `/mnt/db1/cassandra/profiles`. Deliberately not
`artifacts/`, which is world-writable and holds operator-dropped heap dumps that auto-pruning must
never delete.

**New in Kotlin**: a `profiling` command group under `commands/cassandra/`, a
`CassandraProfilingService` owning every `RemoteOperationsService` call, an argument validator, a
serializable profiling-config value type, and `Event.Profiling.*` typed events.

**Modified in Kotlin**: `SetupInstance.setupCassandraSystemdEnv` is replaced by a profiling-config
seeder — `/etc/default/cassandra` currently holds only the two Pyroscope keys, and `CLUSTER_NAME` is
used solely by the agent block being deleted.

**Unchanged**: `PyroscopeManifestBuilder` — the Pyroscope server remains the ingest target.
`install_pyroscope_agent.sh` must stay, because `StressJobService` hostPath-mounts
`/usr/local/pyroscope` into stress pods. Grafana Alloy's `pyroscope.ebpf` sampler is untouched and
remains the only continuous-profiling coverage for non-JVM workloads.

**Dependencies**: `jq` is added to the base image at the owner's direction — the reconciler does not
require it (it uses `yq`, already installed, for every JSON operation), but `jq` is generally useful
on a node and its absence had been a standing awkwardness. `asprof`/`jfrconv` ship with the existing
async-profiler 4.5 install. **Both AMIs therefore rebuild**: base for `jq`, and Cassandra for the
reconciler, its systemd units, and the modified `cassandra.in.sh`.

**Follow-ups this change does not take on**: `cassandra-sidecar`, stress jobs, and Spark/EMR still
run the old Pyroscope Java agent, leaving two profiling mechanisms in the fleet until a separate
issue covers the remaining JVM workloads as a family.
