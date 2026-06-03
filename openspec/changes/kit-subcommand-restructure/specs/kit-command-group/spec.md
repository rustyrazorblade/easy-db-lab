## ADDED Requirements

### Requirement: `kit` top-level parent command
The CLI SHALL expose a `kit` top-level parent command that groups all kit management operations.
When invoked without a subcommand, `kit` SHALL print its help text.

#### Scenario: `kit` with no subcommand prints help
- **WHEN** the user runs `easy-db-lab kit`
- **THEN** the CLI prints the help text listing `install`, `list`, and `uninstall` subcommands

### Requirement: `kit list` subcommand
The CLI SHALL expose a `kit list` subcommand that lists all discoverable install templates.
It SHALL emit `Event.Install.TemplatesListed` with the results.

#### Scenario: `kit list` shows available kits
- **WHEN** the user runs `easy-db-lab kit list`
- **THEN** the CLI emits `Event.Install.TemplatesListed` containing all discoverable kit templates

### Requirement: `kit install` subcommand group
The CLI SHALL expose `kit install` as a subcommand group. Each discoverable kit template SHALL be
registered as a subcommand under `kit install` (e.g. `kit install clickhouse`, `kit install presto`).

#### Scenario: `kit install <kit>` scaffolds kit files
- **WHEN** the user runs `easy-db-lab kit install clickhouse --size 100Gi`
- **THEN** the kit template is rendered and written to disk exactly as before

#### Scenario: `kit install` with no subcommand prints help
- **WHEN** the user runs `easy-db-lab kit install`
- **THEN** the CLI prints help listing all available kit subcommands

### Requirement: `kit uninstall` subcommand
The CLI SHALL expose `kit uninstall` as a subcommand that removes an installed kit.

#### Scenario: `kit uninstall` removes a kit
- **WHEN** the user runs `easy-db-lab kit uninstall <kit>`
- **THEN** the kit is uninstalled as before

### Requirement: Uninstalled kit hint references `kit install`
When the user types a top-level argument that matches an installable kit name,
the hint message SHALL reference `kit install <name>`, not `install <name>`.

#### Scenario: Hint message uses new command path
- **WHEN** the user runs `easy-db-lab clickhouse` before installing the clickhouse kit
- **THEN** the hint says `Run: easy-db-lab kit install clickhouse`
