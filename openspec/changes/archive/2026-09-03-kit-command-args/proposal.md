## Why

Kit scripts (e.g. `producer-perf`, `consumer-perf`, `start`) currently have no way to declare per-invocation CLI flags — users must prefix raw environment variables (`NUM_RECORDS=1000000 easy-db-lab kafka producer-perf`), which is undiscoverable and inconsistent with every other command in the tool. Kit authors need a way to declare named options for individual commands in `kit.yaml` so they appear as proper `--flag` options in `--help` and are passed to scripts as environment variables.

## What Changes

- **BREAKING**: Remove the top-level `args:` list from all existing kits (`sysbench`, `clickhouse`, `kafka`, `presto`, `tidb`). Replace per-invocation args (e.g. `--threads`, `--duration`) with entries in the new `commands:` section.
- Add a `commands:` map to `kit.yaml` — each key is a command name (matching a script filename or lifecycle phase), each value declares `description` and `args`.
- `KitConfig` gains a `commands: Map<String, KitCommandSpec>` field; `KitCommandSpec` is a new data class with `description` and `args: List<KitArgSpec>`.
- `KitRunnerCommandFactory.buildPhaseCommand()` reads the matching `commands:` entry and registers its args as PicoCLI `OptionSpec` entries on the command spec.
- `KitRunnerCommand` captures CLI-provided arg values and layers them into `buildAugmentedEnv()` above install-time resolved args but below cluster state vars.
- All existing kits are migrated: per-invocation args move to `commands:`, install-only args (version, storage size, replica count) remain in `args:`.

## Capabilities

### New Capabilities

- `kit-command-args`: Per-command CLI argument declarations in `kit.yaml` — kit authors declare named options scoped to individual commands; the runtime registers them as PicoCLI flags and injects them as environment variables into the script.

### Modified Capabilities

- None — no existing specs cover this area.

## Impact

- `KitConfig.kt`: new `KitCommandSpec` data class, new `commands` field on `KitConfig`
- `KitRunnerCommandFactory.kt`: reads `commands:` entry per phase, registers `OptionSpec` entries
- `KitRunnerCommand.kt`: captures runtime arg values, layers into env map
- `kit.yaml` files: `kafka`, `sysbench`, `clickhouse`, `presto`, `tidb` all require migration
- `KitRunnerCommandFactoryTest.kt`, `KitRunnerCommandTest.kt`: new test coverage
