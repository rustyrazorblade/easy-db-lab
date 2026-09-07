# Design — issue 902: local observability stack

## Context

The design was produced by the `architect` agent, stress-tested by `design-critic` over two rounds
(21 findings, then 17) across four architect revisions, and approved by the owner at the activation
design stop. Everything bearing on scope or acceptance criteria went back into the issue body; the
mechanism detail lands here.

Two constraints are fixed and are not open to redesign. They are quoted here because every rejected
alternative below is rejected by one of them.

1. **All metrics and logs merge into one store.** Many clusters' data lands in one VictoriaMetrics
   and one VictoriaLogs, distinguished by the `cluster` label, so **clusters can be compared against
   each other on a single panel**. `dashboards/cluster-comparison.json` already exists; the merged
   store is what makes it usable.
2. **No observability data is ever deleted automatically.** Not on a timer, a threshold, a default,
   or a teardown, and not in S3, the local aggregate, or any container the tool generates or starts.
   Retention for observability data is infinite everywhere. The only deletions permitted are an
   explicit `local rm` naming an import and an explicit `local import --replace` naming a cluster.

## Mechanism

### Staging and merge

`vmbackup` produces a full snapshot, and `vmrestore` requires an empty target directory. Neither can
merge. The import therefore runs in four phases:

1. **Restore to staging.** `vmrestore -skipBackupCompleteCheck` writes the snapshot into
   `staging/`, which is empty at the start of every import.
2. **Read from staging.** A transient container is started over the staging directory —
   carrying the unbounded retention value, like every other store — and the import streams
   `/api/v1/export` out of it.
3. **Merge into the live store.** The same stream is transformed and written to the live store's
   `/api/v1/import`. Units are counted at both ends; a count mismatch fails the import rather than
   passing silently on a truncated transfer.
4. **Record and clear.** The ledger entry is written, then `staging/` is cleared.

The transform between the two ends is where the cluster label is stamped, as a **fill**: a record
that already carries `cluster` passes through untouched.

Logs restore is a file-level copy of per-partition snapshots rather than an API import, because
that is the shape `VictoriaBackupJobBuilder` produces (`GET /internal/partition/snapshot/create`,
then `aws s3 sync` one prefix per partition). Partition directories are named `YYYY_MM`.

### The `(clusterLabel, tier)` dedup key

The obvious key — `(name, timestamp, tier)` — only catches re-importing the *same* backup. Because
`vmbackup` snapshots are full, two *different* snapshots of one cluster instance overlap completely
over their shared period, and each carries its own import label, so they land as two distinct series
covering the same period under the same cluster name. Any sum across them is wrong, and nothing
about the result looks broken.

The key is therefore `(clusterLabel, tier)`, independent of timestamp. A bare import refuses the
whole command when that key is already present, naming the recorded snapshot and its time range.
`--replace` is the only way past it.

Profiles are the exception. That S3 prefix is written continuously rather than snapshotted, so
profile blocks accumulate and are deduplicated on their leaf ULIDs. `--replace` still covers them,
so one flag leaves one consistent state per cluster.

### The whole-store snapshot and its phased marker

Atomicity for `--replace` is a whole-store snapshot taken before any mutation, restored in place if
the import fails. A marker file records which phase the import reached, and recovery is identical
from every phase: restore the snapshot, clear staging, leave the ledger as it was. Making recovery
phase-independent is what keeps the rollback path testable — one path, exercised from any
interruption point, rather than one per phase.

### The three-tier single S3 prefix tree

Item 15 moves Pyroscope's store to the account bucket, and fixing the incorrect "no forward slashes"
KDoc lets its prefix sit inside `clusters/` rather than beside it. All three tiers then share one
tree:

```
s3://<accountBucket>/clusters/<label>/victoriametrics/<timestamp>/
s3://<accountBucket>/clusters/<label>/victorialogs/<timestamp>/
s3://<accountBucket>/clusters/<label>/pyroscope/
```

One key addresses a run, so no per-tier URI flag is needed, and "no expiry over prefixes holding
observability data" becomes a single statement rather than two. `ClusterS3Path` already models this
shape; `pyroscope()` sits beside `victoriaMetrics()` and `victoriaLogs()`.

### Resolving the cluster label from the key

Resolution anchors on the literal tier directory name, never on position: the segment immediately
before `victoriametrics` or `victorialogs`, or the first key segment with `pyroscope.` stripped. An
earlier draft said "the last segment of the S3 prefix", which is wrong — for metrics and logs the
last segment is the timestamp.

The stamp is a write that always succeeds, so a wrong value does not fail loudly; it silently
relabels correctly-labelled data. That is why all four ambiguous cases refuse rather than guess.

## Alternatives Considered

### Non-merging designs — ruled out by constraint 1

- **One datasource per import.** Lets a user switch between clusters. Does not let a user compare
  them, which is the requirement.
- **A query fan-out proxy.** Same objection, with a new component to build and operate.
- **Grafana Mixed-datasource comparison.** Same objection again; it also pushes the comparison into
  each panel's construction rather than into the data.

All three were rejected permanently. Anyone reaching for one should re-read constraint 1.

### `vmctl` for the merge — rejected

Rejected in favour of streaming `/api/v1/export` → `/api/v1/import` in Kotlin with exact counts at
both ends. `vmctl` gives no unit count the import can assert on, so a truncated transfer passes as
a success. Doing the transform in Kotlin also puts the cluster-label fill in the same place as the
count, rather than requiring a second pass over the data.

### The native VictoriaMetrics export format — rejected

The native format is relabellable without a full decode (a length prefix followed by plain
`\x01`-delimited labels), which was attractive. Rejected on two grounds: it is an undocumented
internal format with no compatibility promise across the pinned versions, and JSON lines keep one
transform shape across both tiers. `/api/v1/export` supports `max_rows_per_line`, which bounds line
size directly, so the usual objection to JSON lines — unbounded line length on a long series — does
not apply.

### Pointing local Pyroscope at the cluster's S3 bucket — rejected

An earlier draft of the issue proposed it. It needs credentials and a network on every start, which
defeats offline iteration — the thing this change exists to enable. Profiles are synced down and
served from a local filesystem backend instead.

### `init` as the scaffold verb — rejected

`easy-db-lab init` already provisions a cluster. A second `init` under a new group would collide in
exactly the situation where the user is least sure which one they want. `scaffold` is already this
issue's word for what gets generated, and the manifest is named after the command that writes it:
`scaffold.json`.

### Time- or size-bounded retention on the aggregate — rejected

Ruled out by constraint 2. No time window, no size ceiling, no eviction policy, no default, and no
storage-cost refinement. `local rm` is the sole disk-management mechanism, and it is always explicit
and always names what it removes.

### Node-local JFR retention — deliberately not changed

`profiling`'s "Local JFR retention is bounded" requirement stays exactly as it is. Those bounds
apply to the chunk buffer on a database node, not to a store: chunks are shipped to Pyroscope, and
Pyroscope is the store that must accumulate. Removing that bound would let an unreachable Pyroscope
fill the volume Cassandra stores data on, which is the opposite of protecting observability data.

## Domain Facts

These were established by testing against the pinned images, not by argument.

- **`-retentionPeriod=100y` and `-futureRetention=100y` are accepted** by VictoriaMetrics `v1.136.0`
  and VictoriaLogs `v1.47.0`.
- **A bare number means MONTHS in both products.** The unit must always be written. `100` is not
  `100y`.
- **VictoriaMetrics partition directories are named `YYYY_MM`** — a live run created `2026_09`.
- **`vmrestore` refuses a locally-created snapshot directory by default but accepts it with
  `-skipBackupCompleteCheck`.** No copy-over-a-live-directory fallback is needed.
- **`/api/v1/export` supports `max_rows_per_line`**, verified splitting a 10-sample series into 4
  lines at `max_rows_per_line=3`.
- **The native export format is relabellable without a full decode** — a length prefix followed by
  plain `\x01`-delimited labels — but is undocumented and internal.
- **Neither the account bucket nor the data bucket currently carries any lifecycle configuration**,
  so item 14's repair is precautionary rather than urgent.
- **The per-cluster data bucket holds only Pyroscope profiles today** — nothing else. That is what
  makes item 15 the enabling change for keeping the data-bucket expiry.
- **VictoriaMetrics enforces retention as a sliding window against the current clock**, not against
  ingest time, so a restored backup older than the window is silently dropped. **VictoriaLogs
  additionally rejects out-of-retention records at ingest.**

### Pyroscope compactor findings (verified against the v1.18.0 source)

- **`-compactor.disabled-tenants=anonymous` is required.** One gate object stops compaction,
  retention enforcement, hard deletion and bucket-index updates together. Without it, compaction
  merges blocks into new ULIDs and deletes the sources, which invalidates the ledger's recorded
  block identity and makes per-import profile deletion impossible in principle.
- **Consequence 1: the store-gateway falls back to direct bucket scans.** Queries keep working;
  syncs get slower.
- **Consequence 2: `GetProfileStats` has no fallback.** The UI's data-availability and
  default-time-range hint will not see anything in storage. Flame graphs render normally when a time
  range is set explicitly. Documented, not worked around.
- **No Pyroscope version has delete-by-selector**, so block-directory removal is the only deletion
  route that exists — which is the reason block identity must stay stable.

### Repo facts the design depends on

- `TemplateService.kt:35` resolves `BUCKET_NAME` to `state.dataBucket` before `state.s3Bucket`, so
  `pyroscope/config.yaml`'s `bucket_name` points at the bucket `down` expires wholesale.
- `TemplateService.kt:50`'s KDoc claims Pyroscope's storage prefix allows "no forward slashes". It
  does. That incorrect comment is why the profiles prefix is a sibling of `clusters/` rather than
  inside it.
- `Down.kt:359` and `:408-420` apply the cluster-prefix lifecycle rule; `--retention-days` defaults
  to `1`.
- Cluster Grafana URLs are `http://localhost:{8428,9428,3200,4040}` (hostNetwork on the control
  node); compose needs service names, which is the only substitution the generated datasource file
  makes.
- Profiles already carry `cluster` with the same `clusterLabelName()` value metrics and logs use:
  `config.alloy:72`'s `__CLUSTER_NAME__` resolves through `fromResource` → `buildContextVariables()`
  (`TemplateService.kt:37,99`), not through `TemplateVariables`.
- `Down` does not remove `state.json` and only deletes the bucket when empty (`Down.kt:473`), so a
  torn-down workspace stays a valid handle and `metrics ls` still resolves the prefix.

## Risks

- **A wrong cluster stamp is silent.** The label write always succeeds, so a guessed value would
  relabel correctly-labelled data with nothing failing. Mitigated by the four refusals, which is why
  they refuse the whole command rather than importing what they can.
- **A truncated export passes as success unless counted.** Mitigated by asserting unit counts at
  both ends of the stream.
- **A staging store on the default retention truncates before anything reads it.** It is the first
  thing to go wrong and the hardest to notice. Mitigated by requiring the unbounded value on
  transient containers too, and by import refusing against any store whose retention is not that
  value.
- **A dashboard-enforcement test that walks only `expr` fields passes `dashboards/profiling.json`
  while it silently blends.** Its filter is in the Pyroscope `labelSelector`. This is also why an
  earlier survey reported that dashboard as "0 of 0 queries".
- **`local dashboards` with no `--from` can deploy a stale build copy.** The same footgun
  `dashboards/CLAUDE.md:3` already documents for `grafana update-config`. Mitigated by requiring the
  command to state which copy it used.
- **The scaffold must not read a source checkout.** Most users install from a package. Any path that
  reads a working tree works on the developer's machine and fails for everyone else.
- **Generated files must not collide with the repo's own top-level `docker-compose.yml` and
  `otel-collector-config.yaml`**, which belong to the unrelated valkey/OTel dev harness.
- **`BUCKET_NAME` has a second consumer.** ClickHouse's S3 data disk. Moving `BUCKET_NAME` itself
  rather than adding a variable would move ClickHouse's data to the account bucket as a side effect.
- **Four checks remain for implementation**, none affecting the design's shape.
