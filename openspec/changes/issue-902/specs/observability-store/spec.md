## ADDED Requirements

### Requirement: Every cluster belongs to an experiment

The system SHALL let the operator choose an experiment at `init` with `--experiment <name>`.  An experiment is one test; it groups one or more clusters, and every cluster of an experiment is in one tenant.  An experiment SHALL be identified by its tenant and its name together: names are unique within a tenant, and the same name in two tenants is two experiments.  With no `--experiment`, the experiment SHALL be the cluster's `<name>-<id>`.  `init` SHALL store the resolved experiment in the cluster's init configuration, next to the tenant, and it SHALL NOT change after `init`.  An experiment name SHALL match `^[a-z0-9][a-z0-9_-]{0,79}$`, so a name may start with a digit and a `YYYY-MM-DD` prefix sorts experiments by date.  `init` SHALL make no S3 call and no check against other tenants for the experiment.  A cluster state written before experiments existed SHALL read its experiment as its `<name>-<id>`.

#### Scenario: Experiment chosen at init
- **WHEN** a user runs `init --experiment 2026-10-13-ab`
- **THEN** the cluster state holds the experiment `2026-10-13-ab` next to the tenant

#### Scenario: Default experiment
- **WHEN** a user runs `init` with no `--experiment` for a cluster named `lab`
- **THEN** the cluster state holds the experiment `lab-<id>`, the cluster's own `<name>-<id>`

#### Scenario: Invalid experiment name is refused
- **WHEN** a user runs `init --experiment` with a name that breaks `^[a-z0-9][a-z0-9_-]{0,79}$`, such as `AB-test`, `-ab`, `a/b`, or a name of 81 characters
- **THEN** `init` fails with an error that states the rule
- **AND** no file, state or infrastructure is created

#### Scenario: A name that starts with a digit is accepted
- **WHEN** a user runs `init --experiment 2026-10-13-ab`
- **THEN** `init` accepts the name

#### Scenario: init makes no cross-tenant check
- **WHEN** a user runs `init --tenant acme --experiment e1` and tenant `default` already has an experiment `e1`
- **THEN** `init` succeeds with no S3 call for the experiment
- **AND** `acme/e1` and `default/e1` are two experiments

#### Scenario: State without an experiment
- **WHEN** a cluster state written before experiments existed is loaded
- **THEN** its experiment is the cluster's `<name>-<id>`

## MODIFIED Requirements

### Requirement: Cluster names are validated

A cluster name SHALL match `^[a-z][a-z0-9_-]{0,39}$`.  `init` SHALL refuse any other name before creating infrastructure, and loading a cluster state whose name breaks the rule SHALL fail with an error that names the rule.  The name flows into configuration files, file names, S3 keys, metric labels, AWS resource names and Kubernetes names, so it SHALL NOT contain characters that break any of them.  A name MAY hold `_`, so a cluster can be named for the variant it tests, such as `unthrottled_cursor`.  Every name derived from the cluster name that does not accept `_` SHALL convert it; today that is the OpenSearch domain name.

#### Scenario: Invalid cluster name is refused at init
- **WHEN** a user runs `init` with a cluster name such as `My/Cluster`, `db:1` or `_lab`
- **THEN** `init` fails before any infrastructure is created, with an error that names the rule

#### Scenario: Invalid cluster name in state is refused at load
- **WHEN** a cluster state holds a name that breaks the rule
- **THEN** loading the state fails with an error that names the rule

#### Scenario: A cluster name with an underscore is accepted
- **WHEN** a user runs `init` with the cluster name `unthrottled_cursor`
- **THEN** `init` succeeds
- **AND** every AWS and Kubernetes name derived from the cluster name is valid

### Requirement: Producers read the tenant from the cluster configuration

The tenant and the experiment SHALL be published in the `cluster-config` ConfigMap, next to the cluster's name and S3 settings, under the lowercase keys `tenant` and `experiment`.  Workloads SHALL read them through the environment variables `TENANT` and `EXPERIMENT`.  The OTel collector, Alloy, Tempo, Mimir and Loki SHALL read them from there.  Producers that run outside Kubernetes (the Cassandra JFR shipper, the Java agents, EMR, and the Trino and Presto start scripts) SHALL receive the same values through their existing configuration path.

#### Scenario: One source for the tenant
- **WHEN** the observability stack is deployed on a cluster in tenant `acme`
- **THEN** the `cluster-config` ConfigMap holds `acme`
- **AND** the collector, Alloy, Tempo, Mimir and Loki send `acme` without the value being written into their configuration files

#### Scenario: The experiment is published after up
- **WHEN** a cluster initialized with `--experiment 2026-10-13-ab` runs `up`
- **THEN** the `cluster-config` ConfigMap holds the key `experiment` with the value `2026-10-13-ab`
- **AND** a workload that reads the ConfigMap sees `EXPERIMENT=2026-10-13-ab`

### Requirement: Every series and every log line carries its cluster

Every metric series, log line, span, eBPF profile, JFR profile and mirrored annotation that a cluster produces SHALL carry `cluster=<name>-<id>`, `experiment=<its experiment>` and `edl_tenant=<its tenant>`.  Spans SHALL carry them as the resource attributes `cluster`, `experiment` and `edl_tenant`.  Every place that sets `cluster` SHALL also set `experiment` and `edl_tenant`: the OTel collector's scrape relabels and resource processor, Alloy, the stress sidecar, the Cassandra sidecar's Java agent, EMR, the kit start scripts, the JFR shipper, Tempo's metrics generator, Beyla, and the annotation mirror.  A unit test SHALL walk every registered place that sets `cluster` and SHALL fail when one does not set both labels; it SHALL also scan the source for patterns that set `cluster` and SHALL fail on a hit in a place it has not registered.  Loki SHALL index `cluster`, `experiment`, `edl_tenant`, `host.name`, `node_role` and `source` as labels.  Readers the tool runs (`logs query`, `spark logs`, the EMR step log lookup and the MCP metrics collector) SHALL scope their queries to the current cluster by default.  `logs query -q` SHALL pass raw LogQL through unchanged.

#### Scenario: Metrics carry the cluster
- **WHEN** the OTel collector exports metrics
- **THEN** Mimir stores every series with a `cluster=<name>-<id>` label

#### Scenario: Logs carry the cluster
- **WHEN** the OTel collector or Fluent Bit emits a log line
- **THEN** Loki stores it with a `cluster=<name>-<id>` label

#### Scenario: Every signal carries the experiment and the tenant
- **WHEN** a cluster in tenant `acme` with experiment `e1` is up and produces metrics, logs, spans, eBPF profiles, JFR profiles and mirrored annotations
- **THEN** each of them carries `experiment=e1` and `edl_tenant=acme` next to `cluster`

#### Scenario: Loki indexes the experiment and the tenant
- **WHEN** Loki's configuration is rendered
- **THEN** its index labels are `cluster`, `experiment`, `edl_tenant`, `host.name`, `node_role` and `source`

#### Scenario: A registered cluster setter without the experiment fails the test
- **WHEN** the collector config, Alloy, the stress sidecar config, a kit start script, the JFR shipper or the annotation mirror sets `cluster` and does not set `experiment` or `edl_tenant`
- **THEN** the unit test fails and names the place

#### Scenario: A new pipeline that sets only cluster fails the test
- **WHEN** a new file sets `cluster` and is not registered with the test
- **THEN** the unit test fails with "unregistered cluster setter" and names the file

#### Scenario: Readers default to the current cluster
- **WHEN** a user runs `logs query` without `-q` on a tenant shared with other clusters
- **THEN** only the current cluster's lines are returned

#### Scenario: logs query options work on Loki
- **WHEN** an operator runs `logs query` with `--source`, `--host`, `--unit`, `--grep`, `--since`, or `--limit`
- **THEN** it returns the matching lines from Loki

#### Scenario: A raw query is LogQL
- **WHEN** an operator passes a raw query with `logs query -q`
- **THEN** the query is sent to Loki as LogQL, unchanged

#### Scenario: Spark and EMR step logs come from Loki
- **WHEN** `spark logs` runs or a failed EMR step looks up its logs
- **THEN** the lines come from Loki, scoped to the current cluster

### Requirement: Clusters in one tenant never overwrite each other

Clusters that share a tenant SHALL never overwrite each other's data.  Block, chunk and index file names SHALL be unique per cluster.  Each Loki ingester SHALL be named `<tenant>.<name>-<id>`, so every Loki index file name carries its tenant and cluster.  A cluster name that holds `_` SHALL give a valid ingester name.

#### Scenario: Two clusters write every signal
- **WHEN** two clusters with the same tenant write metrics, logs, traces and profiles at the same time
- **THEN** every block, chunk and index file from each cluster is kept

#### Scenario: Loki index files name their cluster
- **WHEN** Loki on cluster `lab-<id>` in tenant `acme` uploads an index file
- **THEN** the file name carries `acme.lab-<id>`

#### Scenario: An ingester name with an underscore works
- **WHEN** Loki runs with the ingester name `acme.unthrottled_cursor-<id>` and receives log lines
- **THEN** it accepts the lines, flushes them, and uploads an index file whose name carries `acme.unthrottled_cursor-<id>`

### Requirement: Observability data lands in one layout in the account bucket

All observability data of a cluster SHALL land in the account bucket.  Nothing SHALL be written to the per-cluster data bucket by the observability stack.  Cluster configuration SHALL stay under `clusters/<name>-<id>/config/`.  No metrics, logs or annotations snapshot backup SHALL be written: metrics and logs are stored in S3 by their backends.

Each backend's root SHALL sit at the top level of the account bucket and SHALL be named for the tool whose file format it holds:

- Mimir's backend SHALL use the storage prefix `mimir`; Mimir lays out one directory per tenant under it.
- Loki's backend SHALL use the object prefix `loki`; Loki lays out its chunks per tenant and its index tables under `loki/index/`.
- Tempo's backend SHALL use the prefix `tempo`; Tempo lays out one directory per tenant under it.
- The Pyroscope server's backend SHALL use the prefix `pyroscope`; Pyroscope lays out its own directories under it.
- The Grafana annotations backup SHALL use `grafana/annotations/<tenant>/`.
- The reports of an experiment SHALL use `reports/<tenant>/<experiment>/`.  A cluster from before experiments existed keeps its documents at `reports/<tenant>/<name>-<id>/`, which is the folder of its experiment of one.

No code, configuration or document SHALL name the former `observability` or `observabilitymetrics` prefixes.

#### Scenario: Metric blocks location
- **WHEN** Mimir on a cluster in tenant `acme` ships a block
- **THEN** the block lands under `mimir/acme/`

#### Scenario: Log chunks and index location
- **WHEN** Loki on a cluster in tenant `acme` flushes chunks and uploads its index
- **THEN** the chunks land under `loki/acme/`
- **AND** the index files land under `loki/index/`

#### Scenario: Trace blocks location
- **WHEN** Tempo on a cluster in tenant `acme` writes a block
- **THEN** the block lands under `tempo/acme/`

#### Scenario: Profiles location
- **WHEN** the Pyroscope server writes profile data
- **THEN** the data lands under `pyroscope/` in the account bucket
- **AND** nothing is written to the cluster's data bucket

#### Scenario: Annotations backup location
- **WHEN** `down` or `grafana backup` backs up the annotations of a cluster in tenant `acme`
- **THEN** the backup lands under `grafana/annotations/acme/`

#### Scenario: Reports location
- **WHEN** `report upload` runs for experiment `e1` in tenant `acme`
- **THEN** the report lands under `reports/acme/e1/`

#### Scenario: Default tenant location
- **WHEN** a cluster initialized without `--tenant` ships metrics
- **THEN** the blocks land under `mimir/default/`

#### Scenario: No snapshot backups are written
- **WHEN** `down` runs on a cluster
- **THEN** no VictoriaMetrics or VictoriaLogs snapshot is taken or uploaded

#### Scenario: The former prefixes are gone
- **WHEN** the source tree, the rendered configuration and the docs are searched
- **THEN** neither `observabilitymetrics` nor an `observability/` S3 prefix appears

### Requirement: The cluster cannot delete observability objects

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/`, `grafana/` and `reports/` in the account bucket.  The account bucket policy SHALL carry one Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/` and `tempo/` for every principal it grants access to: the EC2 instance role, the EMR service role and the EMR EC2 role.  `up` SHALL re-apply both every time.  The `pyroscope/` root SHALL be excluded, because Pyroscope v2 compaction runs in the cluster and removes the segments it merged.  Only the account compactor's task role SHALL delete under `mimir/`, `loki/` and `tempo/`.  Deletes made by the owner from a workstation SHALL NOT be affected.  No command of the tool SHALL overwrite or delete a report zip; the report pointer is the only object under `reports/` that is ever rewritten.

#### Scenario: A backend delete is denied
- **WHEN** any process on a cluster tries to delete an object under `mimir/`, `loki/`, `tempo/` or `grafana/`
- **THEN** S3 refuses the delete and the object is kept

#### Scenario: A document delete is denied
- **WHEN** the cluster IAM role tries to delete an object under `reports/`
- **THEN** S3 denies it and the object is kept

#### Scenario: EMR cannot delete compacted roots
- **WHEN** an EMR step tries to delete an object under `mimir/`, `loki/` or `tempo/`
- **THEN** S3 refuses the delete and the object is kept

#### Scenario: Only the compactor deletes while a cluster runs
- **WHEN** a cluster runs
- **THEN** only the compactor's task role can delete under `mimir/`, `loki/` and `tempo/`

#### Scenario: Pyroscope compaction still works
- **WHEN** Pyroscope v2 merges segments under `pyroscope/`
- **THEN** the merged segments can be removed

#### Scenario: The deny is re-applied on up
- **WHEN** `up` runs
- **THEN** the instance role and the bucket policy hold the deny

#### Scenario: A report upload never replaces a report
- **WHEN** an operator runs `report upload` twice for one experiment
- **THEN** both report zips are kept unchanged

### Requirement: Mimir reads the whole shared store

Mimir on every cluster SHALL run the store-gateway and SHALL answer queries from S3 as well as from its ingester, for every tenant in the account bucket.  Its readers SHALL use `querier.query_store_after: 0`, `blocks_storage.bucket_store.sync_interval: 1m`, `blocks_storage.bucket_store.ignore_blocks_within: 0`, and `blocks_storage.bucket_store.bucket_index.max_stale_period: 87600h`.  The ingester SHALL keep local blocks for 15 minutes (`blocks_storage.tsdb.retention_period: 15m`); older blocks SHALL be read from S3.  The store-gateway's index, chunks and metadata caches and the query-frontend's results cache SHALL use the memcached sidecar in the Mimir pod.  Query sharding SHALL be off.  Mimir on a cluster SHALL run no compactor.

#### Scenario: Stored blocks are queryable
- **WHEN** a tenant's blocks are in `mimir/<tenant>/` and listed in its bucket index
- **THEN** a metrics query on any cluster with that tenant in `X-Scope-OrgID` returns their samples

#### Scenario: Queries succeed while the compactor is stopped
- **WHEN** the compactor service has been stopped for more than 1 hour
- **THEN** metrics queries on every cluster still succeed

#### Scenario: Local blocks are kept for 15 minutes
- **WHEN** Mimir's configuration is rendered
- **THEN** its targets include `store-gateway` and exclude `compactor`
- **AND** its local block retention is 15 minutes and `query_store_after` is 0

#### Scenario: Reads are cached in memcached
- **WHEN** Mimir's configuration is rendered
- **THEN** the index, chunks, metadata and results caches name the memcached sidecar
- **AND** query sharding is off
