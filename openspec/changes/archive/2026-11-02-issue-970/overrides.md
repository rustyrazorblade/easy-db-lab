## Overrides existing behavior

### observability-store: Mimir reads only the cluster's own data (REMOVED)
**Currently** (`openspec/specs/observability-store/spec.md`): "Mimir on a cluster SHALL write every block to S3 and SHALL answer queries only from its own ingester: the in-memory head and the local blocks, which it SHALL keep for the cluster's life.  Mimir SHALL run no compactor and no store-gateway, and SHALL NOT query the S3 block store."
**This change:** removed and replaced by "Mimir reads the whole shared store": the store-gateway runs, `query_store_after` is 0, the bucket index may be up to about 10 years stale, and local blocks are kept for 2 hours.

### observability-store: No observability backend deletes data automatically (MODIFIED)
**Currently:** "Mimir SHALL run with no compactor module, so no compaction, retention, cleanup or tenant-deletion path exists in the cluster."  Scenario "Mimir has no deletion path": "its module list contains no compactor and no store-gateway".
**This change:** the in-cluster rules stay; the scenario drops "no store-gateway".  Adds that compaction is allowed only where a merged copy holds all its sources' data, that the account compactor runs with retention and every other deletion path off, and the scenario "The account compactor runs with retention off".

### observability-store: The cluster cannot delete observability objects (MODIFIED)
**Currently:** issue-966's delta is the effective text: "The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/` and `grafana/` in the account bucket.  `up` SHALL re-apply it every time."  (The main spec still names the pre-966 prefixes.)
**This change:** adds one bucket-policy Deny under `mimir/`, `loki/` and `tempo/` for the EC2 instance role, the EMR service role and the EMR EC2 role, re-applied by `up`, and states that only the compactor's task role deletes there.  Adds the scenarios "EMR cannot delete compacted roots" and "Only the compactor deletes while a cluster runs".

### cluster-lifecycle: Cluster Teardown (MODIFIED)
**Currently:** issue-966's delta is the effective text.  Phase A step 1: "Check that Loki runs.  IF the logs signal is not yet recorded and Loki is scaled to 0 or not ready, the logs signal fails with the cause 'Loki was stopped by an earlier `down`'".  Logs: "...then verify on the control node that no index write-ahead data is left and that every locally built index file exists in S3."  Metrics: "...then verify that the head was compacted into blocks (no failed compaction, and the newest block covers the head's newest sample) and that every block the cluster wrote exists in S3, then stop the Mimir process."  Record: "with the time it completed and what it verified".  Scenario "A re-run after a stopped Loki reports the real cause".  Scenario "Teardown of all clusters": "every tagged VPC and its resources are removed".  In code, Mimir and Tempo also run a running check before their flush.
**This change:** Phase A is the mirror then the collector stop.  The Loki and Mimir flushes wait for the synchronous flush and for the process to stop, and verify nothing further.  A new rule forbids any check beyond flush-and-wait.  The record keeps only the completion time.  The stopped-Loki scenario is replaced by "down runs no verify check" and "A flush that does not finish stops down".  "Teardown of all clusters" keeps the `easy-db-lab-compactor` VPC.  Every other rule of issue-966's text is unchanged.

## Conflicts with other in-flight changes

**issue-966** (merged, not archived) modifies `observability-store` ("Observability data lands in one layout in the account bucket", "The cluster cannot delete observability objects", "Data reaches S3 while the cluster is up and survives a restart") and `cluster-lifecycle` ("Cluster Teardown").

- Two requirements overlap: "The cluster cannot delete observability objects" and "Cluster Teardown".  issue-970's MODIFIED blocks are written from issue-966's delta text, so issue-966 must be archived first; archiving issue-970 then replaces both with the full text here.  Archiving in the other order would let issue-966 overwrite this change's teardown and deny text.
- "Mimir reads only the cluster's own data" and "No observability backend deletes data automatically" are untouched by issue-966, so they apply cleanly in either order.
- No other change is open under `openspec/changes/`.
