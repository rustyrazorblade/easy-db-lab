## Why

Issue 967 put every observability root under an `observability` prefix in the account bucket.  Mimir accepts only letters and digits in its storage prefix, so metrics went to `observabilitymetrics/` beside it, and the prefix idea did not work.  Each tool stores its own file format and lays out its own tenant directories, so each root belongs at the top level of the bucket, named for the tool.

`down` flushes Loki and Mimir one after the other and does not save Tempo's last traces.  Tempo 3.0.3 has no flush endpoint, and a graceful stop does not upload its last blocks.  Other clusters see a cluster's data only after it reaches S3, and today that takes up to 2 hours for metrics, up to 2 hours for a continuously written log stream, and up to 5 minutes for traces.  The cross-cluster view in #970 needs minutes.

## What Changes

**Storage layout**
- Each backend's root moves to the top level of the account bucket, named for the tool: `mimir/` (Mimir blocks, per tenant), `loki/` (Loki chunks per tenant, index under `loki/index/`), `tempo/` (per tenant), `pyroscope/`, and `grafana/annotations/<tenant>/` for the annotations backup.  Every reference to the old `observability` and `observabilitymetrics` prefixes is removed.
- The cluster instance role denies `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/` and `grafana/`, and allows deletes under `pyroscope/`, where Pyroscope's own compaction removes the segments it merged.

**Teardown (`down`)**
- Phase A, in order: check that Loki runs; mirror the Grafana annotations into Loki; delete the OTel collector DaemonSet and wait until its pods are gone.  The collector is the only sender to Tempo, and its shutdown drains its last batches into Loki and Mimir while they still accept writes.  The stop succeeds when the collector is already gone.
- Phase B, in parallel on a platform-thread pool: the Loki flush, the Mimir flush, the Tempo drain, the Pyroscope note, and the annotations backup.  Every step runs to completion.  The errors are collected; if any step failed, `down` stops, removes no infrastructure, never starts a backend again, names every failed signal and `--force`, and exits non-zero.
- A failed annotation mirror fails the logs signal only: the Loki flush is skipped and Loki keeps running, and the other steps still run.
- The Tempo drain waits until Tempo holds no live traces and creates no new ones, then polls the control node's disk until no Tempo WAL block holds a `meta.json` and every local block carries its `flushed` marker.  Tempo is never stopped or restarted.  Timeout 5 minutes.
- Profiles need no flush: Pyroscope writes each batch to S3 before it accepts it.  `down` reports this.
- The cluster state records the logs and metrics signals, each at the moment its flush finishes, through one writer.  These are the two steps that cannot be repeated, because each leaves its backend stopped.  A re-run skips the recorded signals; the annotations backup, the collector stop, the Tempo drain and the Pyroscope note run on every `down`.  `state.json` is written atomically.
- `down --force` shows the signals it will not save with the preview, before the confirmation prompt, then skips every step.

**Faster uploads**
- Mimir cuts a 1-minute block (`block_ranges_period: [1m]`, `head_compaction_interval: 15s`, `ship_interval: 15s`, `head_compaction_idle_timeout: 2m`).
- Tempo cuts a block every minute (`live_store.max_block_duration: 1m`).
- Loki flushes a chunk after 15 minutes (`ingester.max_chunk_age: 15m`), and re-lists the index every minute (`tsdb_shipper.resync_interval: 1m`).  Loki's index still uploads on its fixed 15-minute rotation.

**Rules and docs**
- The ABSOLUTE RULE on deleting data in the root `CLAUDE.md` gains the owner-approved text: compaction is not deletion.
- The user docs, `CLAUDE.md`, `configuration/CLAUDE.md`, `commands/CLAUDE.md`, `services/aws/CLAUDE.md` and the help topic describe the new layout, the IAM split, the parallel teardown, and `--force`.

**Folded-in structural fixes**
- The SSH connection cache becomes thread-safe, because the parallel steps use it at the same time.
- The flush model is rebuilt for parallel steps: one progress record per step, and one failure per signal.
- The Mimir S3 check uses one listing of the tenant's blocks, started at this cluster's oldest local block, instead of one request per block.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `observability-store`: the layout, the delete-deny prefixes, and the upload timing.
- `cluster-lifecycle`: the teardown order, the parallel phase, the Tempo drain, the Pyroscope note, the per-signal record, and the `--force` report.
- `cloudwatch-metrics-export`: the S3 location of the CloudWatch-sourced metrics after teardown.
- `grafana-annotations`: the S3 location of the `grafana backup` artifact.

## Impact

- Code: `Constants.Observability`, `ObservabilityStore`, the Mimir, Loki, Tempo and Pyroscope configs and manifest builders, `AWSPolicy`, `TeardownFlushService`, `TeardownBackupService`, `LokiTailFlush`, `MimirTailFlush`, a new `TempoTailFlush`, a new `TelemetrySenders`, `ClusterState`, `ClusterStateManager`, `DefaultSSHConnectionProvider`, `Down`, teardown events, and `bin/end-to-end-test`.
- Existing clusters: a new cluster writes to the new roots.  Clusters are ephemeral, so nothing migrates.
- Out of scope: the account compactor, the store-gateway, the tenant datasources (#970); the datasource picker (#971); multi-dc changes; a patched Loki image.
