## ADDED Requirements

### Requirement: Foreground command execution with logging

The `exec run` command SHALL execute commands on remote hosts via `systemd-run --wait`, routing stdout and stderr to a log file under `/var/log/easydblab/tools/`. After the command completes, its output SHALL be displayed to the user.

#### Scenario: Foreground command runs and output is logged

- **WHEN** the user runs `exec run -t cassandra -- ls /mnt/db1`
- **THEN** the command SHALL execute on all Cassandra nodes via `systemd-run --wait`
- **AND** stdout and stderr SHALL be written to `/var/log/easydblab/tools/<name>.log`
- **AND** the command output SHALL be displayed to the user after completion

#### Scenario: Foreground command respects host filter

- **WHEN** the user runs `exec run -t cassandra --hosts db0 -- df -h`
- **THEN** the command SHALL execute only on host `db0`

### Requirement: Background command execution

The `exec run --bg` command SHALL start a long-running command on remote hosts via `systemd-run` without blocking, routing output to a log file.

#### Scenario: Background command starts and returns immediately

- **WHEN** the user runs `exec run --bg -t cassandra -- inotifywait -m /mnt/db1/data`
- **THEN** the command SHALL start on all Cassandra nodes as a systemd transient unit
- **AND** the CLI SHALL return immediately with the unit name
- **AND** stdout and stderr SHALL be written to `/var/log/easydblab/tools/<name>.log`

#### Scenario: Background command survives SSH disconnect

- **WHEN** a background command is running and the SSH session ends
- **THEN** the command SHALL continue running as a systemd unit

### Requirement: Unit naming

Background and foreground commands SHALL be run as systemd transient units with predictable names following the pattern `edl-exec-<name>`.

#### Scenario: User-provided name

- **WHEN** the user runs `exec run --bg --name watch-imports -- inotifywait -m /mnt/db1`
- **THEN** the systemd unit SHALL be named `edl-exec-watch-imports`
- **AND** the log file SHALL be `/var/log/easydblab/tools/watch-imports.log`

#### Scenario: Auto-derived name

- **WHEN** the user runs `exec run --bg -- inotifywait -m /mnt/db1` without `--name`
- **THEN** the unit name SHALL be derived as `edl-exec-<tool>-<epoch>` where `<tool>` is the first token of the command
- **AND** the log file SHALL use the same derived name

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
- **AND** the log file SHALL remain for OTel to finish shipping

#### Scenario: Stop reports error for unknown unit

- **WHEN** the user runs `exec stop nonexistent`
- **THEN** the command SHALL report that no such unit was found

### Requirement: Tool log directory exists on all nodes

The directory `/var/log/easydblab/tools/` SHALL exist on all Cassandra and stress nodes after AMI provisioning.

#### Scenario: Directory created during Packer build

- **WHEN** a node is provisioned from the AMI
- **THEN** `/var/log/easydblab/tools/` SHALL exist and be writable by root
