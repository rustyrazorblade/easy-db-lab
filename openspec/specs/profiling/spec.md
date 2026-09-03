# profiling Specification

## Purpose
TBD - created by archiving change issue-880. Update Purpose after archive.
## Requirements
### Requirement: Profiling is controlled at runtime, not at JVM startup

The system SHALL attach async-profiler to the already-running Cassandra JVM on demand, and SHALL NOT
inject any profiling agent into Cassandra's JVM startup options. Changing what is being profiled
SHALL NOT require restarting Cassandra.

#### Scenario: Cassandra starts with no profiling agent injected
- **WHEN** a Cassandra node starts
- **THEN** no `-javaagent:pyroscope.jar` and no `-agentpath` profiling option is present in the JVM's
  options, and Cassandra's startup does not depend on any profiler being installed or functional

#### Scenario: Profiler attaches to a running JVM
- **WHEN** `cassandra profile start -- -e cpu` is run against a node whose Cassandra JVM is already
  running
- **THEN** async-profiler attaches to that JVM without restarting it, and JFR chunks begin appearing
  in the node's profile directory

#### Scenario: Attaching does not interrupt Cassandra
- **WHEN** the profiler attaches to or detaches from a running Cassandra JVM
- **THEN** Cassandra continues serving requests, and its process ID does not change

### Requirement: async-profiler parameters pass through unmodified

The system SHALL pass user-supplied async-profiler arguments to `asprof` byte-identically, without
re-parsing, re-quoting, reinterpreting, or validating them against any enumerated set of known
profiling options. The system SHALL NOT model async-profiler's option surface.

#### Scenario: Arguments reach asprof unchanged
- **WHEN** a user runs `cassandra profile start -- -e wall -i 10ms`
- **THEN** `asprof` on the node receives exactly the arguments `-e wall -i 10ms`, in that order, in
  addition to the output arguments the system supplies

#### Scenario: An argument containing spaces is preserved as one argument
- **WHEN** a user supplies a profiling argument whose value contains spaces or shell metacharacters
- **THEN** `asprof` receives it as exactly one argument, with no word-splitting and no shell
  interpretation on the node

#### Scenario: An unrecognised profiling option is still forwarded
- **WHEN** a user supplies a profiling option the system has no knowledge of, such as one added by a
  newer async-profiler release
- **THEN** the system forwards it unchanged rather than rejecting it

### Requirement: Output-plumbing parameters are reserved and rejected

The system SHALL reject user-supplied async-profiler arguments that control output destination,
output format, rotation, or session duration, because the system itself supplies these and the chunk
shipper depends on them. The system SHALL also reject arguments that change which JFR event types
reach the shipper, on the same ground as output format. Rejection SHALL occur at the CLI before
contacting any node, and the error SHALL name the offending argument and the reason.

Reservation SHALL cover both of async-profiler's spelling systems — the CLI flag form and the
comma-separated agent-option form — and SHALL cover reserved options smuggled inside another
argument's value after a comma.

#### Scenario: Reserved output-file argument is rejected
- **WHEN** a user runs `cassandra profile start -- -e cpu -f /tmp/mine.jfr`
- **THEN** the command fails before contacting any node, and the error names `-f` and states that the
  system controls async-profiler's output

#### Scenario: Reserved argument in agent-option spelling is rejected
- **WHEN** a user supplies an argument in agent-option form such as `file=/tmp/mine.jfr`,
  `loop=1h`, or `timeout=30`
- **THEN** the command fails before contacting any node, naming the offending option

#### Scenario: Reserved argument smuggled after a comma is rejected
- **WHEN** a user supplies `-e cpu,file=/tmp/elsewhere.jfr`
- **THEN** the command fails before contacting any node, because each argument value is split on
  commas and every fragment is checked against the reserved set in agent-option form

#### Scenario: A bare output-format token is rejected
- **WHEN** a user supplies a bare format word such as `collapsed` or `flamegraph` in a position where
  it would set the output format
- **THEN** the command fails before contacting any node

#### Scenario: Session-ending arguments are rejected
- **WHEN** a user supplies `-d 30`, `--duration 30`, or `--timeout 30`
- **THEN** the command fails before contacting any node, because a self-terminating session would be
  restarted by the reconciler, producing a perpetual attach and detach cycle

#### Scenario: The wall-clock batching switch is rejected
- **WHEN** a user supplies `--nobatch`, its agent-option spelling `nobatch`, or either smuggled after
  a comma as in `-e wall,nobatch`
- **THEN** the command fails before contacting any node, and the error explains that the flag makes
  async-profiler emit wall-clock samples as `jdk.ExecutionSample` — indistinguishable from CPU
  samples — so the CPU profile is silently corrupted, rather than reporting it as output plumbing

#### Scenario: Non-reserved profiling arguments are accepted
- **WHEN** a user supplies profiling arguments that do not touch output, rotation, duration, or the
  emitted event type — for example `-e wall`, `-i 10ms`, `--alloc 2m`, `--lock 10ms`, `--cstack vm`,
  or `--chunksize 8m`
- **THEN** the command accepts them and forwards them unchanged

#### Scenario: Rotation remains available through the command's own option
- **WHEN** a user needs to change the JFR rotation interval
- **THEN** the command's own `--loop` option sets it, so reserving async-profiler's `--loop` removes
  no capability

### Requirement: Desired profiling state is stored on each node

The system SHALL store the desired profiling state on each Cassandra node as a durable document
written by the CLI and read by the node's reconciler. The write SHALL be atomic, so a partially
written document is never observable. Stopping profiling SHALL be represented as an explicit disabled
state rather than by removing the document, and SHALL NOT alter any other field of that document.
Retention and size bounds the node cannot act on SHALL be refused before any node is contacted, and a
document that exists but cannot be read SHALL be reported as such rather than treated as absent.

#### Scenario: Starting profiling records desired state
- **WHEN** `cassandra profile start -- -e cpu` is run
- **THEN** the node's desired-state document records the profiling arguments, the rotation interval,
  the retention bounds, the Pyroscope address, the cluster name, and that profiling is enabled

#### Scenario: Stopping profiling records an explicit disabled state
- **WHEN** `cassandra profile stop` is run
- **THEN** the desired-state document records that profiling is disabled, along with when the change
  was made, rather than the document being deleted

#### Scenario: Stopping preserves the retention bounds the operator chose
- **WHEN** `cassandra profile stop` is run on a node started with non-default retention bounds
- **THEN** those bounds are preserved, because resetting them to defaults would have the reconciler
  prune away exactly the profiles the operator stopped in order to collect

#### Scenario: A retention or size bound the node cannot honour is refused
- **WHEN** `cassandra profile start` is given a retention window or byte ceiling below the smallest
  the node can act on, such as a negative or zero retention window, or a byte ceiling too small to
  hold one JFR chunk
- **THEN** the command fails naming the option, and no node is contacted — a negative bound would
  make every reconcile pass refuse to act on the document, and a zero one would prune each chunk as
  fast as the node writes it

#### Scenario: A retention window shorter than the shipping delay is refused
- **WHEN** `cassandra profile start` is given a retention window shorter than the time a chunk
  needs before it can be shipped at the given rotation interval, such as an hourly rotation against
  the default sixty-minute window
- **THEN** the command fails naming both options, and no node is contacted — every chunk would be
  age-pruned on the pass before the one that would have uploaded it, so nothing would ever reach
  Pyroscope with nothing anywhere reported as failing

#### Scenario: A rotation faster than the node can ship is refused
- **WHEN** `cassandra profile start` is given a rotation interval that produces chunks faster than
  one reconcile pass can upload them, such as a five-second rotation
- **THEN** the command fails naming the option, and no node is contacted — the per-pass upload budget
  is what keeps a pass inside the unit's start timeout and cannot be raised to meet the rotation, so
  the queue would grow every pass and each chunk would be deleted, unshipped, once it aged past the
  retention window, with nothing anywhere reported as failing

#### Scenario: Falling back to default bounds is reported
- **WHEN** `cassandra profile stop` finds a desired-state document that exists but cannot be read,
  and therefore has no bounds to preserve
- **THEN** it still disables profiling, and emits a typed profiling event naming the node and the
  default bounds it applied, so the reset is not silent

#### Scenario: A partial write is never observed
- **WHEN** the desired-state document is being replaced while the reconciler reads it
- **THEN** the reconciler observes either the complete previous document or the complete new one,
  never a partial one

### Requirement: The reconciler makes actual profiling state match desired state

Each Cassandra node SHALL run a periodic reconciler that compares desired profiling state against
what is actually running and takes whatever action closes the gap. The reconciler SHALL determine
whether a profiling session is running by querying the live JVM, and SHALL NOT treat any stored
record as authoritative for liveness. The reconciler pass SHALL be idempotent.

#### Scenario: Profiler is started when it should be running but is not
- **WHEN** desired state is enabled and no profiling session is running against the current Cassandra
  process
- **THEN** the reconciler starts one with the desired arguments

#### Scenario: A matching running session is left alone
- **WHEN** desired state is enabled and a session is already running against the current Cassandra
  process with the same arguments
- **THEN** the reconciler takes no action, and running the pass again changes nothing

#### Scenario: A session started with no arguments is recognised as converged
- **WHEN** desired state is enabled with an empty argument list — profiling with the profiler's own
  defaults — and a matching session is already running
- **THEN** the reconciler takes no action, rather than reading "no arguments" as "no record" and
  tearing the session down and re-attaching on every pass

#### Scenario: Attaching and detaching are recorded
- **WHEN** the reconciler attaches a profiler to the database process, or confirms a detach
- **THEN** it logs that transition with the process and the rotation interval, and increments a
  cumulative attach counter, so a node repeatedly re-attaching is visible as a rate rather than as
  silence

#### Scenario: Profiler is stopped when profiling is disabled
- **WHEN** desired state is disabled and a session is running
- **THEN** the reconciler stops it

#### Scenario: A record naming a dead process is discarded
- **WHEN** the stored record of the running session names a process ID other than the current
  Cassandra process ID
- **THEN** the reconciler discards that record before deciding, and starts a session cleanly

#### Scenario: An unrecognised running session converges
- **WHEN** a session is running but the system cannot confirm it matches the desired arguments
- **THEN** the reconciler stops it and starts one with the desired arguments, converging in a single
  pass

#### Scenario: Cassandra being down does not block shipping and pruning
- **WHEN** the Cassandra process is not running
- **THEN** the reconciler skips attaching but still ships completed chunks and enforces retention

#### Scenario: Wanting to profile with nothing to attach to is reported
- **WHEN** desired state is enabled and there is no database process to attach to
- **THEN** the reconciler logs that outcome and increments a counter kept separate from refused
  attaches, rather than converging silently and leaving the pass indistinguishable from a healthy one

#### Scenario: A database that is not ready to be attached to is never signalled
- **WHEN** desired state requires the reconciler to start, stop or replace a session, and the database
  process exists but is not yet ready to receive an attach
- **THEN** the reconciler makes no attach, detach or status call against that process at all, because
  every such call signals it and a process that has not yet installed a handler for that signal is
  killed by it

#### Scenario: Waiting for the database is reported as a wait, not as a failure
- **WHEN** the reconciler defers an attach because the database is not ready
- **THEN** it logs that outcome as a warning naming the reason and what it would have done, and
  increments a counter kept separate from both refused attaches and an absent database — and does
  not increment the refused-attach counter, because a database that is still starting is not a node
  misconfiguration

#### Scenario: Readiness does not depend on where the database binds
- **WHEN** the database's client-facing transport is bound to the node's private address rather than
  to loopback, which is how every node in a cluster is configured
- **THEN** the reconciler still observes that node as ready and attaches within one pass, rather than
  falling through to the slower fallback that exists for a transport which is deliberately disabled

#### Scenario: A deferred attach does not stop the rest of the pass
- **WHEN** the reconciler defers an attach because the database is not ready
- **THEN** the pass still ships completed chunks, still enforces both retention bounds, and still
  writes its counters, its metrics and its effective state

#### Scenario: A deferred pass does not discard a session it cannot ask about
- **WHEN** the reconciler cannot query the database because it is not ready to be signalled, and its
  own last record names that same process as running a session
- **THEN** it carries that record forward rather than reporting nothing attached, so a session that
  is still running is not erased and then torn down as a spec change by the next pass

#### Scenario: Desired and attached state are separable in the metric store
- **WHEN** the reconciler completes any pass
- **THEN** it exports what was asked for and what is actually attached as separate values, so that
  profiling deliberately off, profiling running, and profiling wanted but dead are three
  distinguishable states in the metric store without the CLI

#### Scenario: Unreadable desired state never stops a running session
- **WHEN** the desired-state document is present but cannot be parsed, or is missing the Pyroscope
  ingest address
- **THEN** the reconciler reports a structured error and leaves any running profiling session exactly
  as it is

#### Scenario: Unreadable desired state never stops shipping, pruning or reporting
- **WHEN** the desired-state document cannot be read
- **THEN** the pass still ships completed chunks, still enforces both retention bounds, and still
  writes its counters, its metrics and its effective state — refusing only the attach and detach
  decision, because a pass that returns early leaves the profile directory growing without bound on
  the database's own volume while every metric series stops being written

#### Scenario: Shipping and pruning continue under the operator's own bounds
- **WHEN** the desired-state document cannot be read and a previous pass recorded the bounds it ran
  under
- **THEN** the pass ships and prunes under those recorded bounds rather than the tool's built-in
  defaults, so a document that goes unreadable cannot silently discard a longer retention window the
  operator chose

#### Scenario: An unreadable configuration is reported as its own condition
- **WHEN** the desired-state document cannot be read
- **THEN** the reconciler exports a distinct value saying so and records the reason in its effective
  state, and `cassandra profile status` reports the configuration as the cause and emits a typed
  profiling event for it, instead of reporting the node's report as stale and directing the operator
  at the reconcile timer — which is the one component still working

### Requirement: Profiling survives a Cassandra restart

The system SHALL re-establish profiling after the Cassandra JVM restarts, with no operator action and
with no CLI process running.

#### Scenario: Profiling resumes after a restart
- **WHEN** the Cassandra JVM restarts, by systemd restart, bootstrap, or crash, while desired state
  is enabled
- **THEN** the reconciler attaches a new session to the new process within one reconcile interval

#### Scenario: Resumption requires no operator involvement
- **WHEN** profiling resumes after a restart
- **THEN** it does so without any command being run and without the CLI being present on any machine

### Requirement: Cluster-up starts profiling automatically

The system SHALL seed each Cassandra node's desired profiling state during cluster setup so that a
newly provisioned cluster is profiling without operator action.

#### Scenario: A new cluster profiles CPU, allocation and lock contention
- **WHEN** a cluster finishes coming up
- **THEN** every Cassandra node has profiling enabled with CPU, allocation and lock-contention
  sampling in one session, and is shipping chunks to Pyroscope — only wall-clock sampling is
  exclusive of a CPU event, and the profiling dashboard's memory, lock and mutex panels read the
  other two

### Requirement: Changing profiling arguments switches the session without losing data

The system SHALL treat any change to the profiling arguments as a request to replace the running
session. The outgoing session SHALL be stopped in a way that finalizes its in-flight chunk so that
chunk remains shippable.

#### Scenario: Switching the profiled event
- **WHEN** a session is running with one set of profiling arguments and a different set is submitted
- **THEN** the running session is stopped, its in-flight chunk is finalized and subsequently shipped,
  and a new session starts with the new arguments

#### Scenario: A session that will not stop is not replaced or misreported
- **WHEN** stopping a running session fails
- **THEN** no new session is started on top of it, the node continues to report the arguments still
  applied rather than the ones requested, and the change is retried on the next reconcile pass

#### Scenario: Changing only an interval also switches
- **WHEN** the submitted arguments differ from the running arguments only in a sampling interval
- **THEN** the session is replaced, because the full argument list is compared rather than the event
  alone

### Requirement: Completed JFR chunks are shipped to Pyroscope

The system SHALL ship completed JFR chunks from each node to the Pyroscope server's ingest endpoint,
labelled with the node's hostname and the cluster name. The system SHALL only ship chunks that are
complete, and SHALL never ship the chunk currently being written. A chunk SHALL be shipped at most
once, and SHALL remain retrievable after it has shipped.

#### Scenario: A completed chunk is shipped
- **WHEN** a JFR chunk completes at the end of a rotation interval
- **THEN** the reconciler uploads it to Pyroscope's ingest endpoint in JFR format, labelled with the
  node's hostname and cluster name, and marks it as shipped

#### Scenario: The chunk being written is never shipped while a session is running
- **WHEN** the reconciler examines the profile directory while a profiling session is attached
- **THEN** it excludes the most recently modified unshipped chunk, which under rotation is the one
  still being written

#### Scenario: The final chunk of a stopped session is shipped
- **WHEN** a session has been stopped, so its in-flight chunk was finalized and nothing is writing
- **THEN** that chunk is shipped rather than held back as "the newest", so the last interval of the
  run is not lost to retention

#### Scenario: A shipped chunk stays retrievable and is not shipped again
- **WHEN** a chunk has been uploaded successfully
- **THEN** it is excluded from subsequent uploads, and it remains listed among the node's completed
  chunks so retrieval and flame-graph conversion can still use it

#### Scenario: Profiles are selectable in Pyroscope
- **WHEN** a session profiling CPU with allocation sampling has been shipping for some time
- **THEN** CPU and allocation flame graphs are both selectable and populated in Pyroscope for that
  node

#### Scenario: Wall-clock profiles come from a separate session
- **WHEN** a session profiling wall-clock time has been shipping for some time
- **THEN** the wall-clock flame graph is populated in Pyroscope for that node, and obtaining CPU,
  wall, and allocation profiles requires running more than one session in sequence

#### Scenario: One pass does not run past its systemd timeout
- **WHEN** a backlog of completed chunks is larger than one pass can upload inside the reconcile
  unit's start timeout
- **THEN** the pass uploads a bounded number of them, reports that it was truncated as a distinct
  condition, and leaves the rest for later passes — and if it is killed anyway, it persists its
  counters, metrics and effective state on the way out, so a slow Pyroscope is never reported as a
  reconciler that has stopped running

#### Scenario: A pass killed during the attach persists what it learned
- **WHEN** a pass is killed while attaching to, detaching from, or probing a database JVM that is
  slow to answer
- **THEN** it still writes its counters, metrics and effective state before exiting, and reports the
  kill as its own condition — those calls are the ones that can consume the whole start timeout, and
  a pass killed just after attaching with no record written would have the next pass read the healthy
  session as unknown and tear it down

### Requirement: Shipping failures and rejections are distinguished and surfaced

The system SHALL distinguish transient shipping failures from permanent rejections, and SHALL surface
both rather than dropping them silently. A rejected chunk SHALL NOT be retried, so that one
unparseable chunk cannot block the queue. Failures SHALL NOT be reported through generic message or
error event types.

#### Scenario: A transient failure is retried
- **WHEN** an upload fails with a server error or a network error
- **THEN** the chunk is left in place, retried on a subsequent pass, and counted as a failure

#### Scenario: A rejected chunk is not retried
- **WHEN** an upload is rejected by the server as unacceptable
- **THEN** the chunk is marked rejected, never uploaded again, and counted separately from transient
  failures

#### Scenario: A truncated chunk does not block the queue
- **WHEN** an unclean Cassandra shutdown leaves a truncated chunk that the server will not accept
- **THEN** that chunk is marked rejected and subsequent chunks continue to ship normally

#### Scenario: Failures are queryable without the CLI
- **WHEN** a shipping failure occurs
- **THEN** the reconciler writes a structured log line that reaches the cluster's log store, and
  updates failure and rejection counters that reach the cluster's metric store, labelled with the
  node and the cluster like every other series in the stack

#### Scenario: Failures are recorded at a severity that can be filtered
- **WHEN** the reconciler reports a failure
- **THEN** the log line carries a severity the cluster's log store can filter and alert on, rather
  than arriving indistinguishable from routine informational output

#### Scenario: A metric backend that is unreachable does not stop profiling
- **WHEN** the counters cannot be delivered to the metric store
- **THEN** the condition is logged and the reconcile pass still succeeds, because profiling with
  unreported counters is better than a failed pass

#### Scenario: Failure logs carry the tool's own diagnosis
- **WHEN** an upload, an attach, or a detach fails
- **THEN** the structured log line carries the underlying tool's own error message, bounded in
  length, rather than only a status code or a fixed hint

#### Scenario: Status surfaces failures as typed events
- **WHEN** `cassandra profile status` is run after a shipping failure
- **THEN** it emits a typed profiling domain event carrying the failure, and not a generic message or
  error event

### Requirement: Local JFR retention is bounded

The system SHALL bound the JFR chunks retained on each node by both age and total size, and SHALL
prune automatically. Both bounds SHALL be configurable. Pruning SHALL apply to unshipped chunks as
well, so that an unreachable Pyroscope server cannot exhaust the disk.

#### Scenario: Chunks are pruned by age
- **WHEN** a chunk ages past the configured retention window
- **THEN** it is deleted from the node

#### Scenario: Chunks are pruned by total size
- **WHEN** the profile directory exceeds the configured size ceiling
- **THEN** chunks are deleted oldest-first until it no longer does, regardless of their age

#### Scenario: An unreachable server cannot fill the disk
- **WHEN** Pyroscope has been unreachable for longer than the retention window
- **THEN** unshipped chunks are pruned as well, so the profile directory stays within its bounds

#### Scenario: Pruning accounts for what it deleted
- **WHEN** pruning deletes a chunk that never reached the profile store
- **THEN** it records that deletion as a warning and counts it separately from routine reclamation,
  because that chunk is data the operator cannot get back and its loss must be attributable after
  the fact

#### Scenario: Profiling output does not endanger the database
- **WHEN** profiling runs continuously over a long period
- **THEN** the profile directory remains within its configured bounds, so it cannot exhaust the
  volume Cassandra stores data on

#### Scenario: Chunks lost to pruning reach the operator
- **WHEN** pruning destroys chunks that had never been shipped
- **THEN** the count reaches the node's effective state as well as its logs and metrics, so
  `cassandra profile status` renders it and emits a typed profiling event — it is the only pruning
  number that means irreversible loss rather than reclaimed disk

#### Scenario: Operator-supplied artifacts are never pruned
- **WHEN** an operator has placed files such as heap dumps in the node's artifacts directory
- **THEN** profiling retention never deletes them, because profiling output is kept in its own
  directory

### Requirement: Profiling state is inspectable

The system SHALL report the profiling state of each node, including the profiling arguments as the
user supplied them and the full command as actually invoked. The state requested SHALL be reported
separately from the state actually in effect on the node.

#### Scenario: Status reports the running configuration
- **WHEN** `cassandra profile status` is run
- **THEN** it reports, per node, what was requested and what is actually attached as separate facts,
  the user-supplied profiling arguments verbatim, the full argument list as actually invoked
  including the arguments the system added, the process being profiled, how long the session has
  been running, and the counts of chunks pending, shipped, and rejected

#### Scenario: A node that has not reconciled yet is reported as unknown
- **WHEN** `cassandra profile status` is run against a node whose reconciler has not yet completed
  a pass, as is the case for every node immediately after cluster-up
- **THEN** that node's state is reported as unknown rather than failing the command

#### Scenario: A failing attach is distinguished from a deliberate stop
- **WHEN** profiling is enabled for a node but nothing is attached to its database process
- **THEN** the report says so explicitly, carries the reason the node recorded, and a typed profiling
  domain event is emitted — because a profiler that has been failing to attach for hours otherwise
  renders identically to one the operator deliberately stopped

#### Scenario: A node waiting for its database is reported as waiting
- **WHEN** `cassandra profile status` is run against a node whose last pass deferred an attach
  because the database was not yet ready
- **THEN** the report says the node is waiting for the database to become ready, marks that ahead of
  everything else in it, and emits a typed profiling domain event that is not an error — and does not
  report the node as one whose attach is failing, because both states show profiling enabled with
  nothing attached and only one of them is a fault

#### Scenario: A report older than the reconcile interval is not presented as current
- **WHEN** `cassandra profile status` is run against a node whose reconciler has not completed a
  pass for several intervals — its timer masked or disabled, or its pass timing out
- **THEN** the report says how long ago that node last reconciled, marks the report as stale ahead of
  everything else in it, points at the unit to check, and emits a typed profiling domain event —
  because the stored record otherwise keeps reporting an attached session, with an age that grows
  against the current clock, for a node where nothing is running

#### Scenario: Status reports shipping health
- **WHEN** `cassandra profile status` is run after shipping problems
- **THEN** it reports the most recent shipping error and the bytes of profile data held on the node

### Requirement: JFR chunks can be retrieved and rendered as flame graphs

The system SHALL provide retrieval of raw JFR chunks from nodes, and SHALL provide conversion of
recent chunks into a flame graph. Conversion SHALL happen on the node. Converter arguments SHALL pass
through unmodified, except that the system reserves the converter's input and output arguments.

#### Scenario: Raw chunks are retrieved
- **WHEN** `cassandra profile fetch` is run
- **THEN** the selected completed JFR chunks are downloaded from each targeted node into the local
  workspace, including chunks that have already been shipped to Pyroscope, so that the requested
  count of chunks is honoured rather than limited to whatever has not shipped yet

#### Scenario: A flame graph is produced from recent chunks
- **WHEN** `cassandra profile flamegraph` is run
- **THEN** the selected completed chunks are converted together on the node, and the resulting flame
  graph is downloaded into the local workspace

#### Scenario: Converter arguments pass through
- **WHEN** a user supplies converter arguments such as one selecting a profile type or splitting
  stacks by thread
- **THEN** they are forwarded to the converter unchanged

#### Scenario: Converter input and output arguments are reserved
- **WHEN** a user supplies a positional argument or an output-format argument to the converter
- **THEN** the command fails before contacting any node, because the system supplies the input chunks
  and the output destination

#### Scenario: Thread-level attribution is available
- **WHEN** a user converts chunks with the converter's thread-splitting argument
- **THEN** the resulting flame graph attributes stacks to individual Cassandra threads, which the
  Pyroscope path cannot provide

### Requirement: Profiling commands are scoped to selected nodes

The system SHALL apply every profiling command to all Cassandra nodes by default, and SHALL allow
restricting it to a subset.

#### Scenario: All nodes by default
- **WHEN** a profiling command is run without host selection
- **THEN** it applies to every Cassandra node in the cluster

#### Scenario: A subset is targeted
- **WHEN** a profiling command is run with an explicit host selection
- **THEN** it applies only to those nodes, and other nodes' profiling state is unchanged

### Requirement: The corrupting event combination is documented

The system SHALL document that combining a CPU event with wall-clock sampling in a single recording
produces corrupted CPU sample weights, and SHALL direct users to switch modes instead of combining
them. This documentation SHALL be reachable from the command's own help text as well as the user
guide.

#### Scenario: Help text warns against the combination
- **WHEN** a user views the help for `cassandra profile start`
- **THEN** it states that a CPU event and wall-clock sampling must not be combined in one recording,
  and that modes should be switched instead

#### Scenario: The user guide explains the cause
- **WHEN** a user reads the profiling user guide
- **THEN** it explains that the corruption originates in the upstream Pyroscope JFR parser, that it
  affects only combined recordings, and that separate sessions are unaffected

