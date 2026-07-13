# Workload Runner

## Purpose

Discovers installed workload directories as top-level CLI subcommands, executes their `bin/` scripts with cluster state injected as environment variables, and installs their dashboards after a successful start.

## Requirements

### Requirement: Installed workload dirs are discovered as top-level subcommands
At startup, the CLI SHALL scan `context.workingDirectory` for subdirectories that contain a `bin/` directory. Each such directory SHALL be registered as a top-level PicoCLI subcommand whose sub-subcommands are the executable files found in `bin/`.

#### Scenario: Workload dir with bin/ discovered
- **WHEN** `./clickhouse/bin/start` exists and is executable
- **THEN** `easy-db-lab clickhouse start` is a valid command

#### Scenario: Dir without bin/ is not registered
- **WHEN** `./clickhouse/` exists but has no `bin/` subdirectory
- **THEN** `easy-db-lab clickhouse` is not registered as a subcommand

#### Scenario: Multiple workloads discovered
- **WHEN** both `./clickhouse/bin/` and `./presto/bin/` exist
- **THEN** both `easy-db-lab clickhouse` and `easy-db-lab presto` are valid subcommands

### Requirement: Workload scripts are executed with cluster state as environment variables
When `easy-db-lab <workload> <script>` is invoked, the CLI SHALL exec the corresponding `bin/<script>` file with the following variables injected as environment variables:

| Variable | Value |
|---|---|
| `CLUSTER_NAME` | `ClusterState.name` |
| `CONTROL_HOST` | Control node public IP |
| `CONTROL_HOST_PUBLIC` | Control node public IP |
| `CONTROL_HOST_PRIVATE` | Control node private IP |
| `DB_NODE_COUNT` | Count of db nodes |
| `APP_NODE_COUNT` | Count of app/stress nodes |
| `BUCKET_NAME` | S3 data bucket |
| `REGION` | AWS region |
| `KUBECONFIG` | Path to kubeconfig |
| `EASY_DB_LAB_EXEC` | Absolute path to the `easy-db-lab` executable |

#### Scenario: Env vars injected at exec time
- **WHEN** `easy-db-lab clickhouse start` is run against a cluster with name `my-cluster`
- **THEN** the `start` script receives `CLUSTER_NAME=my-cluster` in its environment

#### Scenario: EASY_DB_LAB_EXEC is set
- **WHEN** `easy-db-lab clickhouse start` is run from a dev installation
- **THEN** `EASY_DB_LAB_EXEC` resolves to the `bin/easy-db-lab` path under `easydblab.apphome`

#### Scenario: Script exit code propagated
- **WHEN** the invoked script exits with a non-zero code
- **THEN** the CLI process exits with the same code

### Requirement: WorkloadRunner installs dashboards after start completes
When the `start` script exits successfully, the runner SHALL scan `<workload>/dashboards/` for `*.json` files and install each one via the Grafana dashboard API. This logic is handled by the runner, not the shell script. The `dashboards/` loop SHALL be removed from `start.sh.template` for all workloads.

#### Scenario: Dashboards installed automatically after start
- **WHEN** `easy-db-lab clickhouse start` is run and `./clickhouse/dashboards/` contains JSON files
- **THEN** each dashboard is installed via Grafana API after the start script exits successfully

#### Scenario: No dashboards dir — no error
- **WHEN** `easy-db-lab presto start` is run and `./presto/dashboards/` does not exist
- **THEN** the runner completes normally without error

#### Scenario: Start script fails — dashboards not installed
- **WHEN** the `start` script exits with a non-zero code
- **THEN** dashboard installation is skipped and the exit code is propagated

### Requirement: Discovered workloads appear in --help output
Workload subcommands registered from the working directory SHALL appear in the top-level `easy-db-lab --help` output alongside static commands.

#### Scenario: Help lists discovered workload
- **WHEN** `./clickhouse/bin/` exists and `easy-db-lab --help` is run
- **THEN** `clickhouse` appears in the command listing
