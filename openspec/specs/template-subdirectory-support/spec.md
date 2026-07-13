# Template Subdirectory Support

## Purpose

Renders workload templates while preserving their relative subdirectory structure, creating parent directories as needed and making files rendered into `bin/` executable.

## Requirements

### Requirement: Template renderer preserves relative subdirectory structure
When rendering templates, the output path SHALL preserve the relative path from the template root. A template at `bin/start.sh.template` SHALL render to `<outputDir>/bin/start.sh`. Parent directories SHALL be created as needed.

#### Scenario: File in bin/ subdir rendered to correct location
- **WHEN** a template source contains `bin/start.sh.template`
- **THEN** the rendered file is written to `<workload>/bin/start.sh`

#### Scenario: Nested subdirectory created
- **WHEN** a template source contains `bin/start.sh.template` and `<workload>/bin/` does not yet exist
- **THEN** the `bin/` directory is created before writing

### Requirement: Files rendered into bin/ are made executable
Any file rendered into a `bin/` subdirectory SHALL have its executable bit set (`chmod +x`).

#### Scenario: bin/ file is executable after render
- **WHEN** `bin/start.sh.template` is rendered
- **THEN** the resulting `bin/start.sh` file is executable by the owner

#### Scenario: Non-bin/ file is not made executable
- **WHEN** `README.md.template` is rendered at the root level
- **THEN** the resulting `README.md` file is not executable

### Requirement: Builtin template scanner recurses into subdirectories
`InstallTemplateResolver` SHALL enumerate template files from classpath (JAR) sources recursively, preserving relative paths in `TemplateEntry.name`.

#### Scenario: Builtin bin/ template discovered from JAR
- **WHEN** the JAR contains `install/clickhouse/bin/start.sh.template`
- **THEN** `listTemplateFiles` returns a `TemplateEntry` with name `bin/start.sh.template`
