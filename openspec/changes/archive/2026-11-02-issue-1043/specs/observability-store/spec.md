## ADDED Requirements

### Requirement: The metrics stream reads with the tenant and the cluster

`metrics stream` SHALL send the cluster's tenant in the `X-Scope-OrgID` header on every query.  It SHALL scope every query to the current cluster.

#### Scenario: Stream queries carry the tenant
- **WHEN** `metrics stream` runs for a cluster initialized with `--tenant acme`
- **THEN** every query it sends carries `X-Scope-OrgID: acme`

#### Scenario: Stream ignores other clusters in the tenant
- **WHEN** the tenant also holds series from another cluster
- **THEN** the samples `metrics stream` publishes come only from the current cluster

## MODIFIED Requirements

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
