## Why

easy-db-lab cannot run SingleStore, a distributed SQL database that speaks the MySQL wire protocol. Without a kit, nobody can stand up a throwaway SingleStore cluster, benchmark it with sysbench, or compare it with TiDB and ClickHouse on the same observability stack. SingleStore always has a root password, and the kit framework cannot pass a password to the `sql` command or to sysbench today. Pod logs also land on the root volume today, which breaks the rule that database data and logs live on the data disk.

## What Changes

- New kit `singlestore` (`type: db`) under `kits/singlestore/`, with no kit-specific Kotlin. It is modeled on `kits/tidb`.
  - Packaged operator manifests taken from the upstream `singlestore-labs/singlestore-helm` templates: a vendored schemaless CRD, RBAC, and an operator Deployment pinned to `singlestore/operator:4.252.0-3adf6c3d` and to the control node.
  - A `MemsqlCluster` on the db nodes with the node image `singlestore/node:alma-9.0.44-1322a9e639` by default. One master aggregator shares a db node with a leaf. One leaf runs on each db node.
  - Named args: `--license` (required), `--version`, `--operator-version`, `--aggregators`, `--leaves`, `--redundancy`, `--size`, `--leaf-cores`, `--leaf-memory`, `--aggregator-cores`, `--aggregator-memory`, `--password` (default `easydblab`), `--database` (default `test`), `--unlimited-storage`, `--blob-cache`.
  - `start` clears leftovers from a failed start, sizes the leaf from the db node's allocatable resources (`lib/singlestore-sizing.sh`), checks the redundancy rule, creates the license and root password Secrets, applies the CR, waits, detects a license refusal, creates the NodePort Service on 30306, and creates the database. With `--unlimited-storage` it creates the database `ON S3` at a new path under the per-cluster data bucket on each start, and prints the path.
  - `stop` deletes the CR, the NodePort Service, and the cluster's PVCs. Every start gives a fresh cluster.
  - `uninstall` removes a leftover CR, the operator, its RBAC, and the CRD. The framework stops a running kit first.
  - Metrics come from the operator's exporter sidecar (port 9104, path `/cluster-metrics`) on the master pod, through a pod-selector scrape.
  - Two kit dashboards: cluster overview, and leaf and partition. Also `METRICS.md`, `metrics-catalog.json`, and `README.md.template`.
- Framework password support:
  - `KitCapability` gains a `password` field (default empty).
  - `KitEndpoint.toTargetVars` adds `TARGET_MYSQL_PASSWORD`, `TARGET_PG_PASSWORD`, and `TARGET_JDBC_PASSWORD` (empty when the kit has none).
  - `KitSqlCommand` and `KitJdbcSqlService` send the password only when it is not blank. No output prints it.
- Framework install-time render: `BaseInstallCommand.renderAndWrite` renders the installed `kit.yaml` through `TemplateService.renderKitTemplate`, so `__VAR__` placeholders for arg values (password, endpoint `database`, JDBC path) resolve. `kit info` renders the packaged placeholders with the arg defaults. `<kit> status` shows the installed endpoints.
- Framework arg validation: `KitArgSpec` gains an optional `pattern`. `kit install` refuses a value that does not match and names the flag, before it writes anything.
- sysbench (`kits/sysbench/bin/`): a new `lib/target-db.sh` builds the driver args once, as a shell array, for prepare, start, and stop. It adds `--mysql-password` or `--pgsql-password` when the target has a password. The create-database pod gets `MYSQL_PWD` when set. `stop` lists events instead of running `kubectl describe pod`, which printed the pod's command line. Passwordless targets behave as today.
- Runtime K3s scripts `services/start-k3s-server.sh` and `services/start-k3s-agent.sh` move `/var/log/pods` to `/mnt/db1/container-logs`, so no kit writes pod logs to the root volume.
- Docs: `docs/user-guide/singlestore.md` (linked in `docs/SUMMARY.md`), the `TARGET_*_PASSWORD` rows and the `password` capability field in `docs/development/kits.md`, `docs/user-guide/sysbench.md`, and `docs/reference/log-infrastructure.md`.

## Capabilities

### New Capabilities

- `singlestore-kit`: the SingleStore kit. Operator install, license, topology, sizing, redundancy, storage, access, metrics, dashboards, lifecycle, and unlimited storage.

### Modified Capabilities

- `kit-capabilities`: the `sql` capability gains an optional `password`, sent only when it is not blank and never printed.
- `kit-cross-targeting`: `TARGET_*` injection adds a password variable per wire type. sysbench authenticates with it in every phase.
- `kit-install-command`: the installed `kit.yaml` is rendered at install time, `kit info` renders placeholders with defaults, and an arg `pattern` is checked before anything is written.
- `workload-status-command`: the status command shows `mysql` and `postgresql` endpoints from the installed, rendered `kit.yaml`.
- `kit-uninstall-command`: uninstalling a running kit runs its `stop` phase first.
- `k8s-container-log-collection`: pod logs live on the data mount.
- `instance-storage-validation`: database data files and logs never live on the root volume.

## Impact

- New resources under `src/main/resources/com/rustyrazorblade/easydblab/kits/singlestore/`.
- Kotlin: `services/KitConfig.kt` (`KitCapability`, `KitEndpoint.toTargetVars`, `KitArgSpec`), `commands/install/BaseInstallCommand.kt`, `commands/install/KitInstallCommand.kt`, `commands/kit/KitInfo.kt`, `commands/kit/KitSqlCommand.kt`, `services/sql/` (`KitJdbcSqlService`), and the workload status endpoint display.
- Shell: `kits/sysbench/bin/*`, new `kits/sysbench/bin/lib/target-db.sh`, `services/start-k3s-server.sh`, `services/start-k3s-agent.sh`.
- Build: new shell tests wired into `testScripts` in `build.gradle.kts`.
- Tests: unit tests, shell tests, a K3s TestContainers integration test, and a real-cluster test on 3 x `i4i.2xlarge` db nodes with a licensed key.
- Docs as listed above. No new external dependency in the CLI. The cluster pulls the public `singlestore/operator` and `singlestore/node` images.
