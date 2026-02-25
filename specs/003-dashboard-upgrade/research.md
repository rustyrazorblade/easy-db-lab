# Research: Grafana Dashboard Upgrade

## R1: OTel CloudWatch Metrics Collection

**Decision**: Use the `awscloudwatch` receiver in the existing OTel Collector Contrib image (`otel/opentelemetry-collector-contrib:latest`) which already includes this receiver.

**Rationale**: The project already uses the contrib image, which bundles the `awscloudwatch` receiver. No custom OTel build needed. The receiver polls CloudWatch API at configurable intervals and outputs metrics in OTLP format, which feeds directly into the existing `prometheusremotewrite` exporter to VictoriaMetrics.

**Alternatives considered**:
- **YACE (Yet Another CloudWatch Exporter)**: Separate sidecar container. Rejected — adds deployment complexity when OTel already runs on every node.
- **CloudWatch Exporter (Prometheus)**: Another sidecar. Same rejection rationale.
- **Direct CloudWatch datasource in Grafana**: Current approach. Being replaced because it requires `__BUCKET_NAME__`/`__METRICS_FILTER_ID__` template substitution and doesn't support multi-cluster.

**Configuration approach**: Add `awscloudwatch` receiver to `otel-collector-config.yaml` targeting AWS/S3, AWS/EBS, and AWS/EC2 namespaces. The receiver runs on the control node only (not all nodes) since CloudWatch is a centralized API — but since OTel runs as a DaemonSet on all nodes, we'll add the receiver to the existing config and let it run everywhere (CloudWatch API calls are idempotent and cheap at 5-minute intervals). Alternatively, the receiver could be limited to the control node only via a separate OTel deployment, but that's unnecessary complexity for now.

**Authentication**: Uses IAM instance role (already available on EC2 nodes). No credentials needed in config — the AWS SDK credential chain handles it automatically, same pattern as Tempo and Vector S3.

**AWS_REGION injection**: Already done for other services. The OTel DaemonSet needs `AWS_REGION` env var from `cluster-config` ConfigMap — check if already present; if not, add it in `OtelManifestBuilder.buildDaemonSet()`.

## R2: Grafana Multi-Select Cluster Variable Pattern

**Decision**: Use a standardized `cluster` template variable (lowercase, type `query`) with `multi: true` and `includeAll: true` across all dashboards.

**Rationale**: The cassandra-overview and cassandra-condensed dashboards already use a `cluster` variable querying `label_values(up{job="cassandra-maac"}, cluster)`. The ClickHouse dashboard uses `Cluster` (capitalized). Standardizing to lowercase `cluster` with multi-select is the cleanest approach.

**Variable definition** (standard across all dashboards):
```json
{
  "allValue": ".*",
  "current": {"selected": true, "text": ["All"], "value": ["$__all"]},
  "datasource": {"type": "prometheus", "uid": "${datasource}"},
  "includeAll": true,
  "multi": true,
  "name": "cluster",
  "label": "Cluster",
  "query": "label_values(up, cluster)",
  "type": "query",
  "refresh": 2
}
```

**Panel query pattern**: All PromQL expressions filter with `cluster=~"$cluster"`. For panels that don't currently use cluster filtering, add this label matcher.

**Per-dashboard considerations**:
- **cassandra-overview, cassandra-condensed**: Change `multi: false` → `multi: true`, already have `cluster=~"$cluster"` in queries
- **clickhouse**: Rename `Cluster` → `cluster`, set `multi: true`, already has cluster filtering
- **system-overview, stress**: Add `cluster` variable, add `cluster=~"$cluster"` to all panel queries
- **clickhouse-logs**: Add `cluster` variable; VictoriaLogs queries need cluster field filtering
- **profiling**: Add `cluster` variable; Pyroscope queries need service label filtering by cluster
- **s3-cloudwatch**: Complete rewrite to PromQL — new queries will include cluster filtering natively
- **emr**: Add `cluster` variable; EMR metrics may use a different label — investigate during implementation
- **opensearch**: Add `cluster` variable; similar to EMR

## R3: Moving Dashboards to Top-Level Directory

**Decision**: Move JSON files to `dashboards/` at repo root. Add `dashboards/` as an additional resource directory in Gradle's `sourceSets`. Update `GrafanaDashboard.resourcePath` to use absolute classpath paths.

**Rationale**: Gradle supports multiple resource directories. The existing pattern already adds `build/aws` as a custom resource dir. Adding `dashboards/` follows the same approach.

**Gradle change**:
```kotlin
sourceSets {
    val main by getting {
        java.srcDirs("src/main/kotlin")
        resources.srcDirs("build/aws", "dashboards")
    }
}
```

Wait — this would put the JSON files at the root of the classpath (e.g., `system-overview.json` not `com/rustyrazorblade/.../dashboards/system-overview.json`). The `TemplateService.fromResource()` uses the class's classloader to resolve relative paths. Two options:

**Option A**: Create subdirectory structure inside `dashboards/` matching the classpath expectation:
```
dashboards/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/system-overview.json
```
This is ugly and defeats the purpose of a clean top-level directory.

**Option B**: Change resource loading to use absolute classpath paths. Instead of `GrafanaDashboard::class.java.getResourceAsStream("dashboards/file.json")` (relative to class), use `Thread.currentThread().contextClassLoader.getResourceAsStream("dashboards/file.json")` (absolute from classpath root). This requires a small change to `TemplateService` or `GrafanaManifestBuilder`.

**Decision**: Option B. Update `TemplateService` to support loading from an absolute classpath path (or add a new method). The `dashboards/` directory in the repo root maps directly to `dashboards/` on the classpath root. `GrafanaDashboard.resourcePath` changes from `"dashboards/system-overview.json"` to `"dashboards/system-overview.json"` (same string, different resolution — from classpath root instead of relative to class).

**Alternative considered**: Symlink from old location to new. Rejected — fragile, doesn't work in JARs.

## R4: Removing Template Substitution

**Decision**: Remove `__KEY__` placeholders from all dashboard JSON. The `GrafanaManifestBuilder.buildDashboardConfigMap()` method will load dashboard JSON directly without calling `template.substitute()`.

**Rationale**: With multi-cluster support, `__CLUSTER_NAME__` in titles is replaced by simple descriptive names. With OTel CloudWatch collection, `__BUCKET_NAME__` and `__METRICS_FILTER_ID__` are eliminated. Zero placeholders remain.

**Impact on TemplateService**: The `TemplateService.fromResource()` method still returns a `Template` object. For dashboards, we can either:
- Call `substitute()` with no vars (harmless, no-op if no `__KEY__` patterns exist)
- Load raw content without Template wrapper

Decision: Keep calling `substitute()` — it's a no-op and maintains consistency. If someone accidentally adds a `__KEY__` placeholder in the future, it would still work.

Actually, wait — the spec says dashboards must be pure standard Grafana JSON importable without any substitution. So `substitute()` should NOT be called. Load the file as raw JSON. This also means the published artifact is exactly what's in the repo.

**Decision revised**: Load dashboard JSON files directly (no Template/substitute), so what's in the `dashboards/` directory is exactly what gets deployed.

## R5: Publishing as GitHub Artifact

**Decision**: Create a GitHub Actions workflow that zips the `dashboards/` directory and attaches it to GitHub Releases.

**Rationale**: The project already uses `publish-container.yml` triggered by version tags. A similar workflow for dashboards is straightforward.

**Workflow approach**:
```yaml
name: Publish Dashboards
on:
  release:
    types: [published]
jobs:
  publish-dashboards:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: zip -j dashboards.zip dashboards/*.json
      - uses: softprops/action-gh-release@v2
        with:
          files: dashboards.zip
```

**Alternative considered**: Gradle task to produce the zip. Rejected as unnecessary — the dashboards are plain JSON files, no build step needed. A simple CI zip is sufficient.

## R6: OTel Collector — Running CloudWatch Receiver on All Nodes vs Control Only

**Decision**: Run the CloudWatch receiver on all nodes (in the existing DaemonSet). Accept duplicate API calls.

**Rationale**: CloudWatch API calls at 5-minute intervals from a few nodes are negligible in cost. Creating a separate OTel Deployment just for CloudWatch adds deployment complexity that isn't justified. The receiver is idempotent — duplicate metrics from multiple nodes will be deduplicated by VictoriaMetrics.

**Risk**: If the cluster scales to many nodes, CloudWatch API calls multiply. Mitigation: can move to a separate Deployment later if needed. For now, YAGNI.
