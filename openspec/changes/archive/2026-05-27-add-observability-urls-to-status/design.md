## Context

The `/status` endpoint returns an `accessInfo.observability` object with URLs for services running on the control node. Currently it includes Grafana, VictoriaMetrics, and VictoriaLogs. Tempo (tracing) and Pyroscope (profiling) run on the same control node and have constants already defined (`TEMPO_PORT=3200`, `PYROSCOPE_PORT=4040` in `Constants.K8s`). The pattern for constructing URLs is uniform: `"http://$controlIp:$port"`.

## Goals / Non-Goals

**Goals:**
- Expose `tempo` and `pyroscope` URLs in the `accessInfo.observability` response object, consistent with existing fields

**Non-Goals:**
- Changing how other fields are constructed
- Adding conditional logic (these services always run on the control node)
- Any changes to port assignments or service configuration

## Decisions

**Field naming: `tempo` and `pyroscope` (no `Url` suffix)**
Consistent with existing fields (`grafana`, `victoriaMetrics`, `victoriaLogs`) which don't use a `Url` suffix. Diverging would be inconsistent.

**No nullability**
All other `ObservabilityAccess` fields are non-nullable `String`. Tempo and Pyroscope are always deployed on the control node alongside the rest of the observability stack, so no conditional logic is needed.

## Risks / Trade-offs

No meaningful risks. The ports are already defined constants, the construction pattern is identical to existing fields, and no consumers currently depend on the shape of `ObservabilityAccess` in tests.
