## 1. Failing test first

- [x] 1.1 Add `CollectorReplayIntegrationTest` (`src/integrationTest/.../configuration/otel/`): the pinned collector with its rendered config and the pinned Mimir with its rendered config on one Docker network, Mimir aliased `mimir.default.svc.cluster.local`; push one sample a second, stop Mimir for 60s, start it, and assert that every sequence number is in Mimir
- [x] 1.2 Run it against the current config and record that it fails (samples of the gap missing)
- [x] 1.3 Add the Loki restart test: the rendered collector in front of the pinned Loki with its rendered config, Loki aliased `loki.default.svc.cluster.local`; send one numbered log line a second, stop Loki for 60s, start it, and assert that every line is in Loki
- [x] 1.4 Run it against the current config and record the result: whether lines of the outage are lost (the 300s retry limit is not reached in 60s) and whether lines are lost to Loki's answers while it starts
  - Result: the Mimir test failed with samples 11-70 missing, exactly the 60s outage (`context deadline exceeded`, dropped items). The Loki test passed: no line was lost, because the 300s retry limit is not reached in 60s, and the Service reaches Loki only once it answers `/ready`, so the collector sees a connection error, never a 500.

## 2. Collector exporters

- [x] 2.1 `prometheus_remote_write`: move `endpoint`, `tls`, `headers` under `http:` with `timeout: 30s`; set top-level `timeout: 0`; `retry_on_failure` with `initial_interval: 5s`, `max_elapsed_time: 0`; `remote_write_queue` enabled with `queue_size: 20000`; comments state why (the 0.161.0 deadline, the uncopied `max_interval`)
- [x] 2.2 `otlp_http/logs` and `otlp_grpc/tempo`: `retry_on_failure.max_elapsed_time: 0` and `sending_queue.queue_size: 20000`, in memory
- [x] 2.3 Update `OtelManifestBuilderTest` (endpoint now under `http`, the retry, timeout and queue settings, and no `storage`, `wal` or `memory_limiter`) and the `otel.yaml` golden manifest
- [x] 2.4 Run `OtelCollectorConfigValidationIntegrationTest` so the pinned collector accepts the new keys

## 2a. Loki at start

- [x] 2a.1 If 1.4 shows lines lost while Loki starts: find which status Loki answers and pick the fix the test proves (a readiness gate on the Loki Service so the collector gets a retried connection error, or an `otlp_http/logs` setting in 0.161.0 that retries a 500), and apply it in `LokiManifestBuilder` or the exporter config
- [x] 2a.2 The Loki restart test fails without that fix and passes with it; if 1.4 lost nothing, record that the test passes with no fix
  - Result: 1.4 lost nothing, so no Loki fix was needed; the test passes with no fix. The unlimited retry (2.2) still matters for outages past 300s.

## 3. Mimir

- [x] 3.1 `mimir.yaml`: `out_of_order_time_window: 1h`, with the reason in the comment (no dollar-brace in comments)
- [x] 3.2 Update `MimirManifestBuilderTest`, the `mimir.yaml` and `compactor.yaml` golden manifests, and the `MimirIntegrationTest` KDoc

## 4. Pyroscope Java agent

- [x] 4.1 Add `Constants.PyroscopeJavaAgent` values for unlimited tries (-1) and a 360-snapshot queue
- [x] 4.2 Pass `-Dpyroscope.ingest.max.tries` and `-Dpyroscope.push.queue.capacity` in `StressJobManifestBuilder`, `SidecarManifestBuilder`, `EMRProvisioningService` and `edl_pyroscope_opts` (`edl-kit-lib.sh`), with the tests that assert those options

## 5. Green

- [x] 5.1 `CollectorReplayIntegrationTest` and the Loki restart test pass with the fix
- [x] 5.2 JDK 21 gates: `ktlintFormat`, `ktlintCheck`, `detekt`, `test`, and the integration tests for the collector, Mimir and golden manifests

## 6. Docs

- [x] 6.1 `docs/user-guide/mimir.md`: what a Mimir restart does to metrics now, the 1h window, and the queue metrics to watch (`otelcol_exporter_queue_size`, `otelcol_exporter_send_failed_metric_points_total`)
- [x] 6.2 `docs/user-guide/loki.md`: the logs retry, the 30-minute limit (Loki's `max_chunk_age: 1h` out-of-order window), and the Loki-start fix if 2a applied one
- [x] 6.3 `docs/reference/opentelemetry.md`: the exporter queue and retry settings (fixed 5s PRW retry; `max_interval` has no effect in PRW 0.161.0), and the Java agent's
- [x] 6.3a Profiling docs: eBPF profiles are lost during a Pyroscope outage longer than about 15s, with a reference to #1206
- [x] 6.4 `configuration/CLAUDE.md` (Mimir and collector entries) and the `observability-stack` skill

## 7. Review and real cluster

- [ ] 7.1 roborev (`roborev review --branch --wait`), fix and close each job
- [ ] 7.2 Real cluster (1 db, 1 app, 1 control, `--tag embark_offhours=off`, default profile): light stress, Mimir down for a few minutes, then show a per-second series in Mimir with no missing samples across the gap, and no rise in `otelcol_exporter_send_failed_metric_points_total`; `down`
- [ ] 7.3 Open the PR with "Closes #991." and the cluster evidence
