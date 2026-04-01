---
name: grafana-dashboards
description: Use when doing any work with Grafana dashboards - creating, modifying, debugging, or deploying, including editing any .json file in the dashboards/ directory. Covers the full architecture, file locations, naming conventions, deployment pipeline, and design principles.
triggers:
  - pattern: "dashboards/*.json"
---

# Grafana Dashboards

## Architecture Overview

Dashboards are standalone JSON files stored as classpath resources. They are loaded by `GrafanaManifestBuilder` (Fabric8), which builds typed ConfigMap objects with `__KEY__` template variable substitution. The `GrafanaDashboard` enum is the single source of truth for dashboard metadata. Grafana loads dashboards via file-based provisioning from mounted ConfigMap volumes.

```
JSON resource files → GrafanaManifestBuilder (Fabric8 ConfigMaps + Deployment)
                          ↓
           GrafanaUpdateConfig command applies to K8s
                          ↓
           Grafana pod mounts ConfigMap volumes → file-based provisioning
```

## Key Files and Locations

| What | Path |
|------|------|
| Dashboard JSON files | `src/main/resources/.../configuration/grafana/dashboards/*.json` |
| Dashboard enum (registry) | `src/main/kotlin/.../configuration/grafana/GrafanaDashboard.kt` |
| Manifest builder | `src/main/kotlin/.../configuration/grafana/GrafanaManifestBuilder.kt` |
| Datasource config | `src/main/kotlin/.../configuration/grafana/GrafanaDatasourceConfig.kt` |
| Dashboard service | `src/main/kotlin/.../services/GrafanaDashboardService.kt` |
| Deploy command | `src/main/kotlin/.../commands/grafana/GrafanaUpdateConfig.kt` |
| Parent command | `src/main/kotlin/.../commands/grafana/Grafana.kt` |

## Existing Dashboards

Defined in the `GrafanaDashboard` enum:

| Enum Entry | ConfigMap Name | JSON Resource |
|------------|---------------|---------------|
| `SYSTEM` | `grafana-dashboard-system` | `dashboards/system-overview.json` |
| `S3` | `grafana-dashboard-s3` | `dashboards/s3-cloudwatch.json` |
| `EMR` | `grafana-dashboard-emr` | `dashboards/emr.json` |
| `OPENSEARCH` | `grafana-dashboard-opensearch` | `dashboards/opensearch.json` |
| `STRESS` | `grafana-dashboard-stress` | `dashboards/stress.json` |
| `CLICKHOUSE` | `grafana-dashboard-clickhouse` | `dashboards/clickhouse.json` |
| `CLICKHOUSE_LOGS` | `grafana-dashboard-clickhouse-logs` | `dashboards/clickhouse-logs.json` |
| `PROFILING` | `grafana-dashboard-profiling` | `dashboards/profiling.json` |
| `CASSANDRA_CONDENSED` | `grafana-dashboard-cassandra-condensed` | `dashboards/cassandra-condensed.json` |
| `CASSANDRA_OVERVIEW` | `grafana-dashboard-cassandra-overview` | `dashboards/cassandra-overview.json` |

## Available Datasources

| Name | `type` value | `uid` value | Port |
|------|-------------|-------------|------|
| VictoriaMetrics | `prometheus` | `VictoriaMetrics` | 8428 |
| VictoriaLogs | `victoriametrics-logs-datasource` | `victorialogs` | 9428 |
| ClickHouse | `grafana-clickhouse-datasource` | (auto) | 9000 |
| Tempo | `tempo` | `tempo` | 3200 |
| Pyroscope | `grafana-pyroscope-datasource` | `pyroscope` | 4040 |

Datasources are created at runtime by `GrafanaDatasourceConfig.create()` and applied as a ConfigMap by `GrafanaDashboardService`.

---

## Creating a New Dashboard

### Step 1: Create the JSON file

**Location:** `src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/{name}.json`

The JSON file is a standard Grafana dashboard export. Use `__KEY__` template variables for dynamic values (see Template Variables section below).

### Step 2: Add enum entry to GrafanaDashboard

**File:** `src/main/kotlin/.../configuration/grafana/GrafanaDashboard.kt`

```kotlin
MY_DASHBOARD(
    configMapName = "grafana-dashboard-my-dashboard",
    volumeName = "dashboard-my-dashboard",
    mountPath = "/var/lib/grafana/dashboards/my-dashboard",
    jsonFileName = "my-dashboard.json",
    resourcePath = "dashboards/my-dashboard.json",
    optional = true,  // true for non-core dashboards
),
```

The enum entry is all that's needed — `GrafanaManifestBuilder` automatically:
- Creates a ConfigMap with the JSON content
- Adds a volume mount in the Grafana Deployment
- Applies `__KEY__` template substitution

### Step 3: Verify and Deploy

```bash
./gradlew :test
# On running cluster: easy-db-lab grafana update-config
```

---

## Modifying an Existing Dashboard

1. Edit the JSON file directly in `dashboards/`
2. Run `./gradlew :test` to verify compilation
3. Deploy with `easy-db-lab grafana update-config`

---

## Deployment Pipeline

### `grafana update-config` command

`GrafanaUpdateConfig.execute()` does:
1. Creates the cluster-config ConfigMap (control node IP, region, S3 bucket, etc.)
2. Applies all Fabric8-built observability resources (OTel, Victoria, Tempo, Vector, Beyla, ebpf_exporter, Registry, S3 Manager, Pyroscope)
3. Calls `GrafanaDashboardService.uploadDashboards()` which:
   - Builds all Grafana resources via `GrafanaManifestBuilder` (dashboard ConfigMaps, datasource ConfigMap, provisioner ConfigMap, Deployment)
   - Applies each resource to K8s via `k8sService.applyResource()`

---

## Template Variable Systems

There are two layers of template variables:

### Layer 1: Cluster Context (`__KEY__` syntax)

Replaced at build time by `TemplateService.buildContextVariables()`:

| Variable | Source |
|----------|--------|
| `__CLUSTER_NAME__` | `state.initConfig?.name ?: "cluster"` |
| `__BUCKET_NAME__` | `state.s3Bucket ?: ""` |
| `__AWS_REGION__` | `user.region` |
| `__CONTROL_NODE_IP__` | `controlHost?.privateIp ?: ""` |
| `__METRICS_FILTER_ID__` | Built from cluster state |
| `__CLUSTER_S3_PREFIX__` | Built from cluster state |

### Layer 2: Grafana Variables (`${var}` syntax)

Defined in the dashboard JSON `templating.list` array. These create dropdowns in the Grafana UI.

**Custom dropdown example** (quantile selector):
```json
{
  "current": { "selected": true, "text": "p99", "value": "0.99" },
  "hide": 0,
  "includeAll": false,
  "label": "Quantile",
  "multi": false,
  "name": "quantile",
  "options": [
    { "selected": false, "text": "p50", "value": "0.5" },
    { "selected": true, "text": "p99", "value": "0.99" }
  ],
  "query": "p50 : 0.5, p75 : 0.75, p95 : 0.95, p99 : 0.99",
  "skipUrlSync": false,
  "type": "custom"
}
```

Reference the variable in panel expressions as `$quantile` or `${quantile}`.

---

## Panel Patterns

**Grid layout:** `gridPos` uses a 24-column grid. `w: 12` = half width, `w: 24` = full width. `h: 8` is standard panel height. `y` increases downward.

**Row separator:**
```json
{ "collapsed": false, "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 }, "id": 100, "title": "Section Name", "type": "row" }
```

**VictoriaMetrics (Prometheus) timeseries panel:**
```json
{
  "type": "timeseries",
  "title": "Panel Title",
  "id": 1,
  "datasource": { "type": "prometheus", "uid": "${datasource}" },
  "gridPos": { "h": 8, "w": 12, "x": 0, "y": 1 },
  "targets": [
    {
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "expr": "rate(my_metric{job=\"my-job\"}[1m])",
      "legendFormat": "{{instance}}",
      "refId": "A"
    }
  ],
  "fieldConfig": {
    "defaults": {
      "unit": "ops",
      "color": { "mode": "palette-classic" },
      "custom": {
        "drawStyle": "line",
        "fillOpacity": 10,
        "lineWidth": 1,
        "pointSize": 5,
        "showPoints": "never",
        "spanNulls": false
      }
    },
    "overrides": []
  },
  "options": {
    "legend": { "displayMode": "table", "placement": "bottom", "showLegend": true },
    "tooltip": { "mode": "multi", "sort": "desc" }
  }
}
```

**Common PromQL patterns:**
```
rate(counter_total{job="my-job"}[1m])              # Rate of a counter
summary_metric{job="my-job", quantile="$quantile"}  # Summary quantile with variable
sum(rate(counter{job="my-job"}[1m])) by (instance)  # Aggregation
```

**Common units:** `ops` (operations/sec), `s` (seconds), `bytes`, `percent`, `short` (plain number)

---

## Dashboard Design Principles

### Layout — Drill Down

Sections should go from high-level to detail as the user scrolls down. For a Cassandra database dashboard the standard order is:

1. **Cluster Overview** — cluster-wide aggregates: throughput, errors, pending compactions, active tasks
2. **Hardware / OS** — node-level system metrics (filtered to the relevant node type, see below)
3. **Per-Node detail** — per-node latency, per-table metrics
4. **Data Status** — SSTable counts, data size, compaction throughput, SSTables per read
5. **Cassandra Internals** — thread pools, pending/blocked tasks, dropped messages, hinted handoff
6. **JVM / Garbage Collection** — heap, GC time, application throughput

### Node Filtering by Dashboard Context

Hardware/OS panels should filter to the node type that is the subject of the dashboard — do not show control or unrelated nodes:

- **Database dashboard** → `host_name=~"db.*"`
- **App/stress dashboard** → `host_name=~"app.*"`

Note: `node_role` label does **not** exist in this stack. Use `host_name` prefix matching only.

Application-level metrics (e.g. `cassandra-easy-stress` throughput) can appear in a database dashboard — they describe the workload against the db nodes and belong in the Cluster Overview section.

### Chart Types

| Scenario | Chart type | Config |
|----------|-----------|--------|
| Cluster-wide aggregate (throughput, errors) | Stacked area | `fillOpacity: 80`, `lineWidth: 0`, `stacking.mode: "normal"` |
| Per-node counts that add up (pending compactions) | Stacked bar | `drawStyle: "bars"`, `fillOpacity: 100`, `stacking.mode: "normal"` |
| Per-node time series (latency, CPU) | Line | `fillOpacity: 0`, `lineWidth: 1` |

### Latency

**p99 and p999 only.** Never add p50, p75, p95, or p98 to latency panels. They add noise without insight.

### Legend Order

Put the most specific label first — cluster is secondary since single-cluster is the default view:

- `{{host_name}} — {{cluster}}`
- `{{pool_name}} — {{cluster}}`
- `{{message_type}} — {{cluster}}`

Never `{{cluster}} — {{host_name}}`.

### MAAC Metric Types

Cassandra metrics from the MAAC exporter are **summaries** (they have a `quantile` label, not `le` buckets). This means:

- `histogram_quantile()` does **not** work on them — there are no `_bucket` series
- Use `{quantile="0.99"}` directly to select a pre-computed percentile
- `_count` and `_sum` suffixes are available for computing rates and means

---

## Querying VictoriaMetrics Directly

Always verify metric names and label values against the live cluster before adding or modifying panels. Get the control node IP from `easy-db-lab status` or the cluster state file.

**Base URL:** `http://<control-ip>:8428`

### Discover available metrics

```bash
curl -s "http://<control-ip>:8428/api/v1/label/__name__/values" | python3 -c "
import json, sys
names = json.load(sys.stdin)['data']
for n in sorted(n for n in names if 'keyword' in n.lower()):
    print(n)
"
```

### Check labels on a metric

```bash
curl -s "http://<control-ip>:8428/api/v1/query?query=my_metric_name" | python3 -c "
import json, sys
results = json.load(sys.stdin)['data']['result']
print(f'Series: {len(results)}')
if results:
    print(json.dumps(results[0]['metric'], indent=2))
"
```

### Check unique values for a label

```bash
curl -s "http://<control-ip>:8428/api/v1/label/host_name/values" | python3 -c "
import json, sys; print(json.load(sys.stdin)['data'])
"
```

### Spot-check a PromQL expression

```bash
curl -s "http://<control-ip>:8428/api/v1/query?query=rate(my_metric%7Bjob%3D%22cassandra-maac%22%7D%5B1m%5D)" | python3 -c "
import json, sys
r = json.load(sys.stdin)
results = r['data']['result']
print(f'{len(results)} series')
for s in results[:3]:
    print(s['metric'].get('host_name'), s['value'])
"
```

### JSON manipulation

Always use Python to edit dashboard JSON — string replacement risks duplicate keys or broken structure:

```bash
# Validate
python3 -c "import json; json.load(open('dashboards/my-dashboard.json')); print('valid')"

# Inspect a panel
python3 -c "
import json
with open('dashboards/my-dashboard.json') as f:
    d = json.load(f)
p = next(p for p in d['panels'] if p.get('id') == 1)
print(json.dumps(p, indent=2))
"

# After any structural edit, re-sort panels by position
d['panels'].sort(key=lambda p: (p['gridPos']['y'], p['gridPos']['x']))
```

---

## Debugging Dashboards

### Dashboard not appearing in Grafana

1. **Check enum entry** — Is the dashboard registered in `GrafanaDashboard` enum? This is the single source of truth.
2. **Check JSON resource path** — Does the `resourcePath` in the enum match the actual file location under `dashboards/`?
3. **Check deployment was applied** — Run `grafana update-config` to reapply all resources.

### Dashboard appears but shows no data

1. **Check datasource** — Verify the `"uid"` in panel datasource matches an available datasource (see table above).
2. **Check metric names** — Query VictoriaMetrics API: `curl http://<control-ip>:8428/api/v1/label/__name__/values`
3. **Check job label** — Verify `{job="..."}` matches what OTel is scraping. Check the OTel collector config for the `job_name`.
4. **Check scrape interval** — If a job runs shorter than the scrape interval, metrics may never be collected.

### JSON syntax errors

Dashboard JSON is stored in standalone files under `dashboards/`. Use your editor's JSON validation or `jq` to check syntax:
```bash
jq . src/main/resources/.../configuration/grafana/dashboards/my-dashboard.json
```
