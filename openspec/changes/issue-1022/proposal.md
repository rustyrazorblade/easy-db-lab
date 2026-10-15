## Why

You cannot use easy-db-lab to measure ClickHouse query performance.  The only benchmark kit, `sysbench`, drives OLTP workloads over the MySQL or PostgreSQL protocols, and it cannot run analytical queries over the ClickHouse native protocol.  Also, the `sysbench` pods have no node selector, so they can land on a database node and compete with the database they measure, and a failed `sysbench` run reports success.

## What Changes

- New built-in `app` kit `clickhouse-benchmark` under `src/main/resources/com/rustyrazorblade/easydblab/kits/clickhouse-benchmark/` (`kit.yaml`, `bin/`, `lib/`, `pod/`, `workloads/clickbench/`, `dashboards/clickhouse-benchmark.json`).  It runs the `clickhouse-benchmark` CLI from the image `clickhouse/clickhouse-server:<version>` against a running `clickhouse` kit instance.
  - Install args: `--target` (required kit-ref, `endpoints: [native, http]`) and `--version` (default `latest`).  The instance name follows the `<kit>-<target>` rule, for example `clickhouse-benchmark-clickhouse`.
  - `prepare` (`--workload`, `--source`, `--storage-policy`, `--recreate`): creates `hits` as a ReplicatedMergeTree table `ON CLUSTER clickhouse` and loads it on the server with `INSERT INTO hits SELECT * FROM s3(...)`.  With no `--source`, the server reads the pinned public ClickBench parquet URL with `NOSIGN`.  A `--source` override is read with the cluster's IAM role.  A failed load stops `prepare` with the server's error and never drops `hits`.  `prepare` refuses while a benchmark run pod is active.
  - `start` (`--workload` or `--queries`, `--concurrency`, `--duration`, `--iterations`, `--randomize`, `--continue-on-errors`, `--settings`): runs the 43 packaged ClickBench queries or the operator's own query file, pushes ten OTLP gauges every 10 seconds, writes `last-run.txt` into the kit directory, and exits non-zero when the run fails.
  - `stop`: deletes the instance's pods and ConfigMaps by label selector.
  - Every kit pod runs on an app node (`type=app`).  `prepare` and `start` fail before they create a pod when the cluster has no app node.
- Kit-ref args gain an `endpoints` list field (`KitArgSpec.endpoints`).  `kit install` refuses a target that lacks any listed endpoint type and names every missing type.  The install check moves into a pure `KitRefTargetValidator`.  The `capability` field is enforced, as the code already does; the spec stops calling it advisory.
- A `native` endpoint on the target yields `TARGET_NATIVE_HOST`, `TARGET_NATIVE_PORT`, `TARGET_NATIVE_USER` and `TARGET_NATIVE_DATABASE`.  `KitEndpoint.toTargetVars` becomes an exhaustive `when`.  The `clickhouse` kit's Native endpoint declares `database: "default"`.
- A declared kit arg that the operator does not give, and that has no default, reaches the script as an empty string, in both the install factory and the runner factory.  It no longer arrives as the string `null`, and an exported shell variable of the same name cannot leak in.
- Boolean kit args take arity `0..1` in both factories, for every kit.  `--flag`, `--flag=true`, `--flag false` and `--flag=false` all work.  This also fixes `clickhouse --s3-cache-on-write false`.
- New shared kit shell library `edl-kit-lib.sh`, packaged as `configuration/edl-kit-lib.sh`.  `ToolWrapperInstaller` writes it once into `<workspace>/bin/` and never rewrites it.  `clean` removes it.  `KitProcessEnvironment` sets `EDL_KIT_LIB` to its absolute path for every kit process.  It holds the app-node check, pod overrides, labelled ConfigMap creation, the Pending wait with fast fail on pull errors and a 10-minute limit, log following with resume, the exit-code wait and the gauge push.  It works under bash 3.2.
- `sysbench` adopts the library: all of its pods (run, prepare, createdb, cleanup) run on app nodes, every script checks for an app node before its first pod, `stop` fails with the app-node message when the cluster has none, the 60-second Ready wait becomes the Pending wait, and `start` checks the run pod's exit code.
- `SysbenchFlagsDocumentedTest` becomes a parameterised kit-flags-documented test that covers `sysbench` and `clickhouse-benchmark`.
- Docs: new `docs/user-guide/clickhouse-benchmark.md`, `docs/SUMMARY.md`, the kit list in `docs/user-guide/kits.md`, an app-node note in `docs/user-guide/sysbench.md`, and `docs/development/kits.md` (the `endpoints` field, the `native` TARGET_* row, the `lib/` layout, and a "Shared shell library" section).  The root `CLAUDE.md` bin/ paragraph lists the library, and its kit section points kit authors at it.

## Capabilities

### New Capabilities
- `clickhouse-benchmark-kit`: the `clickhouse-benchmark` kit, its install, `prepare`, `start` and `stop` behaviour, metrics, dashboard, results and docs.
- `kit-shell-library`: the packaged shell library in `<workspace>/bin/edl-kit-lib.sh`, how it is written, found and removed, and the contract of its functions.
- `sysbench-app-node-placement`: `sysbench` pods run on app nodes, the start wait, and the run pod exit code.

### Modified Capabilities
- `kit-cross-targeting`: the kit-ref requirement gains the enforced capability check and the `endpoints` list; the TARGET_* requirement gains the `native` endpoint variables.
- `kit-command-args`: an unset arg with no default reaches the script as an empty string; boolean args accept an optional value.
- `kit-install-command`: install args with no value are recorded as empty strings; boolean install args accept an optional value.
- `clickhouse`: the Native endpoint declares the `default` database.

## Impact

- Kotlin: `services/KitConfig.kt` (`KitArgSpec.endpoints`, `toTargetVars`), `commands/install/KitInstallCommand.kt`, new `KitRefTargetValidator`, `commands/install/KitInstallCommandFactory.kt`, `commands/install/KitRunnerCommandFactory.kt`, `proxy/ToolWrapperInstaller.kt`, `services/KitProcessEnvironment.kt`, `Constants.kt`.
- Resources: new `kits/clickhouse-benchmark/`, new `configuration/edl-kit-lib.sh`, `kits/sysbench/bin/*.sh.template`, `kits/clickhouse/kit.yaml`.
- Tests: new shell tests under `src/test/shell/` with Gradle tasks in `testScripts` (also run under `/bin/bash` when it is 3.x), Kotlin tests for the validator, the factories, `toTargetVars`, the installer and the process environment, and the kit-flags-documented test.
- Behaviour change for existing users: `sysbench` needs an app node for every command, including `prepare` and `stop`.
- No new external dependency.  The kit scripts need no `jq` at run time.
- Related issues: 969 (trivial merge conflict in the runner factory and `KitArgSpec`), 953 (migrates TARGET_NATIVE_* later), 874 (stays open for other kits), 985 (docs note).
