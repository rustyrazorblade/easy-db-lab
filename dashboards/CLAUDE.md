# Dashboards

JSON dashboard files in this directory are loaded into Grafana via `GrafanaManifestBuilder`. Gradle copies them into classpath resources at build time. **Always run `./gradlew installDist` before `grafana update-config`** — `update-config` reads from the built JAR, not the source files directly.

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

## Modifying Dashboards

When adding new dashboards or modifying existing ones:

1. Edit the JSON in `dashboards/` (or kit-specific path under `src/main/resources/.../kits/`)
2. Run `./gradlew installDist` to bundle the updated JSON into the JAR
3. Run `<cluster>/easy-db-lab grafana update-config` to push to the cluster
4. Push test data if the change affects trace/metric panels
5. Grafana hot-reloads provisioned dashboards from ConfigMaps — no Grafana restart needed for dashboard changes (datasource config changes do require a restart)
