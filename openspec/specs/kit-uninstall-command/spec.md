# Kit Uninstall Command

## Purpose

Provides a top-level `uninstall` command group that lists installed kits exposing an uninstall phase as subcommands, runs a kit's uninstall phase, and removes its local directory on success.

## Requirements

### Requirement: Top-level uninstall command group exists
The CLI SHALL expose a top-level `uninstall` command group that is always present in the help output, even when no kits are installed.

#### Scenario: Help output when no kits installed
- **WHEN** the user runs `easy-db-lab uninstall --help`
- **THEN** the command prints usage information for the `uninstall` group

### Requirement: Installed kits with an uninstall phase are listed as subcommands
The CLI SHALL register a subcommand under `uninstall` for each kit directory in the working directory that exposes an uninstall phase, where an uninstall phase is defined as either a `bin/uninstall.sh` script or a non-empty `uninstall` step list in `config.yaml`.

#### Scenario: Kit with bin/uninstall.sh is registered
- **WHEN** a kit directory exists with `bin/uninstall.sh` present
- **THEN** `easy-db-lab uninstall <kit>` is a valid command

#### Scenario: Kit with typed uninstall steps is registered
- **WHEN** a kit directory exists with an `config.yaml` containing a non-empty `uninstall` section
- **THEN** `easy-db-lab uninstall <kit>` is a valid command

#### Scenario: Kit without an uninstall phase is not registered
- **WHEN** a kit directory exists with no `bin/uninstall.sh` and no `uninstall` steps in `config.yaml`
- **THEN** `easy-db-lab uninstall <kit>` is NOT a valid command

### Requirement: Running uninstall executes the kit uninstall phase then removes the local directory
Running `easy-db-lab uninstall <kit>` SHALL execute the kit's uninstall phase. On success, the local kit directory SHALL be deleted.

#### Scenario: Successful uninstall removes local directory
- **WHEN** the user runs `easy-db-lab uninstall presto` and the uninstall phase exits with code 0
- **THEN** the `presto/` directory in the working directory is deleted

#### Scenario: Failed uninstall preserves local directory
- **WHEN** the user runs `easy-db-lab uninstall <kit>` and the uninstall phase exits with a non-zero code
- **THEN** the local kit directory is preserved for debugging
- **THEN** the CLI exits with a non-zero code
