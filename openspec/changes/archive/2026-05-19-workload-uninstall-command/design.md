## Context

`easy-db-lab install <workload>` is a top-level command that scaffolds workload files into the working directory. After installation, per-workload lifecycle commands (start, stop, uninstall) are registered as subcommands of the workload name (e.g., `easy-db-lab presto uninstall`). There is no symmetric top-level `easy-db-lab uninstall <workload>` entry point.

`WorkloadRunnerCommand` already executes the `uninstall` phase and deletes `workloadDir` on success. The gap is purely a routing/registration problem: no top-level `uninstall` command group exists, and no dynamic registration wires installed workloads into it.

## Goals / Non-Goals

**Goals:**
- `easy-db-lab uninstall <workload>` executes the workload's uninstall phase and removes the local directory
- Discoverable: `easy-db-lab uninstall --help` lists all installed workloads that have an uninstall phase
- Symmetric with `easy-db-lab install`

**Non-Goals:**
- Changing how uninstall is executed — `WorkloadRunnerCommand` handles that already
- Adding an uninstall phase to workloads that don't have one
- Bulk uninstall (`easy-db-lab uninstall --all`)

## Decisions

### New `Uninstall` parent command

Add `commands/install/Uninstall.kt` — a `@Command(name = "uninstall")` class analogous to `Install.kt`. Registered statically in `CommandLineParser` the same way `install` is. This is the parent command group; subcommands are populated dynamically.

**Why a static parent + dynamic subcommands over fully dynamic registration?** The static parent enables `easy-db-lab uninstall --help` to work even when no workloads are installed, which matches the behavior of `easy-db-lab install --help`. Fully dynamic would skip the group entirely when nothing is installed, making the command undiscoverable.

### `registerDynamicUninstallSubcommands()` in `CommandLineParser`

Scan `context.workingDirectory` for installed workload directories that expose an `uninstall` phase:
- A `bin/uninstall.sh` script exists and is executable, OR
- `install.yaml` has a non-empty `uninstall` step list

For each qualifying workload, call `WorkloadRunnerCommandFactory.buildPhaseCommand(workloadName, workloadDir, "uninstall")` and add it under the `uninstall` parent. This reuses the exact same factory already used for per-workload subcommands — no new execution path.

**Why not reuse `buildWorkloadGroup`?** `buildWorkloadGroup` creates a group with all phases. Here we want a flat subcommand directly under `uninstall`, not a nested group. `buildPhaseCommand` is already public and does exactly this.

### No changes to `WorkloadRunnerCommand`

The `uninstall` phase execution and `workloadDir.deleteRecursively()` are already implemented. The new routing simply provides a second entry point to the same command object.

## Risks / Trade-offs

- [Risk] A workload might appear under both `easy-db-lab presto uninstall` and `easy-db-lab uninstall presto` with identical behavior. → Acceptable duplication; the per-workload group retains `uninstall` for discoverability within the workload's own subcommand tree.
- [Risk] A workload directory exists but `install.yaml` is malformed — the dynamic registration silently skips it. → Consistent with how `registerDynamicWorkloadSubcommands` handles the same case today.
