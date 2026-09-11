## Why

During A/B config testing, an operator changes a database setting (say `concurrent_reads` from 64 to 128) and wants the Grafana timeline to show when and what changed. Today there is no way to mark that; a dashboard variable or a remembered time window cannot show the actual setting values. Operators need to drop a human-readable marker ("concurrent_reads 64->128") onto the time axis at the moment of the change.

Grafana annotations live only in Grafana's own database on the ephemeral control node. At teardown they are lost with the node, so the marks that make an A/B comparison legible do not survive the run they describe. Backups, observations, and metrics are critical work product; the tool must never lose them to teardown. There is also no automatic metrics backup today — only config files are auto-backed-up — so metrics are lost at `down` too unless the operator remembers to act.

## What Changes

- Add a `grafana annotate` command under the `Grafana` parent. It POSTs an annotation to the running Grafana HTTP API (`POST /api/annotations`) on the control node, over the proxied HTTP client, exactly like `metrics backup`. It carries `@RequiresProxy` and reuses `GrafanaDashboardService`'s injected `OkHttpClient`.
- `grafana annotate` exposes `--text`, `--tags`, `--time` (a human string converted to epoch milliseconds, defaulting to now), `--time-end` (a region annotation), and `--dashboard`/`--panel` scoping. Global annotations (no dashboard) are the primary path. On success it emits `Event.Grafana.AnnotationCreated` identifying the created annotation.
- `grafana annotate` exits non-zero with a clear error naming the unreachable Grafana endpoint when the API cannot be reached. It does not emit a failure event and return 0.
- Provision a tag-based annotation query on the core dashboards (via `GrafanaManifestBuilder` / the dashboard JSONs) so a global annotation carrying the agreed tag renders on the dashboards' timelines.
- Add a `grafana backup` command that captures the Grafana annotations (`GET /api/annotations`) as a JSON artifact and uploads it to an account-level S3 location, never under a per-cluster prefix that `down` sets to expire. It reports the resulting S3 URI, and fails fast with the standard "run up first" message when no S3 bucket is configured.
- Add an automatic backup at `down` that captures VictoriaMetrics and the Grafana annotations as one coupled operation, running before any infrastructure is torn down. The two backups are always attempted together — never one without the other. If the backup fails, `down` retries; if it still fails, `down` aborts and tears down no infrastructure, reporting the failure. `down --force` skips the backup and proceeds with teardown.
- Update the user documentation for the new commands and the `down --force` flag.

## Capabilities

### New Capabilities
- `grafana-annotations`: A `grafana annotate` command that POSTs a human-readable, optionally scoped, tagged annotation to the running Grafana API over the proxied HTTP client and exits non-zero when the API is unreachable; a `grafana backup` command that captures the annotations as JSON to an account-level S3 location; and a tag-based annotation query provisioned on the core dashboards so global annotations render on their timelines.

### Modified Capabilities
- `cluster-lifecycle`: Cluster Teardown gains an automatic metrics + annotations backup that runs before any infrastructure is torn down. A backup failure aborts teardown with no infrastructure removed; the `--force` flag skips the backup and proceeds.

## Impact

- New command: `commands/grafana/GrafanaAnnotate.kt` (`@RequiresProxy`, `@McpCommand`, `@RequireProfileSetup`), registered in the `Grafana` parent's `subcommands` list and the Koin commands module.
- New command: `commands/grafana/GrafanaBackup.kt` (same annotations), delegating to a new backup service.
- New annotation call on `services/GrafanaDashboardService.kt` (`createAnnotation`, `fetchAnnotations`), reusing its injected `OkHttpClient` and kotlinx.serialization types.
- New `services/GrafanaAnnotationBackupService.kt` (interface + default impl) for the annotations GET → JSON → account-level S3 upload.
- New serialization types: `GrafanaAnnotationRequest`, and the annotation response shape returned by the API.
- New account-level S3 path helper (a sibling of the per-cluster helpers in `configuration/ClusterS3Path.kt`, but outside the prefix `Down.setClusterLifecycleRule()` expires).
- New events: `Event.Grafana.AnnotationCreated`/`AnnotationFailed`, `Event.Backup.GrafanaAnnotationsBackupStarting`/`Complete`. Domain-typed, structured fields, no `Event.Message`/`Event.Error`.
- New teardown wiring in `commands/Down.kt`: run the coupled backup first, add the `--force` flag, abort on backup failure. A new `TeardownBackupService` orchestrates the metrics + annotations coupling in one testable home.
- Dashboard change (the tag-based annotation query) goes through the `dashboard-editor` agent per repo rules.
- A PicoCLI converter for `--time` (human string to epoch millis) under `commands/converters/`.
- Documentation: the Grafana commands reference and the teardown docs.
- Retry uses resilience4j (`RetryUtil`), not a hand-rolled loop. K8s config uses fabric8. No SOCKS JVM globals are touched.
