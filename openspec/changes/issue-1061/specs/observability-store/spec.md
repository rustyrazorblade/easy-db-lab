## ADDED Requirements

### Requirement: The cluster configuration publishes the store with neutral keys

The `cluster-config` ConfigMap SHALL publish the cluster's object store under neutral keys: `store_bucket`, `store_prefix`, `store_region`, `store_endpoint`, `traces_store_prefix`, `metrics_store_prefix` and `logs_store_prefix`.  It SHALL NOT publish `aws_region`, `s3_bucket` or any `*_s3_prefix` key.  Each backend SHALL read these values through environment variables named for the store (`STORE_BUCKET`, `STORE_REGION`, `STORE_ENDPOINT`, `*_STORE_PREFIX`).  On AWS, `store_prefix` SHALL be empty and `store_endpoint` SHALL be `s3.<region>.amazonaws.com`.

#### Scenario: Neutral keys on AWS
- **WHEN** the observability stack is deployed on an AWS cluster in `us-west-2` with account bucket `easy-db-lab-x`
- **THEN** `cluster-config` holds `store_bucket` `easy-db-lab-x`, an empty `store_prefix`, `store_region` `us-west-2` and `store_endpoint` `s3.us-west-2.amazonaws.com`
- **AND** `traces_store_prefix`, `metrics_store_prefix` and `logs_store_prefix` are `tempo`, `mimir` and `loki`

#### Scenario: The former keys are gone
- **WHEN** the source tree, the rendered configuration and the docs are searched
- **THEN** no `cluster-config` key named `aws_region`, `s3_bucket` or `*_s3_prefix` appears

## MODIFIED Requirements

### Requirement: Producers read the tenant from the cluster configuration

The tenant SHALL be published in the `cluster-config` ConfigMap, next to the cluster's name and store settings.  The OTel collector, Alloy, Tempo, Mimir and Loki SHALL read it from there through an environment variable.  Producers that run outside Kubernetes (the Cassandra JFR shipper, the Java agents, EMR, and the Trino and Presto start scripts) SHALL receive the same value through their existing configuration path.

#### Scenario: One source for the tenant
- **WHEN** the observability stack is deployed on a cluster in tenant `acme`
- **THEN** the `cluster-config` ConfigMap holds `acme`
- **AND** the collector, Alloy, Tempo, Mimir and Loki send `acme` without the value being written into their configuration files

### Requirement: Observability data lands in one layout in the account bucket

All observability data of a cluster SHALL land in the account bucket.  Nothing SHALL be written to the per-cluster data bucket by the observability stack.  Cluster configuration SHALL stay under `clusters/<name>-<id>/config/`.  No metrics, logs or annotations snapshot backup SHALL be written: metrics and logs are stored in the object store by their backends.

Every root below SHALL sit under the store prefix.  The store prefix is empty on AWS, so each root sits at the top level of the account bucket there.  Each backend's root SHALL be named for the tool whose file format it holds:

- Mimir's backend SHALL use the storage prefix `mimir`; Mimir lays out one directory per tenant under it.
- Loki's backend SHALL use the object prefix `loki`; Loki lays out its chunks per tenant and its index tables under `loki/index/`.
- Tempo's backend SHALL use the prefix `tempo`; Tempo lays out one directory per tenant under it.
- The Pyroscope server's backend SHALL use the prefix `pyroscope`; Pyroscope lays out its own directories under it.
- The Grafana annotations backup SHALL use `grafana/annotations/<tenant>/`.
- Test documents SHALL use `reports/<tenant>/`.
- Per-cluster files SHALL use `clusters/<name>-<id>/`.

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

#### Scenario: Every root moves under a store prefix
- **WHEN** the store prefix is `team-a`
- **THEN** the backend roots are `team-a/mimir`, `team-a/loki`, `team-a/tempo` and `team-a/pyroscope`
- **AND** the annotation, document and per-cluster paths start with `team-a/grafana/`, `team-a/reports/` and `team-a/clusters/`

#### Scenario: No snapshot backups are written
- **WHEN** `down` runs on a cluster
- **THEN** no VictoriaMetrics or VictoriaLogs snapshot is taken or uploaded

#### Scenario: The former prefixes are gone
- **WHEN** the source tree, the rendered configuration and the docs are searched
- **THEN** neither `observabilitymetrics` nor an `observability/` S3 prefix appears

### Requirement: The cluster cannot delete observability objects

The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/`, `grafana/` and `reports/` in the account bucket.  The account bucket policy SHALL carry one Deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/` and `tempo/` for every principal it grants access to: the EC2 instance role, the EMR service role and the EMR EC2 role.  Each of these denies SHALL also cover the same roots under any store prefix, and SHALL NOT depend on the store prefix of the caller that puts it: every path that puts the instance-role policy or the bucket policy (`up`, `profile setup`, account bucket creation, IAM setup) SHALL put the same deny, so no cluster loses its guard to another one's `up`.  Apart from the deny resources under any prefix, the instance-role policy and the bucket policy SHALL be byte-identical to the documents in use before the store prefix existed.  `up` SHALL re-apply both every time.  The `pyroscope/` root SHALL be excluded, because Pyroscope v2 compaction runs in the cluster and removes the segments it merged.  Only the account compactor's task role SHALL delete under `mimir/`, `loki/` and `tempo/`, with or without a store prefix.  Deletes made by the owner from a workstation SHALL NOT be affected, so `report upload` can still replace a document.

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

#### Scenario: An empty prefix changes the policies only by the denies under any prefix
- **WHEN** the instance-role policy and the bucket policy are built with an empty store prefix
- **THEN** each matches its golden file, which differs from the capture before the change only by the deny resources `<bucket>/*/<root>/*`

#### Scenario: The deny does not depend on the prefix
- **WHEN** the instance-role policy and the bucket policy are built with an empty store prefix, with `team-a` and with `team-b/nested`
- **THEN** each deny is the same in all three
- **AND** the deny built with an empty prefix covers every denied root at the top of the bucket and under `team-a/` and `team-b/nested/`
- **AND** no Allow statement covers more than it did with an empty prefix, apart from the compactor task role's prefixed compacted roots

#### Scenario: Another cluster's up keeps the guard
- **WHEN** a cluster on the store prefix `team-a` runs `up`, and then `profile setup` or a cluster on no prefix puts the policies again
- **THEN** the cluster roles still cannot delete under `team-a/mimir/`, `team-a/loki/`, `team-a/tempo/`, `team-a/grafana/` or `team-a/reports/`

#### Scenario: A store prefix that could widen a policy is refused
- **WHEN** a store prefix holds `*`, `?`, a `..` segment or an empty segment, or begins with `mimir`, `loki`, `tempo`, `grafana`, `reports` or `pyroscope`
- **THEN** building the store backend fails, and no policy is built from it
