# Kit Install Command Spec

## Purpose

The `kit install` command group scaffolds kit-specific files (`kit.yaml`, Helm values, scripts,
README) into a local directory. Users then run the kit via `easy-db-lab <kit> start` — the CLI
recognises the directory by its `kit.yaml` and registers its `bin/` scripts as top-level
subcommands automatically.
## Requirements
### Requirement: Template discovery sources
Templates SHALL be resolved from four sources in priority order:

1. **Profile directory**: `~/.easy-db-lab/profiles/<profile>/kits/<name>/`
2. **Additional sources**: directories registered via `kit source add`, searched in registration order; each registered directory's subdirectories are treated as individual kit templates
3. **Built-in classpath**: `kits/<name>/` (bundled with the CLI)
4. **Ad-hoc path**: supplied via `--from <path>` flag (install-time only, never listed)

Profile templates override built-ins and additional-source templates when names collide.
Additional-source templates override built-ins when names collide. Within multiple registered
additional sources, the first-registered source wins on name collision.
A missing additional-source directory (path no longer exists on disk) is silently skipped —
it does not cause an error.
The ad-hoc `--from` path is the one-time variant of a registered source: both resolve through
`TemplateSource.Directory` and render through the same pipeline.

#### Scenario: Kit from additional source appears in list
- **WHEN** a directory is registered via `kit source add ~/myproject/kits/`
- **AND** that directory contains a `my-kit/` subdirectory with `kit.yaml`
- **THEN** `kit list` includes `my-kit`

#### Scenario: Kit from additional source is inspectable
- **WHEN** a directory is registered via `kit source add ~/myproject/kits/`
- **AND** that directory contains a `my-kit/` subdirectory with `kit.yaml`
- **THEN** `kit info my-kit` displays the kit details

#### Scenario: Kit from additional source is installable
- **WHEN** a directory is registered via `kit source add ~/myproject/kits/`
- **AND** that directory contains a `my-kit/` subdirectory with `kit.yaml`
- **THEN** `kit install my-kit` copies the kit files into the cluster directory

#### Scenario: Additional source kit shadows built-in of same name
- **WHEN** a registered additional source contains a kit with the same name as a built-in kit
- **THEN** the additional-source kit is used (priority 2 beats priority 3)

#### Scenario: Profile kit shadows additional source of same name
- **WHEN** the profile kits directory contains a kit with the same name as a registered additional source
- **THEN** the profile kit is used (priority 1 beats priority 2)

#### Scenario: Missing additional source directory is skipped silently
- **WHEN** a registered additional source path no longer exists on disk
- **THEN** `kit list` does not include kits from that path
- **THEN** no error is raised

### Requirement: Per-call kit source directory
`kit install <name>` SHALL accept `--source <dir>`. With it, the kit SHALL be read from
`<dir>/<name>/` for that call only. The profile kits directory, the sources registered with
`kit source add`, and the built-in kits SHALL NOT be consulted for that kit, and
`kit-sources.yaml` SHALL NOT be read for it or changed. Without `--source`, resolution SHALL
follow the template discovery order unchanged. The `--source` value is read before parsing,
because the kit's `kit.yaml` declares the install options the command accepts.

#### Scenario: --source installs from the given directory
- **WHEN** a source named `shared` is registered and holds kit `my-kit`
- **AND** the user runs `kit install my-kit --source <dir>` where `<dir>/my-kit/kit.yaml` exists
- **THEN** the files from `<dir>/my-kit/` are installed, and options declared by that `kit.yaml` are accepted
- **THEN** `kit-sources.yaml` is byte-for-byte unchanged

#### Scenario: Without --source the registered source is used
- **WHEN** a source is registered that holds kit `my-kit`
- **AND** the user runs `kit install my-kit`
- **THEN** the kit is installed from the registered source

#### Scenario: --source directory lacks the kit
- **WHEN** the user runs `kit install my-kit --source <dir>` and `<dir>/my-kit/kit.yaml` does not exist
- **THEN** the command fails with an error that names `<dir>` and `my-kit`
- **THEN** nothing is written to the workspace

#### Scenario: --source kit has an invalid kit.yaml
- **WHEN** the user runs `kit install my-kit --source <dir>` and `<dir>/my-kit/kit.yaml` is not a valid kit descriptor
- **THEN** the command fails with an invalid-argument error that names that file, with no stack trace
- **THEN** nothing is written to the workspace

#### Scenario: --source kit name leaves the directory
- **WHEN** the user runs `kit install <name> --source <dir>` and `<name>` is not a single directory name inside `<dir>` (for example `../other/my-kit`)
- **THEN** the command fails with an error that names `<name>` and `<dir>`

### Requirement: Kit descriptor filename
The kit descriptor file SHALL be named `kit.yaml`.
The CLI loader SHALL look for `kit.yaml` when resolving a kit's configuration from any template source (classpath, profile directory, or ad-hoc `--from` path).

Every kit directory installed in the cluster workspace SHALL contain a `kit.yaml`, with no exceptions. That descriptor is the sole marker that a directory is an installed kit, and its presence is an invariant of installation rather than a property of any particular template. This does not apply to kits registered in the tool in code, which are not discovered from the filesystem at all.

The CLI SHALL enforce that invariant at install time. `kit install --from <dir>` SHALL reject a template directory that does not contain a `kit.yaml`, failing with a message naming the missing file, before writing anything to the workspace.

#### Scenario: Loader finds kit.yaml in classpath
- **WHEN** the CLI resolves a built-in kit template
- **THEN** it reads `kit.yaml` from the template directory

#### Scenario: Loader finds kit.yaml in profile directory
- **WHEN** a user has a custom template at `~/.easy-db-lab/profiles/<profile>/install/<name>/kit.yaml`
- **THEN** the CLI reads that file and it overrides the built-in

#### Scenario: Loader ignores config.yaml
- **WHEN** a template directory contains `config.yaml` but no `kit.yaml`
- **THEN** the CLI does not load it (no silent fallback)

#### Scenario: An installed kit directory carries its descriptor
- **WHEN** `kit install <name>` completes successfully
- **THEN** the installed kit directory in the workspace contains a `kit.yaml`
- **AND** the directory is registered as a top-level subcommand

#### Scenario: --from rejects a template without kit.yaml
- **WHEN** `kit install --from <dir>` runs against a directory holding `bin/start.sh` but no `kit.yaml`
- **THEN** the command fails with a message naming the missing `kit.yaml`
- **AND** nothing is written to the workspace

#### Scenario: --from accepts a template with kit.yaml
- **WHEN** `kit install --from <dir>` runs against a directory holding `kit.yaml`
- **THEN** the install proceeds

### Requirement: Kit node-type requirement field
A kit MAY declare `type: db` or `type: app` in `kit.yaml`; when declared, the cluster MUST have at least
one node of that type before installation proceeds. The field is optional — a kit without it has no
node-type requirement. The check runs at the start of `kit install`, before any templates are rendered or
files are written, so a failure leaves nothing on disk.

#### Scenario: Install fails when required node pool is absent
- **GIVEN** a kit declares `type: app`
- **WHEN** the user runs `easy-db-lab kit install <kit>` and the cluster has no app nodes
- **THEN** the install fails immediately with `Event.Kit.RequirementNotMet`
- **THEN** no files are written to disk

#### Scenario: Install proceeds when required node pool is present
- **GIVEN** a kit declares `type: db`
- **WHEN** the user runs `easy-db-lab kit install <kit>` and the cluster has at least one db node
- **THEN** the install proceeds normally

#### Scenario: No type field means no requirement check
- **GIVEN** a kit declares no `type` field
- **WHEN** the user runs `easy-db-lab kit install <kit>`
- **THEN** no node-type check is performed

### Requirement: Dynamic install subcommands registered under `kit install`
At startup, `CommandLineParser` SHALL scan all available `kit.yaml` files (classpath + profile dir)
and register a dynamic PicoCLI subcommand under `kit install` for each one found, with flags
from the `args` list. There SHALL be no top-level `install` command; all install, list, and
uninstall operations are rooted at the `kit` command group.

Adding a new kit requires only:
1. Creating a `kits/<name>/` template directory with `kit.yaml`
2. Adding template files (including `bin/` scripts)

No Kotlin code changes are needed.

#### Scenario: Dynamic subcommands appear under `kit install`
- **WHEN** the CLI starts and discovers built-in kit templates
- **THEN** each kit appears as a subcommand of `kit install` (e.g. `easy-db-lab kit install clickhouse`)

#### Scenario: Dynamic subcommands are NOT registered at root `install`
- **WHEN** the CLI starts
- **THEN** the top-level `install` command does NOT exist; all install operations are under `kit install`

#### Scenario: Listing kits is a dedicated subcommand
- **WHEN** the user wants to see available kits
- **THEN** `easy-db-lab kit list` SHALL list them
- **AND** no `--list` flag SHALL exist on `kit install`

### Requirement: Install args with no value are recorded as empty strings
`kit install` SHALL record every declared top-level arg in the kit's `resolved-args.env`.  When the user gives no value and the arg has no default, install SHALL record an empty string for its variable.  Install SHALL fill these empty values after it applies the declared defaults, so an unresolved `${VAR}` default (one that is never handed to picocli) is still applied and not replaced by an empty string.  The recorded empty value SHALL overwrite a variable of the same name that the operator's shell exports when a kit process runs.

#### Scenario: Optional install arg left out
- **WHEN** a kit declares an install arg `--extension` with no default
- **AND** the user runs `kit install <kit>` without `--extension`
- **THEN** `resolved-args.env` holds the arg's variable set to an empty string

#### Scenario: Unresolved default is kept
- **WHEN** a kit declares an install arg with default `${DB_NODE_COUNT}`
- **AND** the template variables do not hold `DB_NODE_COUNT`
- **AND** the user does not give the arg
- **THEN** `resolved-args.env` holds the default text, not an empty string

#### Scenario: Exported shell variable does not leak into a kit process
- **WHEN** the operator's shell exports `EXTENSION=foo`
- **AND** `resolved-args.env` records `EXTENSION` as an empty string
- **THEN** every kit process for that kit sees `EXTENSION` as an empty string

### Requirement: Boolean install args accept an optional value
A `boolean` install arg SHALL take an optional value (picocli arity `0..1`) in the install factory.  A bare `--flag` SHALL mean `true`, and `--flag false` and `--flag=false` SHALL record `false`.

#### Scenario: Boolean install arg turned off
- **WHEN** the user runs `kit install clickhouse --s3-cache-on-write false`
- **THEN** install succeeds and records `false` for the arg's variable

#### Scenario: Boolean install arg given as a bare flag
- **WHEN** the user runs `kit install clickhouse --s3-cache-on-write`
- **THEN** install records `true` for the arg's variable

#### Scenario: Boolean install arg with an equals value
- **WHEN** the user runs `kit install clickhouse --s3-cache-on-write=false`
- **THEN** install records `false` for the arg's variable

## Template Structure

Each template directory contains a `kit.yaml` descriptor and files with a `.template` suffix:

```
kits/<name>/
├── kit.yaml              # Kit descriptor (flags, collision detection)
├── README.md.template
├── values.yaml.template  (or other config files)
└── bin/
    ├── start.sh.template
    └── stop.sh.template
```

Files without `.template` suffix are copied verbatim. Rendered output is written to `./<kit>/`.
Files in `bin/` are rendered executable (`chmod +x`).

## kit.yaml Descriptor

Each kit declares its CLI flags and behaviour in `kit.yaml`:

```yaml
name: clickhouse
description: "Install ClickHouse via the Altinity operator"
collisionCheck: true
args:
  - flag: --replicas
    variable: REPLICAS
    description: "ClickHouse replica count"
    default: "${DB_NODE_COUNT}"
  - flag: --size
    variable: STORAGE_SIZE
    description: "Storage per node (e.g. 100Gi)"
    required: true
  - flag: --s3-cache
    variable: S3_CACHE_SIZE
    description: "S3 disk cache size"
    default: "0"
```

`default` values support `${VAR}` interpolation from cluster state (e.g. `${DB_NODE_COUNT}`,
`${APP_NODE_COUNT}`). Raw `${VAR}` text is kept when cluster state is unavailable.

## Kit Lifecycle Hooks

A kit can declare hooks that fire after another kit successfully starts or stops. This
lets observer kits (e.g. Presto) react to peer kit lifecycle events without each kit
knowing about Presto.

Hooks are declared in `kit.yaml` under the `hooks` key:

```yaml
hooks:
  post-workload-start:
    script: bin/update-catalogs.sh
    workloads:             # optional — omit to fire for all kits
      - cassandra
      - clickhouse
  post-workload-stop:
    script: bin/update-catalogs.sh
```

### Hook fields

| Field | Type | Required | Description |
|---|---|---|---|
| `script` | string | yes | Path to the script, relative to the installed kit directory |
| `workloads` | list of strings | no | Kits that trigger this hook. Empty (or omitted) means fire for every kit |

### Hook execution behaviour

- Hooks are fired by `KitHookExecutor` after `KitRunnerCommand` or `CassandraStartCommand` successfully completes the start/stop phase.
- All installed kit directories are scanned for `kit.yaml`. Each one whose hooks block contains a matching hook is considered.
- **Self-skip**: A kit's own hooks never fire when that same kit triggers the event (e.g. Presto's hooks do not fire when Presto itself starts).
- **Kit scoping**: If `workloads` is non-empty, the hook fires only when the triggering kit is in that list.
- The hook script is invoked as a subprocess with the same cluster state environment variables injected as in normal kit scripts (including `RUNNING_KITS`).
- Hooks are retried on failure using resilience4j. On retry exhaustion, `Event.Kit.HookFailed(kit, hook, reason)` is emitted and execution continues — a single hook failure does not abort the workflow.

## Template Variable Contract

All templates receive the following standard variables from cluster state and command flags:

| Variable | Source |
|---|---|
| `__CLUSTER_NAME__` | `ClusterState.name` |
| `__CONTROL_NODE_IP__` | Control node public IP |
| `__CONTROL_HOST_PUBLIC__` | Control node public IP |
| `__CONTROL_HOST_PRIVATE__` | Control node private IP |
| `__DB_NODE_COUNT__` | Count of `ServerType.Cassandra` hosts |
| `__APP_NODE_COUNT__` | Count of `ServerType.Stress` hosts |
| `__BUCKET_NAME__` | `ClusterState.dataBucket` (falls back to `s3Bucket`) |
| `__REGION__` | `InitConfig.region` |
| `__STORAGE_CLASS_WFC__` | `Constants.K8s.LOCAL_STORAGE_WFC_CLASS` (`local-storage-wfc`) |
| `__KIT_NAME__` | Kit name (e.g., `clickhouse`) |
| `__STORAGE_SIZE__` | `--size` flag value (e.g., `100Gi`) |
| `__KUBECONFIG__` | Path to local kubeconfig |
| `__RUNNING_KITS__` | Comma-separated names of currently running kits |

Kit-specific variables are declared in `kit.yaml` args and passed as `__VARIABLE_NAME__`.

Unresolved `__VAR__` placeholders emit a warning but do not fail the render.

## Dynamic Subcommand Registration

### Kit runner subcommands

At startup, `CommandLineParser` scans `context.workingDirectory` for directories that contain a
`kit.yaml`. Each such directory becomes a top-level subcommand group; each script in `bin/`
becomes a subcommand of that group. A `bin/` directory alone does not qualify a directory —
source checkouts and virtualenvs have one too.

For example, after `easy-db-lab kit install clickhouse`:

```
clickhouse/
├── bin/
│   ├── start.sh    → easy-db-lab clickhouse start
│   └── stop.sh     → easy-db-lab clickhouse stop
├── kit.yaml        → marks the directory as an installed kit
└── clickhouseinstallation.yaml
```

Scripts are executed with cluster state variables injected as environment variables (`CLUSTER_NAME`,
`CONTROL_NODE_IP`, `EASY_DB_LAB_EXEC`, `DB_NODE_COUNT`, etc.).

After a successful `start`, the CLI scans `<kit>/dashboards/*.json` and installs each
dashboard into Grafana automatically.

## `kit install clickhouse`

```
kit install clickhouse [--replicas N] [--size Gi] [--s3-cache Gi] [--s3-cache-on-write bool] [--s3-tier-move-factor N] [--force]
```

Adds extra template variables:

| Variable | Flag | Description |
|---|---|---|
| `__REPLICAS__` | `--replicas` | ClickHouse replica count (default: db node count) |
| `__S3_CACHE_SIZE__` | `--s3-cache` | S3 disk cache size (default: `0Gi`) |
| `__S3_CACHE_ON_WRITE__` | `--s3-cache-on-write` | Enable write-through caching |
| `__S3_TIER_MOVE_FACTOR__` | `--s3-tier-move-factor` | Move factor for S3 tiering |

Collision detection: if a `ClickHouseInstallation` CR already exists in the cluster, the command warns and requires `--force` to proceed.

## `kit install presto`

```
kit install presto [--workers N]
```

Adds extra template variables:

| Variable | Flag | Description |
|---|---|---|
| `__WORKERS__` | `--workers` | Worker count (defaults to app node count) |

Presto is stateless — no `platform create-pvs` in `start.sh`. The Presto Helm chart targets `type=app` nodes.

Presto owns its catalog configuration. Built-in kit connectors are declared as templates under
`presto/catalogs/<kit>.properties` (substituted at install time). At runtime,
`presto/bin/update-catalogs.sh` reads `$RUNNING_KITS` and assembles a Helm values override that
includes only the currently running kits.

Presto declares `post-workload-start` and `post-workload-stop` hooks in its `kit.yaml`, so
`update-catalogs.sh` fires automatically whenever any other kit starts or stops.

### Custom kit catalogs

Ad-hoc kits installed via `--from` can expose a Presto catalog by dropping a
`presto-catalog.properties` file in their installed directory:

```
my-kit/
├── bin/
│   └── start.sh
└── presto-catalog.properties
```

`update-catalogs.sh` scans all sibling kit directories for this file and includes them in the
catalog override. The catalog name is the kit directory name.

## Artifact Events

Each rendered file emits `Event.Install.ArtifactWritten(kit, filePath)`. Template source (built-in vs profile vs ad-hoc) is reported via a corresponding event.

Kit script execution emits `Event.Kit.ScriptStarted(kit, script)` and `Event.Kit.ScriptFinished(kit, script, exitCode)`.
