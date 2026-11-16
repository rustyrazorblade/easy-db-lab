## MODIFIED Requirements

### Requirement: No observability backend deletes data automatically

No backend, setting, or default SHALL delete stored observability data.  Compaction SHALL be allowed only where it writes a merged copy holding all of its sources' data before it removes them; retention and expiry SHALL stay off everywhere.

- Mimir on a cluster SHALL run with no compactor module, so no compaction, retention, cleanup or tenant-deletion path exists in the cluster's own Mimir.
- Loki on a cluster SHALL run with retention disabled, deletion mode `disabled`, and a compaction interval so long that compaction never runs.
- Tempo on a cluster SHALL run with compaction and retention disabled for every tenant, and SHALL also set `block_retention` to `876000h`, so its own backend worker never deletes a block by age even if the compaction override is lost.
- The Pyroscope server SHALL run pure v2 storage with the metastore retention cleanup disabled and no retention period.  Pyroscope v2 compaction MAY merge segments into blocks, because the merged block keeps their data.
- The account compactor, which runs in its own `account-compactor` pod on every cluster and works only while it holds the store lease, SHALL compact Mimir, Loki and Tempo data with retention and every other deletion path off, as the `account-compactor` capability specifies.

#### Scenario: Mimir has no deletion path in the cluster
- **WHEN** Mimir runs on a cluster
- **THEN** its module list contains no compactor
- **AND** no Mimir block, bucket index, or tenant is deleted or marked for deletion by the cluster's own Mimir

#### Scenario: Loki in the cluster never compacts or deletes
- **WHEN** Loki runs on a cluster for longer than any former retention period
- **THEN** no compaction and no retention run in the cluster's own Loki
- **AND** a delete request is refused

#### Scenario: Tempo in the cluster keeps every block
- **WHEN** Tempo runs on a cluster for longer than any former retention period
- **THEN** its configuration disables compaction and retention for every tenant, and its `block_retention` is `876000h`
- **AND** no Tempo block is deleted by the cluster's own Tempo

#### Scenario: Pyroscope keeps every profile
- **WHEN** the Pyroscope server runs
- **THEN** it uses v2 storage with retention cleanup disabled
- **AND** no profile is deleted by age

#### Scenario: The account compactor runs with retention off
- **WHEN** the account compactor runs
- **THEN** no retention setting is on in Mimir, Loki or Tempo
- **AND** every sample, log line and trace is still queryable after compaction

### Requirement: The cluster cannot delete observability objects

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `grafana/` and `reports/` in the account bucket.  It SHALL NOT deny deletes under `mimir/`, `loki/` and `tempo/`, because the account compactor runs on the cluster with the instance role and removes the sources it merged.  The account bucket policy SHALL carry one Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/` and `tempo/` for the EMR service role and the EMR EC2 role.  `up` SHALL re-apply both every time.  The `pyroscope/` root SHALL be excluded, because Pyroscope v2 compaction runs in the cluster and removes the segments it merged.  Under `mimir/`, `loki/` and `tempo/`, only the account compactor SHALL delete, and only while it holds the store lease; the cluster's own backends SHALL have no deletion path, as the requirement "No observability backend deletes data automatically" specifies.  Deletes made by the owner from a workstation SHALL NOT be affected, so `report upload` can still replace a document.

#### Scenario: A Grafana delete is denied
- **WHEN** any process on a cluster tries to delete an object under `grafana/`
- **THEN** S3 refuses the delete and the object is kept

#### Scenario: A document delete is denied
- **WHEN** the cluster IAM role tries to delete an object under `reports/`
- **THEN** S3 denies it and the object is kept

#### Scenario: EMR cannot delete compacted roots
- **WHEN** an EMR step tries to delete an object under `mimir/`, `loki/` or `tempo/`
- **THEN** S3 refuses the delete and the object is kept

#### Scenario: The lease holder can remove merged sources
- **WHEN** the compactor that holds the lease removes a source object under `mimir/`, `loki/` or `tempo/` after it merged that object's data
- **THEN** S3 accepts the delete

#### Scenario: Pyroscope compaction still works
- **WHEN** Pyroscope v2 merges segments under `pyroscope/`
- **THEN** the merged segments can be removed

#### Scenario: The deny is re-applied on up
- **WHEN** `up` runs
- **THEN** the instance role holds the deny under `grafana/` and `reports/`, and the bucket policy holds the EMR deny under `mimir/`, `loki/` and `tempo/`

### Requirement: Mimir reads the whole shared store

Mimir on every cluster SHALL run the store-gateway and SHALL answer queries from S3 as well as from its ingester, for every tenant in the account bucket.  Its readers SHALL use `querier.query_store_after: 0`, `blocks_storage.bucket_store.sync_interval: 1m`, `blocks_storage.bucket_store.ignore_blocks_within: 0`, and `blocks_storage.bucket_store.bucket_index.max_stale_period: 87600h`.  The ingester SHALL keep local blocks for 2 hours (`blocks_storage.tsdb.retention_period: 2h`); older blocks SHALL be read from S3.  Mimir on a cluster SHALL run no compactor of its own; the account compactor runs in its own pod.

#### Scenario: Stored blocks are queryable
- **WHEN** a tenant's blocks are in `mimir/<tenant>/` and listed in its bucket index
- **THEN** a metrics query on any cluster with that tenant in `X-Scope-OrgID` returns their samples

#### Scenario: Queries succeed while no compactor holds the lease
- **WHEN** no compactor has held the lease for more than 1 hour
- **THEN** metrics queries on every cluster still succeed

#### Scenario: Local blocks are kept for 2 hours
- **WHEN** Mimir's configuration is rendered
- **THEN** its targets include `store-gateway` and exclude `compactor`
- **AND** its local block retention is 2 hours and `query_store_after` is 0
