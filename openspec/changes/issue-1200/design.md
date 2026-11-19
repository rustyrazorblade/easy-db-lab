## Context

### Where raw remote output comes from today

Every remote command goes through one path: a caller calls `RemoteOperationsService.executeRemotely(host, command, output = true, secret = false)`.  `DefaultRemoteOperationsService` takes the host's `ISSHClient` from `SSHConnectionProvider`.  `SSHClient.executeRemoteCommand` runs the command through MINA's `ClientSession.executeRemoteCommand(command, stdout, stderr, charset)` into two `ByteArrayOutputStream`s.  When the command ends with exit 0 and `output` is true, it emits `Event.Ssh.CommandOutput(stdout)` (`SSHClient.kt:106`): the whole stdout as one string, with no host, after the command has finished.  Stderr is never shown on success.  `output` defaults to `true`, so 69 call sites in 29 files print their output this way.  The node scripts during `up` are the main ones: `NodeSetupService.runSetupScript` (`sudo bash setup_instance.sh`), `K3sAgentService.start` (`start-k3s-agent.sh`), and the K3s server and Cilium steps.

The emitters of the chatter events:

- `Ssh.CommandOutput`: `SSHClient.executeRemoteCommand`, the only emitter.
- `Ssh.UploadingFile`: `SSHClient.uploadFile`, once per file.  `uploadDirectory` calls it for every file of the tree.
- `Ssh.UploadingDirectory`: `DefaultRemoteOperationsService.uploadDirectory`.
- `Ssh.ExecutingCommand` and `Ssh.ExecutingHiddenCommand`: defined, never emitted.
- Raw output that a command re-emits as its own event: `Cassandra.NodetoolOutput` (`Nodetool.kt`, `output = false` and then the event with a `=== host ===` header), `Cassandra.PatchOutput` (`UpdateConfig.kt`; the output is printed twice today, once as `Ssh.CommandOutput` and once as this) and `Cassandra.PatchStderr` (raw stderr as an error event).

The consumers:

- `ConsoleEventListener` prints `toDisplayString()`: the raw text for `CommandOutput`.
- `RedisEventListener` publishes every one as a JSON envelope.
- Tests: `SSHClientTest` ("a successful command's output is redacted before it reaches the event bus") reads it back through `BufferedOutputHandler`.  `EventSerializationTest` covers no `Ssh` type.  No integration test, no script in `bin/`, no skill and no doc reads them.  `providers/CLAUDE.md` names `Ssh.CommandOutput` as one of the four redaction sinks.
- The server and the REPL have no special handling.  `ClusterStatusCollector` calls `getRemoteVersion`, which uses `output = false`.

Other raw-output events, which come from no SSH command but carry raw text all the same:

- `Cassandra.CqlQueryOutput(output)`: emitted only by `Cql.execute` (`cassandra cql`) with the rows the driver returned, already formatted as text.
- `Stress.CommandOutput(output)`: emitted by `StressInfo`, `StressList` and `StressFields` with the logs of the short-lived `cmd-<timestamp>` K8s job that `StressJobService.runCommand` starts and deletes.  The pod name stays inside the service.
- `Stress.PodLogOutput(logs)`: emitted only by `StressLogs` (`cassandra stress logs`) with one pod's logs.  Around it, `StressLogs` emits `Stress.PodLogHeader(podName, podStatus)` (`=== Pod: <name> (<status>) ===`, only when the job has more than one pod), `Stress.PodLogsEmpty` (`(no logs available yet)`) and `Stress.PodLogSeparator` (a blank line, more than one pod).  `Stress.PodLogFailed` is an error.

Their consumers are the same two listeners.  Their tests read them back through the test listener that forwards events to `BufferedOutputHandler.messages`: `CqlTest`, `StressInfoTest`, `StressListTest` (`StressLogsTest` asserts only the service calls).  No serializer registration lists them by hand: the sealed hierarchy is `@Serializable` and `EventSerializationTest` covers none of them.  No doc, script or skill reads them.

Paths this change does not move: kit shell steps run locally and already `println` each line (`WorkloadStepExecutor.runShellStep`).

### Bring-up

`up` runs one virtual thread per node (`NodeBringUp`, `NodePipeline`, `InstanceRun`).  `NodePipeline.failed` emits `Provision.NodeFailed(alias, step, reason, instanceId, instanceKept)` and `ready` emits `Provision.NodeReady(alias, instanceId, elapsedSeconds, replacements)`.  `NodeBringUp.controlFailed` emits `Provision.ControlNodeFailed(alias, step, reason)`.  A failed join's reason already ends with the K3s agent journal tail.  No other failure carries the node's output, and the setup failure reason is only the script's `ERROR:` lines.

## Goals / Non-Goals

**Goals:**

- Every raw remote output line is printed with its node alias, as it arrives, as one whole line.
- No raw remote output and no per-command chatter reaches the event bus.
- `NodeReady` and `NodeFailed` stay, and a failure carries the node's recent output.
- A quiet mode hides raw output and keeps every event and the failure evidence.
- The output path is a provider-neutral port, so a non-SSH transport (an existing EKS cluster, a cloud's run-command API) feeds the same port.

**Non-Goals:**

- Local process output (kit shell steps, hooks, `helm` run locally): it is already plain `println` and comes from one process.
- A quiet mode on any command but `up`, `init` and `setup-instances` (owner decision).
- A log file of the full output.  The tail in a failure report is the only kept copy.
- Colour or column alignment of the tag.

## Decisions

### D1. A `NodeOutput` port, not the event bus

New in `output/` (provider-neutral; it names a node by its alias string and imports nothing from `providers.*` or `ssh.*`):

```kotlin
/** Where the raw output of commands run on cluster nodes goes. */
interface NodeOutput {
    fun line(node: String, stream: OutputStream, text: String)   // stream: Stdout or Stderr
    fun notice(node: String, text: String)                       // a tool line such as an upload
    fun passthrough(source: String, text: String)                // split into lines, tagged, never kept in a tail
    fun tail(node: String): List<String>
    fun reset(node: String)
    fun <T> quietly(block: () -> T): T
}
```

`ConsoleNodeOutput` is the one production adapter, a Koin singleton in `OutputModule`.  The SSH adapter (`SSHClient` through `DefaultRemoteOperationsService`) writes into it.  A future transport (for example `kubectl exec` on EKS, or a cloud's run-command API) writes into the same port; nothing above it changes.

Alternative: keep the event and set `showOnConsole() = false` on it, or filter it out of Redis.  Rejected: the owner's rule is that raw output is not an event at all, and an event that only one listener prints is still serialized and still on the bus.

### D2. Streaming and line splitting

`ISSHClient.executeRemoteCommand(command, output: Boolean, secret: Boolean)` becomes `executeRemoteCommand(command, sink: RemoteOutputSink?, secret: Boolean)`.  `RemoteOutputSink` (in `ssh/`) takes `(stream, line)`.  `SSHClient` passes MINA two tee streams: each writes into the `ByteArrayOutputStream` it has today (the `Response` and the failure exception are unchanged) and into a `LineSplittingOutputStream` per stream.  MINA writes as data arrives, so lines reach the console while the command runs, not after it ends.

`LineSplittingOutputStream` (pure, in `output/`):

- buffers bytes and emits a line at each `\n`;
- treats a `\r` as the start of a new segment that replaces the current one, as a progress bar redraws its line: at `\n` (or `close()`) it emits the last non-empty segment once, and `\r\n` behaves as `\n`.  So `50%\r100%\r\n` is one line, `100%`; `a\r\rb\n` is `b`; `x\r\r\n` is `x`; and `50%\r` then `close()` is `50%`.  A redrawn bar is one line, not one per redraw, and does not fill the tail;
- decodes each line as UTF-8 only once it is complete, so a multi-byte character split across two writes is never broken (a `\n` byte never occurs inside a UTF-8 sequence);
- emits a segment in pieces of at most `Constants.NodeOutput.MAX_LINE_BYTES` (8192) bytes without waiting for a newline, so output with no newline never grows without bound.  A piece is cut after its last space or tab.  With no whitespace, a URL whose `://` lies in the last `Constants.NodeOutput.URL_CARRY_BYTES` (512) bytes moves whole to the next piece.  Otherwise the piece is cut at the cap, before a lead byte whose whole character does not fit.  A run of continuation bytes (invalid UTF-8) is cut at the cap too;
- emits the last partial line on `close()`, which `SSHClient` calls when the command ends, on success and on failure.

Each line goes through the redaction of `ssh/Redaction.kt` (D11) before the sink sees it.  A URL shorter than the cap never spans a piece, so line-wise redaction covers what whole-text redaction covers today.

`OffThreadOutputSink` hands the lines to the sink on one platform daemon thread per command (`Constants.NodeOutput.DRAIN_THREAD_NAME`), never on MINA's I/O threads.  Every blocking MINA call (connect and auth, exec, SCP upload and download, session close) runs on a platform thread through `ssh/PlatformThreadCall` when its caller is a virtual thread.  MINA blocks in `synchronized` blocks and in `Object.wait` (channel open, `waitFor`), which on JDK 21 pin a virtual thread's carrier; with one bring-up thread per node every carrier was pinned and every command hung.  The virtual caller only waits on a future, and an interrupt of it is passed to the platform thread while the call runs (one from before the call is applied when it starts; none lands after it ends, on the next call of that thread).  `SSHClient`'s per-session lock is a `ReentrantLock`, and anything the call needs under that lock, such as the cached SCP client, is fetched before the hop: the platform thread does not own the lock its virtual caller holds.  When an interrupted command stops waiting for its output after `Constants.NodeOutput.INTERRUPTED_DRAIN_WAIT`, it prints the notice `output still printing; the tail may be incomplete` under the node.

`DefaultRemoteOperationsService.executeRemotely` builds the sink from `host.alias` and `host.instanceId`: `output = true` on a command that is not secret gives `{ stream, line -> nodeOutput.line(host.alias, host.instanceId, stream, line) }`, anything else gives none.  The `RemoteOperationsService` signature does not change, so the 69 callers do not change.

`secret = true` streams nothing and records nothing in the tail.  `SSHClient` already treats a secret command's output as unrepeatable on failure, because such a command echoes its own argument (a Tailscale auth key, an AxonOps key).  Today the success output of `tailscale up` and `setup-axonops` is printed; after this change it is not.

### D3. The tag and the console lock

`ConsoleNodeOutput.line` writes `"[$node] $text"`, the tag padded with spaces to the longest alias it has seen so far, so the text of many nodes lines up.  Stdout lines go to stdout with `println`; stderr lines go to `System.err`, as `ConsoleEventListener` does for errors.  Every line is tagged, also when a command targets one node: the rule is simple, and a single-node command cannot be told apart from a filtered multi-node one by the reader.

One `ReentrantLock` guards each console write and the tail update, so a line is one write and a line from one node is never split by another.  A `ReentrantLock`, not `synchronized`, because bring-up runs on virtual threads, and a virtual thread blocked in a `synchronized` block pins its carrier.  The lock covers only the write of an already-complete line, never a remote read, so a slow node never blocks another node's output.

Events printed by `ConsoleEventListener` are single `println` calls of a whole string, so they never share a line with a node line either.

### D4. The per-node tail

`ConsoleNodeOutput` keeps a ring buffer per node alias of the last `Constants.NodeOutput.TAIL_LINES` (30) lines, stdout and stderr together, in arrival order, tagged with nothing.  A stderr entry starts with `stderr: `.  Each entry is cut to `Constants.NodeOutput.TAIL_ENTRY_CHARS` (512) characters and ends in `…`; the console still prints the whole line.  It records them in quiet mode and in normal mode.  Notice lines (uploads) are printed but not kept, since they would push script output out of the tail.  The retry notice (`retrying the command (attempt N)`) and the attempt notice (`<step> attempt N`) are kept, because they mark where a run's lines start.  Memory: 30 entries × 1,000 nodes is bounded at about 15 MB.

The tail belongs to one instance of the node.  `reset(alias, instanceId)` starts a new tail for that instance, and each line carries the instance id of the host its command ran on.  A line from another instance (an attempt left behind on an instance that ended, or its drain thread printing late) is printed, tagged, but is not kept in the new instance's tail and is not printed while quiet.  A line with no instance id (a host built without one) is kept as before.

The bring-up reads and resets it:

- `InstanceRun` calls `reset(alias, instanceId)` when an instance starts its steps, so after a replacement the tail is the last instance's output, the one that is kept for debugging.
- `NodePipeline.failed` puts `tail(alias)` on `NodeFailed.outputTail`.
- `NodeBringUp.controlFailed` puts the control node's tail on `ControlNodeFailed.outputTail`.
- `SetupInstance` puts the host's tail on `Provision.InstanceSetupFailed.outputTail`, for a failed setup script and for any other failed remote command during setup (whose reason is the command's one-line summary).

`BringUpRun` and `NodeBringUp` take a `BringUpReporting(eventBus, nodeOutput)` holder, passed in by `NodeBringUp`'s caller with the other ports.  Its `nodeOutput` is the `NodeOutput` port, used for the tail, its reset and the attempt notice.  Files in `bringup/` stay free of SSH and AWS types.

`NodeFailed`, `ControlNodeFailed` and `InstanceSetupFailed` render the tail under the reason, indented, with a header `last output of db7:`.  The tail is printed in quiet mode and in normal mode.  In normal mode the lines were shown already, but at 80 nodes they are spread through thousands of other lines; the failure report is where the reader looks.  An empty tail prints nothing.  `NodesFailed` (the summary at the end) does not repeat the tails: it prints one line per node.

A failed remote command's output is printed once, tagged, and kept in the tail; the step's reason does not repeat it.  `StepRunner` uses `RemoteCommandFailedException.summary` (the host and `Remote command failed (N): <command>`) as an attempt's reason, not the exception message with its 50 + 50 output lines.  A failed join keeps the agent's journal in its reason, as today.  `cassandra nt` reports a failure by its summary too, and `exec run` prints the notice `no output` for a unit whose journal is empty.

`NodeReady` already carries the alias, the instance, the elapsed time and the replacements; it needs nothing more.  `NodeFailed` gains only `outputTail`.

### D5. Removing the chatter events

Removed from `Event.kt`: `Ssh.ExecutingCommand`, `Ssh.ExecutingHiddenCommand`, `Ssh.CommandOutput`, `Ssh.UploadingFile`, `Ssh.UploadingDirectory`, `Cassandra.NodetoolOutput`, `Cassandra.PatchOutput`, `Cassandra.PatchStderr`.  `Ssh.SsmToolsMissing` stays (it is a failure an external system needs).

Their replacements:

- Uploads: `DefaultRemoteOperationsService.upload` and `uploadDirectory` call `nodeOutput.notice(host.alias, "uploading <local> to <remote>")` once per call.  `SSHClient.uploadFile` prints nothing, so a directory upload is one line, not one per file.
- `cassandra nt`: runs with `output = true`, so each host's lines are tagged; the `=== host ===` header goes.
- `cassandra update-config`: `patch-config` runs with `output = true` (stdout and stderr both shown, tagged, once).  `Cassandra.PatchFailed` stays.

Also removed, by owner decision: `Cassandra.CqlQueryOutput`, `Stress.CommandOutput`, `Stress.PodLogOutput`, and the three display-only events around the last one, `Stress.PodLogHeader`, `Stress.PodLogsEmpty` and `Stress.PodLogSeparator`.  They are formatting of raw text, not facts.  `Stress.PodLogFailed` (an error) and `Cassandra.CqlDdlOk`, `CqlQueryError`, `Stress.CommandRunning` stay.  The new shapes:

- `cassandra cql`: the rows are printed with `println`, untagged.  They come from the driver, not from a node.
- `cassandra stress info`, `list` and `fields`: the job's output is printed with `println`, untagged.  It comes from one throwaway `cmd-<timestamp>` pod whose name is internal to `StressJobService.runCommand`; a tag of that name would add nothing.
- `cassandra stress logs`: each log line goes through the tagged-line path, tagged with its pod name (`[stress-job-1-abc12] ...`), on every pod, also when the job has one.  It uses `NodeOutput.passthrough(source, text)` (D1): each line tagged and written whole under the same lock, and never kept in a tail, because pod names are unique per job and a long-lived server or REPL would otherwise keep one buffer per pod forever.  Before each pod's lines it prints `[<pod>] status <status>` the same way; a pod with no logs prints `[<pod>] (no logs available yet)`.  The header, the separator and the empty-pod event go.

Clusters are ephemeral and nothing in this repo subscribes to the removed types; no compatibility shim.

### D6. Quiet mode

`--quiet` / `-q`, on `up`, on `init` (it passes `quiet` to the `Up` it schedules for `--up`; `init --quiet` without `--up` is refused with a message naming `--up`) and on `setup-instances`.  `Up` runs the node pipelines (`NodeBringUp.run`) inside `nodeOutput.quietly { }`.  Quiet ends when they finish, and the steps after them (the success message and the post-steps, such as the observability stack and `TailscaleStart`) print as before, so a failure there shows its output.  `quietly` restores the mode when it returns or throws, so a REPL session or the server is not left quiet.

What quiet hides: node lines and notice lines (D3, D5).  What still prints: every event, which includes each node's Ready line, each failure with its output tail, replacements, control-plane health, the gate and the progress line; and everything the command itself prints with `println`.

`ConsoleNodeOutput` holds the mode as an `AtomicInteger` depth: `quietly` increments it on entry and decrements it in `finally`, and output is quiet while it is above zero.  A `quietly` inside another, or two overlapping on different threads, keep quiet until the last one ends.  A save-and-restore of a flag would let the first to end turn quiet off under the other.

Alternative: a global `--quiet` on the root command, for every command.  Rejected by the owner: the noise is in `up` and the setup scripts, and for `exec run`, `cassandra nt` and kits the output is the result.

### D7. `exec run`

Foreground: `systemd-run --wait` runs with `output = true` (its own `Running as unit` lines come out tagged, as today but now with the host), and `printUnitOutput` runs the journal command with `output = true` instead of printing `logOutput.text` under `=== host ===`.  The journal's lines are then tagged with the host and come out whole even under `--parallel`, where today two hosts' journal blocks can interleave.  `exec run` has no quiet option.  Background `--bg` keeps `output = true` and `Command.ToolStarted`.

### D8. Tests

Unit tier (`src/test`):

- `LineSplittingOutputStreamTest`: chunks that split a line and a UTF-8 character across writes, `\r\n`, a lone `\r`, an empty line, the line cap, the partial last line on `close()`.
- `ConsoleNodeOutputTest`: the tag format, stderr to stderr, quiet hides the console line and still records the tail, the tail keeps only the last `TAIL_LINES`, `reset`, `quietly` restores the mode after an exception.  It writes to captured `PrintStream`s handed to the adapter's constructor.
- A concurrency test: 32 virtual threads each write 500 lines through one `ConsoleNodeOutput`; every captured line must match `^\[node(\d+)\] line-\d+-node\1$`, and each node's lines are in its own order.  This fails if the lock is removed.
- `SSHClientTest`: the stubbed `ClientSession` writes stdout and stderr to the streams it is given, in pieces; the sink gets whole, redacted lines; the `Response` text is unchanged; a secret command gives the sink nothing; nothing reaches the event bus (the test listener records no event).  The two tests that read `Ssh.CommandOutput` through `BufferedOutputHandler` are rewritten against the sink.
- `DefaultRemoteOperationsServiceTest`: with the `MockSSHClient` fake feeding lines into the sink it is given, the lines reach a recording `NodeOutput` under the host's alias; `output = false` reaches nothing; an upload gives one notice.
- Bring-up (`NodeBringUpTest` with `BringUpFakes`): a fake `NodeSteps.setup` writes lines for its alias into a recording `NodeOutput` and then fails; `NodeFailed.outputTail` holds those lines.  With a replacement, the tail holds only the last instance's lines.  A control failure carries the control node's tail.
- `Up` (`UpTestFixture`, which already wires a fake `RemoteOperationsService`): with `--quiet`, the fake's node lines do not reach stdout and the `NodeReady` line does; a failed node's error output has its tail.  `init --up --quiet` gives the scheduled `Up` quiet mode.
- `Event` text: `NodeFailed` with a tail renders the header and the indented lines, and without a tail renders as today.  `EventSerializationTest` round-trips `outputTail`.
- `ExecRun`: the journal lines come out tagged with the host, through the recording `NodeOutput`.
- `ConsoleNodeOutputTest` also covers `passthrough`: a multi-line text comes out one tagged line per line, and no tail is kept for its source.
- `StressLogsTest`: two pods, one with logs and one empty; the recording `NodeOutput` holds each pod's status line and log lines under its pod name, and the empty pod's `(no logs available yet)` line.  No event is emitted for them.
- `CqlTest`, `StressInfoTest`, `StressListTest` (and a `StressFields` case): the rows or job output reach stdout (captured with the existing `captureStdout` pattern), untagged, and no event carries them.  The tests that read the removed events through `BufferedOutputHandler.messages` are rewritten this way.

Integration tier (`src/integrationTest`): `SSHClientStreamingIntegrationTest` against a real `sshd` in a container (the alpine `sshd` image the proxy tests already build).  A command that prints a line, sleeps 2 s, then prints to stderr: the first line reaches the sink before `executeRemoteCommand` returns, and the stderr line arrives on the stderr stream.  This needs a socket, so it cannot be in the unit tier.

No test asserts help text, and no test only checks that a mock was called.

### D9. Docs

- `events/CLAUDE.md`: the rule "raw output (remote commands, CQL rows, stress job and pod logs) and per-command progress are not events; node and pod lines go through `NodeOutput`, the rest is `println`", the `Ssh` domain now holding only `SsmToolsMissing`, the `Stress` and `Cassandra` removals, and `outputTail` on `NodeFailed`, `ControlNodeFailed` and `InstanceSetupFailed`.
- `providers/CLAUDE.md`: the SSH section names the sink, and the redaction section's four sinks become the debug log, the node output lines, the `Response` and the exception.
- `services/CLAUDE.md` (bring-up): `InstanceRun` resets the tail, failures carry it.
- Root `CLAUDE.md`: next to the event and `println()` rules, one line for node output.
- `docs/reference/commands.md`: `--quiet` on `up`, `init` and `setup-instances`; the tagged output of `exec run`, `cassandra nt` and `cassandra stress logs` (tagged by pod).
- `docs/user-guide/large-clusters.md`: tagged output, quiet mode, and the output tail in "When a node fails".

### D10. How a step stops its attempt

`StepRunner` runs each attempt on its own virtual thread.  How it stops that thread depends on why the step ends:

- The attempt timed out: it is interrupted and waited for up to `Constants.Scale.ATTEMPT_STOP_GRACE_SECONDS` (30 s), so the next attempt never runs beside it on the same instance.  One that does not stop fails the step with no retry.
- The step's pipeline was interrupted (the control node failed, or `up` is stopping): the attempt is interrupted and waited for once, up to the grace, so nothing it does outlives the pipeline, and the interrupt is then rethrown.  An attempt still running after the grace is reported as `Provision.AttemptAbandoned(alias, step, attempt, graceMs)` and left behind.  An attempt that has already stopped is not waited for.
- The node's instance ended: the attempt is interrupted and not waited for, and the step returns at once, so the replacement starts without delay.  The replacement is a new host, and a late line of the old attempt carries the old instance id, so it never enters the new tail (D4).

An attempt after the first prints the notice `<step> attempt N` under the node, kept in the tail, so the reader can tell which attempt a line came from.

### D11. Redaction

`Redaction.kt` (root package) redacts every node line, pod log line (`passthrough`) and exception text through one function.  It strips URL userinfo (`https://user:token@host` → `https://***@host`), the value of `authkey=`, `token=`, `secret=`, `password=` and `key=`, also ending an environment name such as `AWS_SECRET_ACCESS_KEY=` or `K3S_TOKEN=` (case-insensitive, giving `key=***`), the credential of an `Authorization:` header (`Authorization: ***`), and an AWS access key id (`AKIA` and 16 more characters).  `hasRedactedCredentials` still checks the URL form.

### D12. Remote scripts and uploads

`installFile` uploads to a staged path and moves the file into place.  It prints one notice naming the final path and mode (`installing <remote> (mode <mode>)`), not the staged upload.  The registry scripts that copy from S3 (`configure_registry_tls.sh`, `generate_registry_cert.sh`) pass `--no-progress` to `aws s3 cp`.  `logs query` prints its result lines with `println`, and `Logs.QueryResults` is removed, because its lines are raw output.

## Risks / Trade-offs

- [The tag changes output that scripts may parse, for example `cassandra nt status` piped to `grep`] → `grep` still works on the tagged lines; a reader who wants the raw text strips `^\[[^]]*\] `.  The `event-system` requirement "Backward-Compatible Console Output" was about the move to events, and this change alters console text on purpose, as the owner decided.
- [Secret commands no longer print their success output (`tailscale up`, `setup-axonops`)] → Their events (`Tailscale.Connected`, `Setup.AxonOpsConfiguring`) still print, and a failure still raises the exception.  Printing a secret command's output risks echoing the key.
- [A `\r` replaces the segment before it, so output that uses a lone `\r` as a line break shows only its last segment] → Only progress bars do that, and their final state is what the reader wants.
- [The tail holds 30 lines; the cause may be earlier] → The setup failure's reason keeps the script's `ERROR:` lines, and a failed join keeps the agent journal, as today.  30 is enough for the last command of a script; the instance is kept for the rest.
- [Lines from one node during a join attempt and a `joinLog` read on another thread may interleave by line] → They are still whole, tagged lines; the order between two commands on the same node is arrival order.

## Migration Plan

None.  Clusters are ephemeral; Redis subscribers see the removed types stop.

## Owner Decisions

- `--quiet` / `-q` is on `up`, `init` and `setup-instances` only, not on the root command (D6).
- `Cassandra.CqlQueryOutput`, `Stress.CommandOutput` and `Stress.PodLogOutput` are removed in this change too; their output is plain text (D5).

## Open Questions

None.
