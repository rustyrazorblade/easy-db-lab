## Why

The current `install` command conflates installation and listing under a single command with a
`--list` flag, which is unintuitive and breaks the expected CLI pattern for a kit management
surface. Grouping these under `kit` makes the intent explicit and provides a clean home for
future kit operations.

## What Changes

- `easy-db-lab install --list` → `easy-db-lab kit list`
- `easy-db-lab install <kit> [flags]` → `easy-db-lab kit install <kit> [flags]`
- The top-level `install` parent command is replaced by a `kit` parent command with `install` and `list` as subcommands
- Dynamic kit subcommands (e.g. `install clickhouse`) are now registered under `kit install`
- The `uninstall` command moves to `kit uninstall` for consistency
- **BREAKING**: `easy-db-lab install ...` is removed; users must use `easy-db-lab kit ...`

## Capabilities

### New Capabilities

- `kit-command-group`: Top-level `kit` command group with `install`, `list`, and `uninstall` subcommands

### Modified Capabilities

- `install-command`: The install/list/uninstall surface is now rooted at `kit`; all spec requirements carry forward unchanged, only the command path changes

## Impact

- `CommandLineParser.kt` — registration of `Install`, `Uninstall`, and dynamic install subcommands moves from root / `install` group to `kit` group
- `Install.kt` — the `--list` flag and its branch move to a new `KitList` command; the `Install` class becomes a pure install handler
- `UninstalledKitHintHandler` — hint message updated to reference `kit install <name>`
- `commands/CLAUDE.md` — package map updated
- `docs/` — user-facing documentation updated to reflect new command paths
- Any test referencing `install --list` or the `install` top-level command path
