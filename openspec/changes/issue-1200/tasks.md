## 1. The node output port

- [x] 1.1 `Constants.NodeOutput`: `TAIL_LINES = 30`, `MAX_LINE_BYTES = 8192`
- [x] 1.2 `output/LineSplittingOutputStream.kt` (D2) with `LineSplittingOutputStreamTest`: split chunks, a split UTF-8 character, `\r\n`, a lone `\r`, an empty line, the line cap, the partial last line on `close()`
- [x] 1.3 `output/NodeOutput.kt`: the `NodeOutput` port (`line`, `notice`, `passthrough`, `tail`, `reset`, `quietly`), the narrow `NodeOutputTail` read side, the `OutputStream` enum (`Stdout`, `Stderr`)
- [x] 1.4 `output/ConsoleNodeOutput.kt` (D3, D4, D6): the `[alias] ` tag, stdout and stderr streams from its constructor, one `ReentrantLock` around each write and tail update, a bounded ring buffer per alias, `quietly` with restore on exit.  Koin singleton in `OutputModule`, bound as both `NodeOutput` and `NodeOutputTail`
- [x] 1.5 `ConsoleNodeOutputTest`: tag format, stderr routing, quiet hides lines and keeps the tail, the tail bound, `reset`, `quietly` restores after an exception, nested `quietly`, `passthrough` splits into tagged lines and keeps no tail
- [x] 1.6 Concurrency test: 32 virtual threads × 500 lines through one `ConsoleNodeOutput`; every line matches its own node's tag and each node's order holds
- [x] 1.7 A recording `NodeOutput` fake for tests in `src/test`, wired in `TestModules`

## 2. SSH streams into the port

- [x] 2.1 `ssh/RemoteOutputSink.kt`; `ISSHClient.executeRemoteCommand(command, sink: RemoteOutputSink?, secret)` replaces the `output: Boolean` parameter
- [x] 2.2 `SSHClient`: tee each MINA stream into its `ByteArrayOutputStream` and a `LineSplittingOutputStream` that redacts each line and passes it to the sink; close both on success and failure; no sink for a secret command; remove the `Ssh.CommandOutput` and `Ssh.UploadingFile` emits
- [x] 2.3 `DefaultRemoteOperationsService`: build the sink from `host.alias` when `output` is true; `upload` and `uploadDirectory` print one `notice` each; remove the `Ssh.UploadingDirectory` emit
- [x] 2.4 `MockSSHClient` and the other `ISSHClient` test doubles: feed their configured output into the sink they are given
- [x] 2.5 `SSHClientTest`: lines reach the sink whole and redacted when the stubbed session writes in pieces; stderr goes to the stderr stream; the `Response` is unchanged; a secret command gives the sink nothing; no event is emitted.  Rewrite the two tests that read `Ssh.CommandOutput`
- [x] 2.6 `DefaultRemoteOperationsServiceTest`: lines reach the recording `NodeOutput` under the alias; `output = false` reaches nothing; one notice per upload call
- [x] 2.7 Integration: `SSHClientStreamingIntegrationTest` against a real `sshd` container; the first line reaches the sink before the command returns, and stderr arrives on its own stream

## 3. Events

- [x] 3.1 Remove `Ssh.ExecutingCommand`, `Ssh.ExecutingHiddenCommand`, `Ssh.CommandOutput`, `Ssh.UploadingFile`, `Ssh.UploadingDirectory`, `Cassandra.NodetoolOutput`, `Cassandra.PatchOutput`, `Cassandra.PatchStderr`, `Cassandra.CqlQueryOutput`, `Stress.CommandOutput`, `Stress.PodLogOutput`, `Stress.PodLogHeader`, `Stress.PodLogsEmpty`, `Stress.PodLogSeparator`
- [x] 3.2 Add `outputTail: List<String> = emptyList()` to `Provision.NodeFailed`, `Provision.ControlNodeFailed` and `Provision.InstanceSetupFailed`; render `last output of <alias>:` and the indented lines under the reason, nothing when empty
- [x] 3.3 Tests: the rendered text with and without a tail; `EventSerializationTest` round-trips `outputTail`

## 4. Bring-up carries the tail

- [x] 4.1 `BringUpRun` takes a `NodeOutputTail`; `NodeBringUp`'s caller (`Up`) passes the Koin singleton
- [x] 4.2 `InstanceRun` resets the alias's tail when an instance starts its steps
- [x] 4.3 `NodePipeline.failed` and `NodeBringUp.controlFailed` put the tail on their events
- [x] 4.4 `SetupInstance` puts the host's tail on `InstanceSetupFailed`
- [x] 4.5 `NodeBringUpTest` (with `BringUpFakes`): a failing setup's lines are on `NodeFailed.outputTail`; after a replacement only the last instance's lines are; a control failure carries the control node's tail

## 5. Quiet mode

- [x] 5.1 `--quiet` / `-q` on `Up`; `execute()` runs inside `nodeOutput.quietly { }` when set
- [x] 5.2 `--quiet` / `-q` on `Init`, passed to the `Up` it schedules for `--up`
- [x] 5.3 `--quiet` / `-q` on `SetupInstance`
- [x] 5.4 `UpTestFixture` tests: with `--quiet` no node line reaches stdout and the Ready lines do; a failed node's error output has its tail; `init --up --quiet` runs a quiet `Up`

## 6. Other commands

- [x] 6.1 `cassandra nt`: run with `output = true`; drop the header event
- [x] 6.2 `cassandra update-config`: run `patch-config` with `output = true`; drop `PatchOutput` and `PatchStderr`; keep the success check and `PatchFailed`
- [x] 6.3 `exec run`: the journal command runs with `output = true`; drop the `=== host ===` block (D7).  Test that the journal lines come out tagged with the host through the recording `NodeOutput`
- [x] 6.4 `cassandra cql`: print the rows with `println`, untagged.  `CqlTest` reads them from captured stdout and checks no event carries them
- [x] 6.5 `cassandra stress info`, `list`, `fields`: print the job output with `println`, untagged.  `StressInfoTest`, `StressListTest` and a `StressFields` case read it from captured stdout
- [x] 6.6 `cassandra stress logs`: for each pod, `passthrough(pod, "status <status>")`, then its logs through `passthrough(pod, logs)` or `passthrough(pod, "(no logs available yet)")`; keep `Stress.PodLogFailed`.  `StressLogsTest`: two pods, one empty, through the recording `NodeOutput`
- [x] 6.7 Update the other command tests that asserted the removed events

## 7. Docs

- [x] 7.1 `events/CLAUDE.md`: the rule, the `Ssh` domain, the `Cassandra` and `Stress` removals, `outputTail`
- [x] 7.2 `providers/CLAUDE.md`: the sink in the SSH section, the redaction sinks
- [x] 7.3 `services/CLAUDE.md` (bring-up): the tail reset and the failure tail
- [x] 7.4 Root `CLAUDE.md`: one line for node output beside the event and `println()` rules
- [x] 7.5 `docs/reference/commands.md`: `--quiet` on `up`, `init`, `setup-instances`; tagged output of `exec run`, `cassandra nt` and `cassandra stress logs`
- [x] 7.6 `docs/user-guide/large-clusters.md`: tagged output, quiet mode, the tail in "When a node fails"

## 8. Review fixes (five-lens review of PR 1210)

- [x] 8.1 No JDK 21 carrier deadlock: `ssh/PlatformThreadCall` runs every MINA call of a virtual caller on a platform daemon thread; the drain runs on a platform daemon thread; `SSHClient.operationLock` is a `ReentrantLock`.  `SSHClientStreamingIntegrationTest` saturation case: more virtual callers than processors against a briefly blocking sink, shown to time out first
- [x] 8.2 Splitter: the `\r` segment-replace rule; pieces of at most `MAX_LINE_BYTES`, cut before a character that does not fit; a forced cut on a run of continuation bytes; a URL near a hard cut carried to the next piece.  `LineSplittingOutputStreamTest`
- [x] 8.3 Redaction: `redact()` (root package `Redaction.kt`) adds `key=`-style values, `Authorization:` values and AWS access key ids; `ConsoleNodeOutput` redacts every line, `passthrough` too; no sink for a secret command in `DefaultRemoteOperationsService`
- [x] 8.4 `NodeOutput`: `notice` is not kept; `mark` is kept (retry, attempt N); stderr entries prefixed `stderr: `; entries cut to `TAIL_ENTRY_CHARS`; tags padded to the longest alias; `reset(node, instance)` and lines from another instance not kept; `quietly` as an `AtomicInteger` depth.  `ConsoleNodeOutputTest`, `OutputModuleTest`
- [x] 8.5 Uploads: `installFile` prints one notice with the final path and mode; `aws s3 cp --no-progress` in the registry scripts; a drain timeout gives the notice `output still printing; the tail may be incomplete`
- [x] 8.6 `StepRunner`: a failed remote command's reason is its summary; an interrupted step waits once and only for a live attempt, then emits `Provision.AttemptAbandoned`; an ended instance does not wait; attempts after the first are marked.  `StepRunnerTest`
- [x] 8.7 `BringUpReporting` holds the event bus and node output for `NodeBringUp` and `BringUpRun`; `InstanceRun` resets with the instance id
- [x] 8.8 `up --quiet` covers the node pipelines only; `init --quiet` without `--up` is refused; `NodesFailed` prints one line per node.  `UpTest` quiet-failure case, `InitTest`, `BringUpEventsTest`
- [x] 8.9 `setup-instances` reports any failed remote command of a setup as `InstanceSetupFailed`; `cassandra nt` fails with the summary; `exec run` prints `no output` for an empty journal; `logs query` prints its lines and `Logs.QueryResults` is removed
- [x] 8.10 Test fixes: thread-safe event lists in `UpVpcCidrTest` and `ClusterProvisioningServiceTest`; `MockSSHClient` streams through the real splitter; `UpdateConfigTest` checks `patch-config` runs with its output; `SSHClientTest` lost-connection case

## 9. Verify

- [x] 9.1 `./gradlew ktlintFormat`, then `./gradlew check` on JDK 21 (build-engineer, Sonnet)
- [x] 9.2 Real cluster: `init --up` of 6 nodes; every node script line is tagged; Redis (a local `redis` subscriber) shows `NodeReady` and no output; `up --quiet` on the same cluster prints the Ready and progress lines only; `exec run --parallel` output is tagged and whole.  Tear down
