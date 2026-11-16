## ADDED Requirements

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

## MODIFIED Requirements

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

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/` and `grafana/` in the account bucket.  The account bucket policy SHALL carry one Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/` and `tempo/` for every principal it grants access to: the EC2 instance role, the EMR service role and the EMR EC2 role.  `up` SHALL re-apply both every time.  The `pyroscope/` root SHALL be excluded, because Pyroscope v2 compaction runs in the cluster and removes the segments it merged.  Only the account compactor's task role SHALL delete under `mimir/`, `loki/` and `tempo/`.  Deletes made by the owner from a workstation SHALL NOT be affected.

#### Scenario: A backend delete is denied
- **WHEN** any process on a cluster tries to delete an object under `mimir/`, `loki/`, `tempo/` or `grafana/`
- **THEN** S3 refuses the delete and the object is kept

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

## REMOVED Requirements

### Requirement: Mimir reads only the cluster's own data

**Reason**: Every cluster now reads the whole shared store through Mimir's store-gateway, and the ingester keeps local blocks for 2 hours instead of the cluster's life.

**Migration**: Replaced by "Mimir reads the whole shared store".  Clusters are ephemeral; nothing migrates.
