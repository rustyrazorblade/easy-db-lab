## 1. Remove cluster name prefix from dashboard titles

- [x] 1.1 Remove `__CLUSTER_NAME__ - ` prefix from `"title"` field in all dashboard JSON files under `src/main/resources/.../configuration/grafana/dashboards/`
- [x] 1.2 Remove the `__CLUSTER_NAME__` template variable from `TemplateService` context if it is no longer used anywhere after the title changes (check all dashboard JSON files and other resources for remaining references first) — KEPT: still used by OTel, Beyla, Pyroscope, StressJobs, GrafanaManifestBuilder

## 2. Verify

- [x] 2.1 Validate all dashboard JSON files are valid JSON
- [x] 2.2 Run `./gradlew :test --tests '*GrafanaManifestBuilderTest*'` and `./gradlew :test --tests '*K8sServiceIntegrationTest*'`
- [x] 2.3 Run `./gradlew ktlintCheck`

## 3. Documentation

- [x] 3.1 Update CLAUDE.md if the "Current dashboards" list references titles with cluster prefix — N/A, already clean
