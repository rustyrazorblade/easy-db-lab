# Workload Arg Passthrough

## Purpose

Defines how workload phase commands accept positional arguments and make them available to typed steps and shell scripts.

## Requirements

### Requirement: Workload phase commands accept positional arguments

Workload phase commands (`easy-db-lab <workload> <phase>`) SHALL accept zero or more positional arguments after the phase name. These arguments SHALL be injected into the environment variable map used by both typed step execution and shell script invocation.

#### Scenario: First positional arg available as BACKUP_NAME
- **WHEN** the user runs `easy-db-lab clickhouse backup my-snapshot`
- **THEN** `BACKUP_NAME=my-snapshot` is present in the env vars passed to all steps in the `backup` phase

#### Scenario: No positional arg leaves BACKUP_NAME unset
- **WHEN** the user runs `easy-db-lab clickhouse backup` with no backup name
- **THEN** `BACKUP_NAME` is absent from the env var map
- **AND** any step that requires `$BACKUP_NAME` fails at the shell level with a clear error

#### Scenario: Extra args silently available to all phases
- **WHEN** the user runs `easy-db-lab clickhouse start` with no extra args
- **THEN** `BACKUP_NAME` is not injected and the start phase executes normally

### Requirement: Positional args are passed to shell scripts as environment variables

Shell script files in `bin/` SHALL receive the same env var set as typed `shell` steps, including `BACKUP_NAME` when provided.

#### Scenario: Shell script uses BACKUP_NAME
- **WHEN** `bin/restore.sh` references `$BACKUP_NAME`
- **AND** the user runs `easy-db-lab myworkload restore my-snapshot`
- **THEN** `$BACKUP_NAME` resolves to `my-snapshot` inside the script
