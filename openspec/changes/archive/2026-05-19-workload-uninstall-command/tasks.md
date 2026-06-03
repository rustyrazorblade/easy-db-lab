## 1. Parent Command

- [x] 1.1 Create `commands/install/Uninstall.kt` — a `@Command(name = "uninstall")` class that prints usage when called with no subcommand, mirroring `Install.kt`
- [x] 1.2 Register the `uninstall` parent command statically in `CommandLineParser` alongside the existing `install` registration

## 2. Dynamic Subcommand Registration

- [x] 2.1 Add `registerDynamicUninstallSubcommands()` to `CommandLineParser` — scans `context.workingDirectory` for workload directories that have an `uninstall` phase (`bin/uninstall.sh` or non-empty `install.yaml` `uninstall` section)
- [x] 2.2 For each qualifying workload, call `WorkloadRunnerCommandFactory.buildPhaseCommand(workloadName, workloadDir, "uninstall")` and add it as a subcommand under the `uninstall` parent
- [x] 2.3 Call `registerDynamicUninstallSubcommands()` from the `build()` method after the other dynamic registration calls

## 3. Tests

- [x] 3.1 Add a unit test in `CommandLineParserTest` (or equivalent) verifying that a workload directory with `bin/uninstall.sh` causes `easy-db-lab uninstall <workload>` to be a registered command
- [x] 3.2 Add a unit test verifying that a workload directory with no uninstall phase is NOT registered under `uninstall`
