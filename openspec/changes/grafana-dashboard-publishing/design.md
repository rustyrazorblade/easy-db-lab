## Context

Dashboard JSON files currently live at `src/main/resources/.../configuration/grafana/dashboards/`. They are loaded by `GrafanaManifestBuilder.buildDashboardConfigMap()` via `TemplateService.fromResource()`, which runs `StringSubstitutor` on them. No dashboard uses `__KEY__` template variables, so the substitution does nothing useful — but `StringSubstitutor` treats `$` as an escape character before its `__` delimiter prefix, silently stripping `$` from every `$__rate_interval` (a Grafana built-in variable). This corrupts 243 PromQL expressions in the ClickHouse dashboard alone.

The dashboards also need to be published as a standalone artifact for consumption by another project, and must eventually support multi-cluster comparison scenarios.

## Goals / Non-Goals

**Goals:**
- Move dashboard JSON to a top-level `dashboards/` directory, making them first-class project artifacts
- Include dashboards in the JAR via Gradle `sourceSets` resource directory (same pattern as `build/aws`)
- Eliminate `TemplateService` from the dashboard loading path, fixing the `$__rate_interval` corruption
- Publish dashboards as a zip via GitHub Actions on every main branch push
- Maintain all existing K8s integration tests and manifest builder functionality

**Non-Goals:**
- Adding cluster variables to dashboards that lack them (phase 2 — cross-cluster comparison)
- Changing `TemplateService` or `StringSubstitutor` behavior (the fix is to stop using it where it's not needed)
- Changing the `GrafanaDashboard` enum structure or ConfigMap deployment mechanism

## Decisions

### 1. Dashboard location: top-level `dashboards/` directory

Dashboard JSON files move to `/dashboards/` at the project root. This makes them visible, easy to find, and decoupled from the Kotlin package hierarchy.

**Alternative considered:** A `grafana/` or `grafana/dashboards/` top-level directory. Rejected because these dashboards are Grafana-format but the concept is broader — they're the project's dashboard definitions. `dashboards/` is simpler.

### 2. Classpath inclusion via `sourceSets` resource directory

Add `"dashboards"` to `resources.srcDirs` in `build.gradle.kts`, alongside the existing `"build/aws"` entry. The files will appear on the classpath at their relative path (e.g., `clickhouse.json`). This is the simplest approach — no copy tasks, no custom Gradle configuration.

`GrafanaDashboard.resourcePath` values will change from `"dashboards/clickhouse.json"` to just `"clickhouse.json"` since the `dashboards/` directory itself becomes a resource root.

**Alternative considered:** A Gradle `copy` task in `processResources`. More explicit but unnecessary complexity — `sourceSets.resources.srcDirs` is the idiomatic Gradle approach and already in use.

### 3. Direct classpath loading in `GrafanaManifestBuilder`, no `TemplateService`

`buildDashboardConfigMap()` will load dashboard JSON directly via `GrafanaDashboard::class.java.getResourceAsStream()` instead of going through `TemplateService.fromResource()`. Since no dashboards use `__KEY__` variables, `TemplateService` adds zero value and introduces the `$__rate_interval` bug.

The builder already has access to `TemplateService` for the dashboards provisioning YAML (`dashboards.yaml`) which does use substitution — that stays unchanged.

### 4. GitHub Actions: zip and publish as release asset

A new workflow (`publish-dashboards.yml`) triggers on pushes to `main`. It zips the `dashboards/` directory and uploads it as a GitHub release asset. Uses the `latest` tag (created/updated on each push), consistent with how the container image uses `latest` for main.

**Alternative considered:** Publishing to GitHub Packages as a Maven artifact. Rejected — the consumer is another project that just needs the JSON files, not a JVM dependency.

## Risks / Trade-offs

- **Resource path change breaks test assumptions** → Low risk. `GrafanaDashboard.resourcePath` is the single source of truth for paths. Updating the enum values propagates everywhere. The K8s integration test validates end-to-end.
- **`latest` release tag conflicts** → The workflow will use `gh release upload --clobber` to overwrite the existing asset on each push, same pattern as the container `latest` tag.
- **Dashboard files outside `src/` may surprise contributors** → Mitigated by documenting in CLAUDE.md and the project README.
