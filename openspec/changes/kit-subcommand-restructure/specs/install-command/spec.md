## MODIFIED Requirements

### Requirement: Dynamic install subcommands registered under `kit install`
At startup, `CommandLineParser` SHALL scan all available `kit.yaml` files (classpath + profile dir)
and register a dynamic PicoCLI subcommand under `kit install` for each one found, with flags
from the `args` list.

Adding a new kit requires only:
1. Creating an `install/<name>/` template directory with `kit.yaml`
2. Adding template files (including `bin/` scripts)

No Kotlin code changes are needed.

#### Scenario: Dynamic subcommands appear under `kit install`
- **WHEN** the CLI starts and discovers built-in kit templates
- **THEN** each kit appears as a subcommand of `kit install` (e.g. `easy-db-lab kit install clickhouse`)

#### Scenario: Dynamic subcommands are NOT registered at root `install`
- **WHEN** the CLI starts
- **THEN** the top-level `install` command does NOT exist; all install operations are under `kit install`

## REMOVED Requirements

### Requirement: `install --list` flag
**Reason**: Replaced by the dedicated `kit list` subcommand.
**Migration**: Use `easy-db-lab kit list` instead of `easy-db-lab install --list`.

### Requirement: `install` as a top-level command
**Reason**: All install/list/uninstall operations are now rooted at the `kit` command group.
**Migration**: Replace `easy-db-lab install <kit>` with `easy-db-lab kit install <kit>`.
