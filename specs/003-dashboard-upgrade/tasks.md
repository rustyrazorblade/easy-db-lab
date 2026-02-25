# Tasks: Grafana Dashboard Upgrade

**Input**: Design documents from `/specs/003-dashboard-upgrade/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Move dashboards to top-level directory and update build configuration

- [X] T001 Move all dashboard JSON files from `src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/` to `dashboards/` at repository root
- [X] T002 Add `dashboards/` as a resource source directory in `build.gradle.kts` sourceSets configuration
- [X] T003 Update `GrafanaDashboard.kt` enum `resourcePath` values to use absolute classpath paths (e.g., `"dashboards/system-overview.json"`) and update resource loading in `GrafanaManifestBuilder.kt` to resolve from classpath root instead of class-relative
- [X] T004 Remove `template.substitute()` call from `GrafanaManifestBuilder.buildDashboardConfigMap()` — load dashboard JSON as raw content since no `__KEY__` placeholders will remain
- [X] T005 Verify build compiles and existing tests pass with `./gradlew :test`

**Checkpoint**: Dashboards load from new top-level location, build succeeds, tests pass

---

## Phase 2: Foundational (OTel CloudWatch Receiver)

**Purpose**: Add CloudWatch metrics collection to OTel so the s3-cloudwatch dashboard can query via VictoriaMetrics

**CRITICAL**: Must complete before US1 can rewrite the s3-cloudwatch dashboard

- [X] T006 Add `awscloudwatch` receiver configuration to `src/main/resources/com/rustyrazorblade/easydblab/configuration/otel/otel-collector-config.yaml` — configure AWS/S3, AWS/EBS, and AWS/EC2 namespaces with 300s polling interval, add to metrics pipeline
- [X] T007 Update `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/otel/OtelManifestBuilder.kt` to inject `AWS_REGION` env var from `cluster-config` ConfigMap into the DaemonSet (if not already present)
- [X] T008 Verify OTel resources still apply in K8sServiceIntegrationTest — run `./gradlew :test --tests "*K8sServiceIntegrationTest*"`

**Checkpoint**: OTel collector configured to collect CloudWatch metrics via `awscloudwatch` receiver

---

## Phase 3: User Story 1 — Multi-Cluster Dashboard Filtering (Priority: P1) MVP

**Goal**: Add multi-select cluster variable to all 10 dashboards so users can view and compare data across multiple clusters

**Independent Test**: Deploy dashboards to Grafana instance with multi-cluster data and verify cluster dropdown supports multi-select on every dashboard

### Implementation for User Story 1

- [X] T009 [P] [US1] Add standardized multi-select `cluster` template variable to `dashboards/cassandra-overview.json` — change existing `cluster` variable from `multi: false` to `multi: true`, set `includeAll: true`, `allValue: ".*"`
- [X] T010 [P] [US1] Add standardized multi-select `cluster` template variable to `dashboards/cassandra-condensed.json` — change existing `cluster` variable from `multi: false` to `multi: true`, set `includeAll: true`, `allValue: ".*"`
- [X] T011 [P] [US1] Update `dashboards/clickhouse.json` — rename `Cluster` variable to `cluster` (lowercase), set `multi: true`, `includeAll: true`, `allValue: ".*"`, update all panel query references from `$Cluster` to `$cluster`
- [X] T012 [P] [US1] Add multi-select `cluster` template variable to `dashboards/system-overview.json` — add `cluster` variable with `query: "label_values(up, cluster)"`, `multi: true`, add `cluster=~"$cluster"` filter to all panel PromQL queries
- [X] T013 [P] [US1] Add multi-select `cluster` template variable to `dashboards/stress.json` — add `cluster` variable, add `cluster=~"$cluster"` filter to all panel PromQL queries
- [X] T014 [P] [US1] Add multi-select `cluster` template variable to `dashboards/clickhouse-logs.json` — add `cluster` variable with appropriate VictoriaLogs query filtering
- [X] T015 [P] [US1] Add multi-select `cluster` template variable to `dashboards/profiling.json` — add `cluster` variable, update Pyroscope queries to filter by cluster label
- [X] T016 [P] [US1] Add multi-select `cluster` template variable to `dashboards/emr.json` — add `cluster` variable, add cluster filtering to EMR metric queries
- [X] T017 [P] [US1] Add multi-select `cluster` template variable to `dashboards/opensearch.json` — add `cluster` variable, add cluster filtering to OpenSearch metric queries
- [X] T018 [US1] Rewrite `dashboards/s3-cloudwatch.json` — replace all direct CloudWatch datasource queries with PromQL queries against VictoriaMetrics using metrics from the `awscloudwatch` OTel receiver, add multi-select `cluster` variable, remove all `__BUCKET_NAME__` and `__METRICS_FILTER_ID__` placeholders
- [X] T019 [US1] Remove `__CLUSTER_NAME__` from all dashboard titles — replace with simple descriptive names (e.g., "System Overview", "Stress Overview", "ClickHouse and Keeper Comprehensive Dashboard", "AWS CloudWatch Overview", etc.) across all 10 dashboard JSON files
- [X] T020 [US1] Verify no `__KEY__` placeholders remain in any dashboard file — grep for `__[A-Z_]+__` pattern across all files in `dashboards/`
- [X] T021 [US1] Update `GrafanaManifestBuilderTest.kt` — adjust tests for new resource loading path, verify no-substitution behavior, ensure all 10 dashboards load correctly from `dashboards/` classpath

**Checkpoint**: All 10 dashboards have multi-select cluster variable, zero `__KEY__` placeholders, all tests pass

---

## Phase 4: User Story 2 — Top-Level Dashboard Location (Priority: P2)

**Goal**: Confirm dashboards are discoverable at top-level `dashboards/` directory and old nested path is removed

**Independent Test**: Verify `dashboards/` exists at repo root with all JSON files, old path deleted, build succeeds

### Implementation for User Story 2

- [X] T022 [US2] Delete the now-empty `src/main/resources/com/rustyrazorblade/easydblab/configuration/grafana/dashboards/` directory — verify it no longer exists
- [X] T023 [US2] Update `docs/` documentation for dashboard-related pages — document the new `dashboards/` location, how to add new dashboards, and that dashboards are pure standard Grafana JSON
- [X] T024 [US2] Update CLAUDE.md Grafana Dashboards section — reflect new file location, removal of `__KEY__` substitution, and updated resource loading path

**Checkpoint**: Old dashboard location removed, documentation updated, build still passes

---

## Phase 5: User Story 3 — Publish Dashboards as Separate GitHub Artifact (Priority: P3)

**Goal**: External consumers can download dashboards as a standalone zip from GitHub Releases

**Independent Test**: Run the publish workflow and verify the zip contains all valid Grafana dashboard JSON files

### Implementation for User Story 3

- [X] T025 [US3] Create `.github/workflows/publish-dashboards.yml` — trigger on GitHub Release published event, zip `dashboards/*.json` into `dashboards.zip`, attach to the release using `softprops/action-gh-release@v2`
- [X] T026 [US3] Add a `dashboards/README.md` documenting available dashboards, required datasources (Prometheus-compatible for metrics, VictoriaLogs for logs, Pyroscope for profiling), and import instructions for standalone Grafana instances

**Checkpoint**: Dashboards artifact workflow exists and attaches zip to releases

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [X] T027 Run full build validation: `./gradlew check` (includes test, ktlintCheck, detekt)
- [X] T028 Update `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/grafana/` CLAUDE.md or related documentation if GrafanaDashboard patterns changed
- [X] T029 Verify K8sServiceIntegrationTest passes with all updated resources: `./gradlew :test --tests "*K8sServiceIntegrationTest*"`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 (dashboards must be in new location)
- **US1 (Phase 3)**: Depends on Phase 2 (OTel CloudWatch receiver needed for s3-cloudwatch rewrite)
- **US2 (Phase 4)**: Depends on Phase 1 (dashboards already moved); can run in parallel with US1
- **US3 (Phase 5)**: Can start after Phase 1; independent of US1/US2
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **US1 (P1)**: Depends on Phase 2 (T006-T008) for OTel CloudWatch — this is the critical path
- **US2 (P2)**: Depends only on Phase 1 — cleanup and docs for the already-moved dashboards
- **US3 (P3)**: Depends only on Phase 1 — just needs dashboards at top-level to build the zip workflow

### Within User Story 1

- T009-T017 are all parallelizable (different JSON files)
- T018 (s3-cloudwatch rewrite) depends on T006-T008 (OTel CloudWatch receiver)
- T019 (title cleanup) can run in parallel with T009-T018
- T020 (placeholder verification) depends on T009-T019
- T021 (test update) depends on T004 (no-substitute change) and T009-T020

### Parallel Opportunities

```text
# After Phase 1 completes, three parallel tracks:
Track A: T006-T008 (OTel CloudWatch) → T018 (s3-cloudwatch rewrite) → T009-T017 (cluster vars) → T019-T021
Track B: T022-T024 (US2 — cleanup and docs)
Track C: T025-T026 (US3 — GitHub artifact workflow)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Move dashboards, update build
2. Complete Phase 2: Add OTel CloudWatch receiver
3. Complete Phase 3: Multi-cluster support for all dashboards
4. **STOP and VALIDATE**: Build passes, dashboards have multi-select cluster variable, zero `__KEY__` placeholders

### Incremental Delivery

1. Phase 1 + Phase 2 → Foundation ready
2. Add US1 (multi-cluster) → Validate independently → Core value delivered
3. Add US2 (cleanup + docs) → Old path removed, docs updated
4. Add US3 (artifact publishing) → External consumers can use dashboards standalone
5. Polish → Full validation

---

## Notes

- Dashboard JSON edits (T009-T019) are all different files and highly parallelizable
- The s3-cloudwatch rewrite (T018) is the most complex single task — requires understanding both the old CloudWatch queries and the new PromQL equivalents from OTel-collected metrics
- T004 (removing substitute) and T003 (resource path update) are tightly coupled — do them together
- The `otel/opentelemetry-collector-contrib:latest` image already includes the `awscloudwatch` receiver — no custom build needed
