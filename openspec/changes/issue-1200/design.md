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

- buffers bytes and emits a line at each `\n`; a `\r` before it is dropped; a lone `\r` also ends a line (progress output);
- decodes each line as UTF-8 only once it is complete, so a multi-byte character split across two writes is never broken (a `\n` byte never occurs inside a UTF-8 sequence);
- emits a line of `Constants.NodeOutput.MAX_LINE_BYTES` (8192) bytes without waiting for a newline, so output with no newline never grows without bound;
- emits the last partial line on `close()`, which `SSHClient` calls when the command ends, on success and on failure.

Each line goes through `redactUrlCredentials` before the sink sees it.  A URL never spans a line, so line-wise redaction covers what whole-text redaction covers today.

`DefaultRemoteOperationsService.executeRemotely` builds the sink from `host.alias`: `output = true` gives `{ stream, line -> nodeOutput.line(host.alias, stream, line) }`, `output = false` gives none.  The `RemoteOperationsService` signature does not change, so the 69 callers do not change.

`secret = true` streams nothing and records nothing in the tail.  `SSHClient` already treats a secret command's output as unrepeatable on failure, because such a command echoes its own argument (a Tailscale auth key, an AxonOps key).  Today the success output of `tailscale up` and `setup-axonops` is printed; after this change it is not.

### D3. The tag and the console lock

`ConsoleNodeOutput.line` writes `"[$node] $text"`.  Stdout lines go to stdout with `println`; stderr lines go to `System.err`, as `ConsoleEventListener` does for errors.  Every line is tagged, also when a command targets one node: the rule is simple, and a single-node command cannot be told apart from a filtered multi-node one by the reader.

One `ReentrantLock` guards each console write and the tail update, so a line is one write and a line from one node is never split by another.  A `ReentrantLock`, not `synchronized`, because bring-up runs on virtual threads, and a virtual thread blocked in a `synchronized` block pins its carrier.  The lock covers only the write of an already-complete line, never a remote read, so a slow node never blocks another node's output.

Events printed by `ConsoleEventListener` are single `println` calls of a whole string, so they never share a line with a node line either.

### D4. The per-node tail

`ConsoleNodeOutput` keeps a ring buffer per node alias of the last `Constants.NodeOutput.TAIL_LINES` (30) lines, stdout and stderr together, in arrival order, tagged with nothing.  It records them in quiet mode and in normal mode.  It also records the notice lines (uploads).  Memory: 30 lines × 1,000 nodes at a typical 100 bytes is about 3 MB; at the 8 KB line cap it is bounded at 240 MB, which is never reached in practice by the node scripts.

The bring-up reads and resets it:

- `InstanceRun` calls `reset(alias)` when an instance starts its steps, so after a replacement the tail is the last instance's output, the one that is kept for debugging.
- `NodePipeline.failed` puts `tail(alias)` on `NodeFailed.outputTail`.
- `NodeBringUp.controlFailed` puts the control node's tail on `ControlNodeFailed.outputTail`.
- `SetupInstance` puts the host's tail on `Provision.InstanceSetupFailed.outputTail`.

`BringUpRun` takes the port as `nodeOutput: NodeOutputTail` (a narrow read side of `NodeOutput`: `tail` and `reset`), passed in by `NodeBringUp`'s caller with the other ports.  Files in `bringup/` stay free of SSH and AWS types.

`NodeFailed`, `ControlNodeFailed` and `InstanceSetupFailed` render the tail under the reason, indented, with a header `last output of db7:`.  The tail is printed in quiet mode and in normal mode.  In normal mode the lines were shown already, but at 80 nodes they are spread through thousands of other lines; the failure report is where the reader looks.  An empty tail prints nothing.  `NodesFailed` (the summary at the end) does not repeat the tails.

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

`--quiet` / `-q`, on `up`, on `init` (it passes `quiet` to the `Up` it schedules for `--up`) and on `setup-instances`.  `Up` runs its whole `execute()` inside `nodeOutput.quietly { }`.  Nested commands that `up` runs (`WriteConfig`, `TailscaleStart`) use the same singleton, so they are quiet too.  `quietly` restores the previous mode when it returns or throws, so a REPL session or the server is not left quiet.

What quiet hides: node lines and notice lines (D3, D5).  What still prints: every event, which includes each node's Ready line, each failure with its output tail, replacements, control-plane health, the gate and the progress line; and everything the command itself prints with `println`.

`ConsoleNodeOutput` holds the mode in one `@Volatile` field that only `quietly` sets.  A `quietly` inside another `quietly` keeps quiet.

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

## Risks / Trade-offs

- [The tag changes output that scripts may parse, for example `cassandra nt status` piped to `grep`] → `grep` still works on the tagged lines; a reader who wants the raw text strips `^\[[^]]*\] `.  The `event-system` requirement "Backward-Compatible Console Output" was about the move to events, and this change alters console text on purpose, as the owner decided.
- [Secret commands no longer print their success output (`tailscale up`, `setup-axonops`)] → Their events (`Tailscale.Connected`, `Setup.AxonOpsConfiguring`) still print, and a failure still raises the exception.  Printing a secret command's output risks echoing the key.
- [A lone `\r` as a line break turns a progress bar into many lines] → The node scripts use quiet tools (`curl -s`, `apt-get -q`); a script that draws a bar would also flood today's single-blob output.
- [The tail holds 30 lines; the cause may be earlier] → The setup failure's reason keeps the script's `ERROR:` lines, and a failed join keeps the agent journal, as today.  30 is enough for the last command of a script; the instance is kept for the rest.
- [Lines from one node during a join attempt and a `joinLog` read on another thread may interleave by line] → They are still whole, tagged lines; the order between two commands on the same node is arrival order.

## Migration Plan

None.  Clusters are ephemeral; Redis subscribers see the removed types stop.

## Owner Decisions

- `--quiet` / `-q` is on `up`, `init` and `setup-instances` only, not on the root command (D6).
- `Cassandra.CqlQueryOutput`, `Stress.CommandOutput` and `Stress.PodLogOutput` are removed in this change too; their output is plain text (D5).

## Open Questions

None.
