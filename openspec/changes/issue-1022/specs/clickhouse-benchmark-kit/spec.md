## ADDED Requirements

### Requirement: clickhouse-benchmark kit descriptor
The CLI SHALL ship a built-in kit `clickhouse-benchmark` under `kits/clickhouse-benchmark/`, with `type: app`.  Its `kit.yaml` SHALL declare:

- Install args: `--target` (variable `TARGET`, `type: kit-ref`, `endpoints: [native, http]`, no `capability`, required) and `--version` (variable `VERSION`, default `latest`).
- `prepare` args: `--workload` (default `clickbench`), `--source` (no default; the description names the pinned ClickBench URL), `--storage-policy` (default `default`) and `--recreate` (`boolean`, default `false`).
- `start` args: `--workload` (no default; the description says `(default clickbench)`), `--queries` (no default), `--concurrency` (`int`, default `4`), `--duration` (`int`, default `60`), `--iterations` (`int`, default `0`), `--randomize` (`boolean`, default `true`), `--continue-on-errors` (`boolean`, default `false`) and `--settings` (no default).
- A runtime probe of `type: pods` with selector `easydblab/kit=${KIT_NAME},easydblab/role=benchmark-run`, namespace `default`.
- `collision-check: true`.

Shared kit code SHALL live in `lib/` and `pod/`, never in `bin/`, so only `prepare`, `start` and `stop` become phase commands.  The kit SHALL work from a Homebrew install with no source checkout.

#### Scenario: kit.yaml parsed
- **WHEN** the built-in `clickhouse-benchmark/kit.yaml` is parsed
- **THEN** it gives the install args, the `prepare` and `start` args and defaults listed above, the runtime probe on role `benchmark-run`, and `endpoints: [native, http]` on `--target`

#### Scenario: Only lifecycle scripts become commands
- **WHEN** the kit is installed
- **THEN** its subcommands are `prepare`, `start` and `stop`, and no subcommand comes from `lib/` or `pod/`

### Requirement: Install and instance naming
`kit install clickhouse-benchmark --target <kit>` SHALL install the kit as `clickhouse-benchmark-<kit>`, after the kit-ref target check in the `kit-cross-targeting` capability.  `--version <v>` SHALL select the image `clickhouse/clickhouse-server:<v>` for the benchmark pod and the prepare pod.

#### Scenario: Install against clickhouse
- **WHEN** `kit install clickhouse-benchmark --target clickhouse` runs against an installed `clickhouse` kit
- **THEN** the kit installs as `clickhouse-benchmark-clickhouse`

#### Scenario: Target has no native endpoint
- **WHEN** `kit install clickhouse-benchmark --target tidb` runs and `tidb` declares no `native` endpoint
- **THEN** install fails before it writes the kit, with a message that names every missing endpoint type, for example `Target kit 'tidb' does not have the 'native' endpoint required by clickhouse-benchmark`

#### Scenario: Target not installed
- **WHEN** `--target` names a kit that is not installed
- **THEN** install fails with the existing "is not installed" message

#### Scenario: Target variables reach the scripts
- **WHEN** the target `clickhouse` kit is installed with a `native` endpoint
- **THEN** the kit scripts get `TARGET_NATIVE_HOST` (the first db node's private IP), `TARGET_NATIVE_PORT=30900`, `TARGET_NATIVE_USER` and `TARGET_NATIVE_DATABASE`

#### Scenario: Explicit version
- **WHEN** the kit is installed with `--version 25.4`
- **THEN** the benchmark pod and the prepare pod use the image `clickhouse/clickhouse-server:25.4`

#### Scenario: Default version
- **WHEN** the kit is installed without `--version`
- **THEN** the benchmark pod and the prepare pod use the image `clickhouse/clickhouse-server:latest`

#### Scenario: Required target variables missing
- **WHEN** `prepare` or `start` runs with `TARGET_NATIVE_HOST` or `TARGET_NATIVE_PORT` empty
- **THEN** the script exits 1, names the variable, and creates no pod

### Requirement: App node placement
`prepare` and `start` SHALL call `edl_require_app_node clickhouse-benchmark` before they create any pod or ConfigMap, and SHALL create every pod with `edl_pod_overrides`, so the pod carries `nodeSelector: {type: app}`.

#### Scenario: Pod on an app node
- **WHEN** `prepare` or `start` runs on a cluster with an app node
- **THEN** the pod is scheduled on a node labelled `type=app`

#### Scenario: No app node
- **WHEN** `prepare` or `start` runs on a cluster with no app node
- **THEN** it fails before it creates a pod, with `clickhouse-benchmark needs an app node (type=app); this cluster has none`

### Requirement: Pod and ConfigMap names and labels
Every pod and ConfigMap the kit creates SHALL get its name from `edl_resource_name` (`${KIT_NAME}-<role>-$(date +%s)`) and SHALL carry `easydblab/kit=${KIT_NAME}` and `easydblab/role=<role>`, with role `benchmark-run` for `start` and `prepare` for `prepare`.  Files reach a pod through one ConfigMap per run, made with `edl_create_configmap` and mounted at `/edl`.  `start` SHALL delete its own ConfigMap after the run.

#### Scenario: Pod names and labels
- **WHEN** `start` or `prepare` creates a pod
- **THEN** its name ends in `$(date +%s)` and it carries `easydblab/kit=<instance>` and `easydblab/role=benchmark-run` or `easydblab/role=prepare`

#### Scenario: ConfigMap labelled at creation
- **WHEN** `start` creates the ConfigMap that holds the queries
- **THEN** the ConfigMap carries `easydblab/kit=<instance>` and `easydblab/role=benchmark-run`

#### Scenario: Queries file over the ConfigMap size limit
- **WHEN** `start --queries big.sql` runs and `big.sql` is larger than 1,000,000 bytes with the runner script
- **THEN** `start` fails before it creates a pod, gives the size and the limit

### Requirement: Pod start wait in both commands
`prepare` and `start` SHALL wait for their pod with `edl_wait_for_pod_running`, so a pull error fails at once and a pod that stays Pending fails after 10 minutes.

#### Scenario: Bad version tag
- **WHEN** the kit is installed with `--version 25.44` and the image pull fails with `ImagePullBackOff`
- **THEN** `start` fails at once and shows the reason and the describe events

#### Scenario: Pod pending for ten minutes
- **WHEN** the benchmark pod stays Pending with no pull error for 600 seconds
- **THEN** `start` fails and prints `kubectl describe` for the pod

#### Scenario: Pod finishes before it is seen Running
- **WHEN** the run pod fails at once and its first poll shows phase Failed
- **THEN** `start` goes on to report the exit code and does not wait for the limit

### Requirement: Prepare creates and loads hits
`prepare` SHALL create `default.hits` from the packaged `workloads/clickbench/create-hits.sql` as a ReplicatedMergeTree table `ON CLUSTER clickhouse`, with `SETTINGS storage_policy = '<policy>'`, and SHALL load it on the server with `INSERT INTO hits SELECT * FROM s3(...)`, from a prepare pod that runs `clickhouse-client` (`pod/prepare-load.sh`).  With an empty `--source`, the server SHALL read `https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet` with `s3('<url>', NOSIGN, 'Parquet')`.  A given `--source` SHALL be read with `s3('<url>', 'Parquet')`, which uses the cluster's IAM role.  The cluster name `clickhouse` is a constant `CH_CLUSTER`.  `prepare --workload` SHALL accept only `clickbench`.

#### Scenario: Default source
- **WHEN** `prepare` runs with no `--source`
- **THEN** `hits` is created as a ReplicatedMergeTree table `ON CLUSTER clickhouse` and loaded from the pinned public ClickBench parquet URL with `NOSIGN`, by an `INSERT INTO hits SELECT * FROM s3(...)` that runs on the server

#### Scenario: Source override
- **WHEN** `prepare --source <s3 url>` runs
- **THEN** the server loads `hits` from that URL with the cluster's IAM role credentials

#### Scenario: Source the role cannot read
- **WHEN** the `--source` object cannot be read by the cluster's IAM role
- **THEN** `prepare` fails, shows the server's error and exits non-zero
- **AND** it does not report success

#### Scenario: Source with a quote or backslash
- **WHEN** `--source` holds a `'` or a `\`
- **THEN** `prepare` fails before it creates a pod and names the value

#### Scenario: Storage policy
- **WHEN** `prepare --storage-policy s3_main` runs
- **THEN** `hits` uses the `s3_main` storage policy, as shown in `system.tables`

#### Scenario: Storage policy the server does not define
- **WHEN** `--storage-policy` names a policy that the server does not define
- **THEN** `prepare` fails, shows the server's error and exits non-zero

#### Scenario: Storage policy with invalid characters
- **WHEN** `--storage-policy` does not match `^[A-Za-z0-9_]+$`
- **THEN** `prepare` fails before it creates a pod and names the value

#### Scenario: Unknown prepare workload
- **WHEN** `prepare --workload tpch` runs
- **THEN** `prepare` fails before it creates a pod and lists the valid workloads

### Requirement: Prepare refuses to overwrite and recreates on request
Before it creates a pod, `prepare` SHALL query the target over its HTTP endpoint (`TARGET_HTTP_URL`, through the `curl` workspace wrapper) for whether `hits` exists and its storage policy.  When `hits` exists and `--recreate` is not given, `prepare` SHALL fail with a message that names the table and its current storage policy and tells the user to use `--recreate`, and SHALL NOT change the table.  With `--recreate`, the prepare pod SHALL run `DROP TABLE IF EXISTS hits ON CLUSTER clickhouse SYNC` before it creates and loads `hits`.  When `TARGET_HTTP_URL` is empty, `prepare` SHALL fail with a clear message.

#### Scenario: hits exists without --recreate
- **WHEN** `hits` exists with storage policy `default` and `prepare` runs without `--recreate`
- **THEN** `prepare` fails with a message that names `hits`, its policy `default`, and `--recreate`
- **AND** it creates no pod and does not change the table

#### Scenario: Recreate
- **WHEN** `prepare --recreate` runs
- **THEN** the prepare pod runs `DROP TABLE IF EXISTS hits ON CLUSTER clickhouse SYNC`, then creates and loads `hits`

#### Scenario: Recreate when hits does not exist
- **WHEN** `prepare --recreate` runs and `hits` does not exist
- **THEN** the drop succeeds and the load runs

### Requirement: Prepare waits for the load with no client limit
The prepare pod SHALL run the load with no client-side timeout: `clickhouse-client` with large `receive_timeout` and `send_timeout` and `max_execution_time=0`.  Every `ON CLUSTER` statement SHALL run with a negative `distributed_ddl_task_timeout` (no limit).  The operator side SHALL follow the pod's logs and wait for the pod to exit with no fixed timeout.

#### Scenario: Long load
- **WHEN** the load runs longer than the default client timeout
- **THEN** `prepare` still waits for it to finish and reports the server's result

#### Scenario: distributed_ddl_task_timeout no limit
- **WHEN** `SYSTEM SYNC REPLICA ON CLUSTER clickhouse hits` runs for longer than 180 seconds
- **THEN** the statement does not time out on the client side and `prepare` reports success when it completes

### Requirement: Prepare checks every replica
After the load, the prepare pod SHALL run `SYSTEM SYNC REPLICA ON CLUSTER clickhouse hits` and then read `count()` from every replica with `clusterAllReplicas('clickhouse', currentDatabase(), 'hits')`.  When the counts differ, `prepare` SHALL fail and print each replica's count.

#### Scenario: Replicas agree
- **WHEN** `prepare` finishes
- **THEN** every replica returns the same row count for `hits` and `prepare` prints it

#### Scenario: Replicas disagree
- **WHEN** one replica returns a different row count
- **THEN** `prepare` fails and prints each replica's count

### Requirement: Failed load stops and never drops hits
When a statement in the prepare pod fails, `prepare` SHALL show the server's error and exit non-zero.  It SHALL NOT drop `hits`.  When the `INSERT` fails, the message SHALL also say `hits may be partly loaded; run prepare --recreate to reload it`.  `start` SHALL add no empty-table check.

#### Scenario: Load fails part way
- **WHEN** the `INSERT ... SELECT FROM s3(...)` fails after some rows are written
- **THEN** `prepare` shows the server's error, says `hits may be partly loaded; run prepare --recreate to reload it`, and exits non-zero
- **AND** `hits` still exists

### Requirement: Prepare refuses during an active run
Before it does anything else on the cluster, `prepare` SHALL call `edl_active_pods benchmark-run`.  When a benchmark run pod for this instance is Pending or Running, `prepare` SHALL fail, name the pod, and tell the user to run `stop` or wait.

#### Scenario: prepare refuses during an active run
- **WHEN** a `benchmark-run` pod for this instance is Running and `prepare --recreate` runs
- **THEN** `prepare` fails, names the pod, and does not drop `hits`

#### Scenario: Finished run does not block prepare
- **WHEN** the only `benchmark-run` pod for this instance is Succeeded
- **THEN** `prepare` proceeds

### Requirement: Start selects the query set
`start` SHALL run the 43 packaged ClickBench queries (`workloads/clickbench/queries.sql`, one query per line) when `--workload clickbench` is given or when neither `--workload` nor `--queries` is given.  `--queries <local path>` SHALL send that file from the operator's machine to the pod through the run's ConfigMap; a relative path resolves against the operator's current directory.  All checks below SHALL run before any pod is created.

#### Scenario: Default ClickBench workload
- **WHEN** `start` runs with no `--workload` and no `--queries`, or with `--workload clickbench`
- **THEN** `clickhouse-benchmark` runs the 43 packaged ClickBench queries against `TARGET_NATIVE_HOST:TARGET_NATIVE_PORT`

#### Scenario: Own queries file
- **WHEN** `start --queries my.sql` runs
- **THEN** the pod gets the contents of `my.sql` from the operator's machine and runs those queries
- **AND** this works from a Homebrew install with no source checkout

#### Scenario: Queries file missing or empty
- **WHEN** `--queries` names a file that does not exist or is empty
- **THEN** `start` fails before it creates a pod and names the path

#### Scenario: Both workload and queries
- **WHEN** both `--workload` and `--queries` are given
- **THEN** `start` fails before it creates a pod, with a message that you can give only one of them

#### Scenario: Unknown workload
- **WHEN** `--workload` is a value other than `clickbench`
- **THEN** `start` fails before it creates a pod and lists the valid workloads

#### Scenario: hits missing for ClickBench
- **WHEN** `start` runs with the ClickBench workload and `hits` does not exist, as read over the target HTTP endpoint
- **THEN** `start` fails before it creates a pod and tells you to run `prepare`

#### Scenario: Own queries skip the hits check
- **WHEN** `--queries` is given
- **THEN** `start` does not check for `hits`

### Requirement: Start maps its args to clickhouse-benchmark
`start` SHALL pass `--concurrency` as `-c`, `--duration` as `--timelimit`, `--iterations` as `-i`, and SHALL always pass `--delay 10`, `--host`, `--port` and `--database`, and `--user` when `TARGET_NATIVE_USER` is not empty.  It SHALL pass `--randomize` and `--continue_on_errors` only when their args are `true`.  Each `--settings` entry `k=v` SHALL become `--k=v`.  A `--settings` entry SHALL fail before any pod when it has no `=`, when its key does not match `^[A-Za-z0-9_]+$`, or when its key is on the denylist: `host`, `port`, `user`, `password`, `database`, `secure`, `queries_file`, `query`, `concurrency`, `iterations`, `timelimit`, `delay`, `json`, `randomize`, `continue_on_errors`, `cumulative`, `reconnect`, `query_id`, `confidence`, `stage`, `max_consecutive_errors` and `help`.  The failure SHALL name the entry.  A setting value cannot hold a comma.

#### Scenario: Run args mapped
- **WHEN** `start --concurrency 8 --duration 120 --iterations 5 --randomize --continue-on-errors` runs
- **THEN** `clickhouse-benchmark` gets `-c 8`, `--timelimit 120`, `-i 5`, `--randomize` and `--continue_on_errors`

#### Scenario: Randomize turned off
- **WHEN** `start --randomize=false` runs
- **THEN** `clickhouse-benchmark` does not get `--randomize`

#### Scenario: Duration and iterations both set
- **WHEN** both `--duration` and `--iterations` are not 0
- **THEN** the run stops at the first limit it reaches

#### Scenario: Settings mapped
- **WHEN** `--settings max_threads=8,use_query_cache=0` is given
- **THEN** `clickhouse-benchmark` gets `--max_threads=8 --use_query_cache=0`

#### Scenario: Settings entry without equals
- **WHEN** `--settings max_threads` is given
- **THEN** `start` fails before it creates a pod and names `max_threads`

#### Scenario: Settings key with invalid characters
- **WHEN** `--settings max-threads=8` is given
- **THEN** `start` fails before it creates a pod and names `max-threads=8`

#### Scenario: Settings key on the denylist
- **WHEN** `--settings host=10.0.0.9` is given
- **THEN** `start` fails before it creates a pod and names `host=10.0.0.9`

#### Scenario: Unset settings
- **WHEN** `start` runs without `--settings`
- **THEN** `clickhouse-benchmark` gets no setting flags and `start` does not fail

### Requirement: Start guard
The runtime probe SHALL make a second `start` fail while a `benchmark-run` pod for this instance is still running.  A pod in Succeeded or Failed SHALL NOT block a new `start`.

#### Scenario: Run still active
- **WHEN** a benchmark run pod for this instance is still running
- **THEN** a second `start` is refused

#### Scenario: Previous run finished
- **WHEN** the previous run's pod is in Succeeded
- **THEN** a new `start` is not refused

### Requirement: Start reports the run pod exit code
The run pod SHALL run `pod/run-benchmark.sh` (POSIX sh), which runs `clickhouse-benchmark` with the queries file on stdin and exits with the tool's exit code.  After the log stream ends, `start` SHALL wait for the pod to exit with `edl_wait_for_pod_exit`.  When the exit code is not 0, `start` SHALL say that the run failed, give the exit code, not print the success line, and exit non-zero.

#### Scenario: Benchmark exits non-zero
- **WHEN** the `clickhouse-benchmark` process in the run pod exits non-zero
- **THEN** `start` says that the run failed, gives the exit code, and exits non-zero
- **AND** it does not print the success line

#### Scenario: Query fails and errors stop the run
- **WHEN** a query fails and `--continue-on-errors` is false
- **THEN** the run stops, `start` shows the error and exits non-zero

#### Scenario: Benchmark exits 0
- **WHEN** the run pod exits 0
- **THEN** `start` exits 0

#### Scenario: Run pod deleted mid-run
- **WHEN** `stop` in another terminal deletes the run pod while `start` follows it
- **THEN** `start` fails with `pod <name> was deleted` and does not wait forever

### Requirement: Stop deletes the instance's resources
`stop` SHALL run `kubectl delete pods,configmaps -l easydblab/kit=${KIT_NAME}` and SHALL leave other kits' pods and ConfigMaps alone.

#### Scenario: Stop
- **WHEN** `stop` runs
- **THEN** it deletes the instance's pods and ConfigMaps by label selector
- **AND** other kits' pods are left alone

### Requirement: Live OTLP gauges
While a run is in progress, `start` SHALL parse each report block that `clickhouse-benchmark` prints every 10 seconds (`--delay 10`) from the merged pod log stream (the tool prints its report to stderr), and SHALL push these gauges with `edl_push_gauges clickhouse-benchmark ...`, each labelled `kit=<instance>`: `clickhouse_benchmark_qps`, `clickhouse_benchmark_rows_per_second`, `clickhouse_benchmark_mib_per_second`, `clickhouse_benchmark_queries_executed`, `clickhouse_benchmark_errors`, `clickhouse_benchmark_latency_p50_ms`, `clickhouse_benchmark_latency_p90_ms`, `clickhouse_benchmark_latency_p95_ms`, `clickhouse_benchmark_latency_p99_ms` and `clickhouse_benchmark_latency_p999_ms`.  The parser (`lib/report.sh`) SHALL convert latency from seconds to milliseconds, and SHALL take p999 from the `99.900%` line.  The kit SHALL need no `jq` at run time.

#### Scenario: Gauges every 10 seconds
- **WHEN** a run is in progress
- **THEN** every 10 seconds the kit pushes the ten gauges above to the control node collector, each labelled `kit=<instance>`

#### Scenario: Parser on a saved report
- **WHEN** a saved `clickhouse-benchmark` report, captured from a real run, is fed to the parser in a plain shell test with no Docker
- **THEN** the parser gives the expected QPS, rows/s, MiB/s, query count, error count and the five percentiles in milliseconds

#### Scenario: Missing or non-numeric field
- **WHEN** a report field is missing or is not a number
- **THEN** the parser sends 0 for that gauge and the run goes on

#### Scenario: Gauges after a cut stream
- **WHEN** the log stream is cut while the run pod is Running
- **THEN** `start` resumes the stream and the gauges go on

### Requirement: Kit dashboard
The kit SHALL ship `dashboards/clickhouse-benchmark.json`.  Its panels SHALL show the ten gauges and SHALL filter by kit instance.  It SHALL name Mimir only through the `metrics_datasource` picker, and its link to the `clickhouse` dashboard SHALL carry the datasource pickers and the selected cluster.  The dashboard is built and checked with the `dashboard-editor` agent.

#### Scenario: Dashboard installed after start
- **WHEN** `start` succeeds
- **THEN** `clickhouse-benchmark.json` is installed in a Grafana folder for the kit
- **AND** its panels show the gauges and can be filtered by kit instance

#### Scenario: Link to the clickhouse dashboard
- **WHEN** you open the dashboard's link to the `clickhouse` dashboard
- **THEN** the link carries the datasource pickers and the selected cluster

#### Scenario: Datasource variables test
- **WHEN** `DashboardDatasourceVariablesTest` runs
- **THEN** `clickhouse-benchmark.json` passes

#### Scenario: Panels show data on a real cluster
- **WHEN** a run is in progress on a real cluster, after an earlier successful `start` installed the dashboard
- **THEN** each dashboard panel shows non-zero data, as read back from Grafana

### Requirement: Run results in the kit directory
After the run pod ends, `start` SHALL read the full log with `kubectl logs <pod>` (not `-f`) and SHALL write, in the kit directory, `last-run.txt` (the run settings, the exit code and the final report).  The kit SHALL NOT write a JSON result file; the run's numbers are in the metrics.  It SHALL write the file for every ended run, failed runs included, and each run SHALL replace the previous file.  The final report is printed last, so they stay in the current log file when the kubelet rotates the log at about 10 MiB; early interval blocks can be lost from `last-run.txt`.

#### Scenario: Text result
- **WHEN** a run finishes
- **THEN** `last-run.txt` in the kit directory holds the run settings and the final report

#### Scenario: No JSON result
- **WHEN** a run finishes
- **THEN** the kit directory holds no `last-run.json`

#### Scenario: Results replaced
- **WHEN** a new run finishes
- **THEN** it replaces the previous run's `last-run.txt`

### Requirement: clickhouse-benchmark user guide
`docs/user-guide/clickhouse-benchmark.md` SHALL cover install, `prepare`, `start`, `stop`, every arg in a Flags table, `--source`, `--queries` (one query per line, path relative to the current directory), `--settings` (no commas in values, the denylist), the app-node requirement, and how to read the dashboard.  It SHALL say that the server reads `--source` with the cluster's IAM role (`use_environment_credentials`), so a private source must be readable by that role.  It SHALL say that `hits` must be loaded again with `prepare` after the target `clickhouse` kit stops and starts.  `docs/SUMMARY.md` and the kit list in `docs/user-guide/kits.md` SHALL include `clickhouse-benchmark`.  A kit-flags-documented test SHALL check that every flag in the kit's `kit.yaml` appears in the page's Flags table.

#### Scenario: User guide content
- **WHEN** a user reads `docs/user-guide/clickhouse-benchmark.md`
- **THEN** it covers install, `prepare`, `start`, `stop`, every arg, `--source`, `--queries`, the app node requirement, and how to read the dashboard

#### Scenario: IAM note for a private source
- **WHEN** a user reads the `--source` section
- **THEN** it says that the server reads `--source` with the cluster's IAM role (`use_environment_credentials`), so a private source must be readable by that role

#### Scenario: Reload after the target restarts
- **WHEN** a user reads the page
- **THEN** it says that `hits` must be loaded again with `prepare` after the target `clickhouse` kit stops and starts

#### Scenario: Kit lists
- **WHEN** a user reads `docs/SUMMARY.md` or the kit list in `docs/user-guide/kits.md`
- **THEN** it includes `clickhouse-benchmark`

#### Scenario: Every flag documented
- **WHEN** the kit-flags-documented test runs
- **THEN** every flag declared in `clickhouse-benchmark/kit.yaml` and in `sysbench/kit.yaml` appears in that kit's Flags table

### Requirement: Real cluster check
The kit SHALL be checked on a real cluster with 1 app node and 3 db nodes before it is called done.

#### Scenario: End-to-end run
- **WHEN** the kit runs on a cluster with 1 app node and 3 db nodes, with `clickhouse` installed, `prepare --storage-policy s3_main` and then `start`
- **THEN** the run finishes, the gauges show in Grafana, and both result files are written
