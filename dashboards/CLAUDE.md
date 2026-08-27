# Dashboards

JSON dashboard files in this directory are loaded into Grafana via `GrafanaManifestBuilder`. Gradle copies them into classpath resources at build time. **Always run `./gradlew installDist` before `grafana update-config`** — `update-config` reads from the built JAR, not the source files directly.

Note: These dashboard are considered LEGACY.  Do not add any additional dashboards here.  All dashboards associated with kits should be in the dashboard directory of the kit, not here.

## Datasource UIDs

| Datasource        | UID              | Type                                    |
|-------------------|------------------|-----------------------------------------|
| VictoriaMetrics   | `VictoriaMetrics`| `prometheus`                            |
| VictoriaLogs      | `victorialogs`   | `victoriametrics-logs-datasource`       |
| Tempo             | `tempo`          | `tempo`                                 |
| Pyroscope         | `pyroscope`      | `grafana-pyroscope-datasource`          |

## Label Name Conventions

Labels differ between VictoriaMetrics (Prometheus-style, underscores) and VictoriaLogs (OTel-style, dots):

| Concept      | VictoriaMetrics label | VictoriaLogs field  |
|--------------|-----------------------|---------------------|
| Service name | `service_name`        | `service.name`      |
| Host name    | `host_name`           | `host.name`         |
| Namespace    | `k8s.namespace.name`  | `k8s.namespace.name`|
| Pod name     | `k8s.pod.name`        | `k8s.pod.name`      |
| Trace ID     | n/a                   | `trace_id`          |

**Spanmetrics connector** (from OTel) produces VictoriaMetrics labels: `service_name`, `span_name`, `status_code`, `db_system`. Dashboard panel queries must use these underscore-style names.

The `transform/add_service_name` OTel processor copies `service.name` → `service_name` on all log pipelines so Grafana's auto-generated label filters work against VictoriaLogs too.

## Trace Links in Log Panels — Two Distinct Mechanisms

**This is a frequent source of mistakes. Read carefully before adding any trace navigation to a logs panel.**

### Mechanism 1 — Per-row "View Trace in Tempo" button (datasource-level, global)

Configured on the `victorialogs` datasource in `GrafanaDatasourceConfig.kt` via `derivedFields`:

```kotlin
GrafanaDerivedField(
    name = "trace_id",
    field = "trace_id",
    matcherRegex = "([a-f0-9]{32})",  // only matches valid 128-bit hex trace IDs
    url = "",
    datasourceUid = "tempo",
    urlDisplayLabel = "View Trace in Tempo",
)
```

- Appears as a button in the **log detail drawer** when a log row has a `trace_id` field matching the regex
- Navigates to Tempo Explore with that specific trace ID pre-filled
- **This is global** — it applies to every VictoriaLogs panel on every dashboard
- **Nothing in the dashboard JSON controls this** — do NOT try to configure it per-panel
- If a service has no traces (e.g. TiDB before OTel was wired up), the button simply does not appear because `trace_id` is absent or doesn't match the regex

### Mechanism 2 — Data-point links on metric/timeseries panels (dashboard JSON)

Used on timeseries panels via `fieldConfig.defaults.links`. Appears when hovering over a data point. Contains a full `/explore?panes=` URL with a TraceQL query using Grafana template variables:

```json
{
  "url": "/explore?schemaVersion=1&orgId=1&panes=<URL-encoded JSON with TraceQL>",
  "title": "View in Tempo"
}
```

The panes JSON contains `queryType: "traceql"` with a query like `{ resource.host.name = "${__field.labels.host_name}" }` and `${__from}`/`${__to}` time bounds. See the Cross-Dashboard Navigation section below for how to build these URLs correctly.

- **Logs panels (`type: "logs"`) do NOT support this mechanism** — `fieldConfig.defaults.links` is ignored on log panels
- Only works on `timeseries`, `stat`, `table`, and similar metric panel types
- Use `bin/generate-dashboard-links.py` to build correct URL-encoded panes values

### Summary table

| What you want | Where to configure it | Panel types |
|---|---|---|
| "View Trace in Tempo" per log row | `GrafanaDatasourceConfig.kt` derivedFields | logs (global, auto) |
| Trace search link on hover | `fieldConfig.defaults.links` in panel JSON | timeseries, stat, table |
| Panel-level link (header menu) | `panel.links` in panel JSON | all types |

## Spanmetrics Metric Names

The OTel spanmetrics connector with `namespace: traces.spanmetrics` emits:

- `traces_spanmetrics_calls_total` — request count
- `traces_spanmetrics_duration_milliseconds_bucket` — latency histogram (**milliseconds**)
- `traces_spanmetrics_duration_milliseconds_sum`
- `traces_spanmetrics_duration_milliseconds_count`

**Do NOT use `traces_spanmetrics_latency_bucket`** — that is emitted by Tempo's internal metrics generator and only has `le="+Inf"` (useless for `histogram_quantile`).

Panel units for latency must be `ms` (not `s`) since the histogram buckets are in milliseconds.

## Cross-Dashboard Navigation (dataLinks)

### Grafana Explore URL Format

Always use `panes=` (NOT the legacy `left=` parameter). The `left=` format is undocumented legacy behavior and does not reliably pre-fill Tempo queries.

```
/explore?schemaVersion=1&orgId=1&panes=<URL-encoded-JSON>
```

The panes value is a JSON object URL-encoded with `urllib.parse.quote`. Grafana template variables (`${__field.labels.service_name}`, `${__from}`, `${__to}`) must **not** be URL-encoded — leave them as-is so Grafana interpolates them before navigating.

Use the Python helper in `bin/generate-dashboard-links.py` (or inline in migration scripts) to build correct URLs:

```python
import json, re, urllib.parse

def encode_panes(panes_dict):
    s = json.dumps(panes_dict, separators=(',', ':'))
    vars_found = []
    def stash(m):
        vars_found.append(m.group(0))
        return f"__GV{len(vars_found)-1}__"
    s2 = re.sub(r'\$\{[^}]+\}', stash, s)
    encoded = urllib.parse.quote(s2, safe='')
    for i, v in enumerate(vars_found):
        encoded = encoded.replace(f"__GV{i}__", v)
    return encoded
```

### Tempo Explore Link (TraceQL)

Use `queryType: "traceql"` with a raw TraceQL `query` string. **Do NOT use `queryType: "traceqlSearch"` with a `filters` array** — constructing filter objects from scratch is fragile and results in an empty TraceQL box.

```python
def tempo_explore(traceql, title):
    panes = {"a": {"datasource": "tempo",
                   "queries": [{"refId": "A",
                                "datasource": {"uid": "tempo", "type": "tempo"},
                                "queryType": "traceql",
                                "query": traceql,
                                "limit": 20}],
                   "range": {"from": "${__from}", "to": "${__to}"}}}
    return {"title": title,
            "url": "/explore?schemaVersion=1&orgId=1&panes=" + encode_panes(panes),
            "targetBlank": True}
```

Common TraceQL patterns:
- By service: `{ resource.service.name = "${__field.labels.service_name}" }`
- By host: `{ resource.host.name = "${__field.labels.host_name}" }`
- By service + operation: `{ resource.service.name = "${__field.labels.service_name}" && name = "${__field.labels.span_name}" }`
- ClickHouse spans: `{ span.db.system = "clickhouse" }`

### VictoriaLogs Explore Link

```python
def logs_explore(expr, title):
    panes = {"a": {"datasource": "victorialogs",
                   "queries": [{"refId": "A",
                                "datasource": {"uid": "victorialogs",
                                               "type": "victoriametrics-logs-datasource"},
                                "expr": expr}],
                   "range": {"from": "${__from}", "to": "${__to}"}}}
    return {"title": title,
            "url": "/explore?schemaVersion=1&orgId=1&panes=" + encode_panes(panes),
            "targetBlank": True}
```

Common VictoriaLogs LogsQL patterns:
- By service: `service_name:="${__field.labels.service_name}"`
- By host: `host.name:="${__field.labels.host_name}"`

### Pyroscope Explore Link

```python
def pyroscope_explore(label_selector, title):
    panes = {"a": {"datasource": "pyroscope",
                   "queries": [{"refId": "A",
                                "datasource": {"uid": "pyroscope",
                                               "type": "grafana-pyroscope-datasource"},
                                "profileTypeId": "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
                                "labelSelector": label_selector}],
                   "range": {"from": "${__from}", "to": "${__to}"}}}
    return {"title": title,
            "url": "/explore?schemaVersion=1&orgId=1&panes=" + encode_panes(panes),
            "targetBlank": True}
```

Example: `label_selector = '{service_name="${__field.labels.service_name}"}'`

### Dashboard Navigation Link (panel header)

Added to the panel-level `links` array (not `fieldConfig.defaults.links`). Appears in the panel `...` menu.

```python
def sysoverview_link():
    return {"title": "System Overview",
            "url": "/d/system-overview/system-overview?from=${__from}&to=${__to}",
            "targetBlank": False}
```

### Where Links Go

- `fieldConfig.defaults.links` — data point links, appear on hover over a series (timeseries panels)
- `panel.links` — panel header links, appear in the `...` menu (all panel types including logs panels)

Logs panels (`type: "logs"`) do not support `fieldConfig.defaults.links` for per-row linking. Use datasource `derivedFields` for that (configured in `GrafanaDatasourceConfig.kt`).

## tracesToLogsV2 and tracesToMetrics

Configured on the Tempo datasource in `GrafanaDatasourceConfig.kt`.

**tracesToLogsV2**: Use `customQuery: true` with an explicit LogsQL query to bypass Grafana's default label generation which converts `service.name` → `service_name` (Loki-style), incompatible with VictoriaLogs field naming:

```kotlin
GrafanaTracesToLogsConfig(
    datasourceUid = "victorialogs",
    spanStartTimeShift = "-1m",
    spanEndTimeShift = "1m",
    filterByTraceID = true,
    filterBySpanID = false,
    customQuery = true,
    query = "trace_id:\"\${__trace.traceId}\"",
)
```

**tracesToMetrics**: Use `traces_spanmetrics_duration_milliseconds_bucket` (not `latency_bucket`). Use `histogram_quantile(0.99, ...)` (p99, not p90). The `$$__tags` variable injects the span's service label as a Prometheus filter.

## Kit Dashboard Metric Queries

### NodePort Triple-Scraping

When a kit exposes Prometheus metrics via a K8s NodePort, the OTel collector scrapes from every cluster node (each node's NodePort redirects to the same pod). This produces one series per node in VictoriaMetrics — typically 3× the actual value.

- **Ratio queries** (e.g. cache hit ratio, error rate): triple-counting cancels out in numerator and denominator — no filter needed.
- **Absolute queries** (rates, byte counts, gauge totals): must filter by `host_name="<db-node>"` to get the correct value. Use the node where the pod actually runs (check with `kubectl get pod <pod> -o wide`; for postgres kits this is typically `db0`).

### VictoriaMetrics Counter Naming

The local Prometheus exporter may expose counters without a `_total` suffix, but VictoriaMetrics stores them **with** `_total` following the OpenMetrics convention. Dashboard queries must always use the `_total` form (e.g. `cnpg_pg_stat_database_blks_hit_total`, not `cnpg_pg_stat_database_blks_hit`). Use the `/api/v1/label/__name__/values` endpoint on VictoriaMetrics to confirm the exact stored name before writing a query.

## Modifying Dashboards

When adding new dashboards or modifying existing ones:

1. Edit the JSON in `dashboards/` (or kit-specific path under `src/main/resources/.../kits/`)
2. Run `./gradlew installDist` to bundle the updated JSON into the JAR
3. Run `<cluster>/easy-db-lab grafana update-config` to push to the cluster
4. Push test data if the change affects trace/metric panels
5. Grafana hot-reloads provisioned dashboards from ConfigMaps — no Grafana restart needed for dashboard changes (datasource config changes do require a restart)

### Verify from Grafana, never from the deploy message

`grafana update-config` prints "All Grafana resources applied successfully!" when it applies the
ConfigMaps. That says nothing about whether the content changed — if `build/resources/main/` is
stale, it will redeploy the old panel and still report success.

Step 2 above is not optional and nothing else substitutes for it. `ktlintFormat` does not rebuild
resources. Confirm the build actually picked up the edit:

```bash
diff <(jq -S . dashboards/system-overview.json) <(jq -S . build/resources/main/system-overview.json) \
  && echo "in sync"
```

Then read the query back from Grafana, and evaluate it through Grafana's own datasource proxy:

```bash
G=http://<control-ip>:3000
curl -s "$G/api/search" | jq -r '.[] | "\(.uid)  \(.title)"'
curl -s "$G/api/dashboards/uid/<uid>" | jq -r '.dashboard.panels[] | select(.title|test("CPU";"i")) | .targets[].expr'

DS=$(curl -s "$G/api/datasources" | jq -r '.[] | select(.type=="prometheus") | .uid' | head -1)
curl -s -G "$G/api/datasources/proxy/uid/$DS/api/v1/query" --data-urlencode 'query=<expr>'
```

Testing PromQL against VictoriaMetrics directly proves the query is right. It does not prove
Grafana is serving that query. Only the second failure is the one a user sees.

### Never edit dashboard JSON with `jq`

`jq` round-trips the whole document. In this repo that produced a 1,644-line reindent of
`cluster-comparison.json` for a one-line fix, and unescaped `µ` to `µ` and `—` to `—`
throughout `cassandra-overview.json`.

Use a literal, byte-preserving replacement (`perl -0pi -e` with `\Q...\E`), then check the diff is
the size you expect and the file still parses:

```bash
git diff --stat dashboards/     # expect 1 changed line per file
jq empty dashboards/<name>.json # still valid JSON
```

### Match the panel's unit before changing scale

Check `fieldConfig.defaults.unit` and `max` before adding or removing a `* 100`:

- `percent` → 0..100, use the `100 * (...)` form
- `percentunit` (often `max: 1`) → 0..1, use the bare fraction with **no** `* 100`

`cluster-comparison.json`'s "CPU Usage by Cluster" is `percentunit` with `max: 1`.

### `system_cpu_time_seconds_total` has no per-core label

The OTel hostmetrics `cpuscraper` emits **one series per host**, summed across every core. There
is no `cpu` label, so `avg by(host_name)` averages a single series and is a no-op.

This idiom is wrong here and shipped in four dashboards, rendering about -190% under load:

```promql
100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle"}[1m])) * 100)   # WRONG
```

On a 4-core node the idle rate is ~4.0, giving `100 - 400 = -300%`. Use idle as a fraction of
total across all states — core-count independent, cannot leave 0..100:

```promql
100 * (1 - sum by(host_name) (rate(system_cpu_time_seconds_total{state="idle", ...}[1m]))
          / sum by(host_name) (rate(system_cpu_time_seconds_total{...}[1m])))
```

Always verify against a real multi-core node. This bug is invisible on a single core.

### Fix the class, not the reported panel

One reported negative CPU panel turned out to be four. Before concluding, grep every dashboard for
the same shape:

```bash
grep -l '<metric>' dashboards/*.json
jq -r '.. | objects | select(has("expr")) | .expr | select(test("<metric>"))' dashboards/*.json
```

Grafana's Home dashboard is set by `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` on the grafana
Deployment (currently `system-overview.json`). A bug there is the first thing a user sees, so
always check Home as well as the dashboard that was reported.
