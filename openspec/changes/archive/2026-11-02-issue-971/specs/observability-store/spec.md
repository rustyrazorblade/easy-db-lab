## MODIFIED Requirements

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
