## 1. Schema — WorkloadInstallConfig

- [x] 1.1 Add `WorkloadEndpoint` data class with fields: `name`, `nodeType`, `port`, `type`, `scheme`, `path`
- [x] 1.2 Add `WorkloadRuntime` data class with fields: `type`, `release`, `selector`, `name`, `namespace`
- [x] 1.3 Add `endpoints: List<WorkloadEndpoint>` and `runtime: WorkloadRuntime?` fields to `WorkloadInstallConfig`
- [x] 1.4 Write unit tests for `WorkloadInstallConfig` deserialization with `endpoints` and `runtime` fields

## 2. TemplateVariables — APP_NODE_IPS

- [x] 2.1 Add `appNodeIps: String` field to `TemplateVariables`
- [x] 2.2 Add `APP_NODE_IPS` entry to `toMap()` using `ServerType.Stress` private IPs
- [x] 2.3 Populate `appNodeIps` in `TemplateVariables.from()` from cluster state
- [x] 2.4 Write unit tests for `APP_NODE_IPS` resolution (multiple nodes, zero nodes)

## 3. WorkloadStatusCommand

- [x] 3.1 Create `WorkloadStatusCommand` class extending `PicoBaseCommand` with `workloadName`, `workloadDir`, and `installConfig` constructor parameters
- [x] 3.2 Implement running-state detection: `runtime:` block → typed phase inference → pod label fallback
- [x] 3.3 Implement helm running-state check via `HelmService.releaseExists`
- [x] 3.4 Implement pod running-state check via K8s API (Fabric8 client, label selector)
- [x] 3.5 Implement endpoint URL formatting for all types: `http`, `https`, `jdbc`, `native`, `cql`
- [x] 3.6 Implement private IP resolution: `node-type: app` → `APP_NODE_IPS`, `node-type: db` → `DB_NODE_IPS`, `node-type: control` → `CONTROL_HOST_PRIVATE`
- [x] 3.7 Emit structured events for status output (add `Event.Workload.Status` domain events)
- [x] 3.8 Write unit tests for endpoint URL formatting (all types, multiple nodes, missing optional fields)

## 4. WorkloadRunnerCommandFactory — synthesize status

- [x] 4.1 Add `buildStatusCommand()` method to `WorkloadRunnerCommandFactory` that returns a `WorkloadStatusCommand` wrapped in `CommandLine`
- [x] 4.2 Always add `status` subcommand in `buildWorkloadGroup()` — synthesized unconditionally for every workload
- [x] 4.3 Write unit tests for `WorkloadRunnerCommandFactory` verifying `status` is always present

## 5. install.yaml updates — presto and clickhouse

- [x] 5.1 Add `runtime: {type: helm, release: presto, namespace: default}` to presto `install.yaml`
- [x] 5.2 Add `endpoints:` to presto `install.yaml`: Presto UI (http, app, 8080) and JDBC (jdbc, app, 8080, scheme: presto, path: /cassandra)
- [x] 5.3 Add `runtime:` to clickhouse `install.yaml` (pods selector: `clickhouse.altinity.com/chi=clickhouse`)
- [x] 5.4 Add `endpoints:` to clickhouse `install.yaml`: HTTP (http, db, 8123) and Native (native, db, 9000)

## 6. Events

- [x] 6.1 Add `Event.Workload.StatusRunning(workload: String, readyPods: Int, totalPods: Int)` event
- [x] 6.2 Add `Event.Workload.StatusStopped(workload: String)` event
- [x] 6.3 Add `Event.Workload.StatusEndpoint(workload: String, name: String, type: String, url: String)` event
- [x] 6.4 Add `Event.Workload.StatusUnknown(workload: String, reason: String)` event
