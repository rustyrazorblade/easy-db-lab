## Why

During `up`, the raw output of the node scripts (`setup_instance.sh`, the K3s scripts) is printed with no node name.  In the 6-node #1158 test, 497 of 603 lines had no node name.  At 80 nodes in parallel that is about 6,600 mixed lines, and a failure cannot be traced to its node.  The output also goes through the event bus as `Event.Ssh.CommandOutput`, with `Ssh.UploadingFile` and `Ssh.UploadingDirectory` around it.  These are not domain facts.  They reach Redis and every event consumer, and they fill an agent's context window.  This is on the fix list for the 80-node run.

## What Changes

- Every line of raw remote output is printed with its node alias, for example `[db12] Discarding blocks...Done.`  Output is split into lines per stream (stdout, stderr) as it arrives, so it streams live, and each line is one write.  Lines from parallel nodes never share a line.
- Remote stderr is printed too (to stderr, tagged), not only stdout.  `setup_instance.sh` writes its `ERROR:` lines to stderr, and today they are never shown when the command succeeds.
- Raw remote output is plain console text.  It goes through a new provider-neutral port, `NodeOutput`, not the event bus.  **BREAKING** (for Redis subscribers): the event types `Ssh.CommandOutput`, `Ssh.ExecutingCommand`, `Ssh.ExecutingHiddenCommand`, `Ssh.UploadingFile`, `Ssh.UploadingDirectory`, `Cassandra.NodetoolOutput`, `Cassandra.PatchOutput` and `Cassandra.PatchStderr` are removed.  An upload prints one tagged text line per upload call instead.
- `Provision.NodeReady` and `Provision.NodeFailed` stay.  `NodeFailed`, `Provision.ControlNodeFailed` and `Provision.InstanceSetupFailed` (from `setup-instances`) gain `outputTail`: the last `Constants.NodeOutput.TAIL_LINES` lines of that node's output, kept in a bounded per-node buffer.  Their console text prints the tail under the reason.
- New option `--quiet` / `-q` on `up`, on `init` (passed to the `up` that `--up` runs) and on `setup-instances`.  It hides raw remote output and the upload lines.  Every event still prints, so node-ready lines, failures, replacements and the progress line still show.  A failed node's report still has its output tail.
- `exec run`, `cassandra nt`, `cassandra update-config`, kits and every other command that runs a remote command with output keep printing that output, now tagged.  `exec run` prints the unit's journal as tagged lines instead of under an `=== host ===` header.

## Capabilities

### New Capabilities

- `node-output`: how raw output of commands run on cluster nodes is shown: tagged with the node, one whole line per write, plain text and not events, the per-node tail, and quiet mode.

### Modified Capabilities

- `event-system`: raw remote output and per-command progress chatter are not events.
- `cluster-lifecycle`: `up` (and `init --up`, `setup-instances`) take `--quiet`; a failed node's report carries its output tail.
- `tool-execution`: `exec run` prints the unit's output as lines tagged with the host.

## Impact

- Code: `ssh/SSHClient.kt` and `ssh/ISSHClient.kt` (stream lines to a sink instead of emitting an event), `providers/ssh/DefaultRemoteOperationsService.kt` (tags with the host alias, prints upload lines), a new `output/NodeOutput.kt` port with `ConsoleNodeOutput`, and a line splitter.  `events/Event.kt` (removals; `outputTail` on `NodeFailed`, `ControlNodeFailed` and `InstanceSetupFailed`).  `services/bringup/` (`NodePipeline`, `NodeBringUp`, `BringUpPortSet`: read and reset the tail).  `commands/Up.kt`, `Init.kt`, `SetupInstance.kt` (`--quiet`), `commands/cassandra/Nodetool.kt`, `UpdateConfig.kt`, `commands/exec/ExecRun.kt`.  `Constants.NodeOutput`.
- Tests: the SSH test doubles (`MockSSHClient`, `TestModules`), `SSHClientTest`, the bring-up tests, and the command tests that asserted the removed events.
- Docs: `events/CLAUDE.md`, `providers/CLAUDE.md`, `services/CLAUDE.md`, root `CLAUDE.md`, `docs/reference/commands.md`, `docs/user-guide/large-clusters.md`.
- Redis: subscribers no longer receive the removed types.  Clusters are ephemeral and nothing in this repo subscribes to them.
