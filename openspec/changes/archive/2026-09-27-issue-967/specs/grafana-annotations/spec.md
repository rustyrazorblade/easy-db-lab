## MODIFIED Requirements

### Requirement: Create a Grafana annotation from the CLI

The system SHALL provide a `grafana annotate` command under the `Grafana` parent that POSTs an annotation to the running Grafana HTTP API (`POST /api/annotations`) on the control node, over the proxied HTTP client. The command SHALL carry `@RequiresProxy` and reuse the existing injected Grafana HTTP client. It SHALL accept `--text` (the annotation body), `--tags` (zero or more tags), `--time` (an optional time defaulting to the current time), `--time-end` (an optional end time producing a region annotation), and `--dashboard`/`--panel` scoping options. After Grafana accepts the annotation, the command SHALL mirror it to Loki, and SHALL exit non-zero if the mirror fails.  On success it SHALL emit `Event.Grafana.AnnotationCreated` identifying the created annotation.

#### Scenario: Annotate at the current time with no explicit time

- **WHEN** a user runs `grafana annotate --text "concurrent_reads 64->128"` against a provisioned, UP cluster with no `--time`
- **THEN** an annotation is created at the current time
- **AND** the operator sees confirmation identifying the created annotation

#### Scenario: Annotate with an explicit time and tags

- **WHEN** a user runs `grafana annotate` with an explicit `--time` and one or more `--tags` (and any other supported fields)
- **THEN** the annotation is created at that time carrying those tags and fields

#### Scenario: Annotate a specific dashboard or panel scope

- **WHEN** a user runs `grafana annotate` with a supported scope (global, or a specific `--dashboard`/`--panel`)
- **THEN** the annotation lands in that scope

#### Scenario: A backdated annotation reaches Loki

- **WHEN** a user runs `grafana annotate --time -2h` on a cluster whose Loki already holds newer annotations
- **THEN** Loki stores the annotation at the requested time

### Requirement: Global annotations render on the core dashboards

The system SHALL provision a Loki annotation query on the core dashboards so that every global annotation (no dashboard scope) from the selected clusters in the tenant renders on those dashboards' timelines.  Dashboard-scoped annotations of the current cluster SHALL keep rendering through Grafana's built-in annotation query.  Each annotation SHALL render once.  A global A/B marker SHALL be visible on the core dashboards without the operator manually adding an annotation query.

#### Scenario: A global annotation appears on a core dashboard

- **WHEN** a global annotation exists in Loki for a selected cluster
- **AND** a user opens a core dashboard
- **THEN** the annotation renders on that dashboard's timeline through the Loki annotation query

#### Scenario: An annotation renders once

- **WHEN** the tool creates a global annotation, which is stored in both Grafana and Loki
- **THEN** the core dashboard shows it once

### Requirement: Back up Grafana annotations to an account-level S3 location

The system SHALL provide a `grafana backup` command that first mirrors every Grafana annotation inside Loki's accepted window to Loki, then captures the Grafana annotations (`GET /api/annotations`) as a JSON artifact and uploads it to the cluster tenant's annotations location in the account bucket, `observability/annotations/<tenant>/`. The artifact SHALL be named `<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, so that artifacts from different clusters in one tenant never collide, and a backup SHALL never overwrite an earlier one taken in the same second. On success the command SHALL report the resulting S3 URI. The command SHALL carry `@RequiresProxy` and reach the API over the proxied HTTP client.

#### Scenario: Backup uploads annotations and reports the URI

- **WHEN** a user runs `grafana backup` against an UP cluster in tenant `acme` with an S3 bucket configured
- **THEN** every Grafana annotation inside Loki's accepted window is mirrored to Loki
- **AND** the Grafana annotations are captured as a JSON artifact uploaded under `observability/annotations/acme/`, named with the timestamp and the cluster's name and id
- **AND** the resulting S3 URI is reported

#### Scenario: Backup fails fast with no S3 bucket

- **WHEN** a user runs `grafana backup` with no S3 bucket configured
- **THEN** the command fails fast with the same "run up first" message the other backups use

## ADDED Requirements

### Requirement: Grafana annotations are mirrored to Loki

Grafana SHALL remain where annotations are written: `grafana annotate`, the Cilium install marker, and the Grafana UI.  The system SHALL mirror annotations to Loki as log lines under the cluster's tenant: right after each annotation the tool creates, and every annotation inside Loki's accepted window at `grafana backup` and before teardown.  Loki's accepted window is from `Constants.Loki.MAX_ENTRY_AGE_HOURS` (8760 hours) ago to `Constants.Loki.MAX_ENTRY_AHEAD_HOURS` (24 hours) ahead.  An annotation outside the window SHALL NOT be mirrored and SHALL NOT fail the mirror: it SHALL be reported with the `Event.Grafana.AnnotationsOutsideLokiWindow` warning naming its id, and SHALL be kept in Grafana and in the S3 JSON backup.  Each annotation SHALL be its own Loki stream, labelled with `cluster`, `source="annotation"` and its Grafana id, carrying its tags, dashboard, panel and end time as metadata.  Mirroring the same annotation twice SHALL NOT create a second entry.

#### Scenario: A tool annotation reaches Loki at once
- **WHEN** a user runs `grafana annotate` on a cluster in tenant `acme`
- **THEN** Loki stores the annotation under tenant `acme` with the cluster label

#### Scenario: A UI annotation reaches Loki before teardown
- **WHEN** an operator creates an annotation in the Grafana UI and later runs `down`
- **THEN** Loki stores that annotation before any infrastructure is torn down

#### Scenario: An annotation outside Loki's window is reported and kept
- **GIVEN** a Grafana annotation dated more than 8760 hours ago, or more than 24 hours ahead
- **WHEN** the annotations are mirrored at `grafana backup` or before teardown
- **THEN** that annotation is not sent to Loki, and every annotation inside the window is mirrored
- **AND** the `AnnotationsOutsideLokiWindow` warning names its id, the mirror does not fail, and the annotation stays in Grafana and in the S3 JSON backup

#### Scenario: Mirroring is idempotent
- **WHEN** the same annotation is mirrored twice
- **THEN** a Loki query returns it once
