## 1. Template Subdirectory Support

- [x] 1.1 Change `TemplateEntry.name` to carry the relative path from the template root (e.g., `bin/start.sh.template`) rather than a bare filename
- [x] 1.2 Update `InstallTemplateResolver.listBuiltinEntries` to recurse into subdirectories in the JAR, preserving relative paths in `TemplateEntry.name`
- [x] 1.3 Update `InstallTemplateResolver.listTemplateFiles` for `ProfileDirectory` and `AdHoc` sources to use relative paths (currently loses path info via `it.name`)
- [x] 1.4 Update `BaseInstallCommand.renderAndWrite` to create parent subdirectories for each output file before writing
- [x] 1.5 In `renderAndWrite`, call `setExecutable(true)` on any rendered file whose relative path starts with `bin/`
- [x] 1.6 Write unit tests for subdirectory rendering: file written to correct subpath, `bin/` files are executable, non-`bin/` files are not

## 2. install.yaml Schema and Parser

- [x] 2.1 Define `WorkloadInstallConfig` data class (kotlinx.serialization) with fields: `name`, `description`, `collisionCheck`, `args: List<WorkloadArgSpec>`
- [x] 2.2 Define `WorkloadArgSpec` data class: `flag`, `variable`, `description`, `required`, `type` (string/boolean/float/int), `default`, `suffix`
- [x] 2.3 Add `InstallTemplateResolver.loadInstallConfig(source)` that reads and parses `install.yaml` from any `TemplateSource`
- [x] 2.4 Write unit tests for `WorkloadInstallConfig` parsing: required fields, optional fields with defaults, cluster state interpolation in defaults

## 3. Dynamic install Subcommand Generation

- [x] 3.1 Create `WorkloadInstallCommandFactory` that takes a `WorkloadInstallConfig` and cluster state and produces a PicoCLI `CommandLine` object with flags built via `OptionSpec.builder()`
- [x] 3.2 Implement cluster state variable interpolation for `default` values (e.g., `${DB_NODE_COUNT}` → actual count; show raw form when state unavailable)
- [x] 3.3 Implement `suffix` transform: append suffix string to parsed value before setting the template variable
- [x] 3.4 Register generated `install <workload>` subcommands in `CommandLineParser` at startup, scanning both classpath and profile dir for `install.yaml` files
- [x] 3.5 Wire collision detection: if `collisionCheck: true`, check K8s for existing Deployment/StatefulSet/CR named after workload before writing; emit `Event.Install.CollisionDetected` and abort unless `--force` is passed
- [x] 3.6 Remove `InstallClickHouse.kt` and `InstallPresto.kt`
- [x] 3.7 Write `install.yaml` for clickhouse template (all existing flags preserved)
- [x] 3.8 Write `install.yaml` for presto template (all existing flags preserved)
- [x] 3.9 Write unit tests for `WorkloadInstallCommandFactory`: correct flags generated, defaults resolved, suffix applied, collision detection respected

## 4. Template Restructuring

- [x] 4.1 Move `clickhouse/start.sh.template` to `clickhouse/bin/start.sh.template`; simplify to use injected env vars (`$CLUSTER_NAME`, `$EASY_DB_LAB_EXEC`, etc.); remove the dashboard install loop (now handled by WorkloadRunner)
- [x] 4.2 Move `clickhouse/stop.sh.template` to `clickhouse/bin/stop.sh.template`
- [x] 4.3 Move `presto/start.sh.template` to `presto/bin/start.sh.template`; simplify similarly
- [x] 4.4 Move `presto/stop.sh.template` to `presto/bin/stop.sh.template`

## 5. WorkloadRunner Command

- [x] 5.1 Create `WorkloadRunner` — a PicoCLI command that accepts workload name and script name, loads cluster state, builds env vars from `TemplateVariables.toMap()`, resolves `EASY_DB_LAB_EXEC`, and execs the script via `ProcessBuilder`
- [x] 5.2 Add `EASY_DB_LAB_EXEC` to `TemplateVariables`: resolve to `/usr/local/bin/easy-db-lab` if it exists, else `${easydblab.apphome}/bin/easy-db-lab`
- [x] 5.3 Propagate script exit code as the CLI process exit code
- [x] 5.4 Register WorkloadRunner-backed subcommands in `CommandLineParser` at startup by scanning `context.workingDirectory` for dirs with `bin/`
- [x] 5.5 After `start` exits successfully, scan `<workload>/dashboards/*.json` and install each via `GrafanaDashboardService` (reuse existing service, do not shell out)
- [x] 5.6 Add `Event.Workload.ScriptStarted(workload, script)` and `Event.Workload.ScriptFinished(workload, script, exitCode)` events
- [x] 5.7 Write unit tests for `WorkloadRunner`: env vars injected, exit code propagated, dashboards installed after successful start, dashboards skipped on failed start, missing script fails cleanly

## 6. Container Wrapper

- [x] 6.1 Create `docker/easy-db-lab.sh` — container wrapper script invoking the JAR with correct JVM flags (no docker-compose, OTel via env var)
- [x] 6.2 Add `docker/easy-db-lab.sh` to jib `extraDirectories`, placing it at `/usr/local/bin/easy-db-lab` with `chmod 755`
- [x] 6.3 Change jib `entrypoint` from implicit `mainClass` launcher to `["/usr/local/bin/easy-db-lab"]`

## 7. Spec and Documentation Updates

- [x] 7.1 Update `openspec/specs/install-command/spec.md` to reflect the new file-driven model (archive existing per-workload Kotlin requirements)
- [x] 7.2 Update `src/main/kotlin/com/rustyrazorblade/easydblab/commands/CLAUDE.md` to reflect that workload install subcommands are now generated, not hand-coded
- [x] 7.3 Update `docs/user-guide/install-clickhouse.md` and `install-presto.md` to show the new `easy-db-lab clickhouse start` pattern

## 8. Quality

- [x] 8.1 Run `./gradlew ktlintFormat && ./gradlew test && ./gradlew detekt` and fix any failures
