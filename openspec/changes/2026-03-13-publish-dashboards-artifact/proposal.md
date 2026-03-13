## Why

The Grafana dashboards in easy-db-lab are purpose-built for database performance testing — covering Cassandra, ClickHouse, OpenSearch, EMR, profiling, stress, system overview, and log investigation. Other projects and teams running similar database clusters want to use these dashboards without depending on the full easy-db-lab CLI or its Kubernetes-based provisioning.

Currently, dashboards are only accessible as classpath resources embedded in the application JAR. There is no standalone artifact that other projects can download and import into their own Grafana instances.

## What Changes

- Add a GitHub Actions workflow that triggers on version tag pushes (`v*`) and packages all dashboard JSON files from the `configuration/grafana/dashboards/` directory into a `easy-db-lab-dashboards.zip` archive.
- Upload the zip as a release asset attached to the GitHub Release created for that tag.
- Document the published artifact and the template variable substitution requirement.

## Capabilities

### New Capabilities

- `dashboard-artifact-publishing`: Package and publish Grafana dashboards as a standalone downloadable artifact on each versioned release.

### Modified Capabilities

_(none)_

## Impact

- **GitHub Actions**: New workflow file added to `.github/workflows/`.
- **Release process**: Each `v*` tag push will now produce a `easy-db-lab-dashboards.zip` release asset in addition to any other release artifacts.
- **Consumers**: External projects can download the dashboard zip from the GitHub Releases page and import dashboards into Grafana. They must handle `__KEY__` template variable substitution themselves (e.g., `__CONTROL_NODE_IP__`, `__BUCKET_NAME__`).
- **No code changes**: No changes to Kotlin source, Koin modules, or existing dashboard provisioning logic.
