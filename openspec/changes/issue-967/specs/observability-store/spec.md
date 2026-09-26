## ADDED Requirements

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

Mimir, Loki, Tempo and the Pyroscope server SHALL run with multi-tenancy enabled.  The storage tenant is the cluster's tenant.  Every writer SHALL send the tenant in the `X-Scope-OrgID` header: the OTel collector's metrics, logs and trace exporters, Tempo's metrics generator, the Alloy eBPF profiler, the Pyroscope Java agent in stress jobs, the Cassandra sidecar, EMR Spark, the Trino and Presto kits, the Cassandra JFR shipper, and the annotation mirror.  This SHALL hold on redirected clusters too.  Every reader SHALL send the tenant: the Grafana datasources, `logs query`, `spark logs`, the EMR step log lookup, the MCP metrics collector, `status`, and the teardown flush.  Mimir SHALL allow queries across tenants (tenant federation) and Loki SHALL allow queries across tenants (`multi_tenant_queries_enabled`), with the tenants listed as `a|b`.

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

- Mimir's backend SHALL use the storage prefix `observabilitymetrics` (Mimir accepts only letters and digits in the prefix); Mimir lays out one directory per tenant under it.
- Loki's backend SHALL use the object prefix `observability/logs`; Loki lays out its chunks per tenant and its index tables under it.
- Tempo's backend SHALL use the prefix `observability/traces`; Tempo lays out one directory per tenant under it.
- The Pyroscope server's backend SHALL use the prefix `observability/profiles`; Pyroscope lays out its own directories under it.

#### Scenario: Metric blocks location
- **WHEN** Mimir on a cluster in tenant `acme` ships a block
- **THEN** the block lands under `observabilitymetrics/acme/`

#### Scenario: Log chunks and index location
- **WHEN** Loki on a cluster in tenant `acme` flushes chunks and uploads its index
- **THEN** the chunks land under `observability/logs/acme/`
- **AND** the index files land under `observability/logs/index/`

#### Scenario: Trace blocks location
- **WHEN** Tempo on a cluster in tenant `acme` writes a block
- **THEN** the block lands under `observability/traces/acme/`

#### Scenario: Profiles location
- **WHEN** the Pyroscope server writes profile data
- **THEN** the data lands under `observability/profiles/` in the account bucket
- **AND** nothing is written to the cluster's data bucket

#### Scenario: Default tenant location
- **WHEN** a cluster initialized without `--tenant` ships metrics
- **THEN** the blocks land under `observabilitymetrics/default/`

#### Scenario: No snapshot backups are written
- **WHEN** `down` runs on a cluster
- **THEN** no VictoriaMetrics or VictoriaLogs snapshot is taken or uploaded

### Requirement: Clusters in one tenant never overwrite each other

Clusters that share a tenant SHALL never overwrite each other's data.  Block, chunk and index file names SHALL be unique per cluster.  Each Loki ingester SHALL be named `<tenant>.<name>-<id>`, so every Loki index file name carries its tenant and cluster.

#### Scenario: Two clusters write every signal
- **WHEN** two clusters with the same tenant write metrics, logs, traces and profiles at the same time
- **THEN** every block, chunk and index file from each cluster is kept

#### Scenario: Loki index files name their cluster
- **WHEN** Loki on cluster `lab-<id>` in tenant `acme` uploads an index file
- **THEN** the file name carries `acme.lab-<id>`

### Requirement: Mimir reads only the cluster's own data

Mimir on a cluster SHALL write every block to S3 and SHALL answer queries only from its own ingester: the in-memory head and the local blocks, which it SHALL keep for the cluster's life.  Mimir SHALL run no compactor and no store-gateway, and SHALL NOT query the S3 block store.  Reading metrics across clusters or from past clusters is not part of a running cluster.

#### Scenario: Local blocks stay queryable
- **WHEN** a cluster has run long enough for Mimir to cut and ship several blocks
- **THEN** queries return the samples in every block the cluster wrote, from local disk

#### Scenario: No block store is queried
- **WHEN** Mimir answers a query
- **THEN** it does not read the S3 block store and needs no bucket index

### Requirement: No observability backend deletes data automatically

No backend, setting, or default SHALL delete stored observability data.

- Mimir SHALL run with no compactor module, so no compaction, retention, cleanup or tenant-deletion path exists in the cluster.
- Loki SHALL run with retention disabled, deletion mode `disabled`, and a compaction interval so long that compaction never runs.
- Tempo SHALL run with compaction and retention disabled for every tenant.
- The Pyroscope server SHALL run pure v2 storage with the metastore retention cleanup disabled and no retention period.  Pyroscope v2 compaction MAY merge segments into blocks, because the merged block keeps their data.

#### Scenario: Mimir has no deletion path
- **WHEN** Mimir runs on a cluster
- **THEN** its module list contains no compactor and no store-gateway
- **AND** no Mimir block, bucket index, or tenant is deleted or marked for deletion

#### Scenario: Loki never compacts or deletes
- **WHEN** Loki runs on a cluster for longer than any former retention period
- **THEN** no compaction and no retention run
- **AND** a delete request is refused

#### Scenario: Tempo keeps every block
- **WHEN** Tempo runs on a cluster for longer than any former retention period
- **THEN** its configuration disables compaction and retention for every tenant
- **AND** no Tempo block is deleted

#### Scenario: Pyroscope keeps every profile
- **WHEN** the Pyroscope server runs
- **THEN** it uses v2 storage with retention cleanup disabled
- **AND** no profile is deleted by age

### Requirement: The cluster cannot delete observability objects

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` on the metrics, logs, traces and annotations prefixes in the account bucket.  `up` SHALL re-apply it every time.  The profiles prefix SHALL be excluded, because Pyroscope v2 compaction deletes the segments it merged.  Deletes made by the owner from a workstation SHALL NOT be affected.

#### Scenario: A backend delete is denied
- **WHEN** any process on a cluster tries to delete an object under `observabilitymetrics/`, `observability/logs/`, `observability/traces/` or `observability/annotations/`
- **THEN** S3 refuses the delete and the object is kept

#### Scenario: Pyroscope compaction still works
- **WHEN** Pyroscope v2 merges segments under `observability/profiles/`
- **THEN** the merged segments can be removed

#### Scenario: The deny is re-applied on up
- **WHEN** `up` runs
- **THEN** the instance role holds the deny

### Requirement: Data reaches S3 while the cluster is up and survives a restart

Mimir SHALL ship each block to S3 within about a minute of cutting it, with 2-hour blocks.  Loki SHALL flush chunks by their age and upload its index while the cluster runs.  Tempo SHALL cut a block at most every 5 minutes and upload it.  The write-ahead data of Mimir, Loki and Tempo, and the Pyroscope metastore state, SHALL live on the control node's disk, so a restart of a backend pod does not lose data it acknowledged.

#### Scenario: Metric blocks exist while the cluster is up
- **WHEN** a cluster runs for longer than one Mimir block period
- **THEN** Mimir blocks exist under `observabilitymetrics/<tenant>/` while the cluster is still up

#### Scenario: Log chunks exist while the cluster is up
- **WHEN** a cluster runs and Loki flushes a chunk
- **THEN** Loki chunks and index files for the cluster's tenant exist under `observability/logs/` while the cluster is still up

#### Scenario: Trace blocks exist while the cluster is up
- **WHEN** a cluster receives traces for longer than 5 minutes
- **THEN** Tempo blocks exist under `observability/traces/<tenant>/` while the cluster is still up

#### Scenario: Restart loses no acknowledged data
- **WHEN** the Mimir, Loki or Tempo pod is restarted while it holds data that is not yet in S3
- **THEN** that data still reaches S3 after the restart

#### Scenario: Profiles stay queryable after a restart
- **WHEN** the Pyroscope pod is restarted
- **THEN** profiles written before the restart are still returned by queries

#### Scenario: Cause of the missing Tempo blocks is recorded
- **WHEN** the cause of the missing Tempo blocks is investigated on a real cluster
- **THEN** the finding is recorded on issue 967
- **AND** a test covers the fix

### Requirement: Every series and every log line carries its cluster

Every metric series and every log line SHALL carry `cluster=<name>-<id>`.  Loki SHALL index `cluster`, `host.name`, `node_role` and `source` as labels.  Readers the tool runs (`logs query`, `spark logs`, the EMR step log lookup and the MCP metrics collector) SHALL scope their queries to the current cluster by default.  `logs query -q` SHALL pass raw LogQL through unchanged.

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

Every image the observability stack runs SHALL be pinned to a released version.  No observability image SHALL use `latest`.  The versions SHALL be: Mimir 3.2.1, Loki 3.7.8, Pyroscope 2.3.1, Tempo 3.0.3, Grafana 13.2.2, Grafana image renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0, Fluent Bit 5.1.2, and OTel collector contrib 0.161.0 (cluster, stress-job sidecar, and EMR).  No VictoriaMetrics, VictoriaLogs, vmbackup or AWS CLI image SHALL be deployed.

#### Scenario: Stack runs the pinned versions
- **WHEN** the observability stack starts
- **THEN** every image runs the version listed in this requirement
- **AND** no image reference uses `latest`

#### Scenario: Collector errors are not hidden
- **WHEN** a `transform` processor statement fails in the collector
- **THEN** the error propagates instead of being silently ignored

#### Scenario: Log queries work on the pinned Loki
- **WHEN** a dashboard panel, dashboard variable, annotation query, or CLI command runs a LogQL query against Loki 3.7.8
- **THEN** the query parses without a syntax error

#### Scenario: Metric queries work on the pinned Mimir
- **WHEN** a dashboard panel or CLI command runs a PromQL query against Mimir 3.2.1
- **THEN** the query parses without a syntax error

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
