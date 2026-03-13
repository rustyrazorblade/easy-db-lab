## Why

The Grafana dashboards bundled with easy-db-lab are purpose-built for database performance testing observability — covering Cassandra, ClickHouse, OpenSearch, EMR, profiling, and system metrics. Currently they are embedded inside the application JAR and only accessible when the full tool is deployed.

Other projects and teams running their own Grafana instances want to import these dashboards without adopting the full easy-db-lab toolchain. Publishing them as a standalone release artifact — attached to every versioned release — makes them directly consumable.

The dashboards are fully Grafana-native: they use Grafana template variables (`type: "datasource"` and `type: "query"` with `label_values()` queries) and standard `${variable}` references. There are no custom template placeholders to substitute. They can be published as-is.

## What Changes

- A new GitHub Actions workflow (`.github/workflows/publish-dashboards.yml`) that triggers on version tag pushes (`v*`)
- The workflow packages all 11 dashboard JSON files into `easy-db-lab-dashboards.zip` and uploads it as a release asset using `softprops/action-gh-release@v2`

## Capabilities

### New Capabilities

- `dashboard-artifact-publishing`: Grafana dashboards are published as a standalone zip artifact attached to every versioned GitHub Release, making them consumable by any project without requiring the full easy-db-lab toolchain

### Modified Capabilities

- `release-process`: Version tag pushes now produce two release artifacts — the existing container image and the new dashboard zip

## Impact

- `.github/workflows/publish-dashboards.yml` — new workflow file (requires manual creation; GitHub App lacks `workflows` scope)
- `docs/` — add a section explaining the dashboard artifact and how to import dashboards into an external Grafana instance
