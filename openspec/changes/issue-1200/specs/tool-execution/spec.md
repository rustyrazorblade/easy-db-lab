## MODIFIED Requirements

### Requirement: Foreground command execution with logging

The `exec run` command SHALL execute commands on remote hosts via `systemd-run --wait`, routing stdout and stderr to the systemd journal.  After the command completes, its output SHALL be displayed to the user, each line tagged with the host alias (for example `[db0] /dev/nvme1n1 ...`).  Under `--parallel`, lines from different hosts SHALL never share a line.  If the unit fails, the command SHALL still display the unit's output from the journal, and then report the failure for that host.

#### Scenario: Foreground command runs and output is logged

- **WHEN** the user runs `exec run -t cassandra -- ls /mnt/db1`
- **THEN** the command SHALL execute on all Cassandra nodes via `systemd-run --wait`
- **AND** stdout and stderr SHALL be captured by the systemd journal under the transient unit name
- **AND** the command output SHALL be displayed to the user after completion, each line tagged with its host

#### Scenario: Foreground command respects host filter

- **WHEN** the user runs `exec run -t cassandra --hosts db0 -- df -h`
- **THEN** the command SHALL execute only on host `db0`

#### Scenario: A failed foreground run prints the unit's journal

- **WHEN** a foreground `exec run` fails on a host because the unit exits non-zero
- **THEN** the command prints the unit's journal output for that host, tagged with the host
- **AND** it then reports the failure for that host

#### Scenario: Parallel output stays whole

- **WHEN** the user runs `exec run --parallel -t cassandra -- dmesg`
- **THEN** every output line is one host's tag followed by one of that host's lines
