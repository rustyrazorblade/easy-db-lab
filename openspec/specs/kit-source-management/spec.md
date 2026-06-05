# Kit Source Management Spec

## Overview

Users can register additional parent directories as named kit sources. Each source has a
user-chosen name and a filesystem path — analogous to `git remote`. Kits found in registered
directories are treated as first-class kits — they appear in `kit list`, are inspectable via
`kit info`, and are installable via `kit install`. This enables project-local or team-shared
kit repositories without requiring changes to the CLI binary.

Registrations are stored in `~/.easy-db-lab/profiles/<profile>/kit-sources.yaml` and persist
across CLI restarts.

## CLI Commands

### `kit source add <name> <path>`

Registers a named additional kit source directory. The directory must exist at registration
time. If the name already exists its path is updated (upsert — not an error, not a no-op).

### `kit source list`

Lists all registered kit source directories showing both the name and the path, formatted as
two aligned columns. Paths that no longer exist on disk are flagged with `[missing]` in the
output.

### `kit source remove <name>`

Removes a registered kit source by name. If no source with that name exists the command
prints a "not found" message.

## Storage

Registrations are stored in `~/.easy-db-lab/profiles/<profile>/kit-sources.yaml` using
kotlinx.serialization. Each entry contains a `name` and a `path`. Paths are stored in
canonical form to deduplicate symlinks. An absent file is treated as an empty source list
(not an error).

## Requirements

### Requirement: User can register a named additional kit source directory
The CLI SHALL allow users to register a parent directory as a named additional kit source via
`kit source add <name> <path>`. The name and canonical path SHALL be stored in
`kit-sources.yaml` in the active profile directory. The path SHALL be an existing directory
at the time of registration.

#### Scenario: Add a valid directory as a kit source
- **WHEN** the user runs `kit source add myproject ~/myproject/kits/`
- **THEN** the entry `{name: myproject, path: <canonical path>}` is written to `kit-sources.yaml`
- **THEN** a confirmation message is printed: `Added kit source 'myproject': <canonical path>`

#### Scenario: Reject a non-existent path
- **WHEN** the user runs `kit source add myproject /does/not/exist`
- **THEN** the command fails with an error message indicating the path does not exist
- **THEN** `kit-sources.yaml` is not modified

#### Scenario: Adding a source with an existing name updates the path (upsert)
- **WHEN** the user runs `kit source add <name> <new-path>` and a source with that name is already registered
- **THEN** the existing entry's path is updated to `<new-path>`
- **THEN** a confirmation message is printed: `Updated kit source '<name>': <canonical path>`
- **THEN** `kit-sources.yaml` reflects the new path

### Requirement: User can list registered kit source directories
The CLI SHALL display all registered additional kit source directories via `kit source list`,
showing the name and the path in aligned columns. Paths that no longer exist on disk SHALL
be flagged with `[missing]`.

#### Scenario: List shows registered sources
- **WHEN** the user runs `kit source list` and one or more sources are registered
- **THEN** each registered source is printed with its name and path on one line, names left-aligned

#### Scenario: List flags missing paths
- **WHEN** a registered path no longer exists on disk
- **THEN** `kit source list` marks it with `[missing]` (appended after the path)

#### Scenario: List with no sources registered
- **WHEN** `kit-sources.yaml` does not exist or is empty
- **THEN** `kit source list` prints a message indicating no sources are registered and shows the `add` command syntax

### Requirement: User can remove a registered kit source by name
The CLI SHALL allow users to unregister a source directory by name via `kit source remove <name>`.

#### Scenario: Remove a registered source by name
- **WHEN** the user runs `kit source remove <name>` and a source with that name is registered
- **THEN** the entry is removed from `kit-sources.yaml`
- **THEN** a confirmation message is printed: `Removed kit source '<name>'`

#### Scenario: Remove a name that is not registered
- **WHEN** the user runs `kit source remove <name>` and no source with that name is registered
- **THEN** a message is printed: `No kit source named '<name>'`
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
