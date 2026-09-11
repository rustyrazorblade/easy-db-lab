# Tasks

## 1. Grafana HTTP annotation types and service methods

- [x] 1.1 Add kotlinx.serialization types: `GrafanaAnnotationRequest` (text, tags, time?, timeEnd?, dashboardUID?, panelId?) and the annotation response shape returned by `GET`/`POST /api/annotations`.
- [x] 1.2 Add `createAnnotation(...)` and `fetchAnnotations(...)` to `GrafanaDashboardService`, reusing its injected `OkHttpClient`. `createAnnotation` throws on a non-2xx or unreachable endpoint with a message naming the endpoint.
- [x] 1.3 Add a PicoCLI converter under `commands/converters/` that parses a human `--time` string to epoch milliseconds, defaulting to now.

## 2. `grafana annotate` command

- [x] 2.1 Add `commands/grafana/GrafanaAnnotate.kt` (`@RequiresProxy`, `@McpCommand`, `@RequireProfileSetup`) with `--text`, `--tags`, `--time`, `--time-end`, `--dashboard`, `--panel`. Named options only.
- [x] 2.2 Register the command in the `Grafana` parent `subcommands` list and the Koin commands module.
- [x] 2.3 Emit `Event.Grafana.AnnotationCreated` (structured fields, identifying the annotation) on success; ensure a non-zero exit on an unreachable API (no failure-event-and-return-0).
- [x] 2.4 Tests: annotate with defaults, with explicit time + tags, with a dashboard/panel scope, and the unreachable-endpoint non-zero-exit path.

## 3. Tag-based annotation query on core dashboards

- [ ] 3.1 Via the `dashboard-editor` agent: add a tag-filtered annotation query to the core dashboards so global annotations carrying the agreed tag render on their timelines. Follow the edit → deploy → read-back-from-Grafana sequence.
- [ ] 3.2 Confirm a tagged global annotation renders on a core dashboard (read-back).

## 4. `grafana backup` (annotations) to account-level S3

- [ ] 4.1 Add an account-level S3 path helper (sibling of the per-cluster helpers in `configuration/ClusterS3Path.kt`, but outside the prefix `Down.setClusterLifecycleRule()` expires), keyed by cluster name + timestamp.
- [ ] 4.2 Add `services/GrafanaAnnotationBackupService.kt` (interface + default impl): `GET /api/annotations` over the proxied client, serialize to JSON, upload to the account-level location, emit `Event.Backup.GrafanaAnnotationsBackup*`.
- [ ] 4.3 Add `commands/grafana/GrafanaBackup.kt` (`@RequiresProxy`) delegating to the service; report the S3 URI; fail fast with the standard "run up first" message when no bucket is configured. Register it.
- [ ] 4.4 Tests: backup uploads to the account-level location and reports the URI; no-bucket fails fast with the "run up first" message.

## 5. Automatic backup at `down`

- [ ] 5.1 Add `services/TeardownBackupService.kt` that runs the metrics backup and the annotations backup as one coupled operation, retried via resilience4j (`RetryUtil`). Both always attempted together.
- [ ] 5.2 Give the teardown-path metrics backup Job a short timeout so a stuck backup does not delay the abort/`--force` decision.
- [ ] 5.3 Wire `commands/Down.kt`: run the coupled backup FIRST, before any infrastructure teardown and before the proxy is torn down (establish the tunnel with `SocksProxyService.ensureRunning`). On backup failure, abort with no infrastructure removed and report the failure.
- [ ] 5.4 Add the `--force` flag to `Down` to skip the backup and proceed.
- [ ] 5.5 Integration tests (K3s TestContainer where K8s is involved): backup runs before teardown; a failed backup aborts `down` with no infra removed; `--force` skips the backup and tears down.

## 6. Documentation

- [ ] 6.1 Document `grafana annotate` and `grafana backup` in the Grafana commands reference.
- [ ] 6.2 Document the automatic backup at `down`, the abort-on-failure behavior, and `down --force`.

## 7. Validation

- [ ] 7.1 `./gradlew ktlintFormat detekt` clean (detekt on JDK 21).
- [ ] 7.2 `./gradlew test` green; `./gradlew integrationTest` green for the new K3s-backed tests.
- [ ] 7.3 Confirm no SOCKS JVM globals are touched; the proxied client uses the per-client SOCKS path.
