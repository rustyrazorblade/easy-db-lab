## 1. Kit-ref endpoints and the install check

- [x] 1.1 Write `KitConfigTest` cases: `endpoints: [native, http]` parses to `[NATIVE, HTTP]`; an unknown type in `endpoints` fails at parse.
- [x] 1.2 Add `KitArgSpec.endpoints: List<KitEndpoint.EndpointType> = emptyList()` in `services/KitConfig.kt`, with KDoc.
- [x] 1.3 Write `KitRefTargetValidatorTest`: no capability and no endpoints means no target load; target not installed keeps the "is not installed (expected ...)" message; missing capability keeps its message; one missing endpoint type; two missing types in one message ("the 'native' and 'http' endpoints"); all types present passes.
- [x] 1.4 Extract the pure `KitRefTargetValidator` from `KitInstallCommand.validateKitRefCapability`, add the endpoint check, and call it before `renderAndWrite`.  Keep the existing `KitInstallCommandTest` kit-ref cases green.

## 2. TARGET_NATIVE_* variables

- [x] 2.1 Change the `KitConfigTest` NATIVE empty-map case: a native endpoint gives `TARGET_NATIVE_HOST`, `TARGET_NATIVE_PORT`, `TARGET_NATIVE_USER` (from the `sql` capability user, empty with no capability) and `TARGET_NATIVE_DATABASE`; HTTPS and CQL give no variables.
- [x] 2.2 Add the `NATIVE` branch to `KitEndpoint.toTargetVars`, make the `when` exhaustive (no `else`), and update its KDoc.
- [x] 2.3 Write a test that the built-in `clickhouse/kit.yaml` Native endpoint has `database` `default` and that `formatUrl` still returns `host:port`.
- [x] 2.4 Add `database: "default"` to the `Native` endpoint in `kits/clickhouse/kit.yaml`.

## 3. Unset args as empty strings and boolean arity

- [x] 3.1 Write `KitRunnerCommandFactory` tests: an unset arg with no default reaches the environment as `""`, not `null`; an exported shell variable of the same name does not leak in (through `KitProcessEnvironment`); a boolean arg gives `true` for a bare flag and `false` for `--flag=false` and `--flag false`.
- [x] 3.2 In `KitRunnerCommandFactory`, record `value?.toString() ?: ""` and give `BOOLEAN` args arity `0..1`.
- [x] 3.3 Write `KitInstallCommandFactoryTest` and `KitInstallCommandTest` cases: an install arg with no value and no default is written to `resolved-args.env` as `""`; an unresolved `${VAR}` default is kept; `--s3-cache-on-write false`, `--s3-cache-on-write=false` and a bare `--s3-cache-on-write` record `false`, `false` and `true`.  Change the case that asserts `isNull()` and rename the misleading boolean test.
- [x] 3.4 In `KitInstallCommandFactory`, give `BOOLEAN` args arity `0..1`.  In `KitInstallCommand`, fill `""` for every declared top-level arg with no value after the `putIfAbsent` default loop.
- [x] 3.5 Update the `KitArgSpec` KDoc for boolean args (help now shows `--flag[=<value>]`).

## 4. Shared kit shell library plumbing (Kotlin)

- [x] 4.1 Add `Constants.ToolWrappers.KIT_LIBRARY`, `Constants.ToolWrappers.KIT_LIBRARY_RESOURCE` and `Constants.Kit.LIBRARY_ENV`.
- [x] 4.2 Write `ToolWrapperInstaller` tests: the library is written when absent and equals the resource; an existing library with different content is left as it is; a foreign `bin/` (no marker) holding `edl-kit-lib.sh` fails and writes nothing; `remove` deletes it.
- [x] 4.3 In `ToolWrapperInstaller`, add the `kitLibrary` `PackagedExecutable`, write it only when absent, add it to the foreign-file list and the remove list, and state the write-once rule in the KDoc.
- [x] 4.4 Write `KitProcessEnvironment` tests that `EDL_KIT_LIB` holds the absolute `<workspace>/bin/edl-kit-lib.sh`, and extend the install shell-step test in `KitInstallCommandTest` to assert it.
- [x] 4.5 Set `EDL_KIT_LIB` in `KitProcessEnvironment.applyTo`.
- [x] 4.6 Write a test that `clean` removes the library.

## 5. Shared kit shell library (shell)

- [x] 5.1 Write `src/test/shell/kit-lib.test.sh` with stub `kubectl`, `curl` and `date` that report pod phases: no app node and app node; overrides JSON valid with and without a ConfigMap, bad names rejected; `edl_resource_name` and `edl_labels`; ConfigMap missing, empty and over 1,000,000 bytes, and the labelled one-call create; `edl_active_pods` field selector; pod Succeeded on the first poll; fast fail per pull reason; the timeout (test override of `EDL_POD_START_TIMEOUT_SECONDS`); NotFound during the wait, the follow and the exit wait; a failed `kubectl get` treated as a retry; resume after a cut stream while Running; a non-zero `logs -f` under `set -euo pipefail`; exit code 0 and non-zero; gauge body, non-numeric value as 0, failed POST returns 0; sourcing leaves `set -o` unchanged; a missing `EDL_KIT_LIB` gives the sourcing message.
- [x] 5.2 Write `src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh` (bash 3.2 compatible, no empty `"${arr[@]}"` under `set -u`, no `jq`) until the test passes.
- [x] 5.3 Add the Gradle task `testKitShellLib` to `testScripts`, and make it, and every kit script test task, also run under `/bin/bash` when it is 3.x.

## 6. clickhouse-benchmark kit resources

- [x] 6.1 Capture a report fixture and an error-run fixture from a real `clickhouse-benchmark` run (stderr included, with `--delay 10`), and confirm the flag spellings and binary name in the image (R2).
- [x] 6.2 Write the report parser test (`src/test/shell/clickhouse-benchmark-report.test.sh`, Gradle `testClickhouseBenchmarkReport`): QPS, rows/s, MiB/s, query count, error count, the five percentiles in ms (p999 from `99.900%`), a missing or non-numeric field gives 0.
- [x] 6.3 Write `lib/report.sh` until the parser test passes.
- [x] 6.4 Write `lib/args.sh` tests (inside the start test or their own file): settings mapping, entry without `=`, key pattern, every denylist key; workload or queries choice; unknown workload lists valid ones; `--source` with `'` or `\` rejected; storage policy pattern.
- [x] 6.5 Write `lib/args.sh` and `lib/clickhouse.sh` (`ch_http_query`).
- [x] 6.6 Add `workloads/clickbench/queries.sql` (43 queries, one per line) and `workloads/clickbench/create-hits.sql`, verbatim from ClickBench.
- [ ] 6.7 Write `pod/run-benchmark.sh` and `pod/prepare-load.sh` (POSIX sh) with stub-binary tests: queries on stdin and exit code passthrough; drop with `--recreate`, `ON CLUSTER clickhouse`, the storage policy in the DDL, negative `distributed_ddl_task_timeout`, `SYSTEM SYNC REPLICA ON CLUSTER clickhouse hits`, client timeouts and `max_execution_time=0`, replica mismatch fails with counts, a failed `INSERT` prints the partial-load message and never drops.
- [ ] 6.8 Write `src/test/shell/clickhouse-benchmark-start.test.sh` (Gradle `testClickhouseBenchmarkStart`): missing TARGET_NATIVE vars; arg mapping; `--randomize=false`; exclusivity; missing and empty `--queries`; no app node runs no `kubectl run`; `hits` missing; `--queries` skips the hits check; `nodeSelector` in the overrides; image from `VERSION`; names and labels; non-zero exit prints the failure and the code and exits non-zero; exit 0; pod deleted mid-run; `last-run.txt` written and replaced, failed runs included, and no `last-run.json`; own ConfigMap deleted.
- [x] 6.9 Write `src/test/shell/clickhouse-benchmark-prepare.test.sh` (Gradle `testClickhouseBenchmarkPrepare`): active run pod refuses and names it; `hits` exists without `--recreate` names the table and policy; `--recreate` order; no app node; bad source and policy fail before a pod; non-zero pod exit fails.
- [x] 6.10 Write `src/test/shell/clickhouse-benchmark-stop.test.sh` (Gradle `testClickhouseBenchmarkStop`): deletes `pods,configmaps -l easydblab/kit=${KIT_NAME}` only.
- [x] 6.11 Write `kit.yaml` and `bin/prepare.sh.template`, `bin/start.sh.template`, `bin/stop.sh.template` until the shell tests pass.
- [x] 6.12 Write `ClickHouseBenchmarkKitTest` (via `BuiltinKitFixture`): the args, commands, defaults, runtime probe and `endpoints: [native, http]`; only `prepare`, `start` and `stop` become subcommands.

## 7. sysbench adopts the library

- [ ] 7.1 Extend `src/test/shell/sysbench-start.test.sh`: `nodeSelector` `type=app`; no app node runs no `kubectl run`; a pod Failed on the first poll reports the exit code; pull error fails fast; non-zero exit prints the failure and the code, not `Run summary written to ...`, and exits non-zero; exit 0 as today.
- [ ] 7.2 Add a sysbench prepare test: createdb and prepare pods pinned, Succeeded on the first poll goes on at once, non-zero pod exit fails, no app node fails.
- [ ] 7.3 Extend `src/test/shell/sysbench-stop.test.sh`: cleanup pod pinned; no app node fails with the app-node message and exits non-zero.
- [ ] 7.4 Change `kits/sysbench/bin/{prepare,start,stop}.sh.template` to source the library and use `edl_require_app_node`, `edl_pod_overrides`, `edl_wait_for_pod_running`, `edl_follow_pod_logs`, `edl_wait_for_pod_exit` and `edl_push_gauges`; remove the local `push_gauges` and the 60-second Ready wait.

## 8. Kit flags documented test

- [ ] 8.1 Turn `SysbenchFlagsDocumentedTest` into a parameterised kit-flags-documented test for `sysbench` and `clickhouse-benchmark`, and add `docs/user-guide/clickhouse-benchmark.md` to the test inputs in `build.gradle.kts`.

## 9. Dashboard

- [ ] 9.1 Use the `dashboard-editor` agent to build `kits/clickhouse-benchmark/dashboards/clickhouse-benchmark.json`: panels for the ten gauges, an instance filter, the `metrics_datasource` picker, and a link to the `clickhouse` dashboard that carries the pickers and the selected cluster.
- [ ] 9.2 Run `DashboardDatasourceVariablesTest` and confirm the new dashboard passes.

## 10. Docs and specs

- [ ] 10.1 Write `docs/user-guide/clickhouse-benchmark.md`: install, `prepare`, `start`, `stop`, a Flags table of every arg, `--source` and the IAM note (`use_environment_credentials`), `--queries` (one query per line, relative to the current directory), `--settings` (no commas in values, the denylist), the app-node requirement, results and log rotation, the reload after the target restarts (issue 985), and how to read the dashboard (first `start` installs it).
- [ ] 10.2 Add the page to `docs/SUMMARY.md` near Sysbench and add `clickhouse-benchmark` to the kit list in `docs/user-guide/kits.md`.
- [ ] 10.3 Add the app-node note to `docs/user-guide/sysbench.md`.
- [ ] 10.4 Update `docs/development/kits.md`: the `endpoints` field, the `native` TARGET_* row, the `lib/` and `pod/` layout note, and a "Shared shell library" section (the sourcing line, each function, bash 3.2 rules including the empty-array rule, written once and never rewritten, and the dev-loop note: use a fresh workspace or delete `<workspace>/bin/edl-kit-lib.sh` after each library edit).
- [ ] 10.5 Update the root `CLAUDE.md`: the bin/ paragraph lists `edl-kit-lib.sh` (written once, removed by `clean`, `EDL_KIT_LIB`), and the Kit Development section points kit authors at the library.
- [ ] 10.6 Check the delta specs in `openspec/changes/issue-1022/specs/` against the built behaviour (messages, names, constants) and correct any that differ.

## 11. Build checks

- [ ] 11.1 Run `./gradlew ktlintFormat`, `./gradlew detekt` (JDK 21), `./gradlew test`, `./gradlew testScripts` and `./gradlew integrationTest` in a subagent, and fix every failure.

## 12. Real cluster check

- [ ] 12.1 Provision a cluster with 1 app node and 3 db nodes, install `clickhouse` and `clickhouse-benchmark --target clickhouse`.
- [ ] 12.2 Run `prepare --storage-policy s3_main`; confirm `system.tables` shows `s3_main` and every replica has the same count; confirm the parquet casts work (R5).
- [ ] 12.3 Run `start` once to install the dashboard, then run it again and check every panel shows non-zero data, read back from Grafana; confirm `last-run.txt`.
- [ ] 12.4 Run `prepare --recreate --source <private s3 url in a bucket the role can read>` and confirm the role credentials work (R6); if not, add the `config.d` `<s3>` block with `use_environment_credentials` to the `clickhouse` kit.
- [ ] 12.5 Run a `start` that fails (bad query without `--continue-on-errors`) and confirm the exit code report.
- [ ] 12.6 Install and run `sysbench --target clickhouse`, and confirm its run, prepare and cleanup pods are on the app node.
