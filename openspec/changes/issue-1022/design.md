## Context

The only benchmark kit, `sysbench` (`kits/sysbench/`), drives OLTP workloads over the MySQL and PostgreSQL protocols.  Nothing in easy-db-lab runs analytical queries over the ClickHouse native protocol.  The `clickhouse` kit (`kits/clickhouse/`) already declares a `Native` endpoint (`type: native`, port 30900, `node-type: db`) and an `HTTP` endpoint (port 30123), and its cluster is named `clickhouse` in `clickhouseinstallation.yaml.template`.

Current state that this change meets:

- `KitEndpoint.toTargetVars` (`services/KitConfig.kt`) sends `NATIVE` to `else -> emptyMap()`, so a bench kit gets no variables for a native endpoint.
- `KitArgSpec` has `capability` but no endpoint field.  `KitInstallCommand.validateKitRefCapability` returns early unless `capability` is set.  The `kit-cross-targeting` spec calls `capability` "advisory", but the code enforces it.
- The runner factory (`KitRunnerCommandFactory`) passes an unset optional arg to the script as the string `null`.  The install factory skips it.  `KitProcessEnvironment.applyTo` copies the parent environment, so an exported shell variable of the same name reaches the script when nothing overwrites it.
- A `boolean` kit arg maps to a picocli `Boolean` with no arity, so `--flag false` fails.  This breaks `clickhouse --s3-cache-on-write false`, which `docs/user-guide/clickhouse.md` documents.
- The `sysbench` pods have no node selector, `start` waits `condition=ready` for 60 seconds, and nothing checks the run pod's exit code (issue 874).
- `ToolWrapperInstaller` writes the workspace tool wrappers and `edl-socks-tunnel` into `<workspace>/bin/` before every kit process.  `KitProcessEnvironment` puts `<workspace>/bin` first on `PATH` for every kit process (phase scripts, shell steps, hooks).
- The cluster IAM role reads S3 only in `arn:aws:s3:::easy-db-lab-*` (`providers/aws/AWSPolicy.kt`), so a signed request to a public bucket is denied.

Constraints: the tool installs from Homebrew with no source checkout, so every file the kit needs is a classpath resource.  The kit scripts run on the operator's machine, which can be macOS with bash 3.2.  The owner's decisions at the design stop are in `.spec-flow/owner-decisions.md` and override the architect's recommendations where they differ.

## Goals / Non-Goals

**Goals:**
- A `clickhouse-benchmark` app kit with ClickBench built in: `prepare` loads `hits` on the server from S3, `start` runs the benchmark, pushes gauges and writes results, `stop` cleans up.
- Kit-ref endpoint checks at install and `TARGET_NATIVE_*` variables.
- Every pod of `clickhouse-benchmark` and `sysbench` runs on an app node, and both `start` scripts fail when the run fails.
- One shared kit shell library that both kits use, so the pod placement, waits and gauge push are written once.
- Fix the unset-arg and boolean-arg bugs for every kit.

**Non-Goals:**
- `report upload` of the results.
- The `clickhouse-benchmark` options `--confidence`, `--cumulative`, `--reconnect` and `--query_id`.
- A configurable report interval (fixed at 10 seconds).
- A new kit capability type.
- Datasets or query sets other than ClickBench.
- Targets other than the `clickhouse` kit (any kit with `native` and `http` endpoints passes install, but only `clickhouse` is tested).
- Multiple `--host` values or one connection per replica.
- TPC workloads (issue 662).
- Moving presto, trino, kafka or other kits onto the shared library (issue 874 stays open for them).
- Migrating TARGET_* onto `DEP_*` (issue 953).

## Decisions

### Approach

New kit `kits/clickhouse-benchmark/`, `type: app`, on the sysbench pattern.  Files:

- `kit.yaml`.
- `bin/prepare.sh.template`, `bin/start.sh.template`, `bin/stop.sh.template`.  No `__VAR__` placeholders; the scripts read the environment only.
- `lib/args.sh`: pure functions for the `--settings` mapping, key pattern and denylist, the workload or queries choice, the `--source` and `--storage-policy` checks, and the `clickhouse-benchmark` argv.
- `lib/report.sh`: `parse_report_block` (awk, seconds to milliseconds) and the stream handler that calls `edl_push_gauges`.
- `lib/clickhouse.sh`: `ch_http_query` over `TARGET_HTTP_URL` through the `curl` workspace wrapper, used for the `hits` checks.  The per-kit `lib/common.sh` of the first design is gone; its functions moved to the shared library.
- `pod/run-benchmark.sh`: POSIX sh in the run pod.  It runs the tool with the queries file on stdin (the tool has no `--queries-file`) and exits with the tool's code.  The tool has no `--json` option in current images (checked in 24.8, 25.8 and 26.9), so there is no JSON result; the run's numbers are in the metrics (Seam 1 redirect, owner decision).
- `pod/prepare-load.sh`: POSIX sh in the prepare pod.  It runs the optional drop, the `CREATE ... ON CLUSTER clickhouse`, the `INSERT ... SELECT FROM s3()`, `SYSTEM SYNC REPLICA ON CLUSTER clickhouse hits` and the per-replica count.
- `workloads/clickbench/queries.sql` (43 queries, one per line) and `workloads/clickbench/create-hits.sql` (the ClickBench columns, ReplicatedMergeTree, ORDER BY), verbatim from ClickBench.  `prepare-load.sh` adds `ON CLUSTER clickhouse` and `SETTINGS storage_policy = '<p>'` after it checks `<p>` against `^[A-Za-z0-9_]+$`.
- `dashboards/clickhouse-benchmark.json`.

Kit code lives in `lib/` and `pod/`, not `bin/`, because `KitRunnerCommandFactory.collectScriptPhases` turns every `bin/*.sh` into a phase command.  The built-in kit scan (ClassGraph, `InstallTemplateResolver`) and `renderAndWrite` copy every entry from the jar, render only `*.template` files and mark only `bin/` files executable, so the extra directories work from a Homebrew install.

Each `prepare` and `start` run makes one ConfigMap (the queries file, the pod-side runner, and for `prepare` the DDL) mounted at `/edl`.  One code path serves the packaged ClickBench file and the operator's own file.  Pods are made with `kubectl run ... --overrides=<json> --override-type=strategic` (strategic merge, so `volumeMounts` merge by container name) and argv after `--command --`; no user value goes inside the JSON.

After the log stream ends, `start` waits for the pod to exit and reads the exit code.  It then fetches the full log with `kubectl logs <pod>` (not `-f`) to build `last-run.txt`, because a SOCKS reconnect can cut `logs -f` short.  The live stream drives display and gauges only.

`TARGET_NATIVE_USER` comes from the target's `sql` capability `user`.  With no `sql` capability it is empty and the script omits `--user`.  The `clickhouse` Native endpoint gains `database: "default"`; `formatUrl` ignores the database for `native`.  The cluster name `clickhouse` is literal in the target template and cannot be read reliably from `system.clusters` (which lists operator clusters too), so it is a script constant `CH_CLUSTER=clickhouse` with a comment.  One table, `default.hits`, ReplicatedMergeTree `ON CLUSTER clickhouse`; the target is 1 shard with N replicas, so every replica holds the full table.  Nothing is added to `ClusterState`.

### Kotlin interfaces

- `KitArgSpec.endpoints: List<KitEndpoint.EndpointType> = emptyList()`.  Typed, so a typo fails at parse.  Messages print `type.name.lowercase()`, which equals every `@SerialName`.
- `validateKitRefCapability` becomes a pure `KitRefTargetValidator`: return early when `capability` is blank and `endpoints` is empty; load the target once and keep the "is not installed (expected ...)" message; keep the capability message; report every missing endpoint type in one message ("the 'native' endpoint" or "the 'native' and 'http' endpoints").  It runs before `renderAndWrite`.  Existing `KitInstallCommandTest` cases stay.
- `toTargetVars` gains a `NATIVE` branch and becomes an exhaustive `when` (HTTPS and CQL return empty maps).  The KDoc that lists NATIVE as having no TARGET_* convention changes.  `KitConfigTest` (the NATIVE empty-map case) changes.  Side effect: neo4j (bolt) and memcached native endpoints now yield `TARGET_NATIVE_*`; this is accepted by scope.
- Runner factory: record `value?.toString() ?: ""` for every declared arg.  Install side: after the `putIfAbsent` loop that applies defaults (`KitInstallCommand`), put `""` for every declared top-level arg that still has no value, so it is written to `resolved-args.env`.  Filling in the setter would drop unresolved `${VAR}` defaults, which are never handed to picocli.  Declared values overwrite inherited ones in `KitProcessEnvironment`.  `KitInstallCommandFactoryTest` that asserts `isNull()` changes.
- Both factories give `BOOLEAN` args arity `0..1`.  The help shows `--flag[=<value>]`; the KDoc on `KitArgSpec` and the misleading test name in `KitInstallCommandFactoryTest` change.
- `ToolWrapperInstaller` gains `kitLibrary: PackagedExecutable = PackagedExecutable.fromResource(Constants.ToolWrappers.KIT_LIBRARY_RESOURCE)`.  `install` writes it only when the file is absent (checked before `writeTo`, which rewrites on a content difference), adds it to the foreign-file list, and `remove` deletes it, so `clean` removes it.  The installer KDoc states the write-once rule.
- `KitProcessEnvironment.applyTo` sets `EDL_KIT_LIB` to the absolute `<workspace>/bin/edl-kit-lib.sh`.
- Constants: `Constants.ToolWrappers.KIT_LIBRARY = "edl-kit-lib.sh"`, `Constants.ToolWrappers.KIT_LIBRARY_RESOURCE = "/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"`, `Constants.Kit.LIBRARY_ENV = "EDL_KIT_LIB"`.

### Shared kit shell library

Resource `src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh`, next to `tool-wrapper.sh` and `edl-socks-tunnel.sh`.  Not under `kits/`: the built-in scan groups resources by the first path segment under `kits/`, so `kits/_lib/` would become an installable kit.  It is written once into `<workspace>/bin/`, never rewritten, and found through `EDL_KIT_LIB`.  The env var states the contract, lets tests point at the resource, and does not depend on the kit directory layout.  Every launch path goes through `KitProcessEnvironment.applyTo`: phase scripts (`KitRunnerCommand`), shell steps (`WorkloadStepExecutor`) and hooks (`KitHookExecutor`).  Built-in and user kit sources get it with no installer change.  It is never a phase and cannot collide with a kit's own `lib/` (`edl_` functions, `EDL_` constants).  Sourcing changes no `set` options, traps or working directory.  It is bash 3.2 compatible.

Functions (read `KIT_NAME`, `KUBECONFIG`, `CONTROL_HOST_PRIVATE` from the environment): `edl_fail`, `edl_labels`, `edl_resource_name`, `edl_require_app_node`, `edl_pod_overrides`, `edl_create_configmap`, `edl_active_pods`, `edl_wait_for_pod_running`, `edl_follow_pod_logs`, `edl_wait_for_pod_exit`, `edl_push_gauges`.  Their contracts are in the `kit-shell-library` spec.  Report parsers, the `--settings` mapping and denylist, the workload, queries and source checks, and the SQL stay in each kit's own `lib/`.

Adopters: `sysbench` (prepare, start, stop) and `clickhouse-benchmark` only.

### Script contracts

- `TARGET_NATIVE_HOST` and `TARGET_NATIVE_PORT` are required (exit 1 naming the variable).  `TARGET_HTTP_URL` is checked defensively; install already requires the `http` endpoint.
- `start` maps `-c`, `--timelimit`, `-i`, `--delay 10`, `--randomize` and `--continue_on_errors` only when true, each `k=v` to `--k=v`, and `--host --port --user --database`.  The queries file is at `/edl/queries.sql`.
- Names `${KIT_NAME}-run-$(date +%s)` and `${KIT_NAME}-prepare-$(date +%s)`, labels `easydblab/kit` and `easydblab/role=benchmark-run|prepare`.
- `stop` deletes `pods,configmaps -l easydblab/kit=${KIT_NAME}`; `start` deletes its own ConfigMap after the run.  Metrics ConfigMaps use the `easydblab.com/kit` label, so the delete does not reach them.
- Every DDL runs with a negative `--distributed_ddl_task_timeout` (no limit).  The recreate drop is `DROP TABLE IF EXISTS hits ON CLUSTER clickhouse SYNC`.
- `last-run.txt` is written for every ended run, failures included, with the exit code recorded.  No JSON result file is written.
- The kubelet rotates pod logs at about 10 MiB.  The final report prints last, so they are always in the current file; early interval blocks can be lost from `last-run.txt`.  The user guide says so.

### Tests

- Docker-free shell tests, as new `Exec` tasks in `testScripts` (`build.gradle.kts`): `testKitShellLib` (`src/test/shell/kit-lib.test.sh`), `testClickhouseBenchmarkReport` (a fixture captured from a real run plus an error-run fixture), `testClickhouseBenchmarkStart`, `testClickhouseBenchmarkPrepare`, `testClickhouseBenchmarkStop`, and the extended `testSysbenchStartScript` and `testSysbenchStopScript`, plus a sysbench prepare test.  Stubs for `kubectl`, `curl`, `date`, `clickhouse-client` and `clickhouse-benchmark` on `PATH` report pod phases, so no wait loop hangs.  Kit script tests set `EDL_KIT_LIB` to the resource path.  Every library and kit script test also runs under `/bin/bash` when it is 3.x (precedent: `edl-cassandra-agents.test.sh` under a real `/bin/sh`).
- Kotlin: `KitConfigTest` (NATIVE vars, `endpoints` parsing), `KitRefTargetValidatorTest`, both factory tests (empty string, arity), `ToolWrapperInstaller` and `KitProcessEnvironment` tests, the install shell-step test asserting `EDL_KIT_LIB`, a `ClickHouseBenchmarkKitTest` via `BuiltinKitFixture`, and the generalised kit-flags-documented test.  `DashboardDatasourceVariablesTest` picks up the new dashboard through `DashboardFiles.all()`.

## Alternatives Considered

Each entry lists the options presented at that decision point, the choice, and why each other option was rejected.

### D1: the unset-arg `null` bug
- **Fold in a fix in the factories (chosen, changed).**  The architect recommended a one-line setter fix that records nothing for a null value.  Critic finding 6 showed that recording nothing lets an exported operator shell variable leak in, because `KitProcessEnvironment.applyTo` copies the parent environment.  The owner chose to record an empty string in both factories and on install.  Critic-2 minor 4 moved the install-side fill after the `putIfAbsent` default loop, so unresolved `${VAR}` defaults survive.
- **Scripts treat the literal `null` as unset.**  Rejected: a workaround, not a fix, and a file named `null` would be misread.
- **Wait for issue 969.**  Rejected: a hard dependency for a one-line fix.

### D2: detecting both `--workload` and `--queries`
- **`start --workload` has no `kit.yaml` default; the script uses clickbench when neither is given; both given fails before any pod (chosen).**  Picocli calls the setter with the default, so with a yaml default the script always sees `WORKLOAD=clickbench` and cannot tell whether the user gave it.  Needs D1.  `prepare --workload` keeps its default.
- **A generic `conflicts-with` field on `KitArgSpec`, mapped to a picocli exclusive ArgGroup.**  Rejected as YAGNI.
- **`--queries` wins silently.**  Rejected: breaks the acceptance criterion.

### D3: where the short control queries run
- **On the operator's machine over the target HTTP endpoint (`TARGET_HTTP_URL`, 30123) through the `curl` wrapper (chosen, extended).**  The checks are `hits` exists before `start`, and exists plus policy before `prepare`, both before any pod.  Critic finding 13 noted that the install check did not enforce an HTTP endpoint; the owner made the kit-ref field a list, `endpoints: [native, http]`, with every type required at install.
- **A short native check pod (`clickhouse-client`).**  Rejected: the acceptance criteria say the checks run before a pod is created, and a check pod is a pod.
- **`kubectl exec` into the server pod (`clickhouse.altinity.com/chi=clickhouse`, as the clickhouse backup step does).**  Rejected: ties the bench kit to the target's internals.

### D4: reading the default `--source`
- **Default URL with `s3(url, NOSIGN, 'Parquet')`; an override with the role's credentials (chosen).**  The cluster IAM role reads only `easy-db-lab-*` buckets, so a signed request to the public bucket is denied.  Critic finding 4 required the exact S3 endpoint to be pinned and verified: the owner pinned `https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet` (verified: S3 returns 200 for `hits_0` and `hits_99`; anonymous ListObjects works).  `datasets.clickhouse.com` is a CDN and must not be used with `s3()`.  The docs say a private source must be in a bucket the role can read.
- **Always NOSIGN.**  Rejected: a private source would never work.
- **Widen the IAM role to `s3:GetObject` on `*`.**  Rejected: security-relevant widening for a test convenience.
- The partitioned `hits_{0..99}.parquet` form was chosen over a single file.

### Failed load handling (critic finding 1)
- **Stop and report; never drop `hits` (owner's choice).**  `prepare` shows the server error, says `hits may be partly loaded; run prepare --recreate to reload it`, and exits non-zero.  `start` adds no empty-table check.
- **`prepare` drops `hits` after a failed load.**  Offered at the design stop.  **Owner override:** rejected.  The owner's decision is that `prepare` never drops `hits`; the failure message tells the user to run `prepare --recreate`.
- **`start` refuses an empty table (`count() = 0`).**  Offered at the design stop.  Rejected by the owner: no empty-table check.

### Pending wait (critic finding 3)
- **Fast fail on `ErrImagePull`, `ImagePullBackOff` or `InvalidImageName` with the reason and the describe events; otherwise a 10-minute limit as a script constant; both kits (owner's choice).**  Critic-2 M1 added that the wait returns once the phase is no longer Pending, so a pod that ends in a second does not wait for the limit.
- **Keep the fixed 60-second `condition=ready` wait.**  Rejected: too short to pull `clickhouse-server` on a fresh node, and a run that fails in a second never becomes Ready, so the script ends through `set -e` without the failure message (R1).
- **No limit (wait for the phase to leave Pending, as the first design had it).**  Rejected: a bad image tag such as `--version 25.44` hangs `start` and `prepare` forever with no reason shown.

### R4: boolean args with a `true` default
- **Arity `0..1` for every `BOOLEAN` arg in both factories, for every kit (owner's choice).**  Fixes `--randomize` and `clickhouse --s3-cache-on-write false`.  Picocli 4.7.7 takes the optional value only for `true` and `false`, and no kit has positional parameters, so it is safe.
- **Make `--randomize` a string `true|false`, as sysbench `--skip-trx` is.**  Rejected: fixes one arg and leaves the same bug in `--s3-cache-on-write` and every future boolean.

### Prepare during a run (critic finding 9)
- **`prepare` refuses while a `benchmark-run` pod for this instance is Pending or Running, naming the pod (owner's choice).**  A `prepare --recreate` during a `start` would drop `hits` under the benchmark.
- **Leave it as today (only `start` is collision-guarded).**  Rejected by the owner.  Two racing `start`s both running stays existing behaviour.

### Debt 1: sysbench prepare, createdb and cleanup pods unpinned
- **Pin all sysbench pods (run, prepare, createdb, cleanup) to `type=app` with the app-node check (owner's choice).**
- **Pin only the run pod and file a separate issue.**  The architect listed this under "separate issues (owner decides)".  **Owner override:** folded in.  The design files record no further reason; the issue's problem statement (a sysbench pod on a database node competes with the database it measures) applies to every sysbench pod.

### Debt 2: no shared kit shell library
- **Fold a shared library into this issue (owner's choice, architect addendum).**  The pod placement, the waits, the log follow and the gauge push are needed by both kits.
- **Per-kit copies: sysbench gets its own `require_app_node`, `pod_overrides_json` and `wait_for_pod_exit` (about 30 duplicated lines), and a separate issue for a shared library (overlaps 874).**  The architect recommended a separate issue.  **Owner override:** folded in.

### O1: where the library lives and when it is written
- **Workspace-level `<workspace>/bin/edl-kit-lib.sh`, written once and never rewritten (owner's choice).**  No refresh logic and no skew or additive-only rule: "there is no upgrade mid-run, stop trying to solve a problem that doesn't exist".  Critic-2 M3 turned this into the write-only-when-absent rule, and critic-2 minor 7 into the dev-loop note (use a fresh workspace, or delete `<workspace>/bin/edl-kit-lib.sh`, after each library edit).
- **Workspace-level library refreshed per process.**  The architect recommended it: an upgrade refreshes it with no reinstall, with function contracts kept additive.  **Owner override:** rejected.  The owner's reason: there is no upgrade mid-run, so refresh logic and a skew rule solve a problem that does not exist.
- **Per-kit copy at install (`<kit>/.edl/edl-kit-lib.sh`, written by `renderAndWrite`).**  Rejected: frozen with the kit's scripts, fixes need a reinstall, and it needs a reserved directory name and a collision check.

### O2: sysbench `stop` with no app node
- **`stop` fails with the app-node message; the kit stays marked running (owner's choice).**
- **`stop` still deletes the pods, reports "the cleanup needs an app node; the sbtest tables were left", exits 0, and the kit leaves `runningKits`.**  The architect recommended it (the existing skip path in `stop.sh.template`).  **Owner override:** rejected.  The owner chose that `stop` fails and the kit stays marked running; the design files record no further reason.

### O3: the default `--source`
- **No `kit.yaml` default; empty means the pinned URL with NOSIGN; the arg help names the URL (chosen, as recommended).**
- **The URL as the yaml default, and the script compares `SOURCE` to it to choose NOSIGN.**  Rejected: an explicit identical URL also gets NOSIGN.  Its gain was showing the URL in `kit info` and `--help`; the arg description names the URL instead.

### T1: how pods get placement and mounts
- **`kubectl run --overrides=<json> --override-type=strategic` (chosen).**  Strategic merge makes `volumeMounts` merge by container name; `--override-type` defaults to merge, so strategic must be explicit.  The JSON is literal text from `edl_pod_overrides`, with no `jq` (critic finding 11).
- **A manifest heredoc.**  Rejected (the architect states only the choice; reason inferred): it builds YAML from strings with user values inside, where the chosen form keeps every user value in argv after `--command --`.
- **A `sed` template.**  Rejected (reason inferred): the same problem, plus escaping.

### T2: the app-node check
- **`kubectl get nodes -l type=app -o name` (chosen).**  Reads the live cluster.  Install already refuses a `type: app` kit with no app host; this is the second line of defence.
- **`APP_NODE_COUNT` from the template variables (`TemplateVariables.kt`).**  Rejected (the architect states only the choice; reason inferred): it is cluster state, not the live node labels the scheduler uses.
- **A Kotlin check in `KitRunnerCommand`.**  Rejected (reason inferred): it puts kit-specific logic in the generic runner, and the check must run before the first pod of each script.

### T3: running the long INSERT
- **The INSERT runs in a pod with `clickhouse-client`, large `receive_timeout` and `send_timeout`, and `max_execution_time=0`; the operator side follows and polls the pod with no client timeout (chosen).**  The architect named no other option.  A client-side run from the operator's machine would be bound by the default client timeout and by the tunnel, which the acceptance criterion rules out.

## Risks / Trade-offs

- [R1] sysbench `start` waits `condition=ready` for 60 seconds; a run that fails in a second never becomes Ready and exits through `set -e` without the failure message. → `edl_wait_for_pod_running` returns when the phase leaves Pending; then follow, wait for exit, check the code.  Same in the new kit.
- [R2] The tool's flags are not verified against the image: `--queries-file` (or stdin), `--randomize`, `--continue_on_errors`, `--delay`, `--json`, and the binary name (`clickhouse-benchmark` or `clickhouse benchmark`).  Whether the tool accepts dash spellings or Boost prefix abbreviations of options (for example `conc=8`) is also not verified. → Verify in the real-cluster check before the scripts are final; use the stdin form if `--queries-file` is absent.  The settings key pattern blocks dash spellings.
- [R3] Report parsing: kubectl merges stderr; a block starts at `Queries executed:`; percentiles end at `99.990%`; p999 is `99.900%`; seconds to ms; the error-count line is uncertain. → Parser test against a fixture captured from a real run, and an error-run fixture; a missing field becomes 0.
- [R4] Boolean args with default `true` cannot be turned off. → Arity `0..1` in both factories, one test per factory (`--flag false` gives false, bare gives true).
- [R5] Parquet `EventTime` and `EventDate` are integers; `INSERT ... SELECT *` relies on insert-time casts. → Verify on the real cluster; if casts fail, select the columns with `toDateTime` and `toDate`.
- [R6] Only the disks set `use_environment_credentials`; an `s3()` read of a private `--source` may not use the role. → Verify with a private source on the real cluster; if it fails, fold in a `config.d` `<s3>` block with `use_environment_credentials` in the `clickhouse` kit.
- [R7] Blast radius: the Kotlin changes are additive; sysbench moves to app nodes (a cluster with no app node cannot install it either); the new `stop` deletes `pods,configmaps -l easydblab/kit=${KIT_NAME}`; metrics ConfigMaps use `easydblab.com/kit` and are not hit.  `toTargetVars` now yields `TARGET_NATIVE_*` for neo4j and memcached too. → Accepted by scope.
- [R8] `hits` is gone when the target stops and starts (issue 985). → Docs note.
- [Critic 1] A failed or partial `prepare` leaves `hits` behind. → Owner decision: stop and report, never drop, no empty-table check.
- [Critic 2] `ON CLUSTER` statements time out at the 180-second `distributed_ddl_task_timeout`; the `SYSTEM SYNC REPLICA` grammar is `SYSTEM SYNC REPLICA ON CLUSTER clickhouse hits`. → Negative `distributed_ddl_task_timeout` (no limit) on every DDL (critic-2 minor 5 rejected the earlier 1800, which could print the partial-load message after a successful load); the prepare test asserts the grammar.
- [Critic 3] A bad image tag hangs `start` forever. → Pending wait decision.
- [Critic 4] The default `--source` was unverified. → Pinned and verified S3 URL.
- [Critic 5] ConfigMaps had no label and a 1 MiB limit. → `edl_create_configmap` creates them labelled in one call (critic-2 minor 2: dry-run, `label --local`, `create -f -`, never `apply`), with timestamped names and a 1,000,000-byte check.
- [Critic 6] An unset arg is read from the operator's shell. → Empty-string D1 fix.
- [Critic 7] A cut log stream stops the gauges and, under `pipefail`, the exit-code check. → `edl_follow_pod_logs` resumes on phase Running with `--since-time` and guards the non-zero `logs -f`; the exit code comes from `edl_wait_for_pod_exit`.
- [Critic 8] The dashboard does not exist during the first run (dashboards install after `start` succeeds). → Real-cluster check runs once to install the dashboard, then checks panels on the second run.
- [Critic 9] `prepare --recreate` during a `start`. → `prepare` refuses during an active run.
- [Critic 10] Inputs reaching flags or SQL. → Settings key pattern and denylist; `--source` with `'` or `\` rejected (validate, not escape); `--storage-policy` pattern; `DROP TABLE IF EXISTS`.
- [Critic 11] sysbench gaining a `jq` dependency. → Literal JSON in `edl_pod_overrides`; no `jq` at run time in either kit.
- [Critic 12] Multi-line queries break; log rotation can drop early output. → Docs say one query per line; the final report prints last.
- [Critic 13] `TARGET_HTTP_URL` not enforced at install. → `endpoints: [native, http]`.
- [Critic-2 M1] A pod that ends before it is seen Running would wait 600 seconds. → Return when the phase leaves Pending; stub case reports Succeeded on the first poll.
- [Critic-2 M2] Unbounded loops for a gone pod. → Resume only while Running; NotFound fails with `pod <name> was deleted`; a failed get is a retry, never phase ""; stubs report phases.
- [Critic-2 M3] The addendum described refresh. → Write-once rule in the installer, KDoc, CLAUDE.md and tests ("an existing library with different content is left as it is").
- [Critic-2 M4] bash 3.2 never tested, and empty arrays under `set -u` fail before bash 4.4. → Tests also run under `/bin/bash` when it is 3.x; the empty-array rule goes into `docs/development/kits.md`.
- [Critic-2 minor 1] Commas in a field selector are AND. → `status.phase!=Succeeded,status.phase!=Failed`.
- [Critic-2 minor 3] Uneven denylist. → Keys must match `^[A-Za-z0-9_]+$`; the out-of-scope options are added.
- [Critic-2 minor 6] `--since-time` has one-second precision. → A resumed stream can repeat up to a second of lines; live gauges only; the results come from the full log.
- [Critic-2 minor 8] Arity `0..1` changes help and makes a KDoc and a test name wrong. → Update both.
- Trade-off: the write-once library means a workspace keeps the library it was first given; a fix to the library reaches new workspaces, or old ones after the file is deleted.

## Migration Plan

No migration: clusters are ephemeral.  A workspace made before this change gets the library on its next kit process, because the file is absent.  `sysbench` now needs an app node for `prepare`, `start` and `stop`.

## Open Questions

None.  R2, R5 and R6 are verified in the real-cluster check, and the follow-up for each is stated above.
