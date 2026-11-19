## Context

Every node runs the OTel collector DaemonSet (`otel-collector-config.yaml`, rendered by `OtelManifestBuilder`).  It is the only process that writes metrics to Mimir: the stress-job sidecar and the EMR collectors send OTLP to a node collector, and Tempo's metrics generator is configured but inert.  The same collector writes logs to Loki (`otlp_http/logs`) and traces to Tempo (`otlp_grpc/tempo`).  Profiles reach Pyroscope from two other senders: the Alloy eBPF DaemonSet (`pyroscope.write`) and the Pyroscope Java agent (stress jobs, the Cassandra sidecar, EMR Spark, and kits through `edl_pyroscope_opts`).

Mimir, Loki and Tempo run as one-replica `Recreate` Deployments.  Every config roll that `ConfigHashAnnotator` triggers takes the backend down for its whole restart, and a bad config takes it down until someone fixes it (11 minutes on qa971f).

All facts below were read from the source of the versions this repo deploys: collector contrib 0.161.0 (`Constants.OtelCollector.VERSION`; core exporterhelper v1.67.0, `cenkalti/backoff` v5.0.3), Alloy v1.20.0, Pyroscope Java agent 2.3.0 (`Constants.PyroscopeJavaAgent.VERSION`).

### How `prometheus_remote_write` fails today (0.161.0)

- The exporter builds its exporterhelper chain with `WithTimeout`, `WithQueue`, `WithStart` and `WithShutdown`.  It does **not** call `WithRetry`.  Retries happen inside its own push function, with `backoff.Retry(ctx, ...)`.
- The exporterhelper timeout sender (top-level `timeout`, default 5s) wraps that push in `context.WithTimeout`.  The push checks `ctx.Done()` before every attempt and stops.  So the whole push, retries included, gets 5 seconds; then the batch is dropped as "context deadline exceeded".  The sender is left out when `timeout` is 0 (`if be.timeoutCfg.Timeout != 0`).
- **There is no `sending_queue`.**  The owner's comment says "in-memory sending_queue"; the exporter's in-memory queue is named `remote_write_queue` (`enabled`, `queue_size`, `num_consumers`).  It maps to an exporterhelper memory queue with the `requests` sizer.  `block_on_overflow` is not set, so a full queue rejects new batches.  It has one consumer unless the alpha `EnableMultipleWorkers` gate is on; each push still sends up to 5 requests in parallel.  Defaults: enabled, 10000 requests.
- Retry backoff: only `initial_interval`, `randomization_factor` and `multiplier` are copied into the backoff.  **`max_interval` is not copied**, so the backoff never grows: every retry waits about `initial_interval` (PRW default 50ms).  `max_elapsed_time: 0` means no limit (the library checks `> 0`); the default is 5m.
- Status handling: 5xx and transport errors (connection refused, a request timeout) are retried.  Every other status, including 429 (unless an alpha gate is on), is permanent and drops the batch.
- HTTP settings: since v0.158.0 the top-level `endpoint`, `tls` and `headers` are deprecated in favour of an `http:` block.  If `http:` is set, it wins and the top-level keys are ignored, so all of them move together.  `http.timeout` is the per-request timeout of the HTTP client.

### The other exporters (0.161.0)

- `otlp_http/logs`: forces the exporterhelper timeout to 0 and relies on the HTTP client timeout (30s).  It uses exporterhelper's `retry_on_failure` (5s initial, 30s max interval, **300s `max_elapsed_time`**) and an in-memory `sending_queue` of 1000 requests with 10 consumers.  It retries transport errors and 429, 502, 503 and 504; every other status, 500 included, is permanent.
- `otlp_grpc/tempo`: the 5s timeout applies to each attempt, inside the retry sender, so it does not cut retries short.  Same retry (300s) and queue (1000) defaults.
- So logs and traces survive an outage of up to 5 minutes, and lose data in a longer one.  This is the same gap with a longer fuse.

### Profiles

- Alloy v1.20.0 `pyroscope.write` has no queue.  `pyroscope.ebpf` wraps each send in a context that ends after the collect interval (15s), and drops what is not sent by then (`pprofsDroppedTotal`).  No argument changes this.
- The Pyroscope Java agent keeps up to 8 snapshots (`PYROSCOPE_PUSH_QUEUE_CAPACITY`, one per 10s upload interval) in memory, drops the oldest when full, and gives up on a snapshot after 8 tries (`PYROSCOPE_INGEST_MAX_TRIES`; a negative value retries forever).  It retries 429, 5xx and I/O errors with backoff from 1s to 30s.  It reads both from system properties (`pyroscope.push.queue.capacity`, `pyroscope.ingest.max.tries`).

### Why Mimir rejects a replay

Mimir's head accepts an in-order sample only if it is no older than half a block range (30s here, with 1-minute blocks) behind the head's newest sample.  After an outage, the first collector to drain moves the head forward, and older samples from the others are then accepted only within `out_of_order_time_window` (10m today).  A sample older than that is answered with 400, which is permanent, and dropped.

## Goals / Non-Goals

**Goals:**

- A Mimir outage or restart of up to 1 hour loses no metric sample, while the collectors keep running.
- The same for traces and profiles from the Java agent; logs for 30 minutes, Loki's window (see Decision 5).
- A Loki restart loses no log line, including what the collector sends while Loki starts (Decision 5a).
- The integration tests prove it for metrics and logs, and each fails before its fix.

**Non-Goals:**

- Surviving a collector restart during an outage.  The queues are in memory (owner decision): what a collector holds when it restarts is lost.
- A persistent queue, a WAL, or `file_storage` (owner decision, option A).
- A `memory_limiter` (absolute rule).
- Switching metrics to OTLP (option C).
- Outages longer than 1 hour.

## Decisions

### 1. `prometheus_remote_write` settings

```yaml
prometheus_remote_write:
  http:
    endpoint: __METRICS_ENDPOINT__
    tls: {insecure: true}
    headers: {X-Scope-OrgID: ${env:TENANT}}
    timeout: 30s
  timeout: 0
  retry_on_failure:
    enabled: true
    initial_interval: 5s
    max_elapsed_time: 0
  remote_write_queue:
    enabled: true
    queue_size: 20000
  resource_to_telemetry_conversion: {enabled: true}
```

- `timeout: 0` removes the deadline around the retries.  `http.timeout: 30s` bounds one request, so a Mimir that accepts a connection and hangs fails that attempt, and the next attempt runs.
- `max_elapsed_time: 0` retries a batch until Mimir answers.
- `initial_interval: 5s`: since the backoff never grows, this is the retry cadence.  At the 50ms default a down Mimir is hit 20 times a second by every collector.
- `max_interval` is **not** set: it has no effect in PRW 0.161.0, which does not pass it to the backoff.  The retry interval is fixed at about 5s (owner decision, 2026-11-17).
- `queue_size: 20000`: the batch processor flushes at 1024 points or 10s.  qa971f sent about 4,700 samples/s in all.  If one collector sent all of it, that is about 4.6 batches a second, about 16,500 an hour; 20000 holds that.  A collector on a db node sends far less.
- `num_consumers` stays at the default.  Without the alpha gate the queue has one consumer, which keeps a collector's batches in order (so its own series replay in order) and each push still sends 5 requests in parallel.

Alternatives considered: a longer `timeout` alone (still a deadline; a long outage still drops); `max_elapsed_time` alone (does nothing while the 5s deadline wraps it, as the findings comment says); the PRW `wal` (option B) and OTLP with `file_storage` (option C), both declined by the owner.

### 2. Mimir `out_of_order_time_window: 1h`

The window must cover the longest outage the queues cover, or the replay is rejected as too old.  1 hour covers a config roll, a crash loop that someone notices and fixes, and the 11-minute qa971f outage with room to spare, and it matches the queue size.  A larger window keeps more out-of-order samples in the head's memory, and is not needed for an outage the queue cannot hold.  The rendered value is shared by the account compactor (it reads the same `mimir.yaml`); the compactor does not use the limit.

### 3. Loki and Tempo exporters

For `otlp_http/logs` and `otlp_grpc/tempo`: `retry_on_failure.max_elapsed_time: 0` and `sending_queue.queue_size: 20000` (requests sizer, the default).  The queues stay in memory, with no `storage`.  `otlp_grpc`'s 5s per-attempt timeout stays: it is not a deadline around the retries.

### 4. Pyroscope Java agent

Add `-Dpyroscope.ingest.max.tries=-1` and `-Dpyroscope.push.queue.capacity=360` (1 hour of 10s snapshots) wherever the tool starts the agent: `StressJobManifestBuilder`, `SidecarManifestBuilder`, `EMRProvisioningService`, and `edl_pyroscope_opts` in `edl-kit-lib.sh`.  The values go in `Constants.PyroscopeJavaAgent`.  The queue holds compressed JFR snapshots in the JVM heap.

### 5. Coverage differs per signal

- Metrics, traces, Java-agent profiles: 1 hour.
- Logs: 30 minutes.  Loki's `max_chunk_age: 1h` makes its per-stream out-of-order window 30 minutes; a line older than that is rejected with 400, which `otlp_http` drops.  Raising the window means raising `max_chunk_age`, which delays when logs reach S3 (the observability-store spec says within about 1 hour).  Loki keeps `max_chunk_age: 1h` (owner decision, 2026-11-17), so logs are covered for 30 minutes; the docs state the limit.
- eBPF profiles: about 15 seconds; Alloy cannot queue (see Context).  The owner accepts this loss; the follow-up is #1206 (keep eBPF profiles through a Pyroscope outage).  The docs reference it.

### 5a. Loki at start

`otlp_http` treats every status other than 429, 502, 503 and 504 as permanent, 500 included.  If Loki answers 500 while it starts (before its ingester is ready), the collector drops those log batches.  The integration test restarts Loki and decides whether this happens.  If it does, the fix is in this change, and the test chooses between:

- a readiness gate: the collector reaches Loki through its Service, so a Loki pod that is not Ready has no endpoint, and the collector sees a connection error, which it retries; or
- an exporter setting that makes `otlp_http/logs` retry a 500, if 0.161.0 has one.

The test proves the chosen fix: it fails without it and passes with it.

### 6. Integration test

`CollectorReplayIntegrationTest` in `src/integrationTest`: the pinned collector with its rendered configuration, unedited, in front of the pinned Mimir with its rendered configuration (`ObservabilityBackends.startMimir`, LocalStack S3).  The containers share a user-defined Docker network, and Mimir carries the network alias `mimir.default.svc.cluster.local`, so the collector finds it again after a restart.

1. Push one gauge sample a second over OTLP to the collector, each with its own timestamp and a value equal to its sequence number.
2. After 15s, stop the Mimir container (its data volume stays), keep pushing for 60s (well past the old 5s deadline), start Mimir again, keep pushing for 15s.
3. Assert that every pushed timestamp is in Mimir: a query over the whole run returns every sequence number, none missing.  Wait up to 4 minutes for the queue to drain.

Before the fix, the batches sent while Mimir was down are dropped after 5s and the gap's sequence numbers are missing, so the test fails.

A second test does the same for logs: the rendered collector in front of the pinned Loki with its rendered configuration (Loki aliased `loki.default.svc.cluster.local`).  It sends one numbered log line a second, stops Loki for 6 minutes (past the old 300s retry limit, so it fails before the fix), starts it again, and asserts that every line is in Loki.  It covers both the outage and Loki's start (Decision 5a).  A third test does the same for traces against the pinned Tempo (aliased `tempo.default.svc.cluster.local`), also down for 6 minutes; it counts a trace only when Tempo's response carries its span, because Tempo answers 200 with an empty trace for an id it does not have.  If Loki's readiness is the fix, the test reaches Loki the way a cluster does: it does not send until Loki answers `/ready`, as the Service would.  A unit test asserts the rendered exporter settings (`TelemetryOutageConfigTest`), and `OtelCollectorConfigValidationIntegrationTest` validates the new keys against the pinned collector.

`PyroscopeAgentReplayIntegrationTest` covers the Java agent: a JVM with the pinned agent jar (`io.pyroscope:agent` at `Constants.PyroscopeJavaAgent.VERSION`, resolved by Gradle) and `OUTAGE_FLAGS` profiles a busy loop (with `itimer`, because perf events are not available in a container) and sends to the pinned Pyroscope with its rendered configuration (aliased `pyroscope.default.svc.cluster.local`).  The test stops Pyroscope for 150s, past the defaults (8 snapshots, about 80s, and 8 tries), starts it again on the same volume, and asserts that every whole 10s window of the outage holds CPU samples.  With the defaults, the first 60s of the outage hold none.

The test also found a loss in the server's shutdown.  From SIGTERM until it exits, Pyroscope 2.3.1 keeps its HTTP port open with the segment writer already stopped, and answers every push with 422 ("service is unavailable").  The agent retries only 429 and 5xx, so it drops each snapshot it sends in that window; a graceful stop took 30s, and the windows around it held no profile.  The wait comes from fixed timers that also run at shutdown: the segment writer's `min_ready_duration` (30s), the metastore's (15s), and the usage-stats module (15s).  `config.yaml` sets both `min_ready_duration` to `0s` and `analytics.reporting_enabled: false`.  Measured in Docker: the server exits within 4s of SIGTERM, the agent then gets connection refused, which it retries, and 20 of 20 profiles pushed in the 20s before the stop are queryable after the restart on the same volume.  `/ready` still waits for every module, and passed 18s after start instead of 44s.

Start has a second window.  Without a tokens file the segment writer rejoins its ring with no tokens, and the distributor caches that empty ring for its 5s refresh.  For about 5.5s after the port opens every push is refused (422 for JFR and pprof, 500 for folded), and `/ready` passes inside that window.  Measured the same on main and on this branch: 6 of 6 starts each, about 45 refused pushes.  `segment_writer.lifecycler.tokens_file_path` puts the tokens on the data volume, so a restarted server takes its first push 0.06 to 0.41s after the port opens, with no refusal (3 of 3).  One race remains: the port opens about 20ms before the segment writer reads the tokens file, and no setting delays the port, so a push in those first milliseconds is refused (3 of 10 Gradle runs refused exactly one push, 2ms after the port opened, and none after it).  An agent that pushes in that instant loses that one snapshot.  A first start on an empty volume still has the window, and no agent has queued profiles for it then.  `PyroscopeIndexDurabilityIntegrationTest` pushes from the moment a restarted server's port opens and asserts that no push is refused after the first 100ms.  The pushes come from a busybox container in the server's network namespace, as an agent's reach it; from the test JVM they went through Docker Desktop's port proxy, which holds a connection made before the server listens for up to 1.5s, so the check missed the window in about half the runs with the tokens file removed.  The restarted server waits at a start gate, a static busybox that execs Pyroscope once a file exists; the pusher sees the port closed, creates the file, and times every push from the moment the port opens.  Docker cannot start a container in the namespace of one that is not running yet, so the gate gives that order.  On the real cluster, a rollout restart before the tokens file logged one 422 in the stress JVM's agent; a restart with it logged none.

### 7. Real-cluster check

1 db, 1 app, 1 control, `--tag embark_offhours=off`, a light stress load.  Restart Mimir and hold it down for a few minutes (scale to 0, then back to 1), then show in Mimir that a per-second series has no missing samples across the gap, and that `otelcol_exporter_send_failed_metric_points_total` did not rise.

## Risks / Trade-offs

- [A collector restart during an outage loses its queue] → Accepted by the owner (in-memory, option A).  It includes a `down` that stops the collectors while Mimir is down; `down` flushes Mimir after the collectors stop, so it never stops them during a backend outage it causes.
- [Capacity: 20000 batches last an hour only below about 5.5 batches (about 5,600 data points) a second; a busier collector fills its queue sooner and drops new batches, and the docs state that limit as a rate. Memory: a full queue holds 20000 batches in the collector's memory, up to a few GB on a busy node] → The nodes have the memory, and there is no limiter by rule.  The queue only grows while a backend is down.
- [Drain time: one consumer drains a backlog more slowly than many] → At tens of pushes a second, an hour's backlog drains in minutes.  The real-cluster check measures it.
- [Replay pressure on Mimir: 429 is permanent in PRW] → Mimir's ingestion limits are far above a test cluster's rate (`ingestion_rate: 10000000`).
- [Loki answers 500 while it starts, which `otlp_http` treats as permanent] → The Loki restart test decides, and this change fixes it (Decision 5a).
- [Java agent heap: 360 snapshots in a small JVM] → The queue fills only during a Pyroscope outage, and it holds the snapshots in the profiled JVM's own heap.  Measured with agent 2.3.0, the production options (`jfr`, `alloc=512k`, `lock=10ms`) and a synthetic load of 8 threads with many distinct stacks, heavy allocation and a contended lock: each upload was 4.1 to 6.4 MB, and live heap after a full GC grew by about 15 MB per snapshot for the first 150s of an outage and about 5 MB per snapshot after that, reaching 260 MB after 240s (24 snapshots).  A real workload is far smaller.  On a real cluster, cassandra-easy-stress (Temurin 21, ZGC) under load with Pyroscope stopped for 14.4 minutes queued 84 snapshots, and the live `byte[]` heap in `jcmd GC.class_histogram` grew from 21.2 MB to 35.7 MB: 0.17 MB a snapshot.  A full 360-snapshot queue is about 62 MB, inside the owner's 256 MB budget, so 360 stays.  After Pyroscope came back the queue drained and the heap was back to 21.2 MB within 80s.

## Migration Plan

None.  The collector and Mimir roll through `ConfigHashAnnotator` on the next `up` or `grafana update-config`.  Clusters are ephemeral.

## Owner decisions (2026-11-17)

- PRW retries at a fixed `initial_interval: 5s`; `max_interval` has no effect in PRW 0.161.0 and is not set.
- Loki keeps `max_chunk_age: 1h`; logs are covered for 30 minutes, and the docs state it.
- Alloy eBPF profiles are lost during a Pyroscope outage longer than about 15s; accepted.  Follow-up: #1206.
- The integration test also restarts Loki and asserts every log line arrives.  If Loki's 500 at start loses lines, this change fixes it (Decision 5a).

## Open Questions

- Closed: the Java agent queue size.  The owner set a floor of 60 snapshots (10 minutes at the 10s upload interval) and a budget of about 256 MB of heap for a full queue.  Measured on a real Cassandra stress workload at 0.17 MB a snapshot, a full 360-snapshot queue is about 62 MB, so the queue stays at 360 (see Risks).
