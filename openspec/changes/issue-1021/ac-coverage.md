# Acceptance criteria coverage for issue-1021

| Source | Requirement | Covering scenario(s) | Status |
|---|---|---|---|
| AC: install without `--license` fails, names `--license` | singlestore-kit: SingleStore kit args | Install without a license fails | ✅ Covered |
| AC: license key (newline stripped) in a Secret, referenced in `spec.licenseSecret` | singlestore-kit: SingleStore license is stored in a Secret | License Secret is referenced by the CR; Trailing newline is stripped | ✅ Covered |
| AC: `kit info singlestore` lists every arg with its default | singlestore-kit: SingleStore kit args; kit-install-command: kit info renders placeholders with arg defaults | kit info lists every arg with its default; kit info shows the default database | ✅ Covered |
| AC: default topology on N db nodes (1 master, 0 aggregators, N leaves, placement) | singlestore-kit: SingleStore topology | Default topology on N db nodes | ✅ Covered |
| AC: even leaves give redundancy 2, odd give 1 | singlestore-kit: SingleStore redundancy rule | Even leaf count gives redundancy 2; Odd leaf count gives redundancy 1 | ✅ Covered |
| AC: `--redundancy` that divides the leaf count is used | singlestore-kit: SingleStore redundancy rule | Explicit redundancy that divides the leaf count | ✅ Covered |
| AC: leaf count not a multiple of redundancy fails, names `--leaves` and `--redundancy` | singlestore-kit: SingleStore redundancy rule | Leaf count not a multiple of the redundancy | ✅ Covered |
| AC: defaults on `i4i.xlarge` fail fast with computed size and minimum | singlestore-kit: SingleStore leaf sizing | Defaults on a 4 vCPU db node | ✅ Covered |
| AC: `--aggregators 2` gives two child aggregators, NodePort selects all three | singlestore-kit: SingleStore topology; SingleStore client access | Child aggregators; NodePort selects every aggregator | ✅ Covered |
| AC: leaf requests allocatable minus collector reserve when sizes not set | singlestore-kit: SingleStore leaf sizing | Computed leaf size | ✅ Covered |
| AC: leaf below 4 cores or 8 GB fails fast with values and minimum | singlestore-kit: SingleStore leaf sizing | Leaf size below the minimum | ✅ Covered |
| AC: leaf size over the license fails with SingleStore's error and `--force` advice; units recorded and formula documented | singlestore-kit: SingleStore license refusal is reported; SingleStore user guide | Leaf size exceeds the license; User guide is linked (tasks 10.2, 10.3) | ✅ Covered |
| AC: start after a failed start clears leftover CR, PVCs, Secrets | singlestore-kit: SingleStore start clears leftovers from a failed start | Start after a failed start | ✅ Covered |
| AC: invalid `--database` or `--password` fails naming the arg | singlestore-kit: SingleStore kit args; kit-install-command: Install arg pattern validation | Invalid database name is refused; Invalid password is refused; Value does not match the pattern | ✅ Covered |
| AC: SingleStore data dir, tracelogs, `/var/log/pods` under `/mnt/db1` | singlestore-kit: SingleStore data and logs on the data disk; k8s-container-log-collection: Pod logs are stored on the data mount; instance-storage-validation: Database data and logs never on the root volume | SingleStore storage placement; Pod logs on the data mount; SingleStore data and pod logs on the data mount | ✅ Covered |
| AC: TiDB data and pod logs under `/mnt/db1` | instance-storage-validation: Database data and logs never on the root volume | TiDB data and pod logs on the data mount | ✅ Covered |
| AC: MySQL client on an app node runs `SELECT 1` on 30306 | singlestore-kit: SingleStore client access | MySQL client on an app node | ✅ Covered |
| AC: `singlestore status` shows MySQL wire and JDBC endpoints on 30306 with the installed database | singlestore-kit: SingleStore client access; workload-status-command: Status command shows connection endpoints | Status shows both endpoints; Installed database name displayed | ✅ Covered |
| AC: `sql "SELECT 1"` with the default password connects with `easydblab` | singlestore-kit: SingleStore client access; kit-capabilities: sql capability type | sql with the default password; Password is sent when set | ✅ Covered |
| AC: `--password X` makes `sql` connect with X, X never in output | singlestore-kit: SingleStore client access; kit-capabilities: sql capability type | sql with a custom password; Password is sent when set | ✅ Covered |
| AC: sysbench prepare and start `oltp_read_write` with no SQL errors and non-zero TPS | kit-cross-targeting: sysbench authenticates with the target password | oltp_read_write on SingleStore | ✅ Covered |
| AC: every sysbench phase uses the password, special characters work, never in output | kit-cross-targeting: sysbench authenticates with the target password; TARGET_* env vars injected at start time | Every phase uses the password; Password with special characters; Password from the target sql capability | ✅ Covered |
| AC: passwordless targets unchanged, existing sysbench tests pass | kit-cross-targeting: sysbench authenticates with the target password | Passwordless targets are unchanged | ✅ Covered |
| AC: `memsql_exporter` series in Mimir with `instance` = pod name | singlestore-kit: SingleStore metrics | Series arrive in Mimir | ✅ Covered |
| AC: series arrive with a non-default password | singlestore-kit: SingleStore metrics | Series arrive with a custom password | ✅ Covered |
| AC: both dashboards in `singlestore` folder, data in every panel, pickers on tenant, `DashboardDatasourceVariablesTest` passes | singlestore-kit: SingleStore dashboards | Dashboards install and show data; Dashboard conventions test | ✅ Covered |
| AC: scrape job removed on stop | singlestore-kit: SingleStore metrics | Scrape job removed on stop | ✅ Covered |
| AC: `stop` removes the CR, NodePort, PVCs | singlestore-kit: SingleStore stop and restart | Stop removes the cluster | ✅ Covered |
| AC: `start` after `stop` gives a fresh cluster with no conflict | singlestore-kit: SingleStore stop and restart | Start after stop is fresh | ✅ Covered |
| AC: `uninstall` while running stops first, then removes operator, RBAC, CRD | singlestore-kit: SingleStore uninstall; kit-uninstall-command: Running uninstall executes the kit uninstall phase then removes the local directory | Uninstall while running; Running kit is stopped first | ✅ Covered |
| AC: `uninstall` after `stop` removes operator, RBAC, CRD | singlestore-kit: SingleStore uninstall | Uninstall after stop | ✅ Covered |
| AC: without `--unlimited-storage` nothing goes to S3, no `ON S3` database | singlestore-kit: SingleStore database creation | Local database | ✅ Covered |
| AC: with `--unlimited-storage`, blob cache from `--blob-cache`, `ON S3` database under the data bucket via node IAM role, writes land | singlestore-kit: SingleStore unlimited storage | ON S3 database takes writes | ✅ Covered |
| AC: stop and start with unlimited storage uses a new path, old objects remain | singlestore-kit: SingleStore unlimited storage | New path on each start | ✅ Covered |
| AC: license without unlimited storage fails with SingleStore's error, no local fallback (unit test) | singlestore-kit: SingleStore unlimited storage | License does not allow unlimited storage (task 6.6 shell test) | ✅ Covered |
| AC: unit tests cover rendered manifest and config for both `--unlimited-storage` settings | singlestore-kit: SingleStore unlimited storage | Rendered config for both settings (tasks 6.6, 7.1) | ✅ Covered |
| AC: user guide covers license, args, topology, redundancy, smallest instance, connect, sysbench, unlimited storage; linked from SUMMARY | singlestore-kit: SingleStore user guide | User guide is linked | ✅ Covered |
| Scope: script pods have timestamped names and kit/role labels, cleanup by label | singlestore-kit: SingleStore kit pods are labeled | Script pods carry labels | ✅ Covered |
| Risk: operator user/password rotation | singlestore-kit: SingleStore root password | Password Secret exists before the CR; Custom password | ✅ Covered |
| Risk: license unit cap | singlestore-kit: SingleStore license refusal is reported | Leaf size exceeds the license | ✅ Covered |
| Risk: exporter sidecar resources | singlestore-kit: SingleStore leaf sizing | Computed leaf size | ✅ Covered |
| Risk: anti-affinity relies on schemaless CR passthrough | singlestore-kit: SingleStore topology | Default topology on N db nodes (each leaf on its own db node; checked in tasks 7.2 and 10.4) | ✅ Covered |
| Risk: password leaks in output | kit-capabilities: sql capability type; kit-cross-targeting: sysbench authenticates with the target password; kit-install-command: Install arg pattern validation; workload-status-command: Status command shows connection endpoints | Password is sent when set; Password with special characters; Value does not match the pattern; Installed database name displayed | ✅ Covered |
| Risk: leftover state after a failed start | singlestore-kit: SingleStore start clears leftovers from a failed start | Start after a failed start | ✅ Covered |
| Risk: S3 path reuse | singlestore-kit: SingleStore unlimited storage | New path on each start | ✅ Covered |
| Risk: pod logs on the root volume | k8s-container-log-collection: Pod logs are stored on the data mount; instance-storage-validation: Database data and logs never on the root volume | Pod logs on the data mount; Pod logs still reach Loki; TiDB data and pod logs on the data mount | ✅ Covered |
