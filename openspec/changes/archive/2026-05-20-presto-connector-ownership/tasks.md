## 1. Running Workload State — ClusterState

- [x] 1.1 Add `runningWorkloads: Set<String>` field to `ClusterState` (default empty set, serialized to `state.json`)
- [x] 1.2 Add `addRunningWorkload(name: String)` and `removeRunningWorkload(name: String)` helpers to `ClusterStateManager`
- [x] 1.3 Write unit tests for `ClusterState` serialization round-trip with `runningWorkloads`

## 2. Running Workload State — Template Variables

- [x] 2.1 Add `runningWorkloads: String` field to `TemplateVariables` (comma-separated)
- [x] 2.2 Add `__RUNNING_WORKLOADS__` entry to `TemplateVariables.toMap()`
- [x] 2.3 Populate `runningWorkloads` in `TemplateVariables.from()` from `ClusterState.runningWorkloads`
- [x] 2.4 Write unit tests for `__RUNNING_WORKLOADS__` template variable (empty set, single, multiple)

## 3. Update Running State on Workload Lifecycle

- [x] 3.1 In `WorkloadRunnerCommand`: call `addRunningWorkload` after successful `start` phase
- [x] 3.2 In `WorkloadRunnerCommand`: call `removeRunningWorkload` after successful `stop` phase
- [x] 3.3 In `CassandraStartCommand` (and equivalent stop): update `runningWorkloads` for `"cassandra"`
- [x] 3.4 Write unit tests verifying `runningWorkloads` updated correctly on start and stop

## 4. Workload Lifecycle Hooks — Schema

- [x] 4.1 Add `WorkloadHook` data class with fields: `script: String`, `workloads: List<String>` (default empty = all)
- [x] 4.2 Add `WorkloadHooks` data class with fields: `postWorkloadStart: WorkloadHook?`, `postWorkloadStop: WorkloadHook?`
- [x] 4.3 Add `hooks: WorkloadHooks?` field to `WorkloadInstallConfig`
- [x] 4.4 Write unit tests for `WorkloadInstallConfig` deserialization with `hooks` (with and without workload scoping)

## 5. Workload Lifecycle Hooks — Execution

- [x] 5.1 Create `WorkloadHookExecutor` service: discovers installed workloads in working directory, loads their `install.yaml`, filters to those with matching hooks, runs scripts with resilience4j retry
- [x] 5.2 Implement hook script invocation: run script as subprocess with cluster state env vars injected (same pattern as `WorkloadStepExecutor` for shell steps)
- [x] 5.3 Implement workload scoping filter: skip hook if `hook.workloads` is non-empty and does not contain the triggering workload name
- [x] 5.4 Skip hook if the declaring workload is the same as the triggering workload
- [x] 5.5 Emit `Event.Workload.HookFailed` error event on retry exhaustion; continue execution
- [x] 5.6 Add `Event.Workload.HookFailed(workload: String, hook: String, reason: String)` to `Event.kt`
- [x] 5.7 Wire `WorkloadHookExecutor` into `WorkloadRunnerCommand` post-start and post-stop
- [x] 5.8 Wire `WorkloadHookExecutor` into `CassandraStartCommand` and equivalent stop command
- [x] 5.9 Register `WorkloadHookExecutor` in Koin modules
- [x] 5.10 Write unit tests for `WorkloadHookExecutor`: hook fires, scoping filter, self-skip, retry-on-failure, error-on-exhaustion

## 6. Presto — Catalog Templates

- [x] 6.1 Create `presto/catalogs/cassandra.properties.template` with connector config using `__DB_NODE_IPS__`
- [x] 6.2 Create `presto/catalogs/clickhouse.properties.template` with ClickHouse JDBC connector config using `__DB_NODE_IPS__`
- [x] 6.3 Remove hard-coded Cassandra catalog from `presto/values.yaml.template`

## 7. Presto — update-catalogs.sh

- [x] 7.1 Create `presto/bin/update-catalogs.sh.template`: reads `RUNNING_WORKLOADS`, renders a catalog properties file for each running workload that has a matching template in `presto/catalogs/`, assembles `--values` override yaml, runs `helm upgrade`
- [x] 7.2 Scan sibling directories for `presto-catalog.properties` as fallback for custom `--from` workloads
- [x] 7.3 Simplify `presto/bin/start.sh.template`: remove inline catalog assembly loop, call `update-catalogs.sh` instead
- [x] 7.4 Add `hooks` block to `presto/install.yaml`: `post-workload-start` and `post-workload-stop` both call `bin/update-catalogs.sh`

## 8. Documentation

- [x] 8.1 Update `install-command` spec to document the `hooks` section of `install.yaml`
- [x] 8.2 Update workload README template or docs to document the `presto-catalog.properties` convention for custom workloads
