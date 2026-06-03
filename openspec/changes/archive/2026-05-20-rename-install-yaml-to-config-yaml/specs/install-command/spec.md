## MODIFIED Requirements

### Requirement: Workload descriptor filename
The workload descriptor file SHALL be named `config.yaml` (previously `install.yaml`).
The CLI loader SHALL look for `config.yaml` when resolving a workload's configuration from any template source (classpath, profile directory, or ad-hoc `--from` path).

#### Scenario: Loader finds config.yaml in classpath
- **WHEN** the CLI resolves a built-in workload template
- **THEN** it reads `config.yaml` from the template directory

#### Scenario: Loader finds config.yaml in profile directory
- **WHEN** a user has a custom template at `~/.easy-db-lab/profiles/<profile>/install/<name>/config.yaml`
- **THEN** the CLI reads that file and it overrides the built-in

#### Scenario: Loader ignores install.yaml
- **WHEN** a template directory contains `install.yaml` but no `config.yaml`
- **THEN** the CLI does not load it (no silent fallback)
