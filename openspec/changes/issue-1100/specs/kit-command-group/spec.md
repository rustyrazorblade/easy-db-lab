## MODIFIED Requirements

### Requirement: `kit install` subcommand group
The CLI SHALL expose `kit install` as a subcommand group. Each discoverable kit template SHALL be
registered as a subcommand under `kit install` (e.g. `kit install clickhouse`, `kit install presto`).

#### Scenario: `kit install <kit>` scaffolds kit files
- **WHEN** the user runs `easy-db-lab kit install clickhouse --storage-size 100Gi`
- **THEN** the kit template is rendered and written to disk exactly as before

#### Scenario: `kit install` with no subcommand prints help
- **WHEN** the user runs `easy-db-lab kit install`
- **THEN** the CLI prints help listing all available kit subcommands
