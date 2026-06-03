## 1. Global Export Script

- [x] 1.1 Create `bin/export-workload-metrics` — reads `env.sh`, queries VictoriaMetrics `/api/v1/series?match[]={job="$1"}`, writes `<workload>/metrics-catalog.json` with `workload`, `exported_at`, and `series` fields (using jq, not Python)
- [x] 1.2 Add usage/error handling: fail with message if argument missing or `env.sh` not found
- [x] 1.3 Make the script executable (`chmod +x`)

## 2. Presto Metrics Artifacts (generated from live cluster)

- [x] 2.1 Run `bin/export-workload-metrics presto` against a live cluster with Presto running; commit the output as `src/main/resources/.../install/presto/metrics-catalog.json`
- [x] 2.2 Author `src/main/resources/.../install/presto/METRICS.md` — markdown table of key Presto metrics derived from `metrics-catalog.json` (JVM heap, active drivers, query latency, etc.)
- [x] 2.3 Author `src/main/resources/.../install/presto/dashboards/presto.json` — Grafana dashboard JSON with panels for key metrics from METRICS.md; include `cluster` multi-select variable; scope all queries with `{cluster=~"$cluster",job="presto"}`

## 3. ClickHouse Metrics Artifacts (generated from live cluster)

- [ ] 3.1 Run `bin/export-workload-metrics clickhouse` against a live cluster with ClickHouse running; commit the output as `src/main/resources/.../install/clickhouse/metrics-catalog.json`
- [ ] 3.2 Author `src/main/resources/.../install/clickhouse/METRICS.md` — markdown table of key ClickHouse metrics (queries/sec, memory, merges, connections, etc.)
- [ ] 3.3 Author `src/main/resources/.../install/clickhouse/dashboards/clickhouse-workload.json` — Grafana dashboard for ClickHouse workload metrics; distinct from any built-in ClickHouse dashboard; scope all queries with `{cluster=~"$cluster",job="clickhouse"}`

## 4. Integration Test: Presto Metrics Verification

- [x] 4.1 Add a step to `bin/tests/presto` after "Starting Presto" that sleeps 30s then queries VictoriaMetrics `/api/v1/series?match[]={job="presto"}` via `CONTROL_HOST_PRIVATE` and asserts ≥10 series; fail with a clear message on insufficient series

## 5. Integration Test: ClickHouse Metrics Verification

- [x] 5.1 Add a step to `bin/tests/clickhouse` (create if it doesn't exist) after the ClickHouse start step that sleeps 30s then queries VictoriaMetrics and asserts ≥10 series with `job="clickhouse"`

## 6. Workload-Creation Skill Update

- [x] 6.1 Update the `generate-workload` skill (or its documentation) to explain that scrape-type workloads SHOULD include `metrics-catalog.json`, `METRICS.md`, and a dashboard; document the workflow: start workload → run `bin/export-workload-metrics` → author METRICS.md from catalog → build dashboard from catalog
