## 1. Failing test first

- [ ] 1.1 Add `CollectorReplayIntegrationTest` (`src/integrationTest/.../configuration/otel/`): the pinned collector with its rendered config and the pinned Mimir with its rendered config on one Docker network, Mimir aliased `mimir.default.svc.cluster.local`; push one sample a second, stop Mimir for 60s, start it, and assert that every sequence number is in Mimir
- [ ] 1.2 Run it against the current config and record that it fails (samples of the gap missing)

## 2. Collector exporters

- [ ] 2.1 `prometheus_remote_write`: move `endpoint`, `tls`, `headers` under `http:` with `timeout: 30s`; set top-level `timeout: 0`; `retry_on_failure` with `initial_interval: 5s`, `max_elapsed_time: 0`; `remote_write_queue` enabled with `queue_size: 20000`; comments state why (the 0.161.0 deadline, the uncopied `max_interval`)
- [ ] 2.2 `otlp_http/logs` and `otlp_grpc/tempo`: `retry_on_failure.max_elapsed_time: 0` and `sending_queue.queue_size: 20000`, in memory
- [ ] 2.3 Update `OtelManifestBuilderTest` (endpoint now under `http`, the retry, timeout and queue settings, and no `storage`, `wal` or `memory_limiter`) and the `otel.yaml` golden manifest
- [ ] 2.4 Run `OtelCollectorConfigValidationIntegrationTest` so the pinned collector accepts the new keys

## 3. Mimir

- [ ] 3.1 `mimir.yaml`: `out_of_order_time_window: 1h`, with the reason in the comment (no dollar-brace in comments)
- [ ] 3.2 Update `MimirManifestBuilderTest`, the `mimir.yaml` and `compactor.yaml` golden manifests, and the `MimirIntegrationTest` KDoc

## 4. Pyroscope Java agent

- [ ] 4.1 Add `Constants.PyroscopeJavaAgent` values for unlimited tries (-1) and a 360-snapshot queue
- [ ] 4.2 Pass `-Dpyroscope.ingest.max.tries` and `-Dpyroscope.push.queue.capacity` in `StressJobManifestBuilder`, `SidecarManifestBuilder`, `EMRProvisioningService` and `edl_pyroscope_opts` (`edl-kit-lib.sh`), with the tests that assert those options

## 5. Green

- [ ] 5.1 `CollectorReplayIntegrationTest` passes with the fix
- [ ] 5.2 JDK 21 gates: `ktlintFormat`, `ktlintCheck`, `detekt`, `test`, and the integration tests for the collector, Mimir and golden manifests

## 6. Docs

- [ ] 6.1 `docs/user-guide/mimir.md`: what a Mimir restart does to metrics now, the 1h window, and the queue metrics to watch (`otelcol_exporter_queue_size`, `otelcol_exporter_send_failed_metric_points_total`)
- [ ] 6.2 `docs/user-guide/loki.md`: the logs retry and the 30-minute out-of-order limit
- [ ] 6.3 `docs/reference/opentelemetry.md`: the exporter queue and retry settings, and the Java agent's
- [ ] 6.4 `configuration/CLAUDE.md` (Mimir and collector entries) and the `observability-stack` skill

## 7. Review and real cluster

- [ ] 7.1 roborev (`roborev review --branch --wait`), fix and close each job
- [ ] 7.2 Real cluster (1 db, 1 app, 1 control, `--tag embark_offhours=off`, default profile): light stress, Mimir down for a few minutes, then show a per-second series in Mimir with no missing samples across the gap, and no rise in `otelcol_exporter_send_failed_metric_points_total`; `down`
- [ ] 7.3 Open the PR with "Closes #991." and the cluster evidence
