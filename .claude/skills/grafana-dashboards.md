---
name: grafana-dashboards
description: Use when doing any work with Grafana dashboards - creating, modifying, debugging, or deploying. Covers the full architecture, file locations, naming conventions, and deployment pipeline.
---

# Grafana Dashboards

## Architecture Overview

Dashboards are Grafana JSON embedded in K8s ConfigMap YAMLs, stored as classpath resources. They are extracted from the JAR, undergo template variable substitution, and are applied to K8s. Grafana loads them via file-based provisioning from mounted ConfigMap volumes.

```
JAR resource YAML → TemplateService (substitutes __KEY__ vars) → kubectl apply → ConfigMap in K8s
                                                                                       ↓
Grafana deployment mounts ConfigMap as volume → Grafana provisioner scans /var/lib/grafana/dashboards/
```

## Key Files and Locations

| What | Path |
|------|------|
| Core dashboard YAMLs | `src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/core/` |
| ClickHouse dashboard YAMLs | `src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/clickhouse/` |
| Grafana deployment | `src/main/resources/.../k8s/core/41-grafana-deployment.yaml` |
| Dashboard provisioner config | `src/main/resources/.../k8s/core/14-grafana-dashboards-configmap.yaml` |
| Dashboard service | `src/main/kotlin/.../services/GrafanaDashboardService.kt` |
| Dashboard commands | `src/main/kotlin/.../commands/dashboards/` (`DashboardsGenerate.kt`, `DashboardsUpload.kt`) |
| Template substitution | `src/main/kotlin/.../services/TemplateService.kt` |
| Datasource config | `src/main/kotlin/.../grafana/GrafanaDatasourceConfig.kt` |
| Service test | `src/test/kotlin/.../services/GrafanaDashboardServiceTest.kt` |

## Existing Dashboards

| File | ConfigMap Name | Datasource |
|------|---------------|------------|
| `15-grafana-dashboard-system.yaml` | `grafana-dashboard-system` | VictoriaMetrics (prometheus) |
| `16-grafana-dashboard-s3.yaml` | `grafana-dashboard-s3` | CloudWatch |
| `17-grafana-dashboard-emr.yaml` | `grafana-dashboard-emr` | CloudWatch |
| `18-grafana-dashboard-opensearch.yaml` | `grafana-dashboard-opensearch` | CloudWatch |
| `19-grafana-dashboard-stress.yaml` | `grafana-dashboard-stress` | VictoriaMetrics (prometheus) |
| `14-grafana-dashboard-clickhouse.yaml` (clickhouse/) | `grafana-dashboard-clickhouse` | ClickHouse |
| `17-grafana-dashboard-clickhouse-logs.yaml` (clickhouse/) | `grafana-dashboard-clickhouse-logs` | VictoriaLogs |

## Available Datasources

| Name | `type` value | `uid` value | Port |
|------|-------------|-------------|------|
| VictoriaMetrics | `prometheus` | `VictoriaMetrics` | 8428 |
| VictoriaLogs | `victoriametrics-logs-datasource` | `victorialogs` | 9428 |
| ClickHouse | `grafana-clickhouse-datasource` | (auto) | 9000 |
| Tempo | `tempo` | `tempo` | 3200 |
| CloudWatch | `cloudwatch` | `cloudwatch` | N/A |

Datasources are created at runtime by `GrafanaDatasourceConfig.create(region)` and applied as a ConfigMap by `GrafanaDashboardService.createDatasourcesConfigMap()`.

---

## Creating a New Dashboard

### Step 1: Create the ConfigMap YAML

**File naming:** `{ordinal}-grafana-dashboard-{name}.yaml`
- Ordinal is two digits (next available after existing dashboards)
- Name is kebab-case: `stress`, `emr`, `opensearch`
- The filename MUST contain `grafana-dashboard` for auto-discovery

**ConfigMap naming:** `grafana-dashboard-{name}` (must match the volume reference in Step 2)

**Required label:** `app.kubernetes.io/name: grafana`

**Template:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboard-{name}
  namespace: default
  labels:
    app.kubernetes.io/name: grafana
data:
  {name}-overview.json: |
    {
      "annotations": { "list": [] },
      "editable": true,
      "fiscalYearStartMonth": 0,
      "graphTooltip": 1,
      "id": null,
      "links": [],
      "liveNow": false,
      "panels": [],
      "refresh": "5m",
      "schemaVersion": 38,
      "style": "dark",
      "tags": [],
      "templating": {
        "list": [
          {
            "current": { "selected": true, "text": "VictoriaMetrics", "value": "VictoriaMetrics" },
            "hide": 0,
            "includeAll": false,
            "label": "Datasource",
            "multi": false,
            "name": "datasource",
            "options": [],
            "query": "prometheus",
            "queryValue": "",
            "refresh": 1,
            "regex": "",
            "skipUrlSync": false,
            "type": "datasource"
          }
        ]
      },
      "time": { "from": "now-1h", "to": "now" },
      "timepicker": {},
      "timezone": "browser",
      "title": "__CLUSTER_NAME__ - {Display Name}",
      "uid": "{name}-overview",
      "version": 1,
      "weekStart": ""
    }
```

For CloudWatch dashboards, change `"query": "prometheus"` to `"query": "cloudwatch"` in the datasource variable.

### Step 2: Register in Grafana Deployment

**File:** `src/main/resources/.../k8s/core/41-grafana-deployment.yaml`

Add **both** a volumeMount and a volume. Use `optional: true` for non-core dashboards.

**volumeMount** (add after last dashboard mount, before `data`):
```yaml
            - name: dashboard-{name}
              mountPath: /var/lib/grafana/dashboards/{name}
              readOnly: true
```

**volume** (add after last dashboard volume, before `data`):
```yaml
        - name: dashboard-{name}
          configMap:
            name: grafana-dashboard-{name}
            optional: true
```

The volume `name` must match in both places. The `configMap.name` must match the ConfigMap metadata name from Step 1.

**CRITICAL:** Without this step, the dashboard will NOT appear in Grafana. Grafana does not auto-discover ConfigMaps. Each dashboard must be explicitly mounted.

### Step 3: Verify and Deploy

```bash
./gradlew :test
# On running cluster: dashboards upload
```

---

## Modifying an Existing Dashboard

1. Edit the JSON inside the ConfigMap YAML directly
2. Run `./gradlew :test` to verify compilation
3. Deploy with `dashboards upload`

The dashboard JSON is indented 4 spaces inside the YAML `|` block. Be careful with indentation — YAML block scalars are whitespace-sensitive.

---

## Deployment Pipeline

### `dashboards upload` command

`GrafanaDashboardService.uploadDashboards()` does:
1. Creates the Grafana datasources ConfigMap (with runtime AWS region)
2. Extracts all classpath resources matching `"grafana-dashboard"` (dashboard ConfigMaps)
3. Extracts the classpath resource matching `"grafana-deployment"` (Grafana Deployment)
4. Applies all of them to K8s via `kubectl apply`

This means `dashboards upload` automatically reapplies the Grafana deployment, picking up any new volume mounts. No separate `k8 apply` is needed.

### `dashboards generate` command

Extracts dashboard YAMLs to the local `k8s/` directory with template substitution but does NOT apply them. Useful for inspecting the substituted output.

### `k8 apply` command

Applies ALL `core/` resources including the Grafana deployment and dashboards. Used during initial cluster setup.

---

## Template Variable Systems

There are two layers of template variables:

### Layer 1: Cluster Context (`__KEY__` syntax)

Replaced at extraction time by `TemplateService.buildContextVariables()`:

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

## Debugging Dashboards

### Dashboard not appearing in Grafana

1. **Check volume mount** — Is the dashboard registered in `41-grafana-deployment.yaml` with both a `volumeMount` and a `volume`? This is the most common cause.
2. **Check deployment was applied** — `dashboards upload` reapplies the deployment automatically. If you applied the ConfigMap manually without the deployment, the new volume mount won't take effect.
3. **Check ConfigMap name matches** — The `configMap.name` in the volume must exactly match the ConfigMap `metadata.name`.
4. **Check filename pattern** — The YAML filename must contain `grafana-dashboard` for auto-discovery by `GrafanaDashboardService`.

### Dashboard appears but shows no data

1. **Check datasource** — Verify the `"uid"` in panel datasource matches an available datasource (see table above).
2. **Check metric names** — Query VictoriaMetrics API: `curl http://<control-ip>:8428/api/v1/label/__name__/values`
3. **Check job label** — Verify `{job="..."}` matches what OTel is scraping. Check the OTel collector config for the `job_name`.
4. **Check scrape interval** — If a job runs shorter than the scrape interval, metrics may never be collected.

### JSON syntax errors

The dashboard JSON is embedded in a YAML `|` block. Common issues:
- Missing comma after a JSON object/array
- Mismatched braces/brackets
- Wrong indentation (all JSON lines must be indented exactly 4 spaces in the YAML)

Run `dashboards generate` to extract the substituted YAML, then validate the JSON portion.
