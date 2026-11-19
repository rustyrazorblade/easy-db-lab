## ADDED Requirements

### Requirement: up has a quiet option

`up`, `init` and `setup-instances` MUST accept `--quiet` (`-q`).  With it, the command MUST NOT print node output lines or notices, and MUST still print every event: each node's Ready line, every failure, replacements, control-plane health, the gate and the progress line.  `up`'s quiet mode MUST cover the node pipelines only, and MUST end when the last pipeline ends; the cluster-wide steps after them print as they do without it.  `init --up --quiet` MUST run its `up` in quiet mode.  `init --quiet` without `--up` MUST be refused before anything is written.  Without it, node output MUST be printed tagged with the node.

#### Scenario: A quiet up
- **WHEN** the user runs `up --quiet` on a cluster of 80 nodes
- **THEN** the console shows no setup or K3s script lines, and shows one Ready line per node and the progress line

#### Scenario: init passes quiet to up
- **WHEN** the user runs `init --up --quiet`
- **THEN** the `up` it runs prints no node output lines

#### Scenario: init refuses quiet without up
- **WHEN** the user runs `init --quiet` without `--up`
- **THEN** it fails naming `--quiet` and `--up`, and writes no cluster state

### Requirement: A node failure report carries the node's output tail

`Provision.NodeFailed`, `Provision.ControlNodeFailed` and `Provision.InstanceSetupFailed` MUST carry the failed node's last `Constants.NodeOutput.TAIL_LINES` output lines as structured data, and their console text MUST print those lines under the reason.  This MUST hold in quiet mode and in normal mode.  When a node was replaced, the tail MUST be the output of its last instance only.  A node with no output MUST print no tail.  A failed remote command's reason MUST be its one-line summary (the host and `Remote command failed (N): <command>`), not its output again; a failed join MUST keep the agent's log in its reason.  `setup-instances` MUST report a failed remote command of a setup, not only a failed setup script, as `Provision.InstanceSetupFailed` with its summary and the tail.  `Provision.NodesFailed` MUST print one line per node, with the first line of its reason.

#### Scenario: A quiet up shows a failed node's output
- **GIVEN** `up --quiet` and `setup_instance.sh` fails on db7 after printing lines
- **WHEN** db7 fails
- **THEN** stderr shows `db7 failed at setup: ...` followed by `last output of db7:` and db7's last output lines, and quiet mode has ended

#### Scenario: A replaced node's tail is from its last instance
- **GIVEN** db7's first instance fails setup and is replaced, and the replacement fails setup too
- **WHEN** db7 fails
- **THEN** its tail holds only lines the replacement printed

#### Scenario: The control node's failure carries its output
- **WHEN** the control node fails at its K3s server step
- **THEN** the `Provision.ControlNodeFailed` line is followed by the control node's last output lines

#### Scenario: The failure list is one line per node
- **GIVEN** db3 failed its join, with the agent's log in its reason
- **WHEN** `Provision.NodesFailed` is printed
- **THEN** db3's entry is one line, ending with the first line of its reason

### Requirement: A bring-up step stops its attempts within a bound

A step whose pipeline is interrupted MUST interrupt its running attempt and wait for it at most once, up to the attempt stop grace, and only when the attempt is still running.  An attempt still running after the grace MUST be reported as `Provision.AttemptAbandoned` and left behind.  A step whose instance ended MUST interrupt its attempt and return at once, without waiting, so the replacement starts.  Every attempt after the first MUST be marked in the node's output as `<step> attempt <N>` before it runs.

#### Scenario: An interrupted step with a stuck attempt
- **GIVEN** an attempt that ignores interrupts and a grace of 50 ms
- **WHEN** the step's pipeline is interrupted
- **THEN** the step is interrupted within about one second, and `Provision.AttemptAbandoned` names the node, the step and the attempt

#### Scenario: An ended instance does not wait
- **GIVEN** an attempt that ignores interrupts
- **WHEN** its instance ends
- **THEN** the step returns at once with the instance's end

#### Scenario: A retried step marks its attempts
- **WHEN** db1's setup succeeds on its third attempt
- **THEN** db1's output holds `setup attempt 2` before the second attempt's lines and `setup attempt 3` before the third's

### Requirement: A node that fails before it joins leaves no old Node behind

A database or application node that fails at a step before its node cleanup MUST delete the alias's Node object and its K3s node password when it fails, if an earlier instance of the alias may have registered them: the node was replaced in this `up`, or its first instance was launched in this `up`.  It MUST NOT delete them for a node `up` found running and never replaced, because they may be its own.  The Node's kubelet is gone, so it never posts status again, and the DaemonSet pods bound to it would fail the post-steps' rollout wait on a node already reported failed.  A deletion that fails MUST be added to the node's failure reason; the node has failed either way.

#### Scenario: A replaced node fails at setup
- **GIVEN** app0 was found running and registered, and its setup fails on every instance
- **WHEN** app0 fails after its 2 replacements
- **THEN** app0's Node object and node password are deleted, and the observability rollout does not wait on app0

#### Scenario: A found-running node fails with no replacement
- **WHEN** a found-running db1 fails at its image check
- **THEN** db1's Node object and node password are kept

### Requirement: The health monitor's stop is not an unhealthy sample

A health sample cut short because the monitor itself was stopped (its thread was interrupted) MUST NOT be recorded: it MUST NOT report `Provision.ControlPlaneUnhealthy` or change the latch.

#### Scenario: The bring-up ends during a sample
- **WHEN** the last pipeline ends while a health sample is in flight
- **THEN** no `Control plane unhealthy` line is printed
