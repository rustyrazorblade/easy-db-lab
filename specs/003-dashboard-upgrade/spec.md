# Feature Specification: Grafana Dashboard Upgrade

**Feature Branch**: `003-dashboard-upgrade`
**Created**: 2026-02-24
**Status**: Draft
**Input**: User description: "Let's do a major upgrade of the dashboards. They are stored in a nested area. Let's move them to the top level. Let's publish them as a separate artifact on GitHub so they can be consumed from other apps. Can they still be bundled in as a resource in the build if I move them there? I also want to support multiple clusters in all the dashboards. Should populate the cluster list and allow me to select multiple in every dashboard."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Multi-Cluster Dashboard Filtering (Priority: P1)

A user running multiple Cassandra/ClickHouse clusters wants to view metrics across several clusters simultaneously on a single dashboard. They open any Grafana dashboard and see a cluster dropdown at the top that is populated with all available clusters from their metrics data. They select two or more clusters, and all panels update to show data from those selected clusters, with series distinguished by cluster name.

**Why this priority**: This is the core UX improvement. Currently some dashboards have single-select cluster variables (or none at all). Multi-select across all dashboards enables cross-cluster comparison, which is the primary analytical workflow users need.

**Independent Test**: Can be tested by deploying dashboards to a Grafana instance with multi-cluster metric data and verifying the cluster variable allows multi-select and panels render correctly for multiple selections.

**Acceptance Scenarios**:

1. **Given** a Grafana instance with metrics from 3 clusters, **When** a user opens any dashboard, **Then** they see a "Cluster" dropdown populated with all 3 cluster names and multi-select is enabled.
2. **Given** a user has selected 2 of 3 clusters in the dropdown, **When** the panels render, **Then** each panel shows time series from both selected clusters, distinguishable by cluster label.
3. **Given** a dashboard that previously had no cluster variable (e.g., system-overview), **When** the dashboard is loaded, **Then** it now includes the cluster multi-select variable and panels filter by selected clusters.
4. **Given** a user selects "All" in the cluster dropdown, **When** panels render, **Then** data from all clusters is displayed.

---

### User Story 2 - Top-Level Dashboard Location (Priority: P2)

A developer or external consumer wants to find, edit, or reuse dashboard JSON files without navigating deep into the Kotlin source tree. The dashboards are located at a top-level `dashboards/` directory in the repository root, making them easy to discover and contribute to.

**Why this priority**: Moving dashboards to a discoverable top-level location is a prerequisite for publishing them as a separate artifact and makes the repo more approachable for dashboard contributors who don't work on the Kotlin codebase.

**Independent Test**: Can be tested by verifying dashboards exist at the top-level path and the Kotlin application still loads them correctly as classpath resources at build time.

**Acceptance Scenarios**:

1. **Given** a fresh clone of the repository, **When** a user lists the top-level directories, **Then** they see a `dashboards/` directory containing all Grafana dashboard JSON files.
2. **Given** dashboards have been moved to `dashboards/`, **When** the Gradle build runs, **Then** the dashboard JSON files are included in the JAR as classpath resources and the application loads them correctly.
3. **Given** the old nested path `src/main/resources/.../dashboards/`, **When** the build runs, **Then** the old path no longer exists (dashboards are not duplicated).

---

### User Story 3 - Publish Dashboards as Separate GitHub Artifact (Priority: P3)

An external team or tool wants to consume the Grafana dashboards without pulling in the entire easydblab codebase. The dashboards are published as a standalone artifact (e.g., a GitHub release asset or a separate publishable archive) that can be downloaded and imported into any Grafana instance.

**Why this priority**: Publishing as a separate artifact enables dashboard reuse across projects and teams, but depends on the dashboards first being moved to a top-level location and having proper multi-cluster support.

**Independent Test**: Can be tested by running the publish workflow and verifying the artifact is downloadable and contains valid Grafana dashboard JSON files that can be imported into a standalone Grafana instance.

**Acceptance Scenarios**:

1. **Given** the dashboards are at the top-level path, **When** a GitHub Actions workflow runs (or a Gradle task is executed), **Then** a dashboards archive artifact is produced containing all dashboard JSON files.
2. **Given** a published dashboards artifact, **When** a user downloads and extracts it, **Then** each JSON file is a valid Grafana dashboard that can be imported via Grafana's UI or API.
3. **Given** a new dashboard is added to the `dashboards/` directory, **When** the next release is published, **Then** the artifact automatically includes the new dashboard.

---

### Edge Cases

- What happens when a dashboard is opened with no clusters reporting metrics? The cluster dropdown should be empty and panels should show "No data" gracefully.
- What happens when a cluster name contains special characters? The variable should handle regex escaping properly since Grafana uses `=~` regex matching.
- What happens when the dashboards artifact is imported into a Grafana instance without VictoriaMetrics? Datasource variables should allow users to select their own compatible Prometheus datasource.
- What happens if someone edits a dashboard JSON in the old nested location after migration? The old location should not exist, preventing stale edits.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: All Grafana dashboards MUST include a "Cluster" template variable that supports multi-select and an "All" option.
- **FR-002**: The "Cluster" variable MUST be auto-populated from available cluster labels in the metrics datasource.
- **FR-003**: All dashboard panels MUST filter their queries by the selected cluster(s) using the cluster variable.
- **FR-004**: Dashboard JSON files MUST reside in a top-level `dashboards/` directory in the repository root.
- **FR-005**: The Gradle build MUST include the top-level `dashboards/` directory contents as classpath resources so the Kotlin application can load them via `TemplateService`.
- **FR-006**: The `GrafanaDashboard` enum and `GrafanaManifestBuilder` MUST be updated to reference the new resource path.
- **FR-007**: A build task or CI workflow MUST produce a standalone dashboards archive artifact suitable for publishing to GitHub Releases.
- **FR-008**: All `__KEY__` template placeholders (`__CLUSTER_NAME__`, `__BUCKET_NAME__`, `__METRICS_FILTER_ID__`) MUST be removed from dashboard JSON files. Dashboard titles MUST use simple descriptive names (e.g., "System Overview", "Stress Overview") without any cluster name prefix. CloudWatch-specific placeholders (`__BUCKET_NAME__`, `__METRICS_FILTER_ID__`) are eliminated by rewriting the s3-cloudwatch dashboard to query CloudWatch metrics via the OTel collector and VictoriaMetrics.
- **FR-009**: Dashboards MUST be pure standard Grafana JSON — importable into any Grafana instance without any substitution step or easydblab-specific tooling.
- **FR-010**: The OTel collector configuration MUST be updated to collect AWS CloudWatch metrics (S3, EBS, EC2) and export them to VictoriaMetrics, so the rewritten s3-cloudwatch dashboard can query them via PromQL.

### Key Entities

- **Dashboard**: A Grafana dashboard JSON file containing panels, template variables, and queries. Lives in `dashboards/` at the repo root.
- **Cluster Variable**: A Grafana template variable of type "query" that populates from Prometheus label values, supports multi-select, and filters all panels.
- **Dashboards Artifact**: A published archive (zip/tar.gz) containing all dashboard JSON files, publishable as a GitHub Release asset.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of dashboards include a multi-select cluster variable that populates from live metrics data.
- **SC-002**: All dashboards are discoverable in the top-level `dashboards/` directory (zero dashboard JSON files remain in the old nested resource path).
- **SC-003**: The application build succeeds and all existing tests pass after the dashboard relocation.
- **SC-004**: A standalone dashboards artifact can be produced and each dashboard within it imports successfully into a clean Grafana instance.
- **SC-005**: Users can select multiple clusters in any dashboard and see per-cluster data series in all panels.

## Clarifications

### Session 2026-02-24

- Q: Is rewriting the s3-cloudwatch dashboard to query via OTel/VictoriaMetrics in scope for this feature? → A: Yes, in scope — rewrite s3-cloudwatch queries to use VictoriaMetrics as part of this feature.
- Q: What should dashboard titles be after removing `__CLUSTER_NAME__`? → A: Simple descriptive names only (e.g., "System Overview", "Stress Overview").
- Q: Is the OTel collector already configured to collect CloudWatch metrics, or does that need to be added? → A: OTel CloudWatch collection needs to be added as part of this feature.

## Assumptions

- The Grafana template variable for clusters will query `label_values(up, cluster)` or a similar broad metric to discover all cluster names, consistent with the existing pattern in cassandra-overview.json.
- After this feature, dashboards contain zero `__KEY__` placeholders. The `TemplateService` substitution step for dashboards is no longer needed — dashboard JSON files are valid Grafana imports as-is.
- The OTel collector will be extended with a CloudWatch receiver to collect AWS metrics (S3, EBS, EC2) and export them to VictoriaMetrics. The s3-cloudwatch dashboard will be rewritten to query these metrics via PromQL instead of direct CloudWatch datasource queries, eliminating `__BUCKET_NAME__`/`__METRICS_FILTER_ID__` placeholders.
- Gradle's `sourceSets` resource configuration can reference a top-level directory outside `src/main/resources` to include it on the classpath.
- The dashboards artifact will be a zip file attached to GitHub Releases, not a separate Git repository or package registry artifact.
