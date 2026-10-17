## Context

SingleStore is a distributed SQL database with a MySQL wire protocol. Its Kubernetes Operator manages a `MemsqlCluster` custom resource. A distributed cluster needs a valid license key. The server refuses `BOOTSTRAP AGGREGATOR` without one, so no deployment path avoids the key.

The closest existing kit is `kits/tidb`: an operator, a CR, a precheck, a wait per component, a NodePort, and an uninstall. The framework today has no password support. `KitCapability` has only `user` and `driver-class`. `toTargetVars` sets no password. sysbench passes no password. The installed `kit.yaml` is copied with a port patch only, so it cannot carry an install-time arg value.

`local-path` PVCs already live on `/mnt/db1`: the runtime K3s scripts link `/var/lib/rancher/k3s` to `/mnt/db1/k3s` (`services/start-k3s-agent.sh:21-28`, `services/start-k3s-server.sh:15-24`). Pod logs do not. `/var/log/pods` stays on the root volume. The unused `packer/base/install/start_k3s_*.sh` scripts already move it, and the OTel Collector already mounts `/mnt/db1/container-logs`.

## Goals / Non-Goals

**Goals:**

- A `singlestore` kit with no kit-specific Kotlin.
- Password support in the `sql` command and in sysbench, with no change for kits without a password.
- Database data and pod logs on the data disk for every kit.
- Metrics, dashboards, docs, and a real-cluster test.

**Non-Goals:**

- SingleStore Studio and the Data API.
- SingleStore's own metrics database pipeline and its MySQL-backed dashboards.
- Node CPU, disk, and network dashboards. The system dashboards cover them.
- A SingleStore-specific load tool.
- An environment-variable fallback for the license.
- Backup and restore phases.
- TLS, and users other than `root`.
- Rack-aware availability-group placement.
- A rowstore option for sysbench.

## Spike Findings

The spike ran operator `4.252.0-3adf6c3d` and node `alma-9.0.44-1322a9e639` on 3 db nodes.

- License:
  - The operator refuses a `MemsqlCluster` with no license, or with `license: ""`.
  - A placeholder value fails at `SET LICENSE` with error 1888.
  - With no key, the server reports `License_type: Developer` and refuses `BOOTSTRAP AGGREGATOR` (error 2446).
  - The operator has no flag that skips the license step.
  - The key must not end with a newline. With the newline, the server reports "The provided license is invalid."
  - The test key reports `License_type: Enterprise`, a capacity of 2 units, and a 30-day expiry.
- Exporter:
  - The operator runs an `exporter` sidecar on port 9104 in the master pod. It requests 100m CPU and 200Mi.
  - It serves `/cluster-metrics` with no extra setup: HTTP 200, about 720 metric names.
  - Each series carries `host`, `node_id`, `role` (`MA`, `LEAF`), and `cluster_name`, for every node.
- Root password:
  - With no password given, the operator makes a random root password in a Secret.
  - A `rootPasswordSecret` with an empty value is treated as unset. The operator then makes a random password and gets stuck trying to rotate root to the empty value.
  - A `rootPasswordSecret` with a value, set before the CR, works.
  - Changing the Secret on a running cluster does not rotate the password.
- Scheduling and labels:
  - `objectMetaOverrides.labels` reach the pods.
  - An `affinity` block in `schedulingDetails.leaf-ag1` passes through to the leaf pods. Three leaves spread across three db nodes.
  - The operator adds its own anti-affinity to the master.
  - `schedulingDetails.*.nodeSelector: {type: db}` works. The operator pod has no selector by default.
  - `serviceSpec.type: ClusterIP` works. The operator makes `svc-<cluster>` (headless) and `svc-<cluster>-ddl` (master only).
- Storage and counts:
  - Operator PVCs carry `app.kubernetes.io/instance=<cluster>`, not `easydblab/kit`.
  - `leafSpec.count` is per availability group. `count: 1` with redundancy 2 gave one leaf in AG 1 and one in AG 2.
- Host settings: no kernel errors with the base AMI. One tuning warning: the host caps the network buffer at 4 MB.
- Unlimited storage:
  - `SET GLOBAL maximum_blob_cache_size_mb` works.
  - `CREATE DATABASE ... ON S3 "<data bucket>/singlestore/<db>" CONFIG '{"region":"..."}' CREDENTIALS '{}'` works through the node IAM role, with no access keys.
  - A columnstore table took inserts, and objects landed under the `singlestore/` prefix.

## Decisions

### D1. Packaged operator manifests

The kit ships the CRD (vendored, schemaless), the RBAC, and the operator Deployment as files under `kits/singlestore/`, taken from the upstream `singlestore-labs/singlestore-helm` templates. The operator Deployment pins `singlestore/operator:__OPERATOR_VERSION__` (default `4.252.0-3adf6c3d`), the args `--merge-service-annotations --disable-finalizer --fs-group-id 5555 --cluster-id singlestore`, and a control-node `nodeSelector`. `install` applies them with `manifest` steps. The default node image is `singlestore/node:alma-9.0.44-1322a9e639`.

### D2. One shell step sizes and applies

`start` runs one shell step that sources `lib/singlestore-sizing.sh` through the script directory. Sizes are computed at run time from the db nodes, so the CR cannot be a plain install-time manifest. The step:

1. Deletes any leftover `MemsqlCluster`, PVCs with `app.kubernetes.io/instance=singlestore`, and the kit's Secrets.
2. Converts `--size` to an integer `storageGB`.
3. Computes the redundancy (2 if leaves are even, else 1) unless `--redundancy` is set. Fails when `leaves % redundancy != 0`, naming `--leaves` and `--redundancy`.
4. Computes the leaf size unless `--leaf-cores` and `--leaf-memory` are set: the smallest db node allocatable, minus a fixed collector reserve, minus `ceil((1 + aggregators) / leaves)` times the aggregator size, minus the exporter sidecar (100m, 200Mi). Fails below 4 cores or 8192 MB with the values and the minimum.
5. Creates the license Secret (trailing newline stripped) and the root password Secret (`ROOT_PASSWORD`), apply-style.
6. Renders and applies the `MemsqlCluster` with `licenseSecret`, `rootPasswordSecret`, `redundancyLevel`, `leafSpec.count = leaves / redundancy`, `serviceSpec.type: ClusterIP`, `storageClass: local-path`, the scheduling details, and the label overrides.

The sizing functions are pure shell functions so `singlestore-sizing.test.sh` can test them with stubbed `kubectl` output.

### D3. Install-time render of kit.yaml (owner override)

`BaseInstallCommand.renderAndWrite` passes the `kit.yaml` content through `TemplateService.renderKitTemplate` with the cluster variables and resolved args, then applies the existing port patch. The singlestore `kit.yaml` holds `password: "__SINGLESTORE_PASSWORD__"` in its `sql` capability and `database: "__SINGLESTORE_DATABASE__"` in its endpoints and JDBC path. `kit info` renders the packaged `kit.yaml` with the arg defaults. `<kit> status` reads the installed file, so it shows installed values. A `kit.yaml` with no placeholder is unchanged.

### D4. Password in the framework

`KitCapability` gains `password` (default `""`). `KitEndpoint.toTargetVars` adds `TARGET_MYSQL_PASSWORD`, `TARGET_PG_PASSWORD`, and `TARGET_JDBC_PASSWORD`, empty when there is none. `KitSqlCommand` passes the password to `KitJdbcSqlService`, which sets the JDBC `password` property only when it is not blank. No event or `println` carries the password. The default `easydblab` is fixed because a blank root password is not possible with the operator.

### D5. Arg pattern validation

`KitArgSpec` gains an optional `pattern`. `KitInstallCommand` checks it before anything is rendered or written, and fails naming the flag, not the value. The singlestore `kit.yaml` uses `[A-Za-z0-9_]+` for `--database` and a pattern that refuses `"`, `\`, and control characters for `--password`. This keeps the kit free of kit-specific Kotlin and keeps the values safe in YAML and SQL literals.

### D6. Readiness and license refusal

A wait step polls the `MemsqlCluster` status and the operator log. When it sees a license refusal, it fails with SingleStore's error and advises `kit install singlestore --force` with smaller `--leaf-cores` and `--leaf-memory`.

### D7. Access

A NodePort Service on 30306 forwards to 3306 and selects `easydblab/tier=aggregator`. The master and child aggregators carry that label through `objectMetaOverrides`. Two endpoints on 30306, `node-type: db`: `mysql` and `jdbc` (scheme `mysql`), both with the installed database. The `sql` capability uses user `root`, the rendered password, and `com.mysql.cj.jdbc.Driver`.

### D8. Database creation

A SQL step runs `kubectl exec -c node` into the master pod. The password reaches `mysql` through `MYSQL_PWD`. The step escapes SQL literals. Without `--unlimited-storage` it runs `CREATE DATABASE IF NOT EXISTS <db>`. With it, it runs `SET GLOBAL maximum_blob_cache_size_mb=<--blob-cache>` and `CREATE DATABASE <db> ON S3 "<BUCKET_NAME>/singlestore/<db>-<timestamp>" CONFIG '{"region":"<REGION>"}' CREDENTIALS '{}'`, and prints the path. A refusal fails the step with SingleStore's error. The SQL builder is a shell function so `singlestore-sql.test.sh` can test both settings.

### D9. Metrics and dashboards

The kit runs no `SET GLOBAL exporter_port`. A pod-selector scrape uses port 9104, path `/cluster-metrics`, selector `easydblab/kit=singlestore,easydblab/role=master`. Two kit dashboards (cluster overview; leaf and partition) are built with the dashboard-editor agent, with the tenant pickers from `dashboards/CLAUDE.md`. They group by the `host`, `node_id`, and `role` labels.

### D10. Stop and uninstall

`stop` deletes the `MemsqlCluster`, the NodePort Service, and the PVCs with `app.kubernetes.io/instance=singlestore`. Every start is fresh. `uninstall` relies on `KitRunnerCommand.stopsBeforeUninstall` to stop a running kit first, then deletes a leftover CR, the operator Deployment, its RBAC, and the CRD.

### D11. sysbench passwords

A new `kits/sysbench/bin/lib/target-db.sh` builds the driver args once, as a shell array, for prepare, start, and stop. It adds `--mysql-password` or `--pgsql-password` when set. The create-database pod gets `MYSQL_PWD` through `--env`. `stop` lists events instead of `kubectl describe pod`, which printed the pod's command line and so the password. Only SQL errors that `oltp_read_write` actually hits on SingleStore are fixed. The default SingleStore table type stays.

### D12. Pod logs on the data mount

The runtime `services/start-k3s-server.sh` and `services/start-k3s-agent.sh` move `/var/log/pods` to `/mnt/db1/container-logs` when `/mnt/db1` is mounted and `/var/log/pods` is not a symlink, as the packer scripts do. The collector already mounts `/mnt/db1/container-logs`. This fixes every kit, TiDB included.

## Risks / Trade-offs

- [The operator rotates the root user or the password] → The password Secret is created before the CR, and the value is never blank. The spike showed a set value works and a later change does not rotate.
- [License unit cap] → Default sizes can exceed a small key. The wait step reports SingleStore's error and the fix. The real-cluster test records the units for a known leaf size, and the user guide states the formula.
- [Exporter sidecar resources] → The sizing subtracts its requests (100m, 200Mi), so the master's node does not overcommit.
- [Anti-affinity relies on the schemaless CR passing `affinity` through] → The spike showed it passes through. The K3s integration test and the real-cluster test check that each leaf is on its own node.
- [Password leaks in output] → `MYSQL_PWD` instead of `-p`, sysbench `stop` lists events instead of `describe`, events and `println` never carry it, and tests assert it is absent.
- [Leftover state after a failed start] → `start` clears the CR, PVCs, and Secrets first.
- [S3 path reuse] → Each start uses a timestamped path. Nothing deletes old paths.
- [Pod logs on the root volume] → D12 moves them for every kit.
- [Kit-yaml render touches every kit] → A `kit.yaml` without placeholders renders unchanged, and existing install tests cover it.

## Migration Plan

None. Clusters are ephemeral. The K3s script change applies to clusters created after it ships.

## Open Questions

- The license unit formula. The real-cluster test records it.

## Alternatives Considered

- **Helm chart.** Rejected. The upstream chart is stale (operator 4.81.0, node alma-8.9.40) and puts the operator and the cluster in one release.
- **`manifest-url` steps.** Rejected. Upstream publishes no raw manifest files to fetch. Packaged manifests are used instead.
- **Cluster-in-a-box single pod.** Rejected by the owner. It is not distributed.
- **No operator, plain StatefulSets.** Rejected. The server still needs a key for `BOOTSTRAP AGGREGATOR`, so it gains nothing and costs more code.
- **Run-time `${VAR}` interpolation with a `KitInstance` class.** The architect recommended it. The owner chose the critic's install-time render of `kit.yaml` instead. Owner override.
- **Blank password default.** Impossible. The operator treats an empty `rootPasswordSecret` as unset, and its rotation gets stuck.
- **Operator-random password read at run time.** Rejected. It needs more framework code. A fixed default `easydblab` is used.
- **Keep PVCs on stop (revived cluster).** The architect recommended it. The owner chose a fresh cluster on every start. Owner override.
- **`SET GLOBAL exporter_port`.** Superseded by the spike. The operator already runs an exporter sidecar.
- **CR `globalVariables` for the exporter.** Rejected for the same reason.
- **Round the redundancy down.** Rejected. Fail fast and name `--leaves` and `--redundancy`.
- **Master aggregator on its own node.** Rejected. The master shares a db node with a leaf, and the sizing counts its share.
- **K3s `--default-local-storage-path`.** Rejected. PVCs are already on `/mnt/db1`. The real gap was `/var/log/pods`.
- **Fixed S3 path with `ATTACH`.** Rejected. It conflicts with a fresh cluster on every start. A unique path per start is used.
- **Message-only recovery from a failed start.** Rejected. `start` clears the leftovers itself.
- **YAML escaping of arg values.** Rejected. Install-time validation refuses unsafe values instead.
- **Privileged sysreq DaemonSet for kernel settings.** Not needed. The spike found no kernel errors.
- **Rowstore option for sysbench.** Not asked for.
