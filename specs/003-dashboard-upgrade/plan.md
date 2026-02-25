# Implementation Plan: Grafana Dashboard Upgrade

**Branch**: `003-dashboard-upgrade` | **Date**: 2026-02-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-dashboard-upgrade/spec.md`

## Summary

Move Grafana dashboards from deeply nested resource path to top-level `dashboards/` directory, add multi-cluster support (multi-select cluster variable) to all 10 dashboards, add OTel CloudWatch metrics collection to replace direct CloudWatch datasource queries, remove all `__KEY__` template placeholders making dashboards pure standard Grafana JSON, and publish dashboards as a standalone GitHub artifact.

## Technical Context

**Language/Version**: Kotlin (JVM 21, Temurin)
**Primary Dependencies**: Fabric8 (K8s manifests), kotlinx.serialization, Gradle (build), OTel Collector Contrib (CloudWatch receiver)
**Storage**: N/A (dashboard JSON files on classpath, metrics in VictoriaMetrics)
**Testing**: JUnit 5 + AssertJ + BaseKoinTest, K3s TestContainers for integration tests
**Target Platform**: Linux (CLI tool + K8s cluster management)
**Project Type**: CLI tool
**Performance Goals**: N/A (dashboard JSON files, no runtime performance concerns)
**Constraints**: Dashboards must be valid standalone Grafana JSON (no easydblab-specific substitution needed)
**Scale/Scope**: 10 existing dashboards, 1 OTel config update, Gradle build config changes, 1 GitHub Actions workflow

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. CLI-First UX | PASS | No new CLI output changes; dashboard files are deployed via K8s ConfigMaps |
| II. Layered Architecture | PASS | Changes stay within configuration layer (GrafanaDashboard, GrafanaManifestBuilder, OtelManifestBuilder) |
| III. Reasonable TDD | PASS | Will update existing GrafanaManifestBuilderTest and K8sServiceIntegrationTest; skip trivial tests on JSON content |
| IV. Never Disable, Always Fix | PASS | Not disabling anything; rewriting CloudWatch dashboard to use OTel pipeline |
| V. Type Safety Over Strings | PASS | K8s manifests remain Fabric8-built; OTel config uses existing YAML template pattern |

**Quality Gates**: ktlintCheck, detekt, test all required before merge.
**Tech Stack**: Fabric8 for K8s, kotlinx.serialization — no new dependencies needed.

## Project Structure

### Documentation (this feature)

```text
specs/003-dashboard-upgrade/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
dashboards/                          # NEW: top-level dashboard directory
├── system-overview.json
├── stress.json
├── cassandra-overview.json
├── cassandra-condensed.json
├── clickhouse.json
├── clickhouse-logs.json
├── profiling.json
├── s3-cloudwatch.json               # Rewritten to use PromQL/VictoriaMetrics
├── emr.json
└── opensearch.json

src/main/kotlin/com/rustyrazorblade/easydblab/
├── configuration/grafana/
│   ├── GrafanaDashboard.kt          # MODIFY: update resourcePath references
│   └── GrafanaManifestBuilder.kt    # MODIFY: update resource loading (no more substitute())
├── configuration/otel/
│   └── OtelManifestBuilder.kt       # MODIFY: inject AWS_REGION env var

src/main/resources/com/rustyrazorblade/easydblab/
├── configuration/otel/
│   └── otel-collector-config.yaml   # MODIFY: add awscloudwatch receiver
├── configuration/grafana/
│   └── dashboards/                  # DELETE: old location (files moved to top-level)

src/test/kotlin/com/rustyrazorblade/easydblab/
├── configuration/grafana/
│   └── GrafanaManifestBuilderTest.kt  # MODIFY: update for new resource path, no substitution
├── services/
│   └── K8sServiceIntegrationTest.kt   # VERIFY: OTel changes still pass apply test

build.gradle.kts                     # MODIFY: add dashboards/ to resource sourceSets

.github/workflows/
└── publish-dashboards.yml           # NEW: workflow to zip and publish dashboards artifact
```

**Structure Decision**: Existing single-project Kotlin CLI structure. The only new top-level directory is `dashboards/` which holds the moved JSON files. No new modules or packages needed.

## Complexity Tracking

No constitution violations. No complexity justification needed.
