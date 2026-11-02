# Tool Execution

## Purpose

Remote command execution framework with systemd integration and journald-based log collection.
## Requirements
### Requirement: Foreground command execution with logging

The `exec run` command SHALL execute commands on remote hosts via `systemd-run --wait`, routing stdout and stderr to the systemd journal.  After the command completes, its output SHALL be displayed to the user.  If the unit fails, the command SHALL still display the unit's output from the journal, and then report the failure for that host.

#### Scenario: Foreground command runs and output is logged

- **WHEN** the user runs `exec run -t cassandra -- ls /mnt/db1`
- **THEN** the command SHALL execute on all Cassandra nodes via `systemd-run --wait`
- **AND** stdout and stderr SHALL be captured by the systemd journal under the transient unit name
- **AND** the command output SHALL be displayed to the user after completion

#### Scenario: Foreground command respects host filter

- **WHEN** the user runs `exec run -t cassandra --hosts db0 -- df -h`
- **THEN** the command SHALL execute only on host `db0`

#### Scenario: A failed foreground run prints the unit's journal

- **WHEN** a foreground `exec run` fails on a host because the unit exits non-zero
- **THEN** the command prints the unit's journal output for that host
- **AND** it then reports the failure for that host

### Requirement: Background command execution

The `exec run --bg` command SHALL start a long-running command on remote hosts via `systemd-run` without blocking, with output captured by the systemd journal.

#### Scenario: Background command starts and returns immediately

- **WHEN** the user runs `exec run --bg -t cassandra -- inotifywait -m /mnt/db1/data`
- **THEN** the command SHALL start on all Cassandra nodes as a systemd transient unit
- **AND** the CLI SHALL return immediately with the unit name
- **AND** stdout and stderr SHALL be captured by the systemd journal under the transient unit name

#### Scenario: Background command survives SSH disconnect

- **WHEN** a background command is running and the SSH session ends
- **THEN** the command SHALL continue running as a systemd unit

### Requirement: Unit naming

Background and foreground commands SHALL be run as systemd transient units with predictable names following the pattern `edl-exec-<name>`.  The `<name>` SHALL keep only the characters systemd allows in a unit name (`A-Z`, `a-z`, `0-9`, `:`, `_`, `.`, `-`), and every other character SHALL become `-`.  `exec run` and `exec stop` SHALL build the unit name the same way, so a tool stops under the name it started with.

#### Scenario: User-provided name

- **WHEN** the user runs `exec run --bg --name watch-imports -- inotifywait -m /mnt/db1`
- **THEN** the systemd unit SHALL be named `edl-exec-watch-imports`

#### Scenario: Auto-derived name

- **WHEN** the user runs `exec run --bg -- inotifywait -m /mnt/db1` without `--name`
- **THEN** the unit name SHALL be derived as `edl-exec-<tool>-<epoch>` where `<tool>` is the first token of the command

#### Scenario: A name with characters systemd does not allow is sanitized

- **WHEN** the user runs `exec run --bg --name "my tool's run" -- sleep 60`
- **THEN** the systemd unit is named `edl-exec-my-tool-s-run`
- **AND** the unit name holds no quote or space

#### Scenario: exec stop sanitizes the name the same way

- **WHEN** the user runs `exec stop "my tool's run"`, or `exec stop "edl-exec-my tool's run.service"`
- **THEN** the command stops the unit `edl-exec-my-tool-s-run`

### Requirement: List running background tools

The `exec list` command SHALL show all running `edl-exec-*` systemd units on target hosts.

#### Scenario: List shows running background tools

- **WHEN** the user runs `exec list -t cassandra`
- **THEN** all running `edl-exec-*` units on Cassandra nodes SHALL be displayed
- **AND** each entry SHALL show the unit name and host

#### Scenario: List defaults to all node types

- **WHEN** the user runs `exec list` without `-t`
- **THEN** running `edl-exec-*` units from all node types SHALL be displayed

### Requirement: Stop background tools

The `exec stop` command SHALL stop a named `edl-exec-*` systemd unit on target hosts.

#### Scenario: Stop a named background tool

- **WHEN** the user runs `exec stop watch-imports -t cassandra`
- **THEN** the `edl-exec-watch-imports` unit SHALL be stopped on all Cassandra nodes
- **AND** journal entries SHALL remain for the journald collector to finish shipping

#### Scenario: Stop reports error for unknown unit

- **WHEN** the user runs `exec stop nonexistent`
- **THEN** the command SHALL report that no such unit was found

### Requirement: Journald log collection pipeline

The system SHALL deploy a dedicated OTel collector DaemonSet that reads systemd journal entries using the journald receiver in chroot mode and forwards them via OTLP to the main OTel collector on localhost. The journald collector SHALL be isolated from the main OTel collector so that failures in journald collection do not affect existing log, metric, or trace pipelines.

#### Scenario: Journald collector deployed as separate DaemonSet

- **GIVEN** `grafana update-config` is run, **WHEN** the cluster is provisioned, **THEN** a DaemonSet named `otel-journald` SHALL be deployed on all nodes, separate from the existing `otel-collector` DaemonSet.

#### Scenario: Journald collector reads host journal

- **GIVEN** the journald collector starts, **WHEN** it initializes, **THEN** it SHALL use `root_path: /host` with the host filesystem mounted read-only at `/host` and execute the host's `journalctl` binary.

#### Scenario: Journal entries forwarded to main collector

- **GIVEN** journal entries are read, **WHEN** they are processed, **THEN** they SHALL be exported via OTLP gRPC to `localhost:4317` (the main OTel collector's OTLP receiver).

#### Scenario: Journald collector failure does not affect other pipelines

- **GIVEN** the journald collector crashes or hangs, **WHEN** a failure occurs, **THEN** the main OTel collector SHALL continue collecting metrics, file-based logs, and traces without interruption.

### Requirement: Journal entries have proper timestamps

Journal entries collected from exec-run tools SHALL have timestamps assigned by systemd at the time each line was written, enabling accurate correlation with other log sources in Loki.

#### Scenario: Timestamps enable cross-source correlation

- **GIVEN** an `inotifywait` event occurs at the same time as a Cassandra log entry
- **WHEN** both are queried in Loki
- **THEN** both entries SHALL have timestamps within the same second, not offset by ingestion delay.

### Requirement: exec run runs the command through a shell

`exec run` SHALL run the command on each host as `bash -c <quoted command line>`.  A single command argument SHALL be a shell command line.  Several command arguments SHALL be quoted one by one, so that each keeps its boundaries.

#### Scenario: A single multi-word argument is a shell line

- **WHEN** the user runs `exec run "uname -n; uptime"`
- **THEN** each host runs `bash -c 'uname -n; uptime'` inside the transient unit
- **AND** both commands run

#### Scenario: A named run passes the whole line to the shell

- **WHEN** the user runs `exec run --name qa-uname "uname -a"`
- **THEN** systemd-run runs `bash -c 'uname -a'`, not an executable named `uname -a`

#### Scenario: Separate words keep their boundaries

- **WHEN** the user runs `exec run -- grep "a b" /etc/hosts`
- **THEN** the shell line is `grep 'a b' /etc/hosts`, with `a b` kept as one argument

### Requirement: exec run exits non-zero when any host fails

If `exec run` fails on any host, the command SHALL still run on the other hosts, SHALL report each failed host, and SHALL exit non-zero.

#### Scenario: One host fails

- **WHEN** `exec run` fails on `db1` and succeeds on `db0` and `db2`
- **THEN** the command runs on all three hosts
- **AND** it reports the failure on `db1`
- **AND** it exits non-zero

