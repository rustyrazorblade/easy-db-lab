---
name: grafana-dashboards
description: Use when doing any work with Grafana dashboards - creating, modifying, debugging, or deploying, including editing any .json file in the dashboards/ directory. Covers the full architecture, file locations, naming conventions, deployment pipeline, and design principles.
triggers:
  - pattern: "dashboards/**/*.json"
---

# Grafana Dashboards

## Architecture Overview

Core dashboards are standalone JSON files in the top-level `dashboards/<folder>/` tree, discovered from the classpath at runtime and copied by `grafana update-config` onto the control node's Grafana hostPath, where one file provider makes one Grafana folder per directory. There is no registry to edit and no K8s object per dashboard. The mechanism is described once, in [`dashboards/CLAUDE.md`](../../dashboards/CLAUDE.md).

## Key Files and Locations

| What | Path |
|------|------|
| Dashboard JSON files | `dashboards/<folder>/*.json` (top-level; folders `cassandra`, `infrastructure`, `observability`, `opensearch`) |
| Dashboard model | `src/main/kotlin/.../configuration/grafana/GrafanaDashboard.kt` (data class, derives the tree and classpath paths from folder + file) |
| Discovery | `src/main/kotlin/.../configuration/grafana/GrafanaDashboardCatalog.kt` (ClassGraph scan of `dashboards/`) |
| Tree writer | `src/main/kotlin/.../configuration/grafana/GrafanaDashboardTreeWriter.kt` (catalog → local `<folder>/<file>` tree) |
| Tree uploader | `src/main/kotlin/.../services/GrafanaDashboardTreeUploader.kt` (SSH copy onto the control node hostPath) |
| Provisioning YAML | `src/main/kotlin/.../configuration/grafana/GrafanaDashboardProvisioningConfig.kt` (single `foldersFromFilesStructure` provider) |
| Manifest builder | `src/main/kotlin/.../configuration/grafana/GrafanaManifestBuilder.kt` (provisioning ConfigMap + Deployment) |
| Datasource config | `src/main/kotlin/.../configuration/grafana/GrafanaDatasourceConfig.kt` |
| Dashboard service | `src/main/kotlin/.../services/GrafanaDashboardService.kt` |
| Deploy command | `src/main/kotlin/.../commands/grafana/GrafanaUpdateConfig.kt` |
| Parent command | `src/main/kotlin/.../commands/grafana/Grafana.kt` |

## Existing Dashboards

Run `find dashboards -name '*.json' | sort` for the current list. The Grafana folder is the directory name verbatim. `infrastructure/system-overview.json` is the home dashboard and must exist at exactly that path.

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

**Location:** `dashboards/<folder>/{name}.json`, where `<folder>` is the Grafana folder it belongs in (`cassandra`, `infrastructure`, `observability`, `opensearch`; make a new directory for a new folder). Never at the root of `dashboards/` — discovery rejects that.

The JSON must carry a top-level `uid`. The same file name may exist in two folders.

### Step 2: There is no step 2

The file is discovered at runtime and copied to the control node with the rest of the tree. Grafana's single file provider files it into a folder named after its directory.

### Step 3: Verify and Deploy

```bash
./gradlew :test
# On running cluster: easy-db-lab grafana update-config
```

---

## Modifying an Existing Dashboard

1. Edit the JSON file directly in `dashboards/<folder>/`
2. Run `./gradlew :test` to verify compilation
3. Deploy with `easy-db-lab grafana update-config`

---

## Deployment Pipeline

### `grafana update-config` command

`GrafanaUpdateConfig.execute()` does:
1. Creates the cluster-config ConfigMap (control node IP, region, S3 bucket, etc.)
2. Applies all Fabric8-built observability resources (OTel, Victoria, Tempo, Vector, Beyla, ebpf_exporter, Registry, S3 Manager, Pyroscope)
3. Prepares `/mnt/db1/grafana` on the control node (mkdir, chown 472)
4. Calls `GrafanaDashboardService.uploadDashboards()` which:
   - Creates the datasource ConfigMap
   - Copies the dashboard tree to `/mnt/db1/grafana/dashboards` on the control node via `GrafanaDashboardTreeUploader` (see [`dashboards/CLAUDE.md`](../../dashboards/CLAUDE.md) for the staging and rename swap)
   - Builds the provisioning ConfigMap and Deployment via `GrafanaManifestBuilder` and applies each via `k8sService.applyResource()`
5. Restarts the observability workloads and waits for them to become Ready

`grafana install <path> --folder=<name>` is the one-off path: it POSTs a single dashboard file to the Grafana HTTP API and does not touch the copied tree.

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

Note: `node_role` exists on Cassandra JVM series (`db`), on the stress sidecar, and on Spark series, because each of those sources declares it as an OTel resource attribute. It does **not** exist on every series, so `host_name` prefix matching is still the portable filter. Check the label on the metric before you rely on it.

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

### Cassandra Metric Types

Cassandra metrics come from the OpenTelemetry Java Agent inside the Cassandra JVM, under `job="cassandra"`. Percentiles are **gauges**, one metric per percentile. There is no `quantile` label and there are no `le` buckets:

- `histogram_quantile()` does **not** work on them — there are no `_bucket` series
- Select a percentile by metric name, e.g. `cassandra_client_request_latency_p99_microseconds`
- The percentiles are pre-aggregated per node, so they cannot be re-aggregated into a cluster p99. Show the spread with `max by (cluster)` and `min by (cluster)` instead.
- Latency is in **microseconds**, matching `nodetool proxyhistograms`. The series name ends `_microseconds`.
- Each node also carries a `cassandra_build` label naming the build it runs, so `sum by (cassandra_build)` splits a mixed-version cluster.

Metrics written before the agent replaced MAAC use `org_apache_cassandra_metrics_*` and `job="cassandra-maac"`. Nothing translates between the two, so a query spanning that change returns two disjoint sets of series.

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
curl -s "http://<control-ip>:8428/api/v1/query?query=rate(my_metric%7Bjob%3D%22cassandra%22%7D%5B1m%5D)" | python3 -c "
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

1. **Check the file location** — Is it at `dashboards/<folder>/<name>.json`, exactly one directory deep? A file at the root or nested deeper fails discovery, and `GrafanaDashboardCatalogTest` fails on it too.
2. **Check the build picked it up** — `ls build/resources/main/dashboards/<folder>/` after `./gradlew installDist`.
3. **Check the tree reached the node** — `ssh control0 ls /mnt/db1/grafana/dashboards/<folder>/` after `grafana update-config`; the provider re-reads it every 10 seconds.

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
