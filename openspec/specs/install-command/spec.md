# Install Command Spec

## Overview

The `install` command group scaffolds workload-specific files (Helm values, scripts, README) into a
local directory. Users then run the workload via `easy-db-lab <workload> start` — the CLI reads the
generated `bin/` scripts and registers them as top-level subcommands automatically.

## Template Discovery

Templates are resolved from three sources in priority order:

1. **Profile directory**: `~/.easy-db-lab/profiles/<profile>/install/<name>/`
2. **Built-in classpath**: `install/<name>/` (bundled with the CLI)
3. **Ad-hoc path**: supplied via `--from <path>` flag

Profile templates override built-ins when names collide. The `--from` path is never included in `--list` output.

## Template Structure

Each template directory contains an `install.yaml` descriptor and files with a `.template` suffix:

```
install/<name>/
├── install.yaml          # Workload descriptor (flags, collision detection)
├── README.md.template
├── values.yaml.template  (or other config files)
└── bin/
    ├── start.sh.template
    └── stop.sh.template
```

Files without `.template` suffix are copied verbatim. Rendered output is written to `./<workload>/`.
Files in `bin/` are rendered executable (`chmod +x`).

## install.yaml Descriptor

Each workload declares its CLI flags and behaviour in `install.yaml`:

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
    suffix: "Gi"
```

`default` values support `${VAR}` interpolation from cluster state (e.g. `${DB_NODE_COUNT}`,
`${APP_NODE_COUNT}`). Raw `${VAR}` text is kept when cluster state is unavailable.

`suffix` appends a string to the parsed flag value before substituting it into templates (e.g.
`"100"` → `"100Gi"`).

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
| `__WORKLOAD_NAME__` | Workload name (e.g., `clickhouse`) |
| `__STORAGE_SIZE__` | `--size` flag value (e.g., `100Gi`) |
| `__KUBECONFIG__` | Path to local kubeconfig |

Workload-specific variables are declared in `install.yaml` args and passed as `__VARIABLE_NAME__`.

Unresolved `__VAR__` placeholders emit a warning but do not fail the render.

## Dynamic Subcommand Registration

### install subcommands

At startup, `CommandLineParser` scans all available `install.yaml` files (classpath + profile dir)
and registers a `install <workload>` subcommand for each, with flags from the `args` list.

Adding a new workload requires only:
1. Creating an `install/<name>/` template directory with `install.yaml`
2. Adding template files (including `bin/` scripts)

No Kotlin code changes are needed.

### Workload runner subcommands

At startup, `CommandLineParser` scans `context.workingDirectory` for directories that contain a
`bin/` subdirectory with at least one executable script. Each such directory becomes a top-level
subcommand group; each script in `bin/` becomes a subcommand of that group.

For example, after `easy-db-lab install clickhouse`:

```
clickhouse/
├── bin/
│   ├── start.sh    → easy-db-lab clickhouse start
│   └── stop.sh     → easy-db-lab clickhouse stop
└── clickhouseinstallation.yaml
```

Scripts are executed with cluster state variables injected as environment variables (`CLUSTER_NAME`,
`CONTROL_NODE_IP`, `EASY_DB_LAB_EXEC`, `DB_NODE_COUNT`, etc.).

After a successful `start`, the CLI scans `<workload>/dashboards/*.json` and installs each
dashboard into Grafana automatically.

## `install clickhouse`

```
install clickhouse [--replicas N] [--size Gi] [--s3-cache Gi] [--s3-cache-on-write bool] [--s3-tier-move-factor N] [--force]
```

Adds extra template variables:

| Variable | Flag | Description |
|---|---|---|
| `__REPLICAS__` | `--replicas` | ClickHouse replica count (default: db node count) |
| `__S3_CACHE_SIZE__` | `--s3-cache` | S3 disk cache size (default: `0Gi`) |
| `__S3_CACHE_ON_WRITE__` | `--s3-cache-on-write` | Enable write-through caching |
| `__S3_TIER_MOVE_FACTOR__` | `--s3-tier-move-factor` | Move factor for S3 tiering |

Collision detection: if a `ClickHouseInstallation` CR already exists in the cluster, the command warns and requires `--force` to proceed.

## `install presto`

```
install presto [--workers N]
```

Adds extra template variables:

| Variable | Flag | Description |
|---|---|---|
| `__WORKERS__` | `--workers` | Worker count (defaults to app node count) |

Presto is stateless — no `platform create-pvs` in `start.sh`. The Trino Helm chart targets `type=app` nodes.

## Artifact Events

Each rendered file emits `Event.Install.ArtifactWritten(workload, filePath)`. Template source (built-in vs profile vs ad-hoc) is reported via a corresponding event.

Workload script execution emits `Event.Workload.ScriptStarted(workload, script)` and `Event.Workload.ScriptFinished(workload, script, exitCode)`.
