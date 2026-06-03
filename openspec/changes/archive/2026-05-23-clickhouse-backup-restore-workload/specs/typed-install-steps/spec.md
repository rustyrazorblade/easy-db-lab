# Typed Install Steps (delta)

Adds `backup` and `restore` as standard lifecycle phase slots in `install.yaml`.

## ADDED Requirements

### Requirement: install.yaml supports backup and restore as optional lifecycle phases

`install.yaml` SHALL support two additional optional top-level lifecycle keys: `backup` and `restore`. Each SHALL accept the same ordered list of typed steps as `install`, `start`, `stop`, and `uninstall`.

#### Scenario: backup phase registered as CLI subcommand
- **WHEN** `install.yaml` declares a non-empty `backup` list
- **THEN** `easy-db-lab <workload> backup` is registered as a subcommand at startup

#### Scenario: restore phase registered as CLI subcommand
- **WHEN** `install.yaml` declares a non-empty `restore` list
- **THEN** `easy-db-lab <workload> restore` is registered as a subcommand at startup

#### Scenario: Omitted backup phase falls back to bin/backup.sh
- **WHEN** `install.yaml` omits the `backup` key
- **AND** `bin/backup.sh` exists in the workload directory
- **THEN** `easy-db-lab <workload> backup` executes `bin/backup.sh` with cluster env vars

#### Scenario: Neither typed phase nor script produces clear error
- **WHEN** neither a `backup` typed phase nor a `bin/backup.sh` exists
- **THEN** `easy-db-lab <workload> backup` fails with a clear error message
