## Context

The current `install` command is registered at the root of the CLI and mixes two concerns: listing
available kits (`--list`) and installing them (`install <kit>`). There is no `kit` parent group,
so the install surface is scattered. `Uninstall` also lives at root level with no parent.

The desired UX is:

```
easy-db-lab kit list
easy-db-lab kit install <kit> [flags]
easy-db-lab kit uninstall <kit>
```

All kit management operations belong under a single `kit` group. Dynamic subcommands (one per
discoverable kit) are registered under `kit install` rather than directly under `install`.

## Goals / Non-Goals

**Goals:**
- Introduce a `Kit` top-level parent command with `install`, `list`, and `uninstall` subcommands
- Move dynamic kit install subcommands from under `install` to under `kit install`
- Remove `--list` flag from `Install`; replace with a dedicated `KitList` command
- Move `Uninstall` from root to under `kit`
- Update `UninstalledKitHintHandler` hint text to reference `kit install <name>`
- Update `commands/CLAUDE.md` and user docs to reflect new paths

**Non-Goals:**
- Changing how kits are discovered or how templates are resolved
- Altering `KitInstallCommandFactory`, `KitRunnerCommandFactory`, or `BaseInstallCommand` logic
- Changing the runner subcommands (`easy-db-lab clickhouse start`) — these stay at root level

## Decisions

### Decision: Separate `KitList` command class

**Rationale**: The `--list` flag in `Install` is a code smell — it makes `Install` handle two
unrelated operations. Extracting it into `KitList` keeps each class single-purpose and testable in
isolation. `KitList.execute()` simply calls `resolver.listAvailableTemplateDetails()` and emits
`Event.Install.TemplatesListed`.

**Alternative considered**: Keep `--list` in `Install` and just move the parent. Rejected because
it perpetuates the mixed-responsibility design.

### Decision: `Kit` is a pure parent command (no-op run)

**Rationale**: PicoCLI parent commands that have no logic of their own should just print help
when invoked without a subcommand. `Kit` will follow the same pattern as `Cassandra`, `Spark`, etc.
in this codebase.

### Decision: Dynamic subcommand registration target changes from `install` to `kit install`

`CommandLineParser.registerDynamicInstallSubcommands()` currently looks up
`commandLine.getSubcommands()["install"]`. After this change it looks up
`commandLine.getSubcommands()["kit"]` and then navigates to the `install` child.

No other logic in the factory changes.

## Risks / Trade-offs

- **[Breaking CLI surface]** → Any script or documentation using `easy-db-lab install ...` will
  break. Acceptable because clusters are ephemeral and there are no long-lived deployments.
- **[UninstalledKitHintHandler lookup]** → The handler checks the first token against known kit
  names and prints a hint. After this change it should say `kit install <name>` rather than
  `install <name>`. Simple string change, no logic change.

## Migration Plan

1. Add `Kit` parent command class and `KitList` command class
2. Remove `--list` flag and its branch from `Install`
3. Move `Uninstall` import / registration from root `@Command(subcommands=[...])` to under `kit`
4. Update `CommandLineParser.registerDynamicInstallSubcommands()` to target `kit install`
5. Update `UninstalledKitHintHandler` hint message
6. Update `commands/CLAUDE.md` package map
7. Update user-facing docs

No rollback is needed — clusters are ephemeral.

## Open Questions

None.
