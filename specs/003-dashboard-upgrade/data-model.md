# Data Model: Grafana Dashboard Upgrade

## Entities

### GrafanaDashboard (enum — existing, modified)

The single source of truth for dashboard metadata. Each entry maps a dashboard JSON file to its K8s deployment configuration.

**Fields**:
- `configMapName: String` — K8s ConfigMap resource name (unchanged)
- `volumeName: String` — K8s volume name (unchanged)
- `mountPath: String` — Grafana container mount path (unchanged)
- `jsonFileName: String` — filename inside ConfigMap data key (unchanged)
- `resourcePath: String` — **CHANGED**: now an absolute classpath path (e.g., `"dashboards/system-overview.json"`) resolved from classpath root, not relative to enum's class
- `optional: Boolean` — whether volume mount is optional (unchanged)

**State transitions**: None (static enum).

### Dashboard JSON (file — existing, modified)

Each dashboard is a standard Grafana dashboard JSON file.

**Key structural changes**:
- `title`: simple descriptive name, no `__CLUSTER_NAME__` prefix
- `templating.list`: MUST include a standardized `cluster` variable with `multi: true`
- All panel `targets[].expr` (PromQL): MUST include `cluster=~"$cluster"` filter
- Zero `__KEY__` placeholders anywhere in the file

### OTel Collector Config (YAML template — existing, modified)

**New receiver added**:
- `awscloudwatch`: polls AWS/S3, AWS/EBS, AWS/EC2 CloudWatch namespaces at 300s intervals
- Added to the `metrics` pipeline alongside existing receivers

**New env var dependency**:
- `AWS_REGION`: injected from `cluster-config` ConfigMap (may already be present in OTel DaemonSet)

### Cluster Variable (Grafana template variable — new standardized pattern)

Standard definition applied to all dashboards:

| Field | Value |
|-------|-------|
| name | `cluster` |
| label | `Cluster` |
| type | `query` |
| query | `label_values(up, cluster)` |
| multi | `true` |
| includeAll | `true` |
| allValue | `.*` |
| refresh | `2` (on time range change) |

## Relationships

```
GrafanaDashboard enum ──references──> dashboards/*.json (classpath)
GrafanaManifestBuilder ──reads──> GrafanaDashboard enum
GrafanaManifestBuilder ──loads──> dashboard JSON via TemplateService (or direct classloader)
GrafanaManifestBuilder ──produces──> K8s ConfigMap per dashboard
OtelManifestBuilder ──loads──> otel-collector-config.yaml (with awscloudwatch receiver)
OtelManifestBuilder ──produces──> K8s ConfigMap + DaemonSet
awscloudwatch receiver ──polls──> AWS CloudWatch API
awscloudwatch receiver ──exports──> VictoriaMetrics (via prometheusremotewrite)
s3-cloudwatch dashboard ──queries──> VictoriaMetrics (via PromQL, replacing direct CloudWatch)
```

## Validation Rules

- Every dashboard JSON MUST have exactly one template variable named `cluster` with `multi: true`
- No dashboard JSON file may contain the pattern `__[A-Z_]+__` (zero template placeholders)
- Dashboard titles MUST NOT contain `__CLUSTER_NAME__`
- The `dashboards/` directory at repo root MUST contain all dashboard JSON files
- The old path `src/main/resources/.../grafana/dashboards/` MUST NOT exist after migration
