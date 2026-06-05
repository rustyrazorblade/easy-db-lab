## ADDED Requirements

### Requirement: User can register an additional kit source directory
The CLI SHALL allow users to register a parent directory as an additional kit source via
`kit source add <path>`. The path SHALL be stored in `kit-sources.yaml` in the active profile
directory. The path SHALL be an existing directory at the time of registration.

#### Scenario: Add a valid directory as a kit source
- **WHEN** the user runs `kit source add ~/myproject/kits/`
- **THEN** the path is appended to `kit-sources.yaml`
- **THEN** a confirmation message is printed

#### Scenario: Reject a non-existent path
- **WHEN** the user runs `kit source add /does/not/exist`
- **THEN** the command fails with an error message indicating the path does not exist
- **THEN** `kit-sources.yaml` is not modified

#### Scenario: Adding a duplicate path is a no-op
- **WHEN** the user runs `kit source add <path>` and that path is already registered
- **THEN** the command prints a message indicating it is already registered
- **THEN** `kit-sources.yaml` is not modified

### Requirement: User can list registered kit source directories
The CLI SHALL display all registered additional kit source directories via `kit source list`.
Paths that no longer exist on disk SHALL be flagged in the output.

#### Scenario: List shows registered paths
- **WHEN** the user runs `kit source list` and one or more paths are registered
- **THEN** each registered path is printed, one per line

#### Scenario: List flags missing paths
- **WHEN** a registered path no longer exists on disk
- **THEN** `kit source list` marks it as missing (e.g. `[missing]` suffix)

#### Scenario: List with no sources registered
- **WHEN** `kit-sources.yaml` does not exist or is empty
- **THEN** `kit source list` prints a message indicating no sources are registered

### Requirement: User can remove a registered kit source directory
The CLI SHALL allow users to unregister a source directory via `kit source remove <path>`.

#### Scenario: Remove a registered path
- **WHEN** the user runs `kit source remove <path>` and the path is registered
- **THEN** the path is removed from `kit-sources.yaml`
- **THEN** a confirmation message is printed

#### Scenario: Remove a path that is not registered
- **WHEN** the user runs `kit source remove <path>` and the path is not registered
- **THEN** a message is printed indicating the path was not found
- **THEN** `kit-sources.yaml` is not modified

### Requirement: Registered sources persist across sessions
Kit source registrations SHALL be stored in `~/.easy-db-lab/profiles/<profile>/kit-sources.yaml`
using kotlinx.serialization. An absent `kit-sources.yaml` SHALL be treated as an empty source list
(not an error).

#### Scenario: Sources survive CLI restart
- **WHEN** the user registers a source and restarts the CLI
- **THEN** the registered source is still present in `kit source list`

#### Scenario: Missing file treated as empty
- **WHEN** `kit-sources.yaml` does not exist
- **THEN** all kit source commands behave as if the source list is empty
