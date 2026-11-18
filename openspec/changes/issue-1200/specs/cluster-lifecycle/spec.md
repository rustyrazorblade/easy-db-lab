## ADDED Requirements

### Requirement: up has a quiet option

`up`, `init` and `setup-instances` MUST accept `--quiet` (`-q`).  With it, the command MUST NOT print node output lines or upload lines, and MUST still print every event: each node's Ready line, every failure, replacements, control-plane health, the gate and the progress line.  `init --up --quiet` MUST run its `up` in quiet mode.  Without it, node output MUST be printed tagged with the node.

#### Scenario: A quiet up
- **WHEN** the user runs `up --quiet` on a cluster of 80 nodes
- **THEN** the console shows no setup or K3s script lines, and shows one Ready line per node and the progress line

#### Scenario: init passes quiet to up
- **WHEN** the user runs `init --up --quiet`
- **THEN** the `up` it runs prints no node output lines

### Requirement: A node failure report carries the node's output tail

`Provision.NodeFailed`, `Provision.ControlNodeFailed` and `Provision.InstanceSetupFailed` MUST carry the failed node's last `Constants.NodeOutput.TAIL_LINES` output lines as structured data, and their console text MUST print those lines under the reason.  This MUST hold in quiet mode and in normal mode.  When a node was replaced, the tail MUST be the output of its last instance only.  A node with no output MUST print no tail.

#### Scenario: A quiet up shows a failed node's output
- **GIVEN** `up --quiet` and `setup_instance.sh` fails on db7 after printing lines
- **WHEN** db7 fails
- **THEN** stderr shows `db7 failed at setup: ...` followed by db7's last output lines

#### Scenario: A replaced node's tail is from its last instance
- **GIVEN** db7's first instance fails setup and is replaced, and the replacement fails setup too
- **WHEN** db7 fails
- **THEN** its tail holds only lines the replacement printed

#### Scenario: The control node's failure carries its output
- **WHEN** the control node fails at its K3s server step
- **THEN** the `Provision.ControlNodeFailed` line is followed by the control node's last output lines
