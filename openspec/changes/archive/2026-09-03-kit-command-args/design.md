## Context

Kit scripts and lifecycle phases currently have no way to declare per-invocation CLI flags. The only mechanism for passing data to scripts is the `resolved-args.env` file written at install time. This forces users to prefix raw environment variables when invoking commands, which is undiscoverable and inconsistent with the rest of the CLI.

The install-time arg pipeline already solves this problem for `kit install` via `KitInstallCommandFactory`, which reads `KitArgSpec` entries from `kit.yaml` and registers them as PicoCLI `OptionSpec` entries. The runtime command factory (`KitRunnerCommandFactory`) needs to do the same for per-command args.

## Goals / Non-Goals

**Goals:**
- Kit authors can declare named CLI options for any command (script or lifecycle phase) in `kit.yaml`
- Declared options appear in `--help` and are passed to scripts as environment variables
- Runtime-provided values override install-time resolved args for that invocation
- All existing kits are migrated: per-invocation args move to `commands:`, install-only args stay in `args:`

**Non-Goals:**
- Persisting runtime arg values to `resolved-args.env` (these are ephemeral per-invocation overrides)
- Positional args or subcommand-specific flags beyond the existing `KitArgSpec` model
- Changing the install-time arg pipeline

## Decisions

### Decision: New `commands:` map, not filtering the existing `args:` list

The existing `args:` list is install-time only — its values are persisted to `resolved-args.env` and reused by every subsequent phase. Adding a `phases:` filter or `scope:` field to `KitArgSpec` would conflate two semantically different concepts (deployment configuration vs. invocation parameters) in one list.

A separate `commands:` map makes the distinction explicit. Kit authors know immediately which args are install-time config and which are per-invocation overrides.

Alternative considered: add `phases: [producer-perf]` to `KitArgSpec`. Rejected because it conflates install and runtime concerns and makes the `args:` list harder to reason about.

### Decision: Reuse `KitArgSpec` for command args

`KitArgSpec` already has `flag`, `variable`, `description`, `type`, `default`, and `required` — everything needed. A separate data class would duplicate the same fields.

### Decision: Env var precedence order

From lowest to highest priority:
1. `argDefaults` (kit.yaml defaults for install-time args)
2. `resolvedArgs` (values from `resolved-args.env`, written at install time)
3. `runtimeArgValues` (CLI-provided values for this invocation)
4. `base` (cluster state: `PRIVATE_IP`, `DB_NODE_COUNT`, etc.)
5. `KUBECONFIG` (always absolute path)

Runtime args sit above install-time resolved args (so `--brokers 5` overrides an installed `BROKERS=3`) but below cluster state (so kit authors can't shadow infrastructure variables like `PRIVATE_IP`).

### Decision: `KitRunnerCommand` holds runtime arg values in a mutable map

`KitInstallCommandFactory` uses the same pattern: `ISetter` callbacks write into `command.argValues`. `KitRunnerCommand` gains a `runtimeArgValues: MutableMap<String, String>` field populated the same way. `KitRunnerCommandFactory.buildPhaseCommand()` passes the command instance to an inline factory method that registers `OptionSpec` entries — mirroring the existing `argOptionSpec()` in `KitInstallCommandFactory`.

### Decision: `KitRunnerCommandFactory` loads `KitConfig` once per `buildKitGroup` call

Currently `buildPhaseCommand` receives no `KitConfig`. The factory already loads `installConfig` at the top of `buildKitGroup`; passing it into `buildPhaseCommand` requires a single signature change. No additional I/O.

## Risks / Trade-offs

- **Variable name collision with cluster state** → Mitigation: cluster state vars are layered last in `buildAugmentedEnv`, so they always win. No runtime check needed; the precedence order handles it silently.
- **`--version` conflict** → `KitInstallCommandFactory` already special-cases `--version` to avoid a PicoCLI conflict with `--help`/`--version` standard options. The same guard must be applied in `KitRunnerCommandFactory` when building specs for commands that declare `--version`.
- **Kit migration is breaking** → All five kits need `kit.yaml` and script updates. Accepted; no backwards compatibility required.

## Migration Plan

1. Add `KitCommandSpec` data class and `commands` field to `KitConfig`
2. Update `KitRunnerCommandFactory` to register per-command args
3. Update `KitRunnerCommand.buildAugmentedEnv` to layer runtime args
4. Migrate each kit: move per-invocation args to `commands:`, add new script args
5. Update tests

No cluster state migration needed — clusters are ephemeral.
