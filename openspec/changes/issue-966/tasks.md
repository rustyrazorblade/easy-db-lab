## 1. Storage layout

- [x] 1.1 `Constants.Observability`: replace `PREFIX`, `TRACES_DIR`, `PROFILES_DIR`, `LOGS_DIR`, `ANNOTATIONS_DIR` and `METRICS_ROOT = "observabilitymetrics"` with `METRICS_ROOT = "mimir"`, `LOGS_ROOT = "loki"`, `TRACES_ROOT = "tempo"`, `PROFILES_ROOT = "pyroscope"`, `GRAFANA_ROOT = "grafana"`, `ANNOTATIONS_DIR = "annotations"`.  Keep the `index` tenant reservation.
- [x] 1.2 `ObservabilityStore`: drop `root()`; each prefix is its tool root; `annotationsRoot()` is `grafana/annotations/<tenant>`.  Update the KDoc.
- [x] 1.3 Update KDoc and comments that name the former prefixes: `ClusterConfigData`, `TemplateService`, `mimir.yaml`, `pyroscope/config.yaml`, the four manifest builders, `LokiTailFlush`, `MimirTailFlush`, `GrafanaAnnotationBackupService`, `GrafanaBackup`, `ClusterS3Path`, `help/observability.md`.
- [x] 1.4 `AWSPolicy`: deny `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/*`, `loki/*`, `tempo/*`, `grafana/*`; leave `pyroscope/*` out.  Comment cites "compaction is not deletion".
- [x] 1.5 `bin/end-to-end-test`: `observabilitymetrics/` → `mimir/`, `observability/logs/index/` → `loki/index/`.
- [x] 1.6 Tests: `ObservabilityStoreTest` (every prefix and the annotations key under the new roots); `AWSPolicyTest` (deny covers the four roots, not `pyroscope/`); `ClusterConfigDataTest` (new prefix values); update the prefix literals in `DownS3NoExpiryIntegrationTest`, `TeardownFlushIntegrationTest`, `ObservabilityBackends` and the config-path tests.
- [x] 1.7 Search the tree (source, resources, tests, docs, `bin/`) and confirm no `observabilitymetrics` and no `observability/` S3 prefix remains.

## 2. Upload speed

- [x] 2.1 `mimir.yaml` `blocks_storage.tsdb`: `block_ranges_period: [1m]` (comment: experimental and hidden in 3.2.1), `head_compaction_interval: 15s`, `ship_interval: 15s`, `head_compaction_idle_timeout: 2m`.
- [x] 2.2 `tempo.yaml`: `live_store.max_block_duration: 1m`; update its comment.
- [ ] 2.3 `loki.yaml`: `ingester.max_chunk_age: 15m` (comment: sets the out-of-order window to 7.5 minutes; `reject_old_samples_max_age` stays); `storage_config.tsdb_shipper.resync_interval: 1m` (comment: read side; the index uploads on Loki's fixed 15-minute rotation).
- [x] 2.4 Unit tests on the rendered configs (`*ManifestBuilderTest`, real `TemplateService`): the new values; replace the "blocks are two hours" and "blocks are cut every five minutes" tests.
- [x] 2.5 Integration test (real Mimir image + LocalStack, no shutdown): a sample appears in a block under `mimir/<tenant>/` within about 3 minutes.  Mimir starting proves 3.2.1 accepts `[1m]` with `out_of_order_time_window: 10m`.
- [x] 2.6 Integration test (real Tempo image + LocalStack, no shutdown): a span's block reaches `tempo/<tenant>/` within about 2 minutes.  Remove the "shorter block cut" override from `TempoBlockDurabilityIntegrationTest` so the real config is tested.
- [ ] 2.7 Integration test (real Loki image + LocalStack, no shutdown): a chunk from a continuously written stream appears under `loki/<tenant>/` within about 15 minutes.

## 3. Thread-safe foundations (folded-in debt)

- [x] 3.1 `DefaultSSHConnectionProvider`: replace the plain map and check-then-`getOrPut` with a `ConcurrentHashMap` and `compute`.  Test: concurrent `getConnection` calls for one host create one connection.
- [x] 3.2 `ClusterStateManager.save`: write a temp file in the same directory, then rename it atomically over `state.json`.  Test: a concurrent `load()` during repeated saves never fails to parse.

## 4. Flush model and recording

- [x] 4.1 Add `TailSignal` (LOGS, METRICS, TRACES, PROFILES, ANNOTATIONS), each with a description.
- [x] 4.2 `FlushStep` gains `signal`; add the Loki running check, the collector stop, and the Tempo steps; split the shared running check into a per-backend check.
- [x] 4.3 `FlushProgress`: one instance per task, constructed with the single workload it owns; merge backend states after the join.
- [x] 4.4 `ClusterState.tailFlush` becomes `TailFlushRecord(signals: Map<TailSignal, SavedSignal>)`; only LOGS and METRICS are ever written.  `markInfrastructureUp()` clears it.
- [x] 4.5 `DefaultTeardownBackupService`: a single-thread writer records LOGS or METRICS the moment its flush succeeds; `unsavedSignals(state)` returns the unrecorded ones among LOGS and METRICS plus TRACES and ANNOTATIONS, never PROFILES; returns `TailFlushFailed` naming every failed signal.
- [x] 4.6 `MimirTailFlush`: replace the per-block `fileExists` with one paginated listing of `mimir/<tenant>/` starting at this cluster's oldest local block ID, then a set difference.  Test with a listing that holds other clusters' blocks.
- [x] 4.7 Tests (`TeardownBackupServiceTest`): a LOGS success is recorded while METRICS is still running; a re-run's pending set excludes recorded signals; `unsavedSignals` for none, logs-only, and both recorded.

## 5. Collector stop and Tempo drain

- [x] 5.1 `TelemetrySenders` + `K8sTelemetrySenders.stop(controlHost, timeout)`: delete the `otel-collector` DaemonSet, wait until its pods are gone (`RetryUtil.createPollUntilRetryConfig`); succeed when it is already absent.
- [x] 5.2 `TempoTailFlush`: check Tempo runs; poll `/metrics` until `tempo_live_store_live_traces` is 0 for every tenant and `tempo_live_store_traces_created_total` is unchanged across two readings at least 2 seconds apart; then poll the node listing of `/mnt/db1/tempo/live-store/wal/` until no top-level WAL block directory holds `meta.json` and every `blocks/<tenant>/<id>/` holding `meta.json` holds `flushed`; timeout 5 minutes; on timeout report live traces and the failed-flush and failed-completion counters.  Never stop or restart Tempo.
- [x] 5.3 `Constants.TeardownFlush`: `SENDERS_STOP_TIMEOUT_SECONDS = 120`, `TEMPO_DRAIN_TIMEOUT_SECONDS = 300`; `FlushTimeouts` gains `sendersStop` and `tempoDrain`.
- [x] 5.4 Unit tests (`TempoTailFlush`, faked `RemoteOperationsService` for the node listing): metric parsing; a changing created-traces count keeps the wait going; the drained predicate (empty head with no `meta.json` → drained; head with `meta.json` → not drained; `meta.deleted.json` → drained; block with `meta.json` and no `flushed` → not drained).
- [x] 5.5 K3s TestContainers test: apply the real `OtelManifestBuilder` DaemonSet, run `K8sTelemetrySenders.stop`, assert the pods are gone; run it again with the DaemonSet absent and assert success.

## 6. Parallel orchestration

- [x] 6.1 `DefaultTeardownFlushService.saveTail(controlHost, state, pending)`: Phase A (Loki running check and mirror only if LOGS pending; then the collector stop), then Phase B on a platform-thread pool with `invokeAll`, each task in `runCatching`: Loki flush (skipped if the check or mirror failed), Mimir flush, Tempo drain, profiles report, annotations backup.  Return `FlushOutcome`.
- [x] 6.2 Koin (`ServicesModule`): register `TempoTailFlush` and `TelemetrySenders`; widen the `DefaultTeardownFlushService` constructor.
- [x] 6.3 Events (`Teardown` domain): `BackupStarting` names traces; `BackupFailedAbort` carries `List<SignalFailure(signal, step, reason)>`, backend states and stopped workloads (including `otel-collector`); `TailAlreadyFlushed` → `TailAlreadySaved(saved)`; new `TelemetrySendersStopped`, `TempoFlushed(blocks)`, `ProfilesNeedNoFlush`, `ForceSkipsTail(unsaved)`.
- [x] 6.4 Unit tests (`TeardownFlushServiceTest`, hand-written fakes): one failing signal leaves every other signal saved and the outcome names all failures; the tasks run concurrently (two fakes each wait on a latch only the other releases, under a timeout); the mirror finishes before the Loki task starts; a failed mirror skips only the Loki flush; a stopped Loki fails LOGS with the "earlier down" cause and skips the mirror; recorded signals are never invoked.
- [x] 6.5 Integration test (`TeardownFlushIntegrationTest`, real images + LocalStack, add `grafana/tempo:3.0.3` on a volume at `/mnt/db1/tempo`): spans pushed just before the save are in `tempo/<tenant>/` (assert specific trace IDs); the drain passes only after the live-trace wait; logs and metrics are recorded while the Tempo drain still runs; a re-run skips the recorded signals and runs the Tempo drain and the annotations backup again.

## 7. `down`

- [x] 7.1 `Down.saveTailBeforeTeardown()`: call `saveTail` with the pending signals; a failure aborts with every failed signal and exit code ERROR; the redirect and infrastructure-not-up skips stay.
- [x] 7.2 `down --force`: emit `ForceSkipsTail(unsavedSignals(state))` with the teardown preview, before the confirmation prompt; then skip both phases.  Update the `--force` option description.
- [x] 7.3 Tests (`DownBackupTest`): `--force` emits the unsaved list before the confirmation prompt and before any teardown call; a failure aborts with ERROR and removes nothing; declining the prompt runs no save step.

## 8. Rules, docs and specs

- [x] 8.1 Root `CLAUDE.md`: append to the ABSOLUTE RULE on deleting data, verbatim: "Compaction is not deletion. A compactor that writes a merged object holding all of its sources' data, and then removes those sources, loses nothing and is allowed. Retention, expiry, and any removal that is not replaced by a merged copy stay forbidden. Every compactor runs with retention off."
- [x] 8.2 Root `CLAUDE.md`: update "Storage backends" (the two-phase parallel save, Tempo drain, per-signal record) and "Observability store and tenant" (the tool-named roots, the IAM split).
- [x] 8.3 `configuration/CLAUDE.md` (tailFlush, `cluster-config` keys, Pyroscope path, store layout), `commands/CLAUDE.md` (the `TeardownBackupService` row), `services/aws/CLAUDE.md` (paths).
- [ ] 8.4 User docs: `docs/user-guide/monitoring.md`, `loki.md` (the 7.5-minute out-of-order window), `mimir.md`, `profiling.md`; `docs/reference/commands.md` (`down`: what it saves, the phases, `--force` listing); `log-infrastructure.md`, `opentelemetry.md`, `pyroscope-configuration.md`.
- [x] 8.5 Run `./gradlew ktlintFormat`, `./gradlew detekt` (JDK 21), `./gradlew test`, and `./gradlew integrationTest`; all pass.
