## ADDED Requirements

### Requirement: Telemetry waits out a backend outage instead of dropping it

A telemetry sender SHALL hold what it cannot deliver while a backend is down or restarting, in memory, and SHALL retry it until the backend accepts it.  It SHALL NOT drop data because of a deadline or a retry limit.  No sender SHALL use a persistent queue, a WAL, `file_storage`, or a `memory_limiter`.

- **Metrics.**  The OTel collector's `prometheus_remote_write` exporter (collector contrib 0.161.0) SHALL keep the HTTP settings (`endpoint`, `tls`, `headers`) under `http:`, with a 30-second per-request `http.timeout`.  It SHALL set the exporterhelper `timeout` to 0, because in 0.161.0 that timeout is a deadline around the whole push, retries included.  It SHALL retry with `retry_on_failure.max_elapsed_time: 0`.  Its in-memory queue is `remote_write_queue`; this exporter has no `sending_queue`.  `remote_write_queue` SHALL be enabled and sized to hold 1 hour of one collector's batches.
- **Out-of-order window.**  Mimir's `limits.out_of_order_time_window` SHALL be 1 hour, so that samples replayed after an outage of up to 1 hour are accepted.  It matches the outage the queues hold.
- **Logs and traces.**  The collector's `otlp_http/logs` and `otlp_grpc/tempo` exporters SHALL retry with `retry_on_failure.max_elapsed_time: 0`, and their in-memory `sending_queue` SHALL be sized to hold 1 hour of one collector's requests.
- **Loki at start.**  A log line the collector sends while Loki is starting SHALL NOT be lost: either the collector cannot reach Loki until it is ready, or the collector retries Loki's answer.
- **Retry interval.**  The metrics exporter SHALL retry at a fixed 5-second interval (`initial_interval: 5s`).  `max_interval` has no effect in `prometheus_remote_write` 0.161.0 and SHALL NOT be relied on.
- **Java-agent profiles.**  Every Pyroscope Java agent the tool starts SHALL retry an upload without limit (`pyroscope.ingest.max.tries=-1`) and SHALL queue 1 hour of snapshots in memory (`pyroscope.push.queue.capacity`).
- **Database-node JFR chunks.**  The JFR shipper on database nodes (`edl-profiling-reconcile`) SHALL keep a chunk that Pyroscope answers with 429, or with a 422 whose body names a server that is shutting down or starting ("service is unavailable", "empty ring"), as it keeps one answered with 5xx or not answered, and SHALL send it again on a later pass.  Any other 4xx, a 422 for a body Pyroscope cannot parse included, marks a chunk rejected, so a malformed chunk cannot hold the queue.

What a sender holds in memory is lost if the sender itself restarts.  Two signals have shorter limits, set by the backend or the sender, not by these settings: Loki keeps `max_chunk_age: 1h`, so it accepts a log line up to 30 minutes out of order per stream, and logs are covered for an outage of up to 30 minutes.  The Alloy eBPF profiler (v1.20.0) has no queue and drops what it cannot send within its 15-second collect interval, so a Pyroscope outage longer than about 15 seconds loses eBPF profiles; the follow-up is #1206.  The OpenTelemetry Java agents (Cassandra, cassandra-easy-stress, Spark) send OTLP to a collector, not to a backend, so a backend outage reaches them only as the collector's queue; but a restart of that collector does.  Their SDK (agent 2.31.1) allows at most 5 attempts with a 1-5s backoff, about 8 seconds, and has no setting for more, so a collector restart that keeps its port closed longer than that loses what those agents send in it.  The user docs SHALL state these three limits.

#### Scenario: A Mimir restart loses no sample
- **WHEN** Mimir is down for longer than 5 seconds while a collector keeps receiving metrics
- **AND** Mimir starts again
- **THEN** every sample the collector received during the outage is in Mimir

#### Scenario: An hour-long Mimir outage loses no sample
- **WHEN** Mimir is down for up to 1 hour while the collectors keep running
- **THEN** each collector keeps the batches it could not send and retries them
- **AND** Mimir accepts the replayed samples after it starts, none rejected as too old

#### Scenario: A rejected batch is not retried forever
- **WHEN** Mimir answers a remote write with any 4xx status, 429 included
- **THEN** the collector drops that batch and sends the next one

#### Scenario: A Loki restart loses no log line
- **WHEN** Loki is down for longer than 5 seconds, and less than 30 minutes, while a collector keeps receiving log lines
- **AND** Loki starts again
- **THEN** every log line the collector received during the outage and while Loki started is in Loki

#### Scenario: A Tempo restart loses no span
- **WHEN** Tempo is down for longer than 5 minutes while a collector keeps receiving spans
- **AND** Tempo starts again
- **THEN** every span the collector received during the outage is in Tempo

#### Scenario: A Pyroscope restart loses no Java-agent profile
- **WHEN** the Pyroscope server is down for less than 1 hour while a Java agent keeps profiling
- **THEN** the agent keeps every snapshot and uploads it after the server starts, except a snapshot pushed in the instant after the restarted server opens its port and before it reads its ring tokens, which is lost

#### Scenario: A Pyroscope restart loses no database-node JFR chunk
- **WHEN** Pyroscope answers a JFR chunk upload with 422 "service is unavailable" or "empty ring", as it does while it shuts down or starts, or with 429
- **THEN** the chunk stays pending and is sent again on a later pass, and it is not marked rejected

#### Scenario: A malformed JFR chunk is still rejected
- **WHEN** Pyroscope answers a JFR chunk upload with a 422 that names neither cause
- **THEN** the chunk is marked rejected and is not sent again

#### Scenario: No sender queues on disk
- **WHEN** the collector configuration is rendered
- **THEN** no exporter has a `storage` or `wal` setting, and no `memory_limiter` processor exists
