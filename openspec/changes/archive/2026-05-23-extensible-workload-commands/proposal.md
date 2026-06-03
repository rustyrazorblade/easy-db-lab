## Why

Adding a new workload (ClickHouse, Presto, etc.) currently requires writing a Kotlin command class per workload. This creates a code-change bottleneck for what should be a purely filesystem operation. The install templates live on disk — the commands that drive them should too.

## What Changes

- **`install.yaml`** — a config file in each template directory declares the workload's CLI arguments, their defaults (which may reference cluster state variables like `${DB_NODE_COUNT}`), and how they map to template variables. The JAR reads this and builds the `install <workload>` subcommand dynamically. No Kotlin required for new workloads.
- **`bin/` subdirectory in templates** — `start.sh.template` and `stop.sh.template` move to `bin/start.sh.template` and `bin/stop.sh.template`. The template renderer gains subdirectory support. Files under `bin/` are made executable on render.
- **Dynamic workload subcommands** — the JAR scans `context.workingDirectory` at startup for installed workload directories (dirs containing `bin/`). Each discovered workload is registered as a PicoCLI subcommand (e.g., `easy-db-lab clickhouse start`). The JAR execs the matching script with cluster state injected as environment variables (`CLUSTER_NAME`, `CONTROL_HOST`, `KUBECONFIG`, `EASY_DB_LAB_EXEC`, etc.).
- **Generic collision detection** — the `--force` guard (currently ClickHouse-specific Kotlin) moves to an `install.yaml` flag (`collision-check: true`). The JAR checks for a running deployment/CR with the workload name before writing files.
- **Container wrapper** — a `/usr/local/bin/easy-db-lab` script is bundled in the jib image so workload scripts can call `${EASY_DB_LAB_EXEC}` from inside the container. The jib entrypoint is updated to use this wrapper.
- **Remove `InstallClickHouse.kt` and `InstallPresto.kt`** — replaced entirely by `install.yaml` in each template directory.

## Capabilities

### New Capabilities

- `workload-install-config`: `install.yaml` schema — declares CLI args, types, defaults (with cluster state interpolation), variable mappings, and collision-check flag. Drives dynamic `install <workload>` subcommand generation.
- `workload-runner`: `easy-db-lab <workload> <script>` — discovers installed workload dirs in CWD, execs `bin/<script>` with full env var injection including `EASY_DB_LAB_EXEC`.
- `template-subdirectory-support`: template renderer preserves relative paths (for `bin/` subdirs), creates output directories as needed, sets executable bit on `bin/*` files.
- `container-wrapper`: bundled `/usr/local/bin/easy-db-lab` in the jib image; jib entrypoint updated to use it.

### Modified Capabilities

- `install-command`: subcommands for named workloads are now file-driven rather than Kotlin-driven. `InstallClickHouse` and `InstallPresto` are removed. Collision detection becomes a generic declarative option.

## Impact

- Removes `InstallClickHouse.kt`, `InstallPresto.kt` — replaced by `install.yaml` in each template dir
- `InstallTemplateResolver` gains subdirectory traversal and relative-path tracking
- `BaseInstallCommand.renderAndWrite` gains subdir creation and executable-bit logic
- `CommandLineParser` gains startup scan of CWD for workload dirs
- New `WorkloadRunner` command handles exec + env injection
- `TemplateVariables` adds `EASY_DB_LAB_EXEC` to the variable set
- `build.gradle.kts` jib config updated: extra directory for container wrapper, entrypoint change
- Template files for clickhouse and presto restructured (`bin/` subdir, simplified scripts)
- Spec update: `install-command` spec reflects new file-driven model
