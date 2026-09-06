# cassandra-dashboards

## ADDED Requirements

### Requirement: Cassandra client-request latency panels declare milliseconds

The Cassandra Overview and Cluster Comparison dashboards SHALL declare their client-request latency panel units as `ms`, matching the millisecond values the metrics collector emits.

#### Scenario: Cassandra Overview latency panels render in milliseconds

- **WHEN** the Cassandra Overview dashboard is opened against a cluster under load
- **THEN** Read Latency (p99 / p999), Write Latency (p99 / p999), Read p99 per Node and Write p99
  per Node each render axis and tooltip values in `ms`
- **AND** a p99 confirmed as roughly 4 ms via `nodetool proxyhistograms` reads as roughly 4 ms,
  not 4 µs and not 4000 ms

#### Scenario: Cluster Comparison latency panels render in milliseconds

- **WHEN** the Cluster Comparison dashboard is opened
- **THEN** the Cluster Summary table, Write p99 per Cluster, Write p50 + p99 per Cluster, Write
  p99 Range, the Write Latency Distribution heatmap Y axis, and the Latency Percentile Ladder all
  render in `ms`
- **AND** no latency panel on either dashboard is left declaring microseconds

#### Scenario: No microsecond unit remains in either dashboard file

- **WHEN** either dashboard file is searched for a microsecond unit
- **THEN** none is found, in either the raw UTF-8 form or the escaped form
- **AND** this includes the microsecond reference inside the heatmap panel's own description prose

### Requirement: Latency threshold breakpoints fire at their intended millisecond values

Latency thresholds SHALL be expressed in the panel's declared unit so that Grafana, which
compares thresholds against the raw value, colors cells at the intended breakpoints of 5 ms and
20 ms.

#### Scenario: Table cells color by threshold at real latencies

- **WHEN** a cluster write p99 sits between 5 ms and 20 ms
- **THEN** the Cluster Summary `Write p99` cell and the Latency Percentile Ladder cells are yellow
- **WHEN** the write p99 exceeds 20 ms
- **THEN** those same cells are red

#### Scenario: Threshold lines on the per-cluster timeseries sit at their intended values

- **WHEN** the Write p99 per Cluster timeseries is rendered
- **THEN** its threshold lines, drawn because the panel sets `thresholdsStyle.mode` to `line`,
  sit at 5 ms and 20 ms rather than below every real data point

### Requirement: The correction is confined to the display layer

The change SHALL alter only unit declarations, threshold values and the one description string,
leaving the metrics pipeline untouched.

#### Scenario: No query or pipeline change accompanies the fix

- **WHEN** the resulting diff is reviewed
- **THEN** no `targets[].expr` string has changed
- **AND** no file under `packer/`, no OTel collector configuration and no collector builder
  appears in it

#### Scenario: The edit preserves file encoding and formatting

- **WHEN** the diff is inspected
- **THEN** only the two dashboard JSON files are modified, both still parse as JSON, and the
  changed-line count matches the enumerated edits with no whole-file reindent
- **AND** every remaining non-ASCII character, including the em dash in the heatmap panel title,
  is byte-identical to before

#### Scenario: The deployed dashboard is verified from Grafana

- **WHEN** the change is deployed with `./gradlew installDist` followed by `grafana update-config`
- **THEN** reading the dashboard back from the Grafana API shows the `ms` unit, confirmed from
  Grafana itself rather than from the deploy command's success message
