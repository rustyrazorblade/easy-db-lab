## MODIFIED Requirements

### Requirement: Workload descriptor filename
The kit descriptor file SHALL be named `kit.yaml`.
The CLI loader SHALL look for `kit.yaml` when resolving a kit's configuration from any template source (classpath, profile directory, or ad-hoc `--from` path).

#### Scenario: Loader finds kit.yaml in classpath
- **WHEN** the CLI resolves a built-in kit template
- **THEN** it reads `kit.yaml` from the template directory

#### Scenario: Loader finds kit.yaml in profile directory
- **WHEN** a user has a custom template at `~/.easy-db-lab/profiles/<profile>/install/<name>/kit.yaml`
- **THEN** the CLI reads that file and it overrides the built-in

#### Scenario: Loader ignores config.yaml
- **WHEN** a template directory contains `config.yaml` but no `kit.yaml`
- **THEN** the CLI does not load it (no silent fallback)

## RENAMED Requirements

### Requirement: Kit name template variable
FROM: `__WORKLOAD_NAME__` template variable holds the workload name
TO: `__KIT_NAME__` template variable holds the kit name
