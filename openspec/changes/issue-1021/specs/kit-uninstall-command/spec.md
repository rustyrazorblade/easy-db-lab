## MODIFIED Requirements

### Requirement: Running uninstall executes the kit uninstall phase then removes the local directory
Running `easy-db-lab uninstall <kit>` SHALL execute the kit's uninstall phase. On success, the local kit directory SHALL be deleted.

When the kit is in `ClusterState.runningKits` and declares a `stop` phase, uninstall SHALL first run the `stop` phase. When the `stop` phase fails, uninstall SHALL stop there with a non-zero exit code, and SHALL NOT run the uninstall phase.

#### Scenario: Successful uninstall removes local directory
- **WHEN** the user runs `easy-db-lab uninstall presto` and the uninstall phase exits with code 0
- **THEN** the `presto/` directory in the working directory is deleted

#### Scenario: Failed uninstall preserves local directory
- **WHEN** the user runs `easy-db-lab uninstall <kit>` and the uninstall phase exits with a non-zero code
- **THEN** the local kit directory is preserved for debugging
- **THEN** the CLI exits with a non-zero code

#### Scenario: Running kit is stopped first
- **WHEN** the user runs `easy-db-lab uninstall singlestore` while `singlestore` is in `runningKits`
- **THEN** the `stop` phase runs before the uninstall phase

#### Scenario: Failed stop blocks uninstall
- **WHEN** the `stop` phase run by uninstall exits with a non-zero code
- **THEN** the uninstall phase does not run
- **AND** the CLI exits with a non-zero code
