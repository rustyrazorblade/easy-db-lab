## 1. Pod logs on the data mount

- [x] 1.1 In `services/start-k3s-server.sh` and `services/start-k3s-agent.sh`, move `/var/log/pods` to `/mnt/db1/container-logs` when `/mnt/db1` is mounted and `/var/log/pods` is not a symlink, as `packer/base/install/start_k3s_*.sh` do
- [x] 1.2 Check that `OtelManifestBuilder` mounts `/mnt/db1/container-logs` on every node that runs pods, and that the `file_log/containers` and `file_log/system` globs still match
- [x] 1.3 Update `docs/reference/log-infrastructure.md` and `docs/user-guide/loki.md` if they describe the old location

## 2. Framework: password support

- [x] 2.1 Add `password` (default `""`) to `KitCapability` in `services/KitConfig.kt`, with a unit test that parses it and defaults it
- [x] 2.2 Add `TARGET_MYSQL_PASSWORD`, `TARGET_PG_PASSWORD`, and `TARGET_JDBC_PASSWORD` to `KitEndpoint.toTargetVars`, with unit tests for a set and a blank password
- [x] 2.3 Pass the password from `KitSqlCommand` to `KitJdbcSqlService`, and set the JDBC `password` property only when it is not blank. Test the connection properties through a fake connection factory
- [x] 2.4 Check every event and `println` on the `sql` path, and add a test that the password does not appear in success or error output

## 3. Framework: install-time render and arg validation

- [x] 3.1 In `BaseInstallCommand.renderAndWrite`, render the `kit.yaml` content through `TemplateService.renderKitTemplate` before the port patch. Test a placeholder from an arg, from a default, and a `kit.yaml` with no placeholder
- [x] 3.2 In `KitInfo`, render the packaged `kit.yaml` placeholders with the arg defaults before it shows endpoints. Test that no `__VAR__` placeholder is shown and no password is printed
- [x] 3.3 Check that `<kit> status` shows `mysql` and `postgresql` endpoints from the installed `kit.yaml`, and add a test with an installed database name
- [x] 3.4 Add an optional `pattern` to `KitArgSpec`. Check it in `KitInstallCommand` before anything is rendered or written, and fail naming the flag, not the value. Test match, mismatch, and no pattern
- [x] 3.5 Update `docs/development/kits.md`: the `TARGET_*_PASSWORD` rows, the `password` capability field, install-time `kit.yaml` placeholders, and the arg `pattern` field

## 4. sysbench passwords and debt

- [ ] 4.1 Add `kits/sysbench/bin/lib/target-db.sh`, sourced through the script directory, that builds the driver args once as a shell array for prepare, start, and stop
- [ ] 4.2 Add `--mysql-password` or `--pgsql-password` when the target password is set. Give the create-database pod `MYSQL_PWD` through `--env` when set
- [ ] 4.3 In `stop.sh.template`, replace `kubectl describe pod` with an events listing, so the pod's command line is never printed
- [ ] 4.4 Add `src/test/shell/sysbench-target-db.test.sh` (passwordless targets unchanged; password with spaces, `*`, and single quotes passed as one arg; no password in output), wire it into `testScripts` in `build.gradle.kts`, and keep `sysbench-start.test.sh` and `sysbench-stop.test.sh` passing
- [ ] 4.5 Update `docs/user-guide/sysbench.md` for target passwords

## 5. SingleStore kit: operator and kit.yaml

- [ ] 5.1 Vendor the CRD (schemaless), RBAC, and operator Deployment from the upstream `singlestore-labs/singlestore-helm` templates into `kits/singlestore/`. Pin the image tag to `__OPERATOR_VERSION__`, set the operator args, and pin the pod to the control node
- [ ] 5.2 Write `kits/singlestore/kit.yaml`: `type: db`, every arg with its default, `required: true` on `--license`, `pattern` on `--database` and `--password`, the `install` steps, the pod-selector scrape on 9104 `/cluster-metrics`, the runtime selector, the two endpoints on 30306 with `database: "__SINGLESTORE_DATABASE__"`, and the `sql` capability with `user: root`, `password: "__SINGLESTORE_PASSWORD__"`, and the MySQL driver class
- [ ] 5.3 Add the NodePort Service template on 30306 selecting `easydblab/tier=aggregator`

## 6. SingleStore kit: start, stop, uninstall

- [ ] 6.1 Write `kits/singlestore/bin/lib/singlestore-sizing.sh`: `--size` to `storageGB`, the redundancy rule, the leaf size formula, and the minimum checks
- [ ] 6.2 Add `src/test/shell/singlestore-sizing.test.sh` with stubbed `kubectl` output (even, odd, explicit, and non-dividing redundancy; computed size on 8 and 4 vCPU nodes; below minimum; aggregator share), and wire it into `testScripts`
- [ ] 6.3 Write the start shell step: clear leftover CR, PVCs, and Secrets; size; create the license Secret (newline stripped) and the root password Secret apply-style; render and apply the `MemsqlCluster` with the scheduling details, anti-affinity, labels, and `local-path`
- [ ] 6.4 Write the wait step, with license-refusal detection from the CR status and the operator log, and the `--force` advice
- [ ] 6.5 Write the SQL step and its shell library: `kubectl exec -c node` into the master pod, `MYSQL_PWD`, escaped literals, local or `ON S3` database with a timestamped path, the printed path, and failure on refusal
- [ ] 6.6 Add `src/test/shell/singlestore-sql.test.sh` (both `--unlimited-storage` settings, escaping, no password on any command line, refusal fails with no local fallback), and wire it into `testScripts`
- [ ] 6.7 Write the stop steps (CR, NodePort, PVCs by `app.kubernetes.io/instance=singlestore`) and the uninstall steps (leftover CR, operator, RBAC, CRD)
- [ ] 6.8 Make every pod a kit script creates use a timestamped name and the `easydblab/kit` and `easydblab/role` labels

## 7. SingleStore kit: tests

- [ ] 7.1 Add `SingleStoreKitTest` using `BuiltinKitFixture`, like `TidbKitTest`: rendered CR fields, NodePort, scrape, args and defaults, the rendered `kit.yaml` password and database, the uninstall order, and both `--unlimited-storage` settings
- [ ] 7.2 Add a K3s TestContainers integration test in `src/integrationTest/` that applies the CRD, RBAC, operator Deployment, a rendered `MemsqlCluster`, and the NodePort Service

## 8. SingleStore kit: metrics, dashboards, docs

- [ ] 8.1 Write `METRICS.md` and `metrics-catalog.json` from the exporter's `/cluster-metrics` series
- [ ] 8.2 Build the cluster overview and the leaf and partition dashboards with the dashboard-editor agent, with the tenant pickers, and keep `DashboardDatasourceVariablesTest` passing
- [ ] 8.3 Write `README.md.template`
- [ ] 8.4 Write `docs/user-guide/singlestore.md` (license key, every arg, topology, redundancy rule, minimum `i4i.2xlarge`, connect, sysbench, unlimited storage, license unit formula) and link it in `docs/SUMMARY.md`
- [ ] 8.5 Update `CLAUDE.md` if a pattern changed (install-time `kit.yaml` render, arg `pattern`)

## 9. Build checks

- [ ] 9.1 Run `./gradlew ktlintFormat`, `./gradlew detekt` on JDK 21, `./gradlew test`, and `./gradlew integrationTest`, in a subagent, and fix every failure

## 10. Real-cluster test (3 x i4i.2xlarge db nodes, owner's Enterprise key)

- [ ] 10.1 Install without `--license` and check that it fails naming `--license`; install with an invalid `--database` and `--password` and check the messages
- [ ] 10.2 Start with defaults. Record either a running cluster or the license-refusal message and its advice
- [ ] 10.3 Record the license units the server reports for a known leaf size, and write the formula in the user guide
- [ ] 10.4 Start with explicit leaf sizes that fit the license. Check the topology, redundancy, node placement, the license Secret, and `--aggregators 2` with the NodePort selecting all three aggregators
- [ ] 10.5 Check `SELECT 1` from an app node on 30306, `singlestore status`, and `singlestore sql` with the default and a custom password, with no password in output
- [ ] 10.6 Run sysbench `oltp_read_write` prepare and start with the default and a custom password (with spaces, `*`, and a single quote). Check non-zero TPS, no SQL errors, and no password in output
- [ ] 10.7 Check `memsql_exporter` series in Mimir with `instance` set to the pod name, with a non-default password too; check both dashboards read back from Grafana with data in every panel; check the scrape job is gone after `stop`
- [ ] 10.8 Run with `--unlimited-storage`: check the blob cache setting, writes, objects under the printed path, then `stop` and `start` and check a new path takes writes and the old objects remain
- [ ] 10.9 Check that the SingleStore data directory, tracelogs, and `/var/log/pods` are under `/mnt/db1`; start TiDB on the same cluster and check its data and pod logs are under `/mnt/db1`
- [ ] 10.10 Check `stop`, a fresh `start`, `uninstall` while running (stops first), and `uninstall` after `stop`
- [ ] 10.11 Run sysbench against TiDB to check passwordless targets are unchanged
