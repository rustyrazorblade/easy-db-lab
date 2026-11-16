## ADDED Requirements

### Requirement: Dynamic install subcommands registered under `kit install`
At startup, `CommandLineParser` SHALL scan all available `kit.yaml` files (classpath + profile dir)
and register a dynamic PicoCLI subcommand under `kit install` for each one found, with flags
from the `args` list. There SHALL be no top-level `install` command; all install, list, and
uninstall operations are rooted at the `kit` command group.

Adding a new kit requires only:
1. Creating a `kits/<name>/` template directory with `kit.yaml`
2. Adding template files (including `bin/` scripts)

No Kotlin code changes are needed.

#### Scenario: Dynamic subcommands appear under `kit install`
- **WHEN** the CLI starts and discovers built-in kit templates
- **THEN** each kit appears as a subcommand of `kit install` (e.g. `easy-db-lab kit install clickhouse`)

#### Scenario: Dynamic subcommands are NOT registered at root `install`
- **WHEN** the CLI starts
- **THEN** the top-level `install` command does NOT exist; all install operations are under `kit install`

#### Scenario: Listing kits is a dedicated subcommand
- **WHEN** the user wants to see available kits
- **THEN** `easy-db-lab kit list` SHALL list them
- **AND** no `--list` flag SHALL exist on `kit install`
