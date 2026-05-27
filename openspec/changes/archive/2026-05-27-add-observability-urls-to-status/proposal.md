## Why

Pyroscope and Tempo are part of the observability stack on every cluster, but their URLs are absent from the `/status` endpoint's `accessInfo.observability` response. Consumers of the status API (MCP agents, scripts) have no way to discover these endpoints without hardcoding port numbers.

## What Changes

- Add `tempo` field to `ObservabilityAccess` in the `/status` response (port 3200)
- Add `pyroscope` field to `ObservabilityAccess` in the `/status` response (port 4040)

## Capabilities

### New Capabilities

None — this extends an existing capability.

### Modified Capabilities

- `server`: The `/status` endpoint's `accessInfo.observability` object now includes `tempo` and `pyroscope` URL fields alongside the existing `grafana`, `victoriaMetrics`, and `victoriaLogs` fields.

## Impact

- `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/StatusResponse.kt` — `ObservabilityAccess` data class
- `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/StatusCache.kt` — `buildAccessInfo()` construction
- No existing tests assert on `ObservabilityAccess` shape; no test changes required
