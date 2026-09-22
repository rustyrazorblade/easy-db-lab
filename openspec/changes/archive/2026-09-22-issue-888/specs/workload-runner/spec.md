# Workload Runner

## MODIFIED Requirements

### Requirement: Installed workload dirs are discovered as top-level subcommands
At startup, the CLI SHALL scan `context.workingDirectory` for subdirectories that contain a `kit.yaml` descriptor. Each such directory SHALL be registered as a top-level PicoCLI subcommand whose sub-subcommands are the executable files found in `bin/`.

A `bin/` directory SHALL NOT qualify a directory as an installed kit. Source checkouts, virtualenvs, and unrelated project trees contain one, and treating them as kits registered them as CLI commands.

#### Scenario: Kit dir with kit.yaml discovered
- **WHEN** `./clickhouse/kit.yaml` exists
- **AND** `./clickhouse/bin/start` exists and is executable
- **THEN** `easy-db-lab clickhouse start` is a valid command

#### Scenario: Dir without kit.yaml is not registered
- **WHEN** `./trunk/bin/cassandra` exists and is executable
- **AND** `./trunk/kit.yaml` does not exist
- **THEN** `easy-db-lab trunk` is not registered as a subcommand

#### Scenario: Dir with neither marker is not registered
- **WHEN** `./notes/` exists and contains neither `kit.yaml` nor a `bin/` subdirectory
- **THEN** `easy-db-lab notes` is not registered as a subcommand

#### Scenario: Multiple workloads discovered
- **WHEN** both `./clickhouse/kit.yaml` and `./presto/kit.yaml` exist
- **THEN** both `easy-db-lab clickhouse` and `easy-db-lab presto` are valid subcommands

### Requirement: Discovered workloads appear in --help output
Workload subcommands registered from the working directory SHALL appear in the top-level `easy-db-lab --help` output alongside static commands.

#### Scenario: Help lists discovered workload
- **WHEN** `./clickhouse/kit.yaml` exists and `easy-db-lab --help` is run
- **THEN** `clickhouse` appears in the command listing

#### Scenario: Help omits a bin/-only directory
- **WHEN** `./trunk/bin/cassandra` exists and is executable, `./trunk/kit.yaml` does not exist, and `easy-db-lab --help` is run
- **THEN** `trunk` does not appear in the command listing

## ADDED Requirements

### Requirement: Filesystem kit discovery has a single source of truth
Every part of the CLI that reports which kits are installed on disk SHALL determine that from one shared implementation. No call site SHALL carry its own copy of the rule.

This covers at least dynamic subcommand registration and the `=== KITS ===` section of `easy-db-lab status`, so the two read one discovery rule. Registration MAY still drop a discovered kit afterwards — on a name collision with a core command, or when building its command group fails — so the two listings are not guaranteed identical.

#### Scenario: status lists only directories holding kit.yaml
- **WHEN** the working directory holds `./clickhouse/kit.yaml` and an executable `./trunk/bin/cassandra` with no `./trunk/kit.yaml`
- **AND** `easy-db-lab status` runs
- **THEN** the `=== KITS ===` section lists `clickhouse` and does not list `trunk`

#### Scenario: status omits the KITS section when nothing qualifies
- **WHEN** the working directory holds only directories with a `bin/` and no `kit.yaml`
- **AND** `easy-db-lab status` runs
- **THEN** no `=== KITS ===` section is printed

#### Scenario: Both listings agree
- **WHEN** the discovery rule changes
- **THEN** subcommand registration and the `status` listing change together, because both read the same implementation
