## Context

Issue 967 moved every observability backend onto S3 in the account bucket, under an `observability` prefix, with metrics beside it at `observabilitymetrics/` because Mimir accepts only letters and digits in its prefix.  `down` saves the tail with a sequential chain: annotation mirror, Loki flush, Mimir flush, annotations backup, recorded all-or-nothing in `ClusterState.tailFlush`.  Tempo's tail is not saved.  #970 will let every cluster read the whole shared store, which needs each backend to upload within minutes.

Owner directives from the design review: treat S3 as reliable and add no S3-failure handling; no failure-case handling for a Loki outage, a multi-dc teardown order, or a collector restore after a failed `down`; remove every reference to the former prefixes.

## Goals / Non-Goals

**Goals:** one tool-named root per backend at the top level of the bucket; a parallel teardown save that covers Tempo; a per-signal record that survives an interrupted `down`; uploads within minutes.

**Non-Goals:** the account compactor, the store-gateway, the tenant datasources and the Mimir local-retention cut (#970); the dashboard picker (#971); multi-dc changes; a patched Loki image.

## Decisions

### Storage move
`Constants.Observability` holds one root per tool: `METRICS_ROOT="mimir"`, `LOGS_ROOT="loki"`, `TRACES_ROOT="tempo"`, `PROFILES_ROOT="pyroscope"`, `GRAFANA_ROOT="grafana"`, `ANNOTATIONS_DIR="annotations"`.  `PREFIX` and the `*_DIR` constants go.  `ObservabilityStore` drops `root()`; each prefix is its tool root, and `annotationsRoot()` is `grafana/annotations/<tenant>`.  Every consumer already reads the store or the `cluster-config` ConfigMap, so the rest are KDoc and comment edits.  `AWSPolicy` denies deletes under `mimir/*`, `loki/*`, `tempo/*`, `grafana/*`, and not under `pyroscope/*`.  The Loki tenant name `index` stays reserved (`loki/index/`).

### Teardown sequence
```
down (no --force), after preview and confirmation
  skip entirely if: redirect cluster | infra not up
  Phase A (sequential)
    A0 Loki running check                 (only if LOGS not recorded)
    A1 annotation mirror                  (only if LOGS not recorded and A0 passed)
    A2 stop the telemetry senders         (delete otel-collector DaemonSet, wait for pods gone; ok if absent)
  Phase B (parallel, platform-thread pool, invokeAll; each task inside runCatching)
    LOGS         Loki flush   (not run if A0 or A1 failed; recorded the moment it succeeds)
    METRICS      Mimir flush  (recorded the moment it succeeds)
    TRACES       Tempo drain
    PROFILES     report "profiles need no flush"
    ANNOTATIONS  annotations backup
  any failure → TailFlushFailed: remove nothing, restart nothing, name every failed signal plus --force, exit non-zero
```
The OTel collector is the only client of Tempo's OTLP receiver (Beyla, the Java agents, the stress sidecar, Fluent Bit and EMR all send to the collector; Tempo's metrics generator is inert), so deleting its DaemonSet quiets Tempo's input, and its graceful shutdown drains its exporter queues into Loki and Mimir while they still accept writes.

### Recording
`ClusterState.tailFlush` records LOGS and METRICS only, each the moment its flush succeeds, through one writer (a single-thread executor that owns every state write during the save).  These are the two steps that cannot be repeated, because each leaves its backend stopped.  The annotations backup, the collector stop, the Tempo drain and the profiles report are safe to repeat and run on every `down`, so their record could only go stale (Grafana keeps running; `OtelSyncService` and `grafana update-config` re-apply the collector).  `ClusterStateManager.save` writes a temp file and renames it, so a concurrent `load()` (for example `ObservabilityHttp`, which loads state per request) never reads a torn file.

```kotlin
data class TailFlushRecord(val signals: Map<TailSignal, SavedSignal> = emptyMap())
data class SavedSignal(val completedAt: Instant, val verifiedObjects: Long)
enum class TailSignal { LOGS, METRICS, TRACES, PROFILES, ANNOTATIONS }
```
Only `LOGS` and `METRICS` ever appear in the record.  `markInfrastructureUp()` clears it.

### Tempo drain
Tempo 3.0.3 has no flush endpoint; `/live-store/prepare-downscale` moves no data; a graceful stop cuts to the WAL and cancels the upload; a restart deletes a never-completed WAL block older than `complete_block_timeout` (20m).  So Tempo is never stopped or restarted.  The drain:
1. Poll Tempo's `/metrics` until `tempo_live_store_live_traces` is 0 for every tenant AND `tempo_live_store_traces_created_total` is unchanged across two readings at least 2 seconds apart.  (The live-traces gauge is set only at the start of each 1-second cut tick, so one reading of 0 can be stale.)
2. Poll the control node over SSH: under `/mnt/db1/tempo/live-store/wal/` (hostPath of `/var/tempo`; `live_store.wal.path` is `/var/tempo/live-store/wal`), no top-level WAL block directory holds a `meta.json`, and every `blocks/<tenant>/<id>/` that holds a `meta.json` also holds `flushed`.
3. Timeout 5 minutes (`Constants.TeardownFlush.TEMPO_DRAIN_TIMEOUT_SECONDS`).

Why this holds (Tempo 3.0.3 source): a cut tick appends traces to the head and calls `Flush()`, which writes the head's `meta.json` first, in the same tick; the empty head from `resetHeadBlock()` has no `meta.json`; completion renames the WAL `meta.json` to `meta.deleted.json`; `flushed` is written only after the copy to the backend finishes.

### Pyroscope
Pyroscope v2.3.1 acknowledges a push only after its segment is in S3 (`segment.flush` → `waitFlushed`), and no metric shows held segments.  `down` reports "profiles need no flush" and does nothing else; `--force` never lists profiles.

### Structure
- `TailSignal` enum (`services/`).
- `FlushStep` gains `signal`; new entries for the Loki running check, the collector stop, and the Tempo steps; the shared running check splits into a per-backend check.
- `FlushProgress`: one instance per task, constructed with the one workload it owns; backend states merged after the join.
- `TempoTailFlush` (new): peer of `LokiTailFlush` and `MimirTailFlush`, same constructor shape (`ObservabilityHttp`, `BackendWorkloads`, `RemoteOperationsService`, `ObjectStore`, `FlushTimeouts`); `ObservabilityHttp` already takes any port.
- `TelemetrySenders` + `K8sTelemetrySenders`: `stop(controlHost, timeout)`; separate from `BackendWorkloads`, whose contract covers only the backends and forbids scale-up; reuses `RetryUtil.createPollUntilRetryConfig`.
- `DefaultTeardownFlushService` orchestrates Phase A and B and takes the pending signals.
- `DefaultTeardownBackupService` owns the record and gains `unsavedSignals(state)`.
- `Down`: `--force` emits the unsaved list with the preview, before the prompt.
- Koin: register `TempoTailFlush` and `TelemetrySenders`.

```kotlin
interface TeardownFlushService {
    fun saveTail(controlHost: ClusterHost, clusterState: ClusterState, pending: Set<TailSignal>): FlushOutcome
}
data class FlushOutcome(
    val saved: Map<TailSignal, SignalReport>,
    val failed: Map<TailSignal, FlushStepFailed>,
    val backends: Map<String, BackendState>,
)
sealed interface SignalReport {
    data class Logs(val indexFiles: Int, val chunksFlushed: Long) : SignalReport
    data class Metrics(val blocks: Int) : SignalReport
    data class Traces(val blocks: Int) : SignalReport
    data object Profiles : SignalReport
    data class Annotations(val key: String) : SignalReport
}
interface TelemetrySenders { fun stop(controlHost: ClusterHost, timeout: Duration) }
```
`FlushTimeouts` gains `sendersStop` (120s) and `tempoDrain` (300s).  Teardown events: `BackupStarting` names traces; `BackupFailedAbort` carries a list of `SignalFailure(signal, step, reason)`, the backend states, and the stopped workloads (including `otel-collector`); `TailAlreadyFlushed` becomes `TailAlreadySaved(saved)`; new `TelemetrySendersStopped`, `TempoFlushed(blocks)`, `ProfilesNeedNoFlush`, `ForceSkipsTail(unsaved)`.

### Upload settings
- `mimir.yaml` `blocks_storage.tsdb`: `block_ranges_period: [1m]` (experimental and hidden in 3.2.1, no validation), `head_compaction_interval: 15s`, `ship_interval: 15s`, `head_compaction_idle_timeout: 2m`.
- `tempo.yaml`: `live_store.max_block_duration: 1m`.
- `loki.yaml`: `ingester.max_chunk_age: 15m` (sets the per-stream out-of-order window to 7.5 minutes; `reject_old_samples_max_age` stays); `storage_config.tsdb_shipper.resync_interval: 1m` (read side).

### Folded-in fixes
- `DefaultSSHConnectionProvider`: `ConcurrentHashMap` with `compute`, replacing the unguarded check-then-`getOrPut`.
- The flush model (above).
- `MimirTailFlush`: one paginated listing of `mimir/<tenant>/` starting at this cluster's oldest local block ID, then a set difference, replacing one `fileExists` per block.

### Concurrency
A platform-thread `ExecutorService` sized to the task count, with `use {}` and `invokeAll`, each task in `runCatching`, matching `HostOperationsService` and `AwsInfrastructureService`.

## Alternatives Considered

- **D-seq, archiving 967's change.** Chosen: `project-manager` archived it first (done, #972).  Rejected: archiving it inside this change (breaks the bulk-archive rule); writing the delta against 967's change (wrong until archived).
- **D1, where the collector stops.** Chosen: a sequential Phase A step after the mirror (architect's recommendation).  Rejected: stopping it inside the parallel Tempo step, as the issue was worded; its final batches would race Loki's and Mimir's shutdown and be lost.
- **D2, a failed mirror.** Chosen: fail LOGS only, leave Loki running, run the rest; plus a Loki running check before the mirror (critic finding 5).  Rejected: stop everything before the parallel phase, as today.
- **D3, recording.** Chosen: record LOGS and METRICS only, each as it finishes, through one writer, with an atomic state write (the critic's finding 1 and 2 fix; an owner choice over the architect's recommendation).  Rejected: the architect's per-signal record for all five signals, saved once after the join (loses finished flushes if interrupted; TRACES and ANNOTATIONS records go stale); all-or-nothing as today.
- **D4, Tempo proof.** Chosen: the metric wait (live traces 0 and created-traces count stable across two readings) plus the disk check; no S3 check (owner directive: S3 is reliable).  Rejected: the architect's disk check plus an S3 `meta.json` check; a fixed wait then the disk check; relying only on the `flushed` marker.
- **D5, Pyroscope.** Chosen: no check, reported with its reason.  Rejected: scale Pyroscope to 0 (proves nothing, stops accepting profiles); a metrics check (no metric exists); omitting it from the output.
- **D6, the former prefixes.** Chosen (owner override): remove every reference; deny only the new roots.  Rejected: the architect's recommendation to keep denying deletes under the former prefixes.
- **D7, Mimir block size.** Chosen: 1-minute blocks, with the local-retention cut moved to #970.  Rejected: 5-minute blocks (about 7–8 minutes cross-cluster); measure first.
- **D8, concurrency.** Chosen: platform-thread pool.  Rejected: `runBlocking(Dispatchers.IO)` with `awaitAll` (no service uses coroutines); `CompletableFuture.supplyAsync` on the common pool (may serialize on a small machine).
- **Loki speed.** Chosen: `max_chunk_age: 15m` (owner change after Seam 1: a 7.5-minute out-of-order window so a slow Loki restart drops no logs), `resync_interval: 1m`.  Rejected: `5m` (2.5-minute window, the Seam 1 choice); `1m` (30-second window); a patched Loki image; leaving the chunk settings.
- **Tempo speed.** Chosen: `max_block_duration: 1m`.  Rejected: keeping 5 minutes; no Tempo step.
- **Debt.** The architect's separate-issue item, an atomic `state.json` write, was folded in by D3.

## Domain Facts

From upstream source research of the pinned versions (research agent):
- Mimir 3.2.1: the ingester cuts a range once a sample later than `range start + 1.5 × range` arrives, at the next head-compaction tick; with 1-minute blocks and 15-second intervals, a sample reaches S3 in about 2 minutes.  `head_compaction_interval` must be in (0, 15m].  `ship_interval` has no minimum.  A head idle for `head_compaction_idle_timeout` is force-compacted, which flushes a stopped cluster's last partial block.
- Tempo 3.0.3: see the Tempo drain section; traces move to the head after `max_trace_idle` or `max_trace_live`; the head is cut when `max_block_duration` has passed since the last cut, even with no new data.
- Loki 3.7.8: a chunk is flushed when full, when idle for `chunk_idle_period`, or when its span exceeds `max_chunk_age`; the out-of-order window per stream is `max_chunk_age / 2`; the TSDB head rotates every 15 minutes on the clock and the index uploads every minute, both constants.
- Pyroscope 2.3.1: the segment writer flushes every 500 ms and a push returns only after its segment is flushed; shutdown drains and flushes.

## Risks / Trade-offs

- Mimir 1-minute blocks: about 1,440 local blocks per day per tenant, each with its own index, kept for the cluster's life until #970 cuts local retention; about 2,880 blocks for a 48-hour cluster.  S3 holds 1-minute blocks until #970's compactor merges them.
- Loki's out-of-order window is 7.5 minutes: a line more than 7.5 minutes older than its stream's newest line is rejected (owner accepted).
- A telemetry-redirect target's Tempo keeps receiving spans from the source DC, so its drain times out while a source DC is up (owner: no handling).
- After a failed `down`, the collector stays deleted (owner: no restore).
- Thread safety: the SSH cache is fixed; the SOCKS proxy is lock-guarded; `EventBus` is synchronized; `ObservabilityHttp` and Fabric8 clients are per call.

## Migration Plan

None.  Clusters are ephemeral; a new cluster writes to the new roots.
