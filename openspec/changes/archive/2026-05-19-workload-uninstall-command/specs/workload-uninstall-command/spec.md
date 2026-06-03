## ADDED Requirements

### Requirement: Top-level uninstall command group exists
The CLI SHALL expose a top-level `uninstall` command group that is always present in the help output, even when no workloads are installed.

#### Scenario: Help output when no workloads installed
- **WHEN** the user runs `easy-db-lab uninstall --help`
- **THEN** the command prints usage information for the `uninstall` group

### Requirement: Installed workloads with an uninstall phase are listed as subcommands
The CLI SHALL register a subcommand under `uninstall` for each workload directory in the working directory that exposes an uninstall phase, where an uninstall phase is defined as either a `bin/uninstall.sh` script or a non-empty `uninstall` step list in `install.yaml`.

#### Scenario: Workload with bin/uninstall.sh is registered
- **WHEN** a workload directory exists with `bin/uninstall.sh` present
- **THEN** `easy-db-lab uninstall <workload>` is a valid command

#### Scenario: Workload with typed uninstall steps is registered
- **WHEN** a workload directory exists with an `install.yaml` containing a non-empty `uninstall` section
- **THEN** `easy-db-lab uninstall <workload>` is a valid command

#### Scenario: Workload without an uninstall phase is not registered
- **WHEN** a workload directory exists with no `bin/uninstall.sh` and no `uninstall` steps in `install.yaml`
- **THEN** `easy-db-lab uninstall <workload>` is NOT a valid command

### Requirement: Running uninstall executes the workload uninstall phase then removes the local directory
Running `easy-db-lab uninstall <workload>` SHALL execute the workload's uninstall phase. On success, the local workload directory SHALL be deleted.

#### Scenario: Successful uninstall removes local directory
- **WHEN** the user runs `easy-db-lab uninstall presto` and the uninstall phase exits with code 0
- **THEN** the `presto/` directory in the working directory is deleted

#### Scenario: Failed uninstall preserves local directory
- **WHEN** the user runs `easy-db-lab uninstall <workload>` and the uninstall phase exits with a non-zero code
- **THEN** the local workload directory is preserved for debugging
- **THEN** the CLI exits with a non-zero code
