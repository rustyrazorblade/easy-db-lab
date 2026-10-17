## ADDED Requirements

### Requirement: SingleStore kit installs the operator from packaged manifests

The system SHALL ship a built-in kit named `singlestore` with `type: db` and no kit-specific Kotlin. Its `install` phase SHALL apply packaged manifests taken from the upstream `singlestore-labs/singlestore-helm` templates: the `MemsqlCluster` CRD (vendored, schemaless), the operator RBAC, and the operator Deployment. The operator image SHALL be `singlestore/operator:<--operator-version>`, with the default `4.252.0-3adf6c3d`. The operator SHALL run with the args `--merge-service-annotations`, `--disable-finalizer`, `--fs-group-id 5555`, and `--cluster-id singlestore`. The operator pod SHALL be pinned to the control node. The install SHALL NOT use a Helm chart or a manifest URL.

#### Scenario: Install applies the operator on the control node
- **WHEN** the user runs `kit install singlestore --license <key>` and then `singlestore start`
- **THEN** the CRD, RBAC, and operator Deployment are applied from files in the installed kit directory
- **AND** the operator pod runs on the control node with the pinned operator image and args

#### Scenario: Operator version arg selects the image tag
- **WHEN** the kit is installed with `--operator-version <tag>`
- **THEN** the operator Deployment uses `singlestore/operator:<tag>`

### Requirement: SingleStore kit args

The kit SHALL declare these args, all as named options, each with the listed default:

- `--license` (required, no default)
- `--version` (`singlestore/node` tag, default `alma-9.0.44-1322a9e639`)
- `--operator-version` (`singlestore/operator` tag, default `4.252.0-3adf6c3d`)
- `--aggregators` (child aggregators, default `0`)
- `--leaves` (default `${DB_NODE_COUNT}`)
- `--redundancy` (default empty: computed at start)
- `--size` (storage per leaf, default `10Ti`, converted to an integer `storageGB` at start)
- `--leaf-cores` and `--leaf-memory` (default empty: computed at start)
- `--aggregator-cores` (default `2`) and `--aggregator-memory` (default `8` GB)
- `--password` (root password, default `easydblab`)
- `--database` (default `test`)
- `--unlimited-storage` (default `false`)
- `--blob-cache` (blob cache size in MB for unlimited storage)

`--database` SHALL accept only letters, digits, and `_`. `--password` SHALL refuse `"`, `\`, and control characters. Both checks SHALL run at `kit install`, before any file is written.

#### Scenario: Install without a license fails
- **WHEN** `kit install singlestore` runs without `--license`
- **THEN** the command fails before it writes any file or creates any cluster resource
- **AND** the message names `--license`

#### Scenario: kit info lists every arg with its default
- **WHEN** `kit info singlestore` runs
- **THEN** every arg above is listed as a named option with its default

#### Scenario: Invalid database name is refused
- **WHEN** `kit install singlestore --license <key> --database my-db` runs
- **THEN** the command fails with a message that names `--database`
- **AND** no file is written

#### Scenario: Invalid password is refused
- **WHEN** `kit install singlestore` runs with a `--password` that holds `"`, `\`, or a control character
- **THEN** the command fails with a message that names `--password`
- **AND** no file is written

### Requirement: SingleStore license is stored in a Secret

`start` SHALL write the `--license` value, with any trailing newline stripped, to a Secret. It SHALL create the Secret apply-style, so a second start does not fail. The `MemsqlCluster` SHALL reference the Secret in `spec.licenseSecret`. There SHALL be no environment-variable fallback for the license.

#### Scenario: License Secret is referenced by the CR
- **WHEN** the kit starts with `--license <key>`
- **THEN** a Secret holds the key with no trailing newline
- **AND** the deployed `MemsqlCluster` references it in `spec.licenseSecret`

#### Scenario: Trailing newline is stripped
- **WHEN** the `--license` value ends with a newline
- **THEN** the Secret value does not end with a newline

### Requirement: SingleStore root password

`start` SHALL create a root password Secret with `ROOT_PASSWORD` set to `--password`, apply-style, before it applies the `MemsqlCluster`. The CR SHALL reference it in `spec.rootPasswordSecret`. The default password SHALL be `easydblab`. A blank password SHALL NOT be offered, because the operator treats an empty value as unset.

#### Scenario: Default password
- **WHEN** the kit starts with no `--password`
- **THEN** root logs in with `easydblab`

#### Scenario: Custom password
- **WHEN** the kit was installed with `--password X`
- **THEN** root logs in with X

#### Scenario: Password Secret exists before the CR
- **WHEN** `start` runs
- **THEN** the root password Secret is applied before the `MemsqlCluster`

### Requirement: SingleStore topology

The `MemsqlCluster` SHALL have one master aggregator, `--aggregators` child aggregators, and `--leaves` leaves. `schedulingDetails` SHALL set `nodeSelector: {type: db}` for the master, the aggregators, and every leaf availability group. Each leaf availability group SHALL carry a `podAntiAffinity` on `easydblab/role=leaf`, so each leaf runs on its own db node. The master and the child aggregators SHALL carry a required `podAntiAffinity` on `easydblab/kit=singlestore,easydblab/tier=aggregator` per host, so each db node holds at most one aggregator. `start` SHALL fail before it creates anything when `1 + --aggregators` or `--leaves` is more than the db node count, with a message that names the flag and the db node count. `objectMetaOverrides` SHALL label the pods `easydblab/kit=singlestore` and `easydblab/role=master`, `aggregator`, or `leaf`. The master and the child aggregators SHALL also carry `easydblab/tier=aggregator`. `--aggregator-cores` and `--aggregator-memory` SHALL size both `masterAggregatorSpec` and `aggregatorSpec`. `serviceSpec.type` SHALL be `ClusterIP`. The leaves SHALL use the `local-path` storage class. Availability groups SHALL use the operator's default pairing.

#### Scenario: Default topology on N db nodes
- **WHEN** the kit starts on N db nodes with default topology args and leaf sizes that fit the license
- **THEN** there is one master aggregator, no child aggregators, and N leaves
- **AND** each leaf is on its own db node
- **AND** the master aggregator is on a db node that also runs a leaf
- **AND** no SingleStore pod runs on an app node or the control node

#### Scenario: Child aggregators
- **WHEN** the kit is installed with `--aggregators 2` and started
- **THEN** two child aggregators run on db nodes
- **AND** the master and both child aggregators carry `easydblab/tier=aggregator`
- **AND** no two aggregators run on the same db node

#### Scenario: More aggregators than db nodes
- **WHEN** `1 + --aggregators` is more than the db node count
- **THEN** start fails before it creates any cluster resource
- **AND** the message names `--aggregators` and the db node count

#### Scenario: More leaves than db nodes
- **WHEN** `--leaves` is more than the db node count
- **THEN** start fails before it creates any cluster resource
- **AND** the message names `--leaves` and the db node count

### Requirement: SingleStore redundancy rule

When `--redundancy` is not set, the redundancy SHALL be 2 when the leaf count is even and 1 when it is odd. `--redundancy` SHALL accept only 1 or 2; any other value SHALL fail before anything is created, with a message that names `--redundancy`. `start` SHALL fail before it creates anything when the leaf count is not a multiple of the redundancy, with a message that names `--leaves` and `--redundancy`. `leafSpec.count` SHALL be the leaf count divided by the redundancy, because the count is per availability group. The CR SHALL set `redundancyLevel`.

#### Scenario: Even leaf count gives redundancy 2
- **WHEN** the leaf count is even and `--redundancy` is not set
- **THEN** the cluster reports redundancy level 2

#### Scenario: Odd leaf count gives redundancy 1
- **WHEN** the leaf count is odd and `--redundancy` is not set
- **THEN** the cluster reports redundancy level 1

#### Scenario: Explicit redundancy that divides the leaf count
- **WHEN** `--redundancy` is set and divides the leaf count
- **THEN** the cluster uses that redundancy level
- **AND** `leafSpec.count` is the leaf count divided by the redundancy

#### Scenario: Leaf count not a multiple of the redundancy
- **WHEN** the leaf count is not a multiple of the redundancy
- **THEN** start fails before it creates any cluster resource
- **AND** the message names `--leaves` and `--redundancy`

### Requirement: SingleStore leaf sizing

`start` SHALL compute the sizes and apply the CR in one shell step that sources `lib/singlestore-sizing.sh` from the script directory. When `--leaf-cores` and `--leaf-memory` are not set, the leaf size SHALL be the smallest db node allocatable CPU and memory, minus a fixed collector reserve, minus one aggregator size (the aggregator anti-affinity puts at most one aggregator on a node), minus the exporter sidecar requests (100m CPU, 200Mi). `start` SHALL fail fast when the computed or given leaf size is below 4 cores or 8192 MB, with a message that gives the values and the minimum. The smallest db instance that works with defaults SHALL be `i4i.2xlarge`.

#### Scenario: Computed leaf size
- **WHEN** `--leaf-cores` and `--leaf-memory` are not set
- **THEN** each leaf requests the db node's allocatable CPU and memory minus the collector reserve, one aggregator size, and the exporter sidecar

#### Scenario: Aggregators outnumber leaves
- **WHEN** the kit starts on 3 db nodes of 8 vCPU with `--leaves 2 --aggregators 2` and computed leaf sizes
- **THEN** each leaf is sized with one aggregator size reserved, not two
- **AND** sizing passes the minimum check

#### Scenario: Leaf size below the minimum
- **WHEN** the computed or given leaf size is below 4 cores or 8192 MB
- **THEN** start fails before it creates any cluster resource
- **AND** the message gives the leaf values and the minimum

#### Scenario: Defaults on a 4 vCPU db node
- **WHEN** the kit starts with defaults on `i4i.xlarge` db nodes
- **THEN** start fails fast with the computed leaf size and the minimum

### Requirement: SingleStore license refusal is reported

The wait step SHALL detect a license refusal in the `MemsqlCluster` status or the operator log. It SHALL then fail with SingleStore's error and advice to re-run `kit install singlestore --force` with smaller `--leaf-cores` and `--leaf-memory`. It SHALL NOT fall back to a smaller cluster.

#### Scenario: Leaf size exceeds the license
- **WHEN** the leaf size is more than the license allows
- **THEN** start fails with SingleStore's error
- **AND** the message says to re-run `kit install singlestore --force` with smaller `--leaf-cores` and `--leaf-memory`

### Requirement: SingleStore start clears leftovers from a failed start

Before it applies anything, `start` SHALL delete any leftover `MemsqlCluster`, the cluster's PVCs (selected by `app.kubernetes.io/instance=singlestore`), and the kit's Secrets.

#### Scenario: Start after a failed start
- **WHEN** a start failed and `start` runs again
- **THEN** it clears the leftover CR, PVCs, and Secrets first
- **AND** it builds a fresh cluster

### Requirement: SingleStore data and logs on the data disk

The SingleStore data directory, its tracelogs, and its pods' logs SHALL be under `/mnt/db1` on each node. None SHALL be on the root volume.

#### Scenario: SingleStore storage placement
- **WHEN** the SingleStore cluster runs
- **THEN** the data directory, the tracelogs, and `/var/log/pods` resolve to paths under `/mnt/db1` on each db node

### Requirement: SingleStore client access

`start` SHALL create a NodePort Service on port 30306 that forwards to 3306 and selects `easydblab/tier=aggregator`. `kit.yaml` SHALL declare two endpoints on 30306 with `node-type: db`: a `mysql` endpoint and a `jdbc` endpoint with scheme `mysql`. Both SHALL use the installed `--database` name. The kit SHALL declare the `sql` capability with user `root`, the installed `--password`, and the MySQL JDBC driver class.

#### Scenario: MySQL client on an app node
- **WHEN** the cluster is ready
- **THEN** a MySQL client on an app node connects to `<db-node-ip>:30306` and runs `SELECT 1`

#### Scenario: NodePort selects every aggregator
- **WHEN** the kit runs with `--aggregators 2`
- **THEN** the NodePort Service endpoints include the master and both child aggregators

#### Scenario: Status shows both endpoints
- **WHEN** `singlestore status` runs
- **THEN** it shows a MySQL wire endpoint and a JDBC endpoint on 30306 with the installed `--database` name

#### Scenario: sql with the default password
- **WHEN** `singlestore sql "SELECT 1"` runs on a cluster installed with the default password
- **THEN** it connects with `easydblab` and returns 1

#### Scenario: sql with a custom password
- **WHEN** the kit was installed with `--password X` and `singlestore sql "SELECT 1"` runs
- **THEN** it connects with X and succeeds
- **AND** X does not appear in the command output

### Requirement: SingleStore database creation

After the cluster is ready, `start` SHALL run SQL through `kubectl exec -c node` into the master pod. The password SHALL reach `mysql` through `MYSQL_PWD`, never on the command line. SQL literals SHALL be escaped. Without `--unlimited-storage`, it SHALL run `CREATE DATABASE IF NOT EXISTS <db>`.

#### Scenario: Local database
- **WHEN** the kit starts without `--unlimited-storage`
- **THEN** the `--database` database exists
- **AND** nothing is written to S3 and no `ON S3` database is created

### Requirement: SingleStore unlimited storage

With `--unlimited-storage`, `start` SHALL run `SET GLOBAL maximum_blob_cache_size_mb=<--blob-cache>` and then `CREATE DATABASE <db> ON S3 "<BUCKET_NAME>/singlestore/<db>-<timestamp>" CONFIG '{"region":"<region>"}' CREDENTIALS '{}'`. The credentials SHALL come from the node IAM role, with no access keys. Each start SHALL use a new path. `start` SHALL print the path. Nothing SHALL delete an old path. When SingleStore refuses the statement, `start` SHALL fail and show SingleStore's error, and SHALL NOT create a local database instead.

#### Scenario: ON S3 database takes writes
- **WHEN** the kit starts with `--unlimited-storage`
- **THEN** `maximum_blob_cache_size_mb` equals `--blob-cache`
- **AND** `--database` is created `ON S3` under `singlestore/` in the per-cluster data bucket, through the node IAM role, with no access keys
- **AND** the database takes writes and objects land under that path
- **AND** start prints the path

#### Scenario: New path on each start
- **WHEN** `stop` and then `start` run with `--unlimited-storage` on
- **THEN** the second start creates its database on a new path and it takes writes
- **AND** the first path's objects are still in S3

#### Scenario: License does not allow unlimited storage
- **WHEN** `--unlimited-storage` is set and SingleStore refuses the `ON S3` statement
- **THEN** start fails and shows SingleStore's error
- **AND** no local database is created instead

#### Scenario: Rendered config for both settings
- **WHEN** unit tests render the kit with `--unlimited-storage` on and off
- **THEN** the off render issues only `CREATE DATABASE IF NOT EXISTS`
- **AND** the on render issues the blob cache setting and the `ON S3` statement with a timestamped path

### Requirement: SingleStore metrics

The kit SHALL NOT run `SET GLOBAL exporter_port`. It SHALL declare a pod-selector scrape on port 9104, path `/cluster-metrics`, selector `easydblab/kit=singlestore,easydblab/role=master`. This scrapes the operator's exporter sidecar in the master pod. The kit SHALL ship `METRICS.md` and `metrics-catalog.json`.

#### Scenario: Series arrive in Mimir
- **WHEN** the cluster starts
- **THEN** Mimir has `memsql_exporter` series from the master aggregator pod within a few minutes, with `instance` set to the pod name

#### Scenario: Series arrive with a custom password
- **WHEN** the kit was installed with a non-default `--password`
- **THEN** the series still arrive

#### Scenario: Scrape job removed on stop
- **WHEN** the kit stops
- **THEN** its scrape job is removed from the collector config

### Requirement: SingleStore dashboards

The kit SHALL ship two dashboards against Mimir: a cluster overview (queries, connections, memory, disk, replication) and a leaf and partition view. They SHALL follow the tenant picker conventions in `dashboards/CLAUDE.md`.

#### Scenario: Dashboards install and show data
- **WHEN** the kit starts
- **THEN** both dashboards install into a `singlestore` Grafana folder
- **AND** every panel shows data during a sysbench run
- **AND** the datasource pickers resolve to the cluster's tenant

#### Scenario: Dashboard conventions test
- **WHEN** the unit tests run
- **THEN** `DashboardDatasourceVariablesTest` passes for both dashboards

### Requirement: SingleStore stop and restart

`stop` SHALL delete the `MemsqlCluster`, the NodePort Service, and the PVCs selected by `app.kubernetes.io/instance=singlestore`.

#### Scenario: Stop removes the cluster
- **WHEN** `stop` runs
- **THEN** the `MemsqlCluster`, the NodePort Service, and the cluster's PVCs are gone

#### Scenario: Start after stop is fresh
- **WHEN** `start` runs again after `stop`
- **THEN** a fresh, empty cluster comes up with no stale-pod or PVC conflict

### Requirement: SingleStore uninstall

`uninstall` SHALL delete any leftover `MemsqlCluster` and then the operator Deployment, its RBAC, and the CRD.

#### Scenario: Uninstall while running
- **WHEN** `uninstall singlestore` runs while the cluster runs
- **THEN** the cluster is stopped first
- **AND** then the operator, its RBAC, and the CRD are removed

#### Scenario: Uninstall after stop
- **WHEN** `uninstall singlestore` runs after `stop`
- **THEN** the operator, its RBAC, and the CRD are removed

### Requirement: SingleStore kit pods are labeled

Every pod a SingleStore kit script creates SHALL have a unique timestamped name and the `easydblab/kit` and `easydblab/role` labels. Cleanup SHALL delete by label.

#### Scenario: Script pods carry labels
- **WHEN** a kit script creates a pod
- **THEN** the pod name ends with a timestamp
- **AND** the pod carries `easydblab/kit` and `easydblab/role`

### Requirement: SingleStore user guide

`docs/user-guide/singlestore.md` SHALL cover how to get a license key, every arg, the topology, the redundancy rule, the smallest db instance, how to connect, sysbench use, unlimited storage, and the license unit formula. `docs/SUMMARY.md` SHALL link it.

#### Scenario: User guide is linked
- **WHEN** the docs build
- **THEN** `docs/SUMMARY.md` links `docs/user-guide/singlestore.md`
- **AND** the page covers every topic above
