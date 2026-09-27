## MODIFIED Requirements

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

### Requirement: The cluster cannot delete observability objects

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/` and `grafana/` in the account bucket.  `up` SHALL re-apply it every time.  The `pyroscope/` root SHALL be excluded, because Pyroscope v2 compaction runs in the cluster and removes the segments it merged.  Deletes made by the owner from a workstation SHALL NOT be affected.

#### Scenario: A backend delete is denied
- **WHEN** any process on a cluster tries to delete an object under `mimir/`, `loki/`, `tempo/` or `grafana/`
- **THEN** S3 refuses the delete and the object is kept

#### Scenario: Pyroscope compaction still works
- **WHEN** Pyroscope v2 merges segments under `pyroscope/`
- **THEN** the merged segments can be removed

#### Scenario: The deny is re-applied on up
- **WHEN** `up` runs
- **THEN** the instance role holds the deny

### Requirement: Data reaches S3 while the cluster is up and survives a restart

Each backend SHALL upload its data to S3 within minutes while the cluster runs, so other clusters can read it:

- Mimir SHALL cut a block every minute and ship it within seconds: 1-minute blocks, a head compaction check every 15 seconds, a ship check every 15 seconds, and an idle head compacted after 2 minutes without writes.
- Tempo SHALL cut a block at most every minute and upload it.
- Loki SHALL flush a chunk when it is 5 minutes old, upload its index on its own fixed rotation, and re-list the index in S3 every minute.

The write-ahead data of Mimir, Loki and Tempo, and the Pyroscope metastore state, SHALL live on the control node's disk, so a restart of a backend pod does not lose data it acknowledged.

#### Scenario: Metric blocks reach S3 within minutes
- **WHEN** a cluster writes a metric sample
- **THEN** a Mimir block that holds it exists under `mimir/<tenant>/` within about 3 minutes, while the cluster is still up

#### Scenario: Trace blocks reach S3 within minutes
- **WHEN** a cluster receives a span
- **THEN** a Tempo block that holds it exists under `tempo/<tenant>/` within about 2 minutes, while the cluster is still up

#### Scenario: Log chunks reach S3 within minutes
- **WHEN** a cluster writes a log line to a stream that keeps writing
- **THEN** a Loki chunk that holds it exists under `loki/<tenant>/` within about 5 minutes, while the cluster is still up
- **AND** its index file reaches `loki/index/` at Loki's next index rotation

#### Scenario: Restart loses no acknowledged data
- **WHEN** the Mimir, Loki or Tempo pod is restarted while it holds data that is not yet in S3
- **THEN** that data still reaches S3 after the restart

#### Scenario: Profiles stay queryable after a restart
- **WHEN** the Pyroscope pod is restarted
- **THEN** profiles written before the restart are still returned by queries
