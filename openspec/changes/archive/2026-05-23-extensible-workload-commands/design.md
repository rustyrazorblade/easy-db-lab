## Context

The install command currently has one Kotlin class per workload (`InstallClickHouse`, `InstallPresto`). Each declares its own PicoCLI options, computes defaults from cluster state, and calls `renderAndWrite`. After install, users run scripts manually from the workload directory. There is no way to invoke them via the CLI, and adding a new workload requires a code change.

The user always runs `easy-db-lab` from the cluster working directory. This is an established app requirement. Installed workload directories live as subdirectories of that working directory.

## Goals / Non-Goals

**Goals:**
- New workloads require no Kotlin code — only an `install.yaml` and template files
- `easy-db-lab <workload> <script>` discovers and executes installed workload scripts
- Cluster state variables are injected as env vars at exec time (not baked into scripts at install time, where possible)
- `easy-db-lab --help` lists discovered workloads alongside static commands
- Container image has a usable `easy-db-lab` binary for scripts running inside it
- Collision detection becomes a generic, declarative option

**Non-Goals:**
- Remote execution — scripts always run on the local machine (control node connectivity is via kubeconfig/SSH)
- Workload lifecycle management beyond exec (no PID tracking, no status polling)
- Supporting workloads outside the CWD

## Decisions

### `install.yaml` schema (kotlinx.serialization, not Jackson)

Each template directory contains an `install.yaml` with:

```yaml
name: clickhouse
description: Scaffold ClickHouse workload files using the Altinity operator
collision-check: true   # generic --force guard

args:
  - flag: --size
    variable: STORAGE_SIZE
    required: true
    description: Storage size per node (e.g. 100Gi)

  - flag: --replicas
    variable: REPLICAS
    default: "${DB_NODE_COUNT}"   # cluster state interpolation
    description: Number of ClickHouse replicas

  - flag: --s3-cache
    variable: S3_CACHE_SIZE
    default: "0"
    suffix: Gi                    # appended to value → "0Gi"
    description: S3 disk cache size in GiB

  - flag: --s3-cache-on-write
    variable: S3_CACHE_ON_WRITE
    type: boolean
    default: "false"

  - flag: --s3-tier-move-factor
    variable: S3_TIER_MOVE_FACTOR
    type: float
    default: "0.1"
```

**Why file-driven over annotation-driven?** Annotations require compiled Kotlin. A YAML file in the template directory is readable, writable, and deployable without a build. It also lives next to the templates it configures, making the template directory fully self-describing.

**Why `${DB_NODE_COUNT}` interpolation?** Defaults that reference cluster state (replica count defaulting to db node count) are the primary reason each workload needed its own Kotlin. Interpolating from the known `TemplateVariables` map at option-parse time eliminates this.

### Dynamic PicoCLI subcommand registration

At `CommandLineParser` construction, after registering static commands, scan `install.yaml` files from two sources:
1. Builtin classpath (`install/*/install.yaml`)
2. Profile dir (`~/.easy-db-lab/profiles/<profile>/install/*/install.yaml`)

For each, build a `CommandLine` object from the `install.yaml` spec using `CommandSpec.builder()` and `OptionSpec.builder()`. Register under `install <name>`. This happens once at startup; no dynamic scanning at runtime.

**Why not runtime scanning?** PicoCLI's help system requires subcommands to be registered before `parse()` is called. Startup scan is simpler and consistent.

### `easy-db-lab <workload> <script>` — WorkloadRunner

At startup, also scan `context.workingDirectory` for subdirectories containing a `bin/` directory. Register each as a top-level PicoCLI subcommand whose subcommands are the scripts found in `bin/`. When executed:

1. Load cluster state
2. Build `TemplateVariables` (with empty `storageSize` — it's already rendered into files)
3. Add `EASY_DB_LAB_EXEC` — derived from `easydblab.apphome` system property: `${apphome}/bin/easy-db-lab` in dev, `/usr/local/bin/easy-db-lab` in container
4. `ProcessBuilder` exec the script with the merged environment, inheriting stdout/stderr
5. Propagate exit code
6. **If the script is `start` and exits successfully**, scan `<workload>/dashboards/` for `*.json` files and install each via `GrafanaInstall` logic (reusing the existing service, not shelling out)

**Why dashboard install in the runner, not the script?** The dashboard loop is generic — every workload with a `dashboards/` directory gets the same behavior. Keeping it in the runner means no workload script needs to repeat this pattern, and it works correctly regardless of how `EASY_DB_LAB_EXEC` is resolved.

**Why `ProcessBuilder` exec vs. shell eval?** Exec gives clean process semantics, proper signal handling, and no shell injection risk. The script file itself declares its interpreter via shebang.

### Template subdirectory support

`TemplateEntry.name` changes from a bare filename to a relative path (e.g., `bin/start.sh.template`). `InstallTemplateResolver.listTemplateFiles` already uses `walkTopDown()` for filesystem sources; the builtin JAR scanner needs the same treatment. `BaseInstallCommand.renderAndWrite` creates parent directories for each output file and calls `setExecutable(true)` on anything under `bin/`.

### Container wrapper

A `docker/easy-db-lab.sh` script is added to the repo:

```bash
#!/bin/bash
exec java \
  -javaagent:/agents/opentelemetry-javaagent.jar \
  -Deasydblab.apphome=/app \
  -Deasydblab.version="${EASY_DB_LAB_VERSION:-unknown}" \
  -jar /app/easy-db-lab-*.jar "$@"
```

This is bundled via jib `extraDirectories` at `/usr/local/bin/easy-db-lab` with `chmod 755`. The jib `entrypoint` is changed from the implicit `mainClass` launcher to `["/usr/local/bin/easy-db-lab"]`.

**Why a separate file vs. inline in build.gradle.kts?** The script is source-controlled and reviewable. Inline strings in Gradle config are harder to read and test.

### Collision detection generalization

The `collision-check: true` flag in `install.yaml` causes the generic install flow to check whether any Deployment, StatefulSet, or CustomResource with the workload name exists in the default namespace before writing files. `--force` overrides. No workload-specific Kotlin needed.

## Risks / Trade-offs

- **PicoCLI dynamic subcommands are verbose** — `CommandSpec`/`OptionSpec` builders are more code than annotations. Mitigated by isolating this in a single `WorkloadInstallCommandFactory` class.
- **`${DB_NODE_COUNT}` interpolation in defaults** — a simple string substitution. If a default references an unknown variable it resolves to empty string and emits a warning. Edge case: cluster state not loaded yet when help is printed. Mitigation: defaults show the raw `${VAR}` form in `--help` output if cluster state is unavailable.
- **Script naming** — scripts in `bin/` have no extension in the CLI (`easy-db-lab clickhouse start`) but the template source is `bin/start.sh.template` → renders as `bin/start.sh`. The WorkloadRunner scans for any executable file in `bin/`, regardless of extension. This is consistent and flexible.
- **Container OTel** — the dev wrapper starts OTel docker-compose; the container wrapper does not. The container relies on an externally configured `OTEL_EXPORTER_OTLP_ENDPOINT`. This is already the case for jib builds; the wrapper just makes it explicit.

## Open Questions

- Should `workload.yaml` (a metadata file written at install time recording `--size`, workload name, etc.) be written alongside the rendered files? Useful for future tooling but not required for this change.
- Should `easy-db-lab clickhouse` (no subcommand) print usage listing available scripts, or error?
