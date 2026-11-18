## Why

Every Mimir restart loses metrics (#991).  The collector's `prometheus_remote_write` exporter gives up on a batch 5 seconds after its first attempt and drops it, so any Mimir outage longer than that leaves a permanent gap.  On qa971f an 11-minute outage lost every metric for those 11 minutes, and a plain config roll (Mimir's Deployment is `Recreate`) also dropped data.  This breaks the rule that the owner's data is never lost.

The cause, read from the collector contrib 0.161.0 source (the version `Constants.OtelCollector.VERSION` deploys): the exporter retries inside its own push, and exporterhelper's top-level `timeout` (default 5s) is a deadline around that whole push, retries included.  When the deadline passes, the retries stop and the batch is dropped ("Exporting failed. Dropping data. error: context deadline exceeded").

## What Changes

- **Metrics (Mimir).**  Keep `prometheus_remote_write` and its in-memory queue (owner decision, option A).  In `otel-collector-config.yaml`:
  - Move `endpoint`, `tls` and `headers` under `http:`, and give each HTTP request a 30s timeout there (`http.timeout`).
  - Set the top-level `timeout: 0`, so no deadline wraps the retries.
  - Set `retry_on_failure.max_elapsed_time: 0` (retry until Mimir answers), `initial_interval: 5s` and `max_interval: 30s`.
  - Size `remote_write_queue.queue_size` to hold one hour of a collector's batches.
- **Mimir.**  Raise `limits.out_of_order_time_window` from 10m to 1h, so the samples a collector replays after an outage of up to an hour are accepted.
- **Logs (Loki) and traces (Tempo).**  These exporters have a related gap: their retries stop after 300s (`max_elapsed_time` default), and their in-memory `sending_queue` holds 1000 requests.  Set `retry_on_failure.max_elapsed_time: 0` and size `sending_queue.queue_size` for the same hour.  The queues stay in memory.
- **Pyroscope Java agent.**  The agent keeps 8 snapshots (80s) in memory, drops the oldest when full, and gives up on a snapshot after 8 tries.  Set unlimited tries and a larger in-memory queue through its system properties, everywhere the tool starts the agent.
- No persistent queue, no WAL and no `file_storage`.  No `memory_limiter`.
- **Not fixable by configuration:** the Alloy eBPF profiler (`pyroscope.write`, Alloy v1.20.0) has no queue.  Its whole send is bounded by the 15s collect interval, so a Pyroscope outage longer than about 15s drops that window's eBPF profiles.  The design records this for the owner.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `observability-store`: a new requirement that every telemetry sender queues and retries through a backend outage of up to an hour, and that Mimir accepts samples up to an hour out of order.

## Impact

- Config: `configuration/otel/otel-collector-config.yaml` (three exporters), `configuration/mimir/mimir.yaml` (out-of-order window).
- Kotlin and shell: the Pyroscope agent options in `StressJobManifestBuilder`, `SidecarManifestBuilder`, `EMRProvisioningService` and `edl-kit-lib.sh` (`edl_pyroscope_opts`).  The agent's queue size and retry setting go in `Constants`.
- Tests: a new integration test (collector and Mimir in TestContainers) that stops Mimir for longer than the old 5s deadline while a collector sends, starts it again, and asserts that no sample of the gap is missing; it fails before the fix.  Updated unit asserts and golden manifests (`otel.yaml`, `mimir.yaml`, `compactor.yaml`).  `OtelCollectorConfigValidationIntegrationTest` validates the new keys against the pinned collector.
- Rollout: the collector and Mimir roll through `ConfigHashAnnotator` on the next `up` or `grafana update-config`.
- Docs: `docs/user-guide/mimir.md`, `docs/user-guide/loki.md`, `docs/reference/opentelemetry.md`, `configuration/CLAUDE.md`, the `observability-stack` skill.
- Related: #971 (where the loss was found), the 80-node run (this is on its fix list).
