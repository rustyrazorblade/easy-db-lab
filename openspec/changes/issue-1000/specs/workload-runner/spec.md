## MODIFIED Requirements

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
| `KUBECONFIG` | Absolute path to the workspace kubeconfig |
| `EASY_DB_LAB_EXEC` | Absolute path to the `easy-db-lab` executable |
| `PATH` | `<workspace>/bin` followed by the inherited `PATH` |

The tool wrappers SHALL be written to `<workspace>/bin` before the script starts. The script SHALL NOT start if the workspace kubeconfig does not exist.

#### Scenario: Env vars injected at exec time
- **WHEN** `easy-db-lab clickhouse start` is run against a cluster with name `my-cluster`
- **THEN** the `start` script receives `CLUSTER_NAME=my-cluster` in its environment

#### Scenario: EASY_DB_LAB_EXEC is set
- **WHEN** `easy-db-lab clickhouse start` is run from a dev installation
- **THEN** `EASY_DB_LAB_EXEC` resolves to the `bin/easy-db-lab` path under `easydblab.apphome`

#### Scenario: Script exit code propagated
- **WHEN** the invoked script exits with a non-zero code
- **THEN** the CLI process exits with the same code

#### Scenario: Script gets the wrappers and an absolute kubeconfig
- **WHEN** `easy-db-lab <workload> <script>` runs
- **THEN** the script's `KUBECONFIG` is an absolute path to a file that exists
- **AND** the first entry of its `PATH` is `<workspace>/bin`
