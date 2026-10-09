## MODIFIED Requirements

### Requirement: Cluster Initialization

The system MUST allow users to initialize a cluster configuration specifying a name and node counts.

The workspace's `bin/` directory belongs to easy-db-lab, which writes its tool wrappers there. `init` MUST fail with a clear message, and write nothing, when the directory already has a `bin/` (a file or a directory). The message says that the directory already has a `bin/`, that easy-db-lab needs `bin/` for its tool wrappers, and to use a new, empty directory as the workspace (for example under `clusters/`). This also keeps a source checkout or a project directory from being used as a workspace. With `--clean`, the check runs after the cleanup.

#### Scenario: Initialize a new cluster

- **GIVEN** a configured AWS profile
- **WHEN** a user initializes a cluster with a name and node count
- **THEN** cluster configuration is created and persisted locally.

#### Scenario: Re-initialize with different parameters

- **GIVEN** an initialized cluster
- **WHEN** the user re-initializes with different parameters
- **THEN** the configuration is updated.

#### Scenario: init refuses a directory that already has bin/

- **GIVEN** a directory that already has a `bin/` file or directory
- **WHEN** the user runs `init`
- **THEN** `init` fails with a message that names `bin/` and asks for a new, empty directory
- **AND** no file is written

#### Scenario: init refuses the source checkout

- **WHEN** the user runs `init` at the root of a source checkout
- **THEN** `init` fails because the checkout has a `bin/`, and writes nothing

#### Scenario: init --clean in a workspace whose bin/ holds only wrappers

- **GIVEN** a workspace whose `bin/` holds only the tool wrappers and their marker
- **WHEN** the user runs `init --clean`
- **THEN** the cleanup removes the wrappers, the marker, and the empty `bin/`, and `init` succeeds

### Requirement: Local Cleanup

The system MUST allow cleanup of locally generated cluster files.

Cleanup MUST remove only what easy-db-lab wrote in `bin/`: the six tool wrappers and the marker, by name. It MUST NOT remove any other file in `bin/`, and MUST remove `bin/` itself only when it is then empty.

#### Scenario: Remove local cluster files

- **GIVEN** a previously initialized cluster
- **WHEN** the user runs local cleanup
- **THEN** state files, SSH config, the proxy env file, and cached configuration are removed.

#### Scenario: Cleanup removes the wrappers

- **GIVEN** a workspace whose `bin/` holds only the tool wrappers and their marker
- **WHEN** the user runs local cleanup
- **THEN** the wrappers, the marker, and the `bin/` directory are removed

#### Scenario: Cleanup keeps files it did not write

- **GIVEN** a workspace whose `bin/` holds the tool wrappers, the marker, and another file
- **WHEN** the user runs local cleanup
- **THEN** the wrappers and the marker are removed
- **AND** the other file and `bin/` remain

### Requirement: Cluster State Restore

The system MUST support restoring cluster configuration from S3 using VPC identification. A restored workspace MUST get its tool wrappers and its proxy env file, so wrapped tools and `env.sh` work without running `up`.

#### Scenario: Restore state from VPC ID

- **GIVEN** a VPC ID from a previously provisioned cluster
- **WHEN** the user restores state
- **THEN** cluster configuration is recovered from S3 and the local environment is rebuilt.
- **AND** the tool wrappers, their marker, and the proxy env file are written

#### Scenario: Restore when no backup exists

- **GIVEN** no backup exists for a VPC
- **WHEN** restore is attempted
- **THEN** the user is informed that no configuration was found.
