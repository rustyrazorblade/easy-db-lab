## ADDED Requirements

### Requirement: Telemetry waits out a backend outage instead of dropping it

A telemetry sender SHALL hold what it cannot deliver while a backend is down or restarting, in memory, and SHALL retry it until the backend accepts it.  It SHALL NOT drop data because of a deadline or a retry limit.  No sender SHALL use a persistent queue, a WAL, `file_storage`, or a `memory_limiter`.

- **Metrics.**  The OTel collector's `prometheus_remote_write` exporter (collector contrib 0.161.0) SHALL keep the HTTP settings (`endpoint`, `tls`, `headers`) under `http:`, with a 30-second per-request `http.timeout`.  It SHALL set the exporterhelper `timeout` to 0, because in 0.161.0 that timeout is a deadline around the whole push, retries included.  It SHALL retry with `retry_on_failure.max_elapsed_time: 0`.  Its in-memory queue is `remote_write_queue`; this exporter has no `sending_queue`.  `remote_write_queue` SHALL be enabled and sized to hold 1 hour of one collector's batches.
- **Out-of-order window.**  Mimir's `limits.out_of_order_time_window` SHALL be 1 hour, so that samples replayed after an outage of up to 1 hour are accepted.  It matches the outage the queues hold.
- **Logs and traces.**  The collector's `otlp_http/logs` and `otlp_grpc/tempo` exporters SHALL retry with `retry_on_failure.max_elapsed_time: 0`, and their in-memory `sending_queue` SHALL be sized to hold 1 hour of one collector's requests.
- **Java-agent profiles.**  Every Pyroscope Java agent the tool starts SHALL retry an upload without limit (`pyroscope.ingest.max.tries=-1`) and SHALL queue 1 hour of snapshots in memory (`pyroscope.push.queue.capacity`).

What a sender holds in memory is lost if the sender itself restarts.  Two signals have shorter limits, set by the backend or the sender, not by these settings: Loki accepts a log line up to 30 minutes out of order per stream, and the Alloy eBPF profiler (v1.20.0) has no queue and drops what it cannot send within its 15-second collect interval.

#### Scenario: A Mimir restart loses no sample
- **WHEN** Mimir is down for longer than 5 seconds while a collector keeps receiving metrics
- **AND** Mimir starts again
- **THEN** every sample the collector received during the outage is in Mimir

#### Scenario: An hour-long Mimir outage loses no sample
- **WHEN** Mimir is down for up to 1 hour while the collectors keep running
- **THEN** each collector keeps the batches it could not send and retries them
- **AND** Mimir accepts the replayed samples after it starts, none rejected as too old

#### Scenario: A rejected batch is not retried forever
- **WHEN** Mimir answers a remote write with a 4xx status other than 429
- **THEN** the collector drops that batch and sends the next one

#### Scenario: A Tempo restart loses no span
- **WHEN** Tempo is down for longer than 5 minutes while a collector keeps receiving spans
- **AND** Tempo starts again
- **THEN** every span the collector received during the outage is in Tempo

#### Scenario: A Pyroscope restart loses no Java-agent profile
- **WHEN** the Pyroscope server is down for less than 1 hour while a Java agent keeps profiling
- **THEN** the agent keeps every snapshot and uploads it after the server starts

#### Scenario: No sender queues on disk
- **WHEN** the collector configuration is rendered
- **THEN** no exporter has a `storage` or `wal` setting, and no `memory_limiter` processor exists
