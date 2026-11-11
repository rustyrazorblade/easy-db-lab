# Acceptance criteria coverage for issue-1022

Notes:
- The AC "WHEN `--target` names a kit that has no `native` endpoint ... `does not have the 'native' endpoint`" now reports every missing type from the `endpoints` list.  The kit declares `endpoints: [native, http]` (owner decision D3), so the message for a target that lacks only `native` is the one in the AC, and a target that lacks both gets "the 'native' and 'http' endpoints".
- The AC "WHEN `start` runs with the ClickBench workload and `hits` does not exist" is unchanged.  There is no empty-table check (owner decision on critic finding 1).

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC | Install as `clickhouse-benchmark-clickhouse` | clickhouse-benchmark-kit: Install against clickhouse | ✅ Covered |
| AC | Target with no `native` endpoint fails before writing, capability-style message | clickhouse-benchmark-kit: Target has no native endpoint; kit-cross-targeting: Target missing one required endpoint type; kit-cross-targeting: Target missing every required endpoint type | ✅ Covered |
| AC | Target not installed gives the "is not installed" message | clickhouse-benchmark-kit: Target not installed; kit-cross-targeting: Target not installed | ✅ Covered |
| AC | TARGET_NATIVE_HOST, _PORT (30900), _USER, _DATABASE reach the scripts | clickhouse-benchmark-kit: Target variables reach the scripts; kit-cross-targeting: Native endpoint produces TARGET_NATIVE_* vars; clickhouse: Native endpoint database parsed | ✅ Covered |
| AC | `--version 25.4` selects the image; default `latest` | clickhouse-benchmark-kit: Explicit version; clickhouse-benchmark-kit: Default version | ✅ Covered |
| AC | `kit.yaml` gives the args, commands, defaults, runtime probe and endpoint field | clickhouse-benchmark-kit: kit.yaml parsed; kit-cross-targeting: endpoints list parsed from kit.yaml | ✅ Covered |
| AC | `prepare` and `start` pods on `type=app` | clickhouse-benchmark-kit: Pod on an app node | ✅ Covered |
| AC | No app node: `prepare` and `start` fail before a pod with the app-node message | clickhouse-benchmark-kit: No app node; kit-shell-library: Cluster has no app node | ✅ Covered |
| AC | sysbench run pod on `type=app` | sysbench-app-node-placement: Run pod on an app node | ✅ Covered |
| AC | sysbench start with no app node fails before a pod, same message | sysbench-app-node-placement: start with no app node | ✅ Covered |
| AC | clickhouse-benchmark non-zero exit: failure, code, non-zero, no success line | clickhouse-benchmark-kit: Benchmark exits non-zero | ✅ Covered |
| AC | sysbench non-zero exit: failure, code, non-zero, no `Run summary written to ...` | sysbench-app-node-placement: sysbench run exits non-zero | ✅ Covered |
| AC | Either run pod exits 0: `start` exits 0 | clickhouse-benchmark-kit: Benchmark exits 0; sysbench-app-node-placement: sysbench run exits 0 | ✅ Covered |
| AC | Default `prepare`: ReplicatedMergeTree `ON CLUSTER clickhouse`, loaded server side from the public URL | clickhouse-benchmark-kit: Default source | ✅ Covered |
| AC | `--source` override loads from that URL | clickhouse-benchmark-kit: Source override | ✅ Covered |
| AC | Unreadable `--source` fails with the server error, no success | clickhouse-benchmark-kit: Source the role cannot read | ✅ Covered |
| AC | `--storage-policy s3_main` shows in `system.tables` | clickhouse-benchmark-kit: Storage policy | ✅ Covered |
| AC | Undefined storage policy fails with the server error | clickhouse-benchmark-kit: Storage policy the server does not define | ✅ Covered |
| AC | `hits` exists without `--recreate`: fail naming table, policy, `--recreate`; no change | clickhouse-benchmark-kit: hits exists without --recreate | ✅ Covered |
| AC | `--recreate` runs `DROP TABLE hits ON CLUSTER clickhouse SYNC` then loads | clickhouse-benchmark-kit: Recreate; clickhouse-benchmark-kit: Recreate when hits does not exist (uses `DROP TABLE IF EXISTS`, critic finding 10) | ✅ Covered |
| AC | Long load does not time out on the client | clickhouse-benchmark-kit: Long load; clickhouse-benchmark-kit: distributed_ddl_task_timeout no limit | ✅ Covered |
| AC | Every replica returns the same row count | clickhouse-benchmark-kit: Replicas agree; clickhouse-benchmark-kit: Replicas disagree | ✅ Covered |
| AC | `--workload clickbench` (default) runs the 43 queries against TARGET_NATIVE_HOST:PORT | clickhouse-benchmark-kit: Default ClickBench workload | ✅ Covered |
| AC | `--queries <local path>` sends the operator's file; works from Homebrew | clickhouse-benchmark-kit: Own queries file | ✅ Covered |
| AC | `--queries` missing or empty fails before a pod, names the path | clickhouse-benchmark-kit: Queries file missing or empty; kit-shell-library: Missing file; kit-shell-library: Empty file | ✅ Covered |
| AC | Both `--workload` and `--queries` fail before a pod | clickhouse-benchmark-kit: Both workload and queries | ✅ Covered |
| AC | Unknown `--workload` fails and lists valid workloads | clickhouse-benchmark-kit: Unknown workload | ✅ Covered |
| AC | ClickBench with no `hits` fails before a pod, says run `prepare` | clickhouse-benchmark-kit: hits missing for ClickBench | ✅ Covered |
| AC | `--queries` skips the `hits` check | clickhouse-benchmark-kit: Own queries skip the hits check | ✅ Covered |
| AC | Run args reach the tool as `-c`, `--timelimit`, `-i`, `--randomize`, `--continue_on_errors` | clickhouse-benchmark-kit: Run args mapped; clickhouse-benchmark-kit: Randomize turned off | ✅ Covered |
| AC | Both `--duration` and `--iterations` non-zero: first limit wins | clickhouse-benchmark-kit: Duration and iterations both set | ✅ Covered |
| AC | `--settings max_threads=8,use_query_cache=0` becomes `--max_threads=8 --use_query_cache=0` | clickhouse-benchmark-kit: Settings mapped | ✅ Covered |
| AC | `--settings` entry without `=` fails before a pod, names it | clickhouse-benchmark-kit: Settings entry without equals | ✅ Covered |
| AC | Query fails with `--continue-on-errors` false: run stops, error shown, non-zero | clickhouse-benchmark-kit: Query fails and errors stop the run | ✅ Covered |
| AC | Second `start` refused while a run pod is running | clickhouse-benchmark-kit: Run still active | ✅ Covered |
| AC | Previous run pod Succeeded does not block `start` | clickhouse-benchmark-kit: Previous run finished | ✅ Covered |
| AC | Pod names end in `$(date +%s)`, labels kit and role | clickhouse-benchmark-kit: Pod names and labels; kit-shell-library: Resource name ends in a timestamp; kit-shell-library: Labels name kit and role | ✅ Covered |
| AC | `stop` deletes by label selector, leaves other kits alone | clickhouse-benchmark-kit: Stop | ✅ Covered |
| AC | Ten OTLP gauges every 10 seconds, labelled `kit=<instance>` | clickhouse-benchmark-kit: Gauges every 10 seconds; kit-shell-library: Gauge body | ✅ Covered |
| AC | Saved report through the parser in a plain shell test gives the values, latency in ms | clickhouse-benchmark-kit: Parser on a saved report | ✅ Covered |
| AC | Missing or non-numeric field sends 0, run goes on | clickhouse-benchmark-kit: Missing or non-numeric field; kit-shell-library: Non-numeric value | ✅ Covered |
| AC | Dashboard installed after `start`, panels show gauges, filter by instance | clickhouse-benchmark-kit: Dashboard installed after start | ✅ Covered |
| AC | Link to the `clickhouse` dashboard carries pickers and cluster | clickhouse-benchmark-kit: Link to the clickhouse dashboard | ✅ Covered |
| AC | `DashboardDatasourceVariablesTest` passes | clickhouse-benchmark-kit: Datasource variables test | ✅ Covered |
| AC | Panels show non-zero data on a real cluster, read from Grafana | clickhouse-benchmark-kit: Panels show data on a real cluster | ✅ Covered |
| AC | `last-run.txt` holds settings and final report | clickhouse-benchmark-kit: Text result | ✅ Covered |
| AC | `last-run.json` holds the `--json` output | clickhouse-benchmark-kit: No JSON result | ⚠️ Excluded — the tool has no `--json` option in current images (24.8, 25.8, 26.9); at the Seam 1 redirect the owner dropped the JSON file because the run's numbers are in the metrics |
| AC | A new run replaces both files | clickhouse-benchmark-kit: Results replaced | ✅ Covered |
| AC | User guide page covers install, commands, every arg, `--source`, `--queries`, app node, dashboard | clickhouse-benchmark-kit: User guide content; clickhouse-benchmark-kit: Every flag documented | ✅ Covered |
| AC | Page states the IAM role note (`use_environment_credentials`) | clickhouse-benchmark-kit: IAM note for a private source | ✅ Covered |
| AC | sysbench guide says the run pod needs an app node | sysbench-app-node-placement: Reader checks the sysbench guide | ✅ Covered |
| AC | Page says to reload `hits` after the target stops and starts (985) | clickhouse-benchmark-kit: Reload after the target restarts | ✅ Covered |
| AC | Kit lists include `clickhouse-benchmark` | clickhouse-benchmark-kit: Kit lists | ✅ Covered |
| AC | Real cluster: 1 app + 3 db, `prepare --storage-policy s3_main`, `start`: run finishes, gauges, both files | clickhouse-benchmark-kit: End-to-end run | ✅ Covered |
| AC | Real cluster: sysbench run pod on the app node | sysbench-app-node-placement: Run pod on the app node of a real cluster | ✅ Covered |
| Risk | R1: sysbench 60s Ready wait misses fast failures | sysbench-app-node-placement: Run pod fails before it is Ready; kit-shell-library: Pod finishes before it is seen Running | ✅ Covered |
| Risk | R2: tool flags and binary name unverified | None (the resulting flags are pinned by clickhouse-benchmark-kit: Run args mapped) | ⚠️ Excluded — a verification step, not a behaviour: task 6.1 checks the flags and binary name in the image, and the real-cluster check (section 12) confirms them |
| Risk | R3: report parsing details | clickhouse-benchmark-kit: Parser on a saved report; clickhouse-benchmark-kit: Missing or non-numeric field | ✅ Covered |
| Risk | R4: boolean args cannot be turned off | kit-command-args: Boolean arg turned off with a value; kit-command-args: Boolean arg given as a bare flag; kit-install-command: Boolean install arg turned off; kit-install-command: Boolean install arg with an equals value; kit-install-command: Boolean install arg given as a bare flag | ✅ Covered |
| Risk | R5: parquet integer date columns need casts | clickhouse-benchmark-kit: End-to-end run (load must succeed) | ⚠️ Excluded — implementation detail verified on the real cluster (task 12.2); no separate spec behaviour |
| Risk | R6: `s3()` may not use role credentials for a private source | clickhouse-benchmark-kit: Source override; clickhouse-benchmark-kit: IAM note for a private source | ✅ Covered (verified in task 12.4) |
| Risk | R7: blast radius (sysbench needs app nodes, stop selector, native vars for other kits) | sysbench-app-node-placement: start with no app node; clickhouse-benchmark-kit: Stop; kit-cross-targeting: Native endpoint produces TARGET_NATIVE_* vars | ✅ Covered |
| Risk | R8: `hits` lost when the target restarts (985) | clickhouse-benchmark-kit: Reload after the target restarts | ✅ Covered |
| Risk | Critic 1: failed or partial load left behind | clickhouse-benchmark-kit: Load fails part way | ✅ Covered |
| Risk | Critic 2: `ON CLUSTER` timeouts and SYNC REPLICA grammar | clickhouse-benchmark-kit: distributed_ddl_task_timeout no limit; clickhouse-benchmark-kit: Replicas agree | ✅ Covered |
| Risk | Critic 3: bad image tag hangs forever | clickhouse-benchmark-kit: Bad version tag; clickhouse-benchmark-kit: Pod pending for ten minutes; kit-shell-library: Pull error fails fast; kit-shell-library: 10-minute limit; sysbench-app-node-placement: Bad image fails fast | ✅ Covered |
| Risk | Critic 4: default `--source` unverified | clickhouse-benchmark-kit: Default source (pinned, verified S3 URL with NOSIGN) | ✅ Covered |
| Risk | Critic 5: unlabelled ConfigMaps, 1 MiB limit | kit-shell-library: ConfigMap carries its labels; kit-shell-library: Size limit; clickhouse-benchmark-kit: ConfigMap labelled at creation; clickhouse-benchmark-kit: Queries file over the ConfigMap size limit | ✅ Covered |
| Risk | Critic 6: unset arg read from the operator's shell | kit-command-args: Unset arg does not take an exported shell variable; kit-install-command: Exported shell variable does not leak into a kit process | ✅ Covered |
| Risk | Critic 7: cut log stream stops gauges and exit-code check | kit-shell-library: Stream cut while the pod runs; kit-shell-library: Failed log stream under pipefail; clickhouse-benchmark-kit: Gauges after a cut stream | ✅ Covered |
| Risk | Critic 8: dashboard absent during the first run | clickhouse-benchmark-kit: Panels show data on a real cluster (after an earlier `start`) | ✅ Covered |
| Risk | Critic 9: `prepare --recreate` during a run | clickhouse-benchmark-kit: prepare refuses during an active run; clickhouse-benchmark-kit: Finished run does not block prepare | ✅ Covered |
| Risk | Critic 10: inputs reaching flags or SQL | clickhouse-benchmark-kit: Settings key on the denylist; clickhouse-benchmark-kit: Settings key with invalid characters; clickhouse-benchmark-kit: Source with a quote or backslash; clickhouse-benchmark-kit: Storage policy with invalid characters; clickhouse-benchmark-kit: Recreate when hits does not exist | ✅ Covered |
| Risk | Critic 11: sysbench gains `jq` | kit-shell-library: Overrides JSON pins to app nodes (literal JSON, no `jq`) | ✅ Covered |
| Risk | Critic 12: one query per line; log rotation drops markers | clickhouse-benchmark-kit: User guide content (requirement text: one query per line, rotation note) | ✅ Covered |
| Risk | Critic 13: `TARGET_HTTP_URL` not enforced at install | kit-cross-targeting: Target missing every required endpoint type; clickhouse-benchmark-kit: kit.yaml parsed | ✅ Covered |
| Risk | Critic-2 M1: pod ends before seen Running | kit-shell-library: Pod finishes before it is seen Running; clickhouse-benchmark-kit: Pod finishes before it is seen Running; sysbench-app-node-placement: Createdb pod finishes in one second | ✅ Covered |
| Risk | Critic-2 M2: unbounded loops for a gone pod | kit-shell-library: Pod deleted while waiting; kit-shell-library: Pod deleted mid-run; kit-shell-library: Pod deleted while waiting for exit; clickhouse-benchmark-kit: Run pod deleted mid-run | ✅ Covered |
| Risk | Critic-2 M3: library written once, not refreshed | kit-shell-library: Library written on the first kit process; kit-shell-library: Existing library is left as it is; kit-shell-library: clean removes the library; kit-shell-library: Library in a foreign bin directory | ✅ Covered |
| Risk | Critic-2 M4: bash 3.2 untested, empty arrays | kit-shell-library: Tests run under bash 3.x; kit-shell-library: Empty optional list under set -u | ✅ Covered |
| Risk | Critic-2 minor 1: field selector commas are AND | kit-shell-library: Pending and Running pods listed | ✅ Covered |
| Risk | Critic-2 minor 2: unlabelled ConfigMap after a failed label | kit-shell-library: ConfigMap carries its labels | ✅ Covered |
| Risk | Critic-2 minor 3: uneven settings denylist | clickhouse-benchmark-kit: Settings key with invalid characters; clickhouse-benchmark-kit: Settings key on the denylist | ✅ Covered |
| Risk | Critic-2 minor 4: install fill drops unresolved defaults | kit-install-command: Unresolved default is kept | ✅ Covered |
| Risk | Critic-2 minor 5: DDL timeout of 1800 misreports success | clickhouse-benchmark-kit: distributed_ddl_task_timeout no limit | ✅ Covered |
| Risk | Critic-2 minor 6: `--since-time` precision | kit-shell-library: Stream cut while the pod runs (requirement text states the one-second repeat) | ✅ Covered |
| Risk | Critic-2 minor 7: write-once dev loop | None | ⚠️ Excluded — developer docs only, no product behaviour: task 10.4 adds the dev-loop note to `docs/development/kits.md` |
| Risk | Critic-2 minor 8: arity changes help, KDoc and test name | kit-command-args: Boolean arg given as a bare flag (requirement text states the help form) | ✅ Covered (KDoc and test name in task 3.5 and 3.3) |
