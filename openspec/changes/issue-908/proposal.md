# issue-908: Cassandra latency panels declare µs but the pipeline emits ms

## Why

The client-request latency panels on **Cassandra Overview** and **Cluster Comparison** declare
their unit as microseconds (`µs`), but the values arriving from the metrics collector are
milliseconds. Latency reads 1000x smaller than reality on the two flagship Cassandra dashboards.

The owner has observed the rendered dashboards directly and confirmed the displayed values are
impossible under a `µs` label — a client-request p99 in the tens of microseconds is below a
single network round trip.

The threshold-colored cells are broken by the same mismatch. Grafana compares thresholds against
the raw value, so breakpoints of `5000` and `20000` — written to mean 5 ms and 20 ms under a µs
unit — never fire at real millisecond-scale latencies.

The cause is settled as collector behavior and is out of scope. The collector is being replaced
with the OTel agent later; a pipeline-side workaround would have to be unwound as part of that
work.

## What Changes

- `dashboards/cassandra-overview.json` — `fieldConfig.defaults.unit` from `µs` to `ms` on the
  four client-request latency panels (ids 4, 5, 28, 29). No thresholds, no `min`/`max` on any of
  them, so the unit is the whole edit.
- `dashboards/cluster-comparison.json` — the same unit correction on all six latency panels
  (including the heatmap Y axis and the `(µs)` in its own description prose), plus a rescale of
  the latency thresholds by 1000 on the three panels that carry them, so yellow fires at 5 ms and
  red at 20 ms as originally intended.
- No PromQL expression changes. No collector, relabeling, agent-config or `packer/` changes. No
  new panels, no restructuring, no `GrafanaDashboard` enum changes, no kit dashboard changes.

User-visible effect: latency panels report true magnitudes, and threshold coloring on the Cluster
Summary table, the Latency Percentile Ladder table and the Write p99 per Cluster timeseries
becomes live again at its intended breakpoints.
