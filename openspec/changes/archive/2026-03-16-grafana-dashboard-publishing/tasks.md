## 1. Move dashboard files

- [x] 1.1 Move all JSON files from `src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/` to top-level `dashboards/` directory
- [x] 1.2 Add `"dashboards"` to `resources.srcDirs` in `build.gradle.kts` (alongside existing `"build/aws"`)
- [x] 1.3 Update `GrafanaDashboard` enum — removed `resourcePath` property entirely (redundant with `jsonFileName` now that files are at resource root)

## 2. Remove TemplateService from dashboard loading

- [x] 2.1 Change `GrafanaManifestBuilder.buildDashboardConfigMap()` to load JSON directly via `GrafanaDashboard::class.java.getResourceAsStream()` instead of `TemplateService.fromResource()`
- [x] 2.2 Add a test in `TemplateServiceTest` that verifies `$__rate_interval` is preserved through substitution (TDD test already written — verify it passes)
- [x] 2.3 Add a test in `GrafanaManifestBuilderTest` that verifies dashboard JSON contains `$__rate_interval` after loading (proves the bug is fixed end-to-end)

## 3. GitHub Actions workflow

- [x] 3.1 Create `.github/workflows/publish-dashboards.yml` that triggers on push to `main`, zips `dashboards/`, and uploads as a release asset under the `latest` tag
- [x] 3.2 Ensure the workflow creates the `latest` release if it doesn't exist and uses `--clobber` to overwrite existing assets

## 4. Verify and update docs

- [x] 4.1 Update `configuration/CLAUDE.md` grafana section to reflect new dashboard file location
- [x] 4.2 Run existing tests (`GrafanaManifestBuilderTest`, `K8sServiceIntegrationTest`) to verify nothing breaks
- [x] 4.3 Run `ktlintCheck` and `detekt` to verify code quality
