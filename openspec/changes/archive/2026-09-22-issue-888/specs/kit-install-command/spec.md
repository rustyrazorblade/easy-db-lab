# Kit Install Command Spec

## MODIFIED Requirements

### Requirement: Kit descriptor filename
The kit descriptor file SHALL be named `kit.yaml`.
The CLI loader SHALL look for `kit.yaml` when resolving a kit's configuration from any template source (classpath, profile directory, or ad-hoc `--from` path).

Every kit directory installed in the cluster workspace SHALL contain a `kit.yaml`, with no exceptions. That descriptor is the sole marker that a directory is an installed kit, and its presence is an invariant of installation rather than a property of any particular template. This does not apply to kits registered in the tool in code, which are not discovered from the filesystem at all.

The CLI SHALL enforce that invariant at install time. `kit install --from <dir>` SHALL reject a template directory that does not contain a `kit.yaml`, failing with a message naming the missing file, before writing anything to the workspace.

#### Scenario: Loader finds kit.yaml in classpath
- **WHEN** the CLI resolves a built-in kit template
- **THEN** it reads `kit.yaml` from the template directory

#### Scenario: Loader finds kit.yaml in profile directory
- **WHEN** a user has a custom template at `~/.easy-db-lab/profiles/<profile>/install/<name>/kit.yaml`
- **THEN** the CLI reads that file and it overrides the built-in

#### Scenario: Loader ignores config.yaml
- **WHEN** a template directory contains `config.yaml` but no `kit.yaml`
- **THEN** the CLI does not load it (no silent fallback)

#### Scenario: An installed kit directory carries its descriptor
- **WHEN** `kit install <name>` completes successfully
- **THEN** the installed kit directory in the workspace contains a `kit.yaml`
- **AND** the directory is registered as a top-level subcommand

#### Scenario: --from rejects a template without kit.yaml
- **WHEN** `kit install --from <dir>` runs against a directory holding `bin/start.sh` but no `kit.yaml`
- **THEN** the command fails with a message naming the missing `kit.yaml`
- **AND** nothing is written to the workspace

#### Scenario: --from accepts a template with kit.yaml
- **WHEN** `kit install --from <dir>` runs against a directory holding `kit.yaml`
- **THEN** the install proceeds
