# observability-store Specification

## Purpose
TBD - created by archiving change issue-967. Update Purpose after archive.
## Requirements
### Requirement: Every cluster belongs to a tenant

The system SHALL let the operator choose a tenant at `init` with `--tenant <name>`.  The tenant is the customer; several clusters may share it.  The default tenant SHALL be `default`.  The tenant SHALL be stored in the cluster's init configuration and SHALL NOT change after `init`.  A cluster state that has no tenant SHALL read as `default`.  A tenant name SHALL match `^[a-z][a-z0-9_-]{0,62}$` and SHALL NOT be `index`, because a Loki tenant named `index` would put its chunks under Loki's index path.

#### Scenario: Tenant chosen at init
- **WHEN** a user runs `init --tenant acme`
- **THEN** the cluster state holds the tenant `acme`

#### Scenario: Default tenant
- **WHEN** a user runs `init` without `--tenant`
- **THEN** the cluster state holds the tenant `default`

#### Scenario: Invalid tenant name is refused
- **WHEN** a user runs `init --tenant` with a name that does not match `^[a-z][a-z0-9_-]{0,62}$`, such as `Acme` or `1acme`
- **THEN** `init` fails before any infrastructure is created, with an error that names the rule

#### Scenario: Reserved tenant name is refused
- **WHEN** a user runs `init --tenant index`
- **THEN** `init` fails before any infrastructure is created, with an error that names the reserved name

#### Scenario: State without a tenant
- **WHEN** a cluster state written before tenants existed is loaded
- **THEN** its tenant is `default`

### Requirement: Cluster names are validated

A cluster name SHALL match `^[a-z][a-z0-9-]{0,39}$`.  `init` SHALL refuse any other name before creating infrastructure, and loading a cluster state whose name breaks the rule SHALL fail with an error that names the rule.  The name flows into configuration files, file names, S3 keys and metric labels, so it SHALL NOT contain characters that break any of them.

#### Scenario: Invalid cluster name is refused at init
- **WHEN** a user runs `init` with a cluster name such as `My/Cluster` or `db:1`
- **THEN** `init` fails before any infrastructure is created, with an error that names the rule

#### Scenario: Invalid cluster name in state is refused at load
- **WHEN** a cluster state holds a name that breaks the rule
- **THEN** loading the state fails with an error that names the rule

### Requirement: Every observability backend runs native multi-tenancy

Mimir, Loki, Tempo and the Pyroscope server SHALL run with multi-tenancy enabled.  The storage tenant is the cluster's tenant.  Every writer SHALL send the tenant in the `X-Scope-OrgID` header: the OTel collector's metrics, logs and trace exporters, Tempo's metrics generator, the Alloy eBPF profiler, the Pyroscope Java agent in stress jobs, the Cassandra sidecar, EMR Spark, the Trino and Presto kits, the Cassandra JFR shipper, and the annotation mirror.  This SHALL hold on redirected clusters too.  Every reader SHALL send the tenant: the Grafana datasources, `logs query`, `spark logs`, the EMR step log lookup, `metrics stream`, `status`, and the teardown flush.  Mimir SHALL allow queries across tenants (tenant federation) and Loki SHALL allow queries across tenants (`multi_tenant_queries_enabled`), with the tenants listed as `a|b`.

#### Scenario: Every writer carries the tenant
- **WHEN** a cluster initialized with `--tenant acme` writes metrics, logs, traces and profiles
- **THEN** every write carries `X-Scope-OrgID: acme`

#### Scenario: Datasources query the cluster's tenant
- **WHEN** a user opens the Mimir, Loki, Tempo or Pyroscope datasource in the cluster's Grafana
- **THEN** the query carries the cluster's tenant and returns that tenant's data

#### Scenario: Redirected clusters send the tenant too
- **WHEN** a redirected cluster sends metrics, logs, traces and profiles to an external stack
- **THEN** each write carries the cluster's tenant in `X-Scope-OrgID`

#### Scenario: Logs are queried across tenants and clusters
- **WHEN** two clusters of tenants `a` and `b` have written logs and a query runs with `X-Scope-OrgID: a|b`
- **THEN** the result holds the lines of both clusters, each marked with its tenant

#### Scenario: Metrics are queried across tenants within one Mimir
- **WHEN** a Mimir holds series of tenants `a` and `b` and a query runs with `X-Scope-OrgID: a|b`
- **THEN** the result holds the series of both tenants, each with a `__tenant_id__` label

#### Scenario: Tempo's metrics generator writes to Mimir
- **WHEN** Tempo's metrics generator has processors enabled
- **THEN** it remote-writes to Mimir with the cluster's tenant

### Requirement: Producers read the tenant from the cluster configuration

The tenant SHALL be published in the `cluster-config` ConfigMap, next to the cluster's name and S3 settings.  The OTel collector, Alloy, Tempo, Mimir and Loki SHALL read it from there through an environment variable.  Producers that run outside Kubernetes (the Cassandra JFR shipper, the Java agents, EMR, and the Trino and Presto start scripts) SHALL receive the same value through their existing configuration path.

#### Scenario: One source for the tenant
- **WHEN** the observability stack is deployed on a cluster in tenant `acme`
- **THEN** the `cluster-config` ConfigMap holds `acme`
- **AND** the collector, Alloy, Tempo, Mimir and Loki send `acme` without the value being written into their configuration files

### Requirement: Observability data lands in one layout in the account bucket

All observability data of a cluster SHALL land in the account bucket.  Nothing SHALL be written to the per-cluster data bucket by the observability stack.  Cluster configuration SHALL stay under `clusters/<name>-<id>/config/`.  No metrics, logs or annotations snapshot backup SHALL be written: metrics and logs are stored in S3 by their backends.

Each backend's root SHALL sit at the top level of the account bucket and SHALL be named for the tool whose file format it holds:

- Mimir's backend SHALL use the storage prefix `mimir`; Mimir lays out one directory per tenant under it.
- Loki's backend SHALL use the object prefix `loki`; Loki lays out its chunks per tenant and its index tables under `loki/index/`.
- Tempo's backend SHALL use the prefix `tempo`; Tempo lays out one directory per tenant under it.
- The Pyroscope server's backend SHALL use the prefix `pyroscope`; Pyroscope lays out its own directories under it.
- The Grafana annotations backup SHALL use `grafana/annotations/<tenant>/`.

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

#### Scenario: Default tenant location
- **WHEN** a cluster initialized without `--tenant` ships metrics
- **THEN** the blocks land under `mimir/default/`

#### Scenario: No snapshot backups are written
- **WHEN** `down` runs on a cluster
- **THEN** no VictoriaMetrics or VictoriaLogs snapshot is taken or uploaded

#### Scenario: The former prefixes are gone
- **WHEN** the source tree, the rendered configuration and the docs are searched
- **THEN** neither `observabilitymetrics` nor an `observability/` S3 prefix appears

### Requirement: Clusters in one tenant never overwrite each other

Clusters that share a tenant SHALL never overwrite each other's data.  Block, chunk and index file names SHALL be unique per cluster.  Each Loki ingester SHALL be named `<tenant>.<name>-<id>`, so every Loki index file name carries its tenant and cluster.

#### Scenario: Two clusters write every signal
- **WHEN** two clusters with the same tenant write metrics, logs, traces and profiles at the same time
- **THEN** every block, chunk and index file from each cluster is kept

#### Scenario: Loki index files name their cluster
- **WHEN** Loki on cluster `lab-<id>` in tenant `acme` uploads an index file
- **THEN** the file name carries `acme.lab-<id>`

### Requirement: No observability backend deletes data automatically

No backend, setting, or default SHALL delete stored observability data.  Compaction SHALL be allowed only where it writes a merged copy holding all of its sources' data before it removes them; retention and expiry SHALL stay off everywhere.

- Mimir on a cluster SHALL run with no compactor module, so no compaction, retention, cleanup or tenant-deletion path exists in the cluster.
- Loki on a cluster SHALL run with retention disabled, deletion mode `disabled`, and a compaction interval so long that compaction never runs.
- Tempo on a cluster SHALL run with compaction and retention disabled for every tenant.
- The Pyroscope server SHALL run pure v2 storage with the metastore retention cleanup disabled and no retention period.  Pyroscope v2 compaction MAY merge segments into blocks, because the merged block keeps their data.
- The account compactor SHALL compact Mimir, Loki and Tempo data with retention and every other deletion path off, as the `account-compactor` capability specifies.

#### Scenario: Mimir has no deletion path in the cluster
- **WHEN** Mimir runs on a cluster
- **THEN** its module list contains no compactor
- **AND** no Mimir block, bucket index, or tenant is deleted or marked for deletion by the cluster

#### Scenario: Loki in the cluster never compacts or deletes
- **WHEN** Loki runs on a cluster for longer than any former retention period
- **THEN** no compaction and no retention run in the cluster
- **AND** a delete request is refused

#### Scenario: Tempo in the cluster keeps every block
- **WHEN** Tempo runs on a cluster for longer than any former retention period
- **THEN** its configuration disables compaction and retention for every tenant
- **AND** no Tempo block is deleted by the cluster

#### Scenario: Pyroscope keeps every profile
- **WHEN** the Pyroscope server runs
- **THEN** it uses v2 storage with retention cleanup disabled
- **AND** no profile is deleted by age

#### Scenario: The account compactor runs with retention off
- **WHEN** the account compactor runs
- **THEN** no retention setting is on in Mimir, Loki or Tempo
- **AND** every sample, log line and trace is still queryable after compaction

### Requirement: The cluster cannot delete observability objects

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/`, `grafana/` and `reports/` in the account bucket.  The account bucket policy SHALL carry one Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/` and `tempo/` for every principal it grants access to: the EC2 instance role, the EMR service role and the EMR EC2 role.  `up` SHALL re-apply both every time.  The `pyroscope/` root SHALL be excluded, because Pyroscope v2 compaction runs in the cluster and removes the segments it merged.  Only the account compactor's task role SHALL delete under `mimir/`, `loki/` and `tempo/`.  Deletes made by the owner from a workstation SHALL NOT be affected, so `report upload` can still replace a document.

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

### Requirement: Data reaches S3 while the cluster is up and survives a restart

Each backend SHALL upload its data to S3 within minutes while the cluster runs, so other clusters can read it:

- Mimir SHALL cut a block every minute and ship it within seconds: 1-minute blocks, a head compaction check every 15 seconds, a ship check every 15 seconds, and an idle head compacted after 2 minutes without writes.
- Tempo SHALL cut a block at most every minute and upload it.
- Loki SHALL flush a chunk when it is 1 hour old, upload its index on its own fixed rotation, and re-list the index in S3 every minute.

The write-ahead data of Mimir, Loki and Tempo, and the Pyroscope metastore state, SHALL live on the control node's disk, so a restart of a backend pod does not lose data it acknowledged.

#### Scenario: Metric blocks reach S3 within minutes
- **WHEN** a cluster writes a metric sample
- **THEN** a Mimir block that holds it exists under `mimir/<tenant>/` within about 3 minutes, while the cluster is still up

#### Scenario: Trace blocks reach S3 within minutes
- **WHEN** a cluster receives a span
- **THEN** a Tempo block that holds it exists under `tempo/<tenant>/` within about 2 minutes, while the cluster is still up

#### Scenario: Log chunks reach S3 within minutes
- **WHEN** a cluster writes a log line to a stream that keeps writing
- **THEN** a Loki chunk that holds it exists under `loki/<tenant>/` within about 1 hour, while the cluster is still up
- **AND** its index file reaches `loki/index/` at Loki's next index rotation

#### Scenario: Restart loses no acknowledged data
- **WHEN** the Mimir, Loki or Tempo pod is restarted while it holds data that is not yet in S3
- **THEN** that data still reaches S3 after the restart

#### Scenario: Profiles stay queryable after a restart
- **WHEN** the Pyroscope pod is restarted
- **THEN** profiles written before the restart are still returned by queries

### Requirement: Every series and every log line carries its cluster

Every metric series and every log line SHALL carry `cluster=<name>-<id>`.  Loki SHALL index `cluster`, `host.name`, `node_role` and `source` as labels.  Readers the tool runs (`logs query`, `spark logs`, the EMR step log lookup and `metrics stream`) SHALL scope their queries to the current cluster by default.  `logs query -q` SHALL pass raw LogQL through unchanged.

#### Scenario: Metrics carry the cluster
- **WHEN** the OTel collector exports metrics
- **THEN** Mimir stores every series with a `cluster=<name>-<id>` label

#### Scenario: Logs carry the cluster
- **WHEN** the OTel collector or Fluent Bit emits a log line
- **THEN** Loki stores it with a `cluster=<name>-<id>` label

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

### Requirement: Observability workloads restart only when their configuration changes

`up` and `grafana update-config` SHALL restart an observability workload only when the configuration it consumes has changed.

#### Scenario: Dashboard change restarts nothing else
- **WHEN** `grafana update-config` runs and only dashboards changed
- **THEN** Mimir, Loki, Tempo and Pyroscope are not restarted

#### Scenario: Config change restarts that workload
- **WHEN** `grafana update-config` runs after a change to Tempo's configuration
- **THEN** Tempo is restarted with the new configuration

### Requirement: The backends' own telemetry is collected

The OTel collector's own telemetry, and the metrics of Mimir, Loki, Tempo and the Pyroscope server, SHALL be scraped into Mimir, so dropped samples, rejected writes and failed flushes are visible.

#### Scenario: Collector export failures are queryable
- **WHEN** the collector fails to send data to a backend
- **THEN** the collector's failure counters are queryable in Mimir, labelled with the cluster

#### Scenario: Backend failures are queryable
- **WHEN** Mimir or Loki rejects or drops data
- **THEN** its own failure counters are queryable in Mimir, labelled with the cluster

### Requirement: Observability images are pinned to released versions

Every image the observability stack runs SHALL be pinned to a released version.  No observability image SHALL use `latest`.  The versions SHALL be: Mimir 3.2.1, Loki 3.7.8, Pyroscope 2.3.1, Tempo 3.0.3, Grafana 13.2.2, Grafana image renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0, Fluent Bit 5.1.2, and OTel collector contrib 0.161.0 (cluster, stress-job sidecar, and EMR).  The `aws-sigv4-proxy` image and the documents web server image in the Grafana pod SHALL each be pinned to a released version.  No VictoriaMetrics, VictoriaLogs, vmbackup or AWS CLI image SHALL be deployed.

#### Scenario: Stack runs the pinned versions
- **WHEN** the observability stack starts
- **THEN** every image runs the version listed in this requirement
- **AND** no image reference uses `latest`

#### Scenario: Documents sidecars are pinned
- **WHEN** the Grafana Deployment is built
- **THEN** the `aws-sigv4-proxy` and documents web server images each name a released version tag

#### Scenario: Collector errors are not hidden
- **WHEN** a `transform` processor statement fails in the collector
- **THEN** the error propagates instead of being silently ignored

#### Scenario: Log queries work on the pinned Loki
- **WHEN** a dashboard panel, dashboard variable, annotation query, or CLI command runs a LogQL query against Loki 3.7.8
- **THEN** the query parses without a syntax error

#### Scenario: Metric queries work on the pinned Mimir
- **WHEN** a dashboard panel or CLI command runs a PromQL query against Mimir 3.2.1
- **THEN** the query parses without a syntax error

#### Scenario: Comparison queries work on the pinned Mimir
- **WHEN** a comparison or Tests dashboard query with a negative `offset`, an `@` modifier or a subquery runs against Mimir 3.2.1
- **THEN** the query parses and returns without an error

#### Scenario: Per-core CPU stays available
- **WHEN** a dashboard queries host CPU time by core
- **THEN** the host metrics carry the per-core label

### Requirement: Grafana reads Mimir and Loki

Grafana SHALL be provisioned with a metrics datasource of type `prometheus` pointing at Mimir with uid `mimir`, and a logs datasource of type `loki` pointing at Loki with uid `loki`, each sending the cluster's tenant.  Dashboards SHALL keep their PromQL.  Every dashboard LogsQL query SHALL be rewritten to LogQL and scoped with `cluster=~"$cluster"`.  The logs datasource SHALL link a log line's `trace_id` to Tempo, and Tempo's trace-to-logs link SHALL query Loki by trace id.

#### Scenario: Metric panels show Mimir data
- **WHEN** an operator opens a core or kit dashboard in Grafana
- **THEN** its PromQL panels show data from Mimir with no change to their queries

#### Scenario: Log panels show Loki data
- **WHEN** an operator opens a dashboard that had LogsQL panels
- **THEN** each panel shows the same logs from Loki, with LogQL

#### Scenario: A log line links to its trace
- **WHEN** a log line carries a `trace_id`
- **THEN** the logs datasource offers a link that opens the trace in Tempo

#### Scenario: A trace links to its logs
- **WHEN** a user follows the logs link from a trace in Tempo
- **THEN** Loki returns the log lines that carry that trace id

### Requirement: VictoriaMetrics and VictoriaLogs are removed

The system SHALL NOT deploy VictoriaMetrics or VictoriaLogs on a cluster.  The commands `metrics backup`, `metrics import`, `metrics ls`, `logs backup`, `logs import` and `logs ls` SHALL NOT exist.  `logs query` SHALL remain with the same options, backed by Loki.  Apart from the telemetry redirect endpoints, the source tree SHALL hold no reference to VictoriaMetrics or VictoriaLogs.

#### Scenario: Removed commands are unknown
- **WHEN** a user runs `metrics backup`, `metrics import`, `metrics ls`, `logs backup`, `logs import` or `logs ls`
- **THEN** the CLI reports an unknown command

#### Scenario: No Victoria workload runs
- **WHEN** a cluster starts
- **THEN** Mimir and Loki run on the control node and no VictoriaMetrics or VictoriaLogs resource exists

#### Scenario: No Victoria references remain
- **WHEN** a search of the source tree runs for VictoriaMetrics or VictoriaLogs
- **THEN** it finds no reference outside the telemetry redirect endpoints

### Requirement: Mimir reads the whole shared store

Mimir on every cluster SHALL run the store-gateway and SHALL answer queries from S3 as well as from its ingester, for every tenant in the account bucket.  Its readers SHALL use `querier.query_store_after: 0`, `blocks_storage.bucket_store.sync_interval: 1m`, `blocks_storage.bucket_store.ignore_blocks_within: 0`, and `blocks_storage.bucket_store.bucket_index.max_stale_period: 87600h`.  The ingester SHALL keep local blocks for 2 hours (`blocks_storage.tsdb.retention_period: 2h`); older blocks SHALL be read from S3.  Mimir on a cluster SHALL run no compactor.

#### Scenario: Stored blocks are queryable
- **WHEN** a tenant's blocks are in `mimir/<tenant>/` and listed in its bucket index
- **THEN** a metrics query on any cluster with that tenant in `X-Scope-OrgID` returns their samples

#### Scenario: Queries succeed while the compactor is stopped
- **WHEN** the compactor service has been stopped for more than 1 hour
- **THEN** metrics queries on every cluster still succeed

#### Scenario: Local blocks are kept for 2 hours
- **WHEN** Mimir's configuration is rendered
- **THEN** its targets include `store-gateway` and exclude `compactor`
- **AND** its local block retention is 2 hours and `query_store_after` is 0

### Requirement: Grafana has a datasource per tenant and one for all tenants

`up` and `grafana update-config` SHALL list the tenant directories under `mimir/` in the account bucket (`ListObjectsV2` with `Delimiter=/`), keep the names that match the tenant name rule, and add the cluster's own tenant.  One pure function SHALL build the datasource set from two inputs: that tenant list (with the cluster's own tenant) and the base URL of each backend.  The cluster SHALL call it with its own backend URLs.

- For metrics, logs and traces, the set SHALL hold one datasource per tenant, with UID `<signal>-<tenant>`, sending that tenant in `X-Scope-OrgID`, and one all-tenants datasource, with UID `<signal>--all`, sending every tenant, sorted and joined with `|`.  A UID longer than 40 characters SHALL be shortened to `<signal>-<prefix>-<first 8 hex characters of the tenant's SHA-256>`.
- The datasources with UIDs `mimir`, `loki` and `tempo` SHALL stay and SHALL send the cluster's own tenant.
- Profiles SHALL have exactly one datasource, UID `pyroscope`, sending the cluster's own tenant.
- A datasource's links to another signal SHALL point at the datasource of that signal for the same tenant view.

#### Scenario: One datasource per tenant and one for all tenants
- **WHEN** `up` runs and `mimir/` holds tenants `default` and `acme`
- **THEN** Grafana has one metrics, one logs and one traces datasource for `default` and for `acme`
- **AND** one all-tenants datasource for each of metrics, logs and traces, sending `acme|default`

#### Scenario: Exactly one profiles datasource
- **WHEN** Grafana starts
- **THEN** it has exactly one profiles datasource

#### Scenario: A new tenant appears on update-config
- **WHEN** a new tenant has a block in `mimir/` and `grafana update-config` runs
- **THEN** Grafana has datasources for that tenant, and the all-tenants datasources include it

#### Scenario: Only the URLs depend on the backend URLs
- **WHEN** the datasource function is called twice with the same tenant list and different backend URLs
- **THEN** the two datasource sets differ only in their URLs

#### Scenario: The stable UIDs read the cluster's own tenant
- **WHEN** a dashboard queries the `mimir`, `loki` or `tempo` datasource on a cluster in tenant `acme`
- **THEN** the query sends `X-Scope-OrgID: acme`

#### Scenario: Directories that are not tenants are ignored
- **WHEN** `mimir/` holds a directory whose name breaks the tenant name rule, such as `__mimir_cluster`
- **THEN** no datasource is made for it

#### Scenario: A long tenant UID is shortened
- **WHEN** a tenant's `<signal>-<tenant>` UID would be longer than 40 characters
- **THEN** its UID is `<signal>-<prefix>-<8 hex characters>` and fits in 40 characters

### Requirement: The metrics stream reads with the tenant and the cluster

`metrics stream` SHALL send the cluster's tenant in the `X-Scope-OrgID` header on every query.  It SHALL scope every query to the current cluster.

#### Scenario: Stream queries carry the tenant
- **WHEN** `metrics stream` runs for a cluster initialized with `--tenant acme`
- **THEN** every query it sends carries `X-Scope-OrgID: acme`

#### Scenario: Stream ignores other clusters in the tenant
- **WHEN** the tenant also holds series from another cluster
- **THEN** the samples `metrics stream` publishes come only from the current cluster

