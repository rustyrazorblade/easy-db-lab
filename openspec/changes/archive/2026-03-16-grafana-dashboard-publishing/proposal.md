## Why

Grafana dashboard JSON files are buried inside `src/main/resources/` and processed through `TemplateService.substitute()` at deploy time, even though none of them use `__KEY__` template variables. The `StringSubstitutor` treats `$` as an escape character before `__` delimiters, which silently corrupts every `$__rate_interval` in the ClickHouse dashboard (243 occurrences). Beyond this bug, the dashboards need to be consumable by other projects and must work in multi-cluster contexts (central metrics database, portable containers with exported data).

## What Changes

- Move all dashboard JSON files from `src/main/resources/.../grafana/dashboards/` to a top-level `dashboards/` directory
- Configure Gradle to copy `dashboards/` into classpath resources at build time so `GrafanaManifestBuilder` still finds them
- Load dashboard JSON directly in `GrafanaManifestBuilder` without routing through `TemplateService` (no substitution is needed, and it causes the `$__rate_interval` corruption bug)
- Add a GitHub Actions workflow that zips `dashboards/` and publishes it as a release asset (tag: `latest`, updated on every push to main)
- Add/update tests: the existing `TemplateServiceTest` for the `$__rate_interval` preservation, and verification that dashboards are loadable from the classpath after the Gradle copy

## Capabilities

### New Capabilities
- `dashboard-publishing`: GitHub Actions workflow to publish dashboards as a standalone zip artifact for consumption by other projects

### Modified Capabilities
- `observability`: Dashboard JSON files move to top-level `dashboards/` directory; `GrafanaManifestBuilder` loads them without `TemplateService`, fixing the `$__rate_interval` corruption bug

## Impact

- `src/main/resources/.../grafana/dashboards/*.json` — files move to `dashboards/` at project root
- `GrafanaManifestBuilder` — changes how dashboard JSON is loaded (direct classpath read instead of `TemplateService.fromResource()`)
- `GrafanaDashboard` enum — resource paths update to reflect new classpath location
- `build.gradle.kts` — new `processResources` configuration to copy dashboards
- `.github/workflows/` — new workflow file for dashboard zip publishing
- Tests in `GrafanaManifestBuilderTest` and `K8sServiceIntegrationTest` — may need path adjustments
