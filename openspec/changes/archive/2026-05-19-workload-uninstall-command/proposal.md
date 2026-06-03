## Why

There is no top-level `easy-db-lab uninstall <workload>` command to mirror `easy-db-lab install <workload>`. Users who want to fully remove a workload currently have no clear, discoverable entry point — the uninstall phase is only reachable via the per-workload subcommand group (e.g., `easy-db-lab presto uninstall`), which only appears after a workload is installed and is not symmetric with how installation works.

## What Changes

- Add a new top-level `uninstall` command group (`easy-db-lab uninstall`)
- At startup, dynamically register a subcommand under `uninstall` for each installed workload directory found in the working directory
- Running `easy-db-lab uninstall <workload>` executes the workload's `uninstall` phase (via the existing `WorkloadRunnerCommand` mechanism), then deletes the local workload directory

## Capabilities

### New Capabilities

- `workload-uninstall-command`: Top-level `uninstall` command group with dynamically registered per-workload subcommands, mirroring the `install` command pattern

### Modified Capabilities

- `install-command`: Document that the workload runner subcommand `uninstall` phase is also exposed via the top-level `uninstall` group

## Impact

- `CommandLineParser`: new `registerDynamicUninstallSubcommands()` method scans `context.workingDirectory` for installed workload directories and registers `WorkloadRunnerCommand(uninstall)` under the `uninstall` parent
- New `Uninstall` parent command class in `commands/install/`
- No changes to `WorkloadRunnerCommand` — it already handles the `uninstall` phase and deletes `workloadDir` on success
- Docs: update user-facing command reference to include `uninstall`
