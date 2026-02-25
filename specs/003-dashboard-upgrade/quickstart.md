# Quickstart: Grafana Dashboard Upgrade

## Prerequisites

- JDK 21 (Temurin) via SDKMAN
- Gradle wrapper (`./gradlew`)
- Docker (for TestContainers integration tests)

## Development Workflow

### 1. Build and Test

```bash
# Run all tests (includes dashboard resource loading tests)
./gradlew :test

# Check code style
./gradlew ktlintCheck

# Check code quality
./gradlew detekt
```

### 2. Editing Dashboards

Dashboard JSON files live at `dashboards/` in the repo root. Edit them directly — they are standard Grafana JSON, no preprocessing needed.

**Every dashboard MUST have**:
- A `cluster` template variable with `multi: true` in `templating.list`
- `cluster=~"$cluster"` in all PromQL panel queries
- A simple descriptive title (no `__CLUSTER_NAME__` prefix)
- Zero `__KEY__` template placeholders

### 3. Adding a New Dashboard

1. Create `dashboards/my-dashboard.json` with standard Grafana JSON
2. Add enum entry in `GrafanaDashboard.kt`
3. Run tests: `./gradlew :test`

### 4. Testing Dashboard Changes

The `GrafanaManifestBuilderTest` verifies:
- All dashboard JSON files load from classpath
- ConfigMaps are built correctly for each dashboard
- No `__KEY__` placeholders remain (should be zero now)

The `K8sServiceIntegrationTest` verifies:
- Grafana K8s resources apply successfully to K3s
- OTel collector resources (including CloudWatch receiver) apply successfully

### 5. Local Validation

```bash
# Full CI-equivalent check
./gradlew check
```
