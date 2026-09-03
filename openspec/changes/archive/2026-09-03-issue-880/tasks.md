## 1. Argument validation (pure logic, no I/O — do this first)

- [x] 1.1 Add `Constants.Profiling` holding the profile directory, the async-profiler and jfrconv
      binary paths, the desired/effective state paths, and the retention, byte-ceiling, and loop
      defaults. No magic numbers or paths inline.
- [x] 1.2 Write `AsprofArgValidator` — returns the offending argument or null. Rules, left to right:
      exact match against a reserved CLI spelling; `<reserved>=<value>` in either spelling system;
      every token split on commas with each fragment re-checked in agent form; a bare output-format
      token rejected only in flag position (preceding token does not start with `-`); any argument
      containing a newline or NUL rejected.
- [x] 1.3 Add a KDoc comment on the reserved-set constant citing `jfr-parser` v0.18.0
      `pprof/parser.go:56` and explaining why a combined cpu+wall mode must never be added. This is
      the signpost that replaces the rejected mode enum — do not omit it.
- [x] 1.4 Fast-tier tests for the validator: every reserved spelling in all four forms (`-f x`,
      `-f=x`, `--file=x`, `--file x`); every comma-smuggled variant (`-e cpu,file=/tmp/x`,
      `-i 10ms,loop=1h`); bare format tokens both in and out of flag position; newline and NUL
      arguments; and a permitted set that must NOT trip — `-e wall`, `-i 10ms`, `--alloc 2m`,
      `--lock 10ms`, `--cstack vm`, `--jfrsync profile`, `--chunksize 8m`, `--include collapsed`.
      `--cstack vm` rather than `--cstack dwarf`: async-profiler 4.4 made `dwarf` an alias for `vm`
      on HotSpot, so listing it would read as an endorsement of a flag whose meaning moved.
- [x] 1.5 Write `JfrconvArgValidator` — simpler: reject any positional argument and the output-format
      argument; everything else passes. Fast-tier tests to match.
- [x] 1.6 Reserve `--nobatch` (and its agent spelling `nobatch`, and the comma-smuggled form) with
      its own rejection message naming the event-type change, not the output plumbing. async-profiler
      4.3 rejected the flag itself and 4.5 accepts it, so the backstop this design relied on is gone.
      Fast-tier tests for both spellings, the comma-smuggled form, and the message.

## 2. Configuration value types

- [x] 2.1 Add a `@Serializable` `ProfilingConfig` data class (kotlinx.serialization) holding
      `enabled`, `asprofArgs` as a `List<String>`, `loopInterval`, `retentionMinutes`, `maxBytes`,
      `pyroscopeUrl`, `clusterName`, `updatedAt`. Class-level KDoc explaining why args are a list and
      not a string.
- [x] 2.2 Add a parser for the effective-state document the reconciler writes, tolerant of malformed
      and truncated input.
- [x] 2.3 Fast-tier tests: config JSON round-trip preserving exact argument tokenization; the
      Pyroscope URL assembled from the control-node IP and `Constants.K8s.PYROSCOPE_PORT`; effective
      state parsing including malformed, truncated, and empty input.

## 3. Typed events

- [x] 3.1 Add an `Event.Profiling` sealed interface in `events/Event.kt` with `@Serializable`
      data-only members: `Started`, `Stopped`, `ShippingFailed`, `ChunksRejected`, `ChunksFetched`,
      `FlamegraphCreated`. `ShippingFailed` and `ChunksRejected` are separate types — they mean
      different things and conflating them hides a real signal. `isError()` true where appropriate.
- [x] 3.2 Formatting lives in `toDisplayString()`; fields stay structured. Do not use
      `Event.Message` or `Event.Error`.
- [x] 3.3 Fast-tier tests for display strings and serialization round-trip.

## 4. The reconciler script (node-resident, where most of the logic lives)

- [x] 4.1 Write `packer/cassandra/bin/edl-profiling-reconcile`. Single pure pass, idempotent, exits 0
      on every non-fatal condition.
- [x] 4.2 Read desired state with `yq -0` into a bash array and expand as `"${USER_ARGS[@]}"` — argv,
      never a shell string. Confirm the exact `yq` flag combination on a real node during
      implementation. Missing file → idle. Corrupt file → structured error, leave any running session
      untouched, exit 0.
- [x] 4.3 Resolve the Cassandra PID with the existing `/usr/local/bin/cassandra-pid`. Do not
      reimplement PID lookup with pgrep.
- [x] 4.4 Implement the decision table: query `asprof status <pid>` for liveness (never trust a file);
      discard any effective-state record whose PID differs from the current one; then disabled+running
      → stop; enabled+not-running → start; running+spec matches → no-op; running+spec differs or
      unknown → stop then start.
- [x] 4.4a Gate every asprof call on `/usr/local/bin/cassandra-ready` before signalling the target.
      The probe reads the socket table for a listener on the native transport port; it must never
      connect to an assumed address, because `rpc_address` is the node's private IP, not loopback.
      jattach signals `SIGQUIT`, and a process that has not installed a handler for it yet is killed
      by it, so a pass that attached to a starting database killed the node. A pass that wanted to
      act and could not logs `jfr_attach_deferred`, counts `edl_jfr_attach_deferred_total`, records
      `attachDeferred` in effective state, and still ships, prunes and reports.
- [x] 4.5 Compose the start command with tool arguments appended last:
      `sudo asprof start "${USER_ARGS[@]}" -o jfr --loop <loop> -f <dir>/cassandra-%p-%t.jfr <pid>`.
      `%t` is mandatory — without it each rotation overwrites the previous chunk.
- [x] 4.6 Ship completed chunks: list unshipped `*.jfr` by mtime, **skip the newest while a session
      is live** (under rotation it is the one being written; after a stop nothing is writing and that
      chunk is the finalized last interval of the run, which must not be stranded), apply a grace
      guard of one loop interval plus 10s, then POST raw bytes as
      `application/octet-stream` to `<pyroscopeUrl>/ingest` with `format=jfr`, url-encoded
      `name=cassandra{hostname=…,cluster=…}`, and `from`/`until` derived from mtime and the loop
      interval. Do not gzip; JFR is already compressed. No protobuf labels needed — ours are static.
- [x] 4.7 Branch on status class: 2xx → rename with a `-shipped.jfr` **infix**, so the chunk keeps
      answering to the `ls *.jfr` listing `fetch`/`flamegraph` use and keeps the extension jfrconv
      dispatches on, while dropping out of the shipping queue (`*.jfr` minus `*-shipped.jfr`);
      5xx or network → leave it alone for the next pass, increment the failure counter;
      **4xx → rename `.jfr.rejected`, never retry**, increment the rejection counter separately.
      Log the tool's own message on every failure branch, bounded and quoted.
- [x] 4.8 Prune under both bounds in the same pass: age past `retentionMinutes`, and total bytes over
      `maxBytes` deleting oldest-first, `-shipped.jfr` before `.jfr.rejected` before unshipped.
      Age-prune unshipped chunks too, so an unreachable Pyroscope cannot fill the disk.
- [x] 4.9 Write structured logfmt failure lines to journald, and a Prometheus textfile with
      `edl_jfr_ship_failures_total`, `edl_jfr_ship_rejected_total`, `edl_jfr_attach_failures_total`,
      `edl_jfr_chunks_pending`, `edl_jfr_bytes_on_disk`,
      `edl_jfr_ship_last_success_timestamp_seconds`, folding in `asprof metrics <pid>`.
- [x] 4.9a **Push** those counters as OTLP to the node-local collector at
      `localhost:4318/v1/metrics`, built with `yq`'s JSON constructor and `strenv()` so a hostname or
      cluster name containing a quote cannot corrupt the body. Nothing on a node reads a Prometheus
      textfile — no node_exporter, no textfile collector, and the collector's receivers are
      `hostmetrics`, `otlp`, and HTTP scrape jobs — so the push is the only route to VictoriaMetrics.
      Adds no node dependency: `yq` is already in the base AMI, which this change does not rebuild.
      A failed export logs and never fails the pass.
- [x] 4.10 Rewrite the effective-state document at the end of each pass, recording the arguments
      **actually applied** (never the ones requested), `desiredEnabled` beside `running`, and
      `attachFailures`/`lastAttachError`. Re-query `asprof status` after a detach rather than
      asserting it worked, and never start on top of a session that refused to stop.
- [x] 4.9b Prefix every log line with its syslog priority (`<3>`/`<4>`/`<6>`) and state
      `SyslogLevelPrefix=yes` on the unit. Severity in VictoriaLogs comes from journald's PRIORITY
      alone — Fluent Bit's mapper reads it and ships the line as an opaque body — so without this
      every attach and shipping failure arrives as `severity=INFO`.
- [x] 4.9c Log the session lifecycle, not only its failures: `jfr_session_started` on a confirmed
      attach and `jfr_session_stopped` on a confirmed detach, both only on a transition, plus a
      cumulative `edl_jfr_session_starts_total` so a node stuck re-attaching every pass is visible as
      a rate instead of as silence.
- [x] 4.9d Make pruning account for what it deletes: log each age-based deletion (warn when the chunk
      never shipped, info when it had) and count `edl_jfr_pruned_unshipped_total`,
      `edl_jfr_pruned_for_age_total`, `edl_jfr_pruned_for_size_total`. The age bound is how an
      operator's profiles get destroyed while shipping is failing, and it did it with a bare `rm -f`.
- [x] 4.11 Add the same cpu+wall warning comment from task 1.3 to this script.
- [x] 4.12 Track "is there a record for this PID" as its own flag rather than inferring it from a
      non-empty argument fingerprint: an empty argument list fingerprints to the empty string, so the
      inference restarted a converged session on every pass.

## 5. Systemd units and node layout

- [x] 5.1 Add `packer/cassandra/services/edl-profiling-reconcile.service` — `Type=oneshot`,
      `User=cassandra` (the user owning the JFR files; no root needed).
- [x] 5.2 Add `packer/cassandra/services/edl-profiling-reconcile.timer` — `OnBootSec`,
      `OnUnitActiveSec`, `Persistent=true`.
- [x] 5.3 Add a comment to `packer/cassandra/services/cassandra.service` recording that runtime
      profiling attach depends on the absence of `PrivateTmp`/`RootDirectory` and on
      `java.io.tmpdir` not being overridden. Cheap insurance against breaking attach at a distance.
- [x] 5.4 Create `/mnt/db1/cassandra/profiles` owned `cassandra:cassandra` in
      `src/main/resources/.../setup_instance.sh`, alongside the existing logs and artifacts mkdirs.
      Deliberately NOT `artifacts/`, which is 777 and holds operator-dropped heap dumps that
      auto-pruning must never delete.
- [x] 5.5 Confirm the packer provisioners already install `bin/` and `services/` contents; wire the
      new files in if not.

## 6. Bash test tier (no Docker — highest-value tests in this change)

- [x] 6.1 Add `packer/cassandra/bin/edl-profiling-reconcile.test.sh` and a plain Gradle `Exec` task,
      following the existing `testCassandraBuildPlan` / `testCassandraResolveRef` precedent.
- [x] 6.2 Cover the reconciler decision table with stubbed `asprof` and `cassandra-pid` on `PATH`:
      not-running→start; matching→no-op (and running twice changes nothing); differing→stop+start;
      disabled+running→stop; PID-mismatched record discarded; corrupt config leaves the session
      untouched; Cassandra down still ships and prunes.
- [x] 6.2a Cover the readiness gate: a pass against a present-but-not-ready pid makes no asprof call
      at all, logs the deferral, counts it apart from both refused attaches and an absent database,
      and still ships and prunes; a ready pid attaches; a deferred pass does not erase a session it
      could not ask about. Assert `cassandra-ready` itself against a port nothing is listening on, a
      listener bound to a non-loopback address, and the uptime backstop.
- [x] 6.3 Cover shipping and pruning against a fixture directory with a stubbed `curl`: newest chunk
      never shipped while a session is live; the final chunk of a stopped session shipped; a shipped
      chunk still listed by `ls *.jfr` and never re-uploaded; a chunk inside the grace window not
      shipped; 200→`-shipped.jfr`; 500→left for retry; 400→`.jfr.rejected` and never retried; a
      network failure carrying curl's exit code and message; age pruning deletes past the window and
      spares younger; the byte ceiling prunes oldest-first when age alone would not; unshipped chunks
      age-pruned.
- [x] 6.4 Prove argv fidelity end to end: an argument containing spaces, quotes, and a newline
      reaches the stubbed `asprof` as exactly one argument.
- [x] 6.5 Cover the attach/detach failure contract with `STUB_ASPROF_START_EXIT` /
      `STUB_ASPROF_STOP_EXIT` and a stateful `asprof status` stub: a failed attach logs
      `jfr_attach_failed` with the hint *and* asprof's own message, records `running: false` with no
      applied arguments, counts an attach failure, and still exits 0; a failed detach logs, keeps the
      applied arguments, starts nothing on top, and converges on the next pass; a hostile tool
      message cannot corrupt the effective-state JSON.
- [x] 6.6 Cover the OTLP counter export: the payload reaches `localhost:4318/v1/metrics`, is a valid
      OTLP document naming every exported series, carries `host.name` and the cluster, sends counters
      as cumulative monotonic sums and point-in-time values as gauges, and an unreachable collector
      logs without failing the pass.
- [x] 6.7 Cover an enabled desired state with an **empty** argument list — what an MCP client means by
      `asprofArgs: []`. The first pass attaches; every later pass must leave that session alone.
      Emptiness must not be the "no record for this PID" signal, or the node tears down and
      re-attaches a profiler to the database JVM every pass forever, losing samples across each
      detach and resetting the session age.
- [x] 6.8 Pin the Pyroscope ingest window: `from`/`until` are computed from the chunk's mtime and the
      configured loop interval, not from the current time and not from a fixed 60s. Getting this wrong
      places every profile at the wrong point on Grafana's timeline while the upload still returns 2xx
      and every counter stays clean. Assert both the default and a non-default `--loop`.
- [x] 6.9 Assert the node's half of the bounds contract: `retentionMinutes: -5` logs
      `config_unreadable`, exits 0, and leaves a running session untouched.
- [x] 6.10 Assert the syslog priority prefix on log lines — `<3>` for error, `<4>` for warn, `<6>` for
      info — since severity in VictoriaLogs is derived from journald's PRIORITY and nothing else.
- [x] 6.11 Assert the session lifecycle is observable: a confirmed attach and a confirmed detach each
      log once, a converged pass logs nothing, and `edl_jfr_session_starts_total` counts restarts.
- [x] 6.12 Assert pruning accounts for itself: an age-pruned unshipped chunk warns and increments
      `edl_jfr_pruned_unshipped_total`; an age-pruned shipped chunk logs at info;
      `edl_jfr_pruned_for_age_total` / `_for_size_total` track each bound.

## 7. Service layer and commands

- [x] 7.1 Extract `String.shellQuote()` from `commands/exec/ExecRun.kt:126` into a shared
      `ShellQuoting.kt` with its tests; this change is its second consumer. Needed for the fetch and
      flamegraph paths, which do build shell strings — not for the start path, which uses argv.
- [x] 7.2 Add `CassandraProfilingService` in `services/` owning every `RemoteOperationsService` call:
      writing desired state (upload then atomic `sudo mv`, the pattern `SetupInstance` already uses),
      reading desired and effective state, listing completed chunks (shipped ones included; the
      in-flight one excluded only while a session is live), fetching chunks, and invoking `jfrconv`
      remotely. Commands must not call SSH directly, per `commands/CLAUDE.md`.
- [x] 7.3 Add the `Profiling` parent command under `commands/cassandra/`, registered in
      `Cassandra.kt`'s subcommands, mirroring the existing `stress/` group.
- [x] 7.4 `ProfilingStart` — `--loop`, `--retention`, `--max-bytes`, `@Mixin HostsMixin`, plus
      trailing passthrough args after `--`. Validate with `AsprofArgValidator` and fail before any
      SSH. Include the cpu+wall warning in the command description so it reaches `--help`.
- [x] 7.4a Refuse `--retention` and `--max-bytes` values the node cannot act on, before any SSH
      round-trip. The node's contract is stricter than the CLI's and fails silently: a negative bound
      makes every reconcile pass discard the document at `config_unreadable` with nothing attached,
      and zero puts the prune cutoff at the current instant.
- [x] 7.5 `ProfilingStop` — reads the node's current desired state and writes it back with
      `enabled: false` (explicit disabled state, not a deleted file), preserving every other field.
      Rebuilding from defaults would reset the retention bounds and prune the profiles the stop was
      run to keep.
- [x] 7.5a Make reading desired state answer three ways — configured, unconfigured, unreadable —
      rather than a nullable config, log the decode failure with the node and path, and have `stop`
      emit `Profiling.DesiredStateUnreadable` when it has no bounds to preserve. Collapsing the last
      two made the fallback this command exists to prevent happen silently.
- [x] 7.6 `ProfilingStatus` — read-only display command: `println()` directly, NOT modelled as an
      event. Reports desired and attached state as separate lines, the user's args verbatim, the full
      rendered command line including tool-appended args, PID and session age, chunks
      pending/shipped/rejected, bytes on disk, last ship and attach errors. Emits typed events for
      surfaced failures, including `Profiling.AttachFailed` when profiling is wanted and nothing is
      attached. `render` is internal so its three branches are asserted directly.
- [x] 7.7 `ProfilingFetch` — `--last`, downloads completed chunks to `./profiles/<host>/`.
- [x] 7.8 `ProfilingFlamegraph` — `--last`, `--format`, trailing jfrconv passthrough after `--`,
      validated by `JfrconvArgValidator`. Converts on the node (jfrconv is not installed locally),
      passing all selected chunks to one conversion, then downloads the result.
- [x] 7.9 All commands use named options only; no `@Parameters` except the trailing passthrough.
      Class-level KDoc on every new class.
- [x] 7.10 Register all five commands in `di/CommandsModule.kt` and in `McpToolRegistry`'s
      `mcpCommandClasses` — `@McpCommand` is inert on its own. Teach the schema generator and the
      argument mapper to expose a list-typed `@Parameters` field as a JSON string array, or the
      passthrough that is the point of `start` and `flamegraph` is unreachable over MCP.

## 8. Remove the old mechanism

- [x] 8.1 Delete the Pyroscope agent block from `packer/cassandra/cassandra.in.sh`. Leave the
      AxonOps, MAAC, GC-logging, log-dir, and ring-delay blocks untouched.
- [x] 8.2 Replace `SetupInstance.setupCassandraSystemdEnv` with a profiling-config seeder writing
      `enabled: true` and `["-e", "cpu"]`. `/etc/default/cassandra` currently holds only the two
      Pyroscope keys and `CLUSTER_NAME` is used solely by the block being deleted, so this is a
      replacement, not an addition.
- [x] 8.3 Rename `Constants.Pyroscope.INSTALL_PATH` to `EMR_INSTALL_PATH` — it is the EMR path
      (`/opt/pyroscope/`), while nodes use `/usr/local/pyroscope/`. One constant covering two paths
      is a trap.
- [x] 8.4 Do **not** delete `packer/cassandra/install/install_pyroscope_agent.sh` —
      `StressJobService.kt:150-151` hostPath-mounts `/usr/local/pyroscope` into stress pods and still
      needs the jar.

## 9. Documentation

- [x] 9.1 Rewrite `docs/user-guide/profiling.md`. It currently documents the Java-agent path and
      states cpu and wall are mutually exclusive for Cassandra — both become false. It must now cover
      two mechanisms, since stress, Presto, Spark/EMR, and the sidecar keep the Java agent. The Data
      Flow diagram and Profile Types table both need rework, not a sentence.
- [x] 9.2 Document the runtime workflow: start, switch modes with stop/start, status, fetch,
      flamegraph. Include real invocations.
- [x] 9.3 Document the cpu+wall hazard: combining them in one recording corrupts CPU sample weights
      via the upstream `jfr-parser` defect, the corruption is scoped per upload so separate sessions
      are clean, and switching modes is the supported approach.
- [x] 9.4 Document the reserved parameter set as a rejection of conflicting input, not a removal of
      capability — every profiling parameter stays reachable, and rotation is available as `--loop`.
- [x] 9.5 Document that thread-pool attribution comes from `jfrconv --threads` on a fetched chunk,
      because Pyroscope's ingest discards thread identity.
- [x] 9.6 Update `configuration/CLAUDE.md`'s profiling section — it currently documents the Java agent
      as Cassandra's mechanism and `PYROSCOPE_PROFILER_EVENT` as the way to switch events.
- [x] 9.7 Update `docs/reference/commands.md` with the new command group.

## 10. Verification

> 10.2–10.7 require a live cluster and are the owner's to run; they cannot be executed from
> the build. Everything below 10.1 is verified locally and in CI.

- [x] 10.1 `./gradlew ktlintFormat` then `./gradlew check` (JDK 21 — detekt 1.23.8 cannot run under
      JDK 25).
- [ ] 10.2 Confirm **Cassandra still starts** on a real cluster. This is the first check of any test
      run, not the last: capture moving out of JVM startup removes a whole failure class, and
      confirming it is what proves the removal was clean.
- [ ] 10.3 On a real cluster, verify `sudo asprof status <cassandra-pid>` succeeds — this is the
      attach path the whole design rests on. Then start profiling, confirm chunks appear and rotate,
      and confirm they reach Pyroscope.
- [ ] 10.4 Verify a mode switch: start cpu, switch to wall, confirm the outgoing chunk is finalized
      and shipped rather than lost.
- [ ] 10.5 Verify restart survival: restart Cassandra and confirm the reconciler re-attaches within
      one interval with no operator action.
- [ ] 10.6 Verify in the Pyroscope UI that a cpu+alloc session yields both CPU and allocation flame
      graphs, and that a wall session yields a populated wall flame graph.
- [ ] 10.7 Verify `flamegraph --threads` produces per-thread attribution showing Cassandra pool names.
