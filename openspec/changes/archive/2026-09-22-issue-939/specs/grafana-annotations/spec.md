## ADDED Requirements

### Requirement: Create a Grafana annotation from the CLI

The system SHALL provide a `grafana annotate` command under the `Grafana` parent that POSTs an annotation to the running Grafana HTTP API (`POST /api/annotations`) on the control node, over the proxied HTTP client. The command SHALL carry `@RequiresProxy` and reuse the existing injected Grafana HTTP client. It SHALL accept `--text` (the annotation body), `--tags` (zero or more tags), `--time` (an optional time defaulting to the current time), `--time-end` (an optional end time producing a region annotation), and `--dashboard`/`--panel` scoping options. On success it SHALL emit `Event.Grafana.AnnotationCreated` identifying the created annotation.

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

### Requirement: Fail non-zero when Grafana is unreachable

WHEN the Grafana API cannot be reached (the control node is down, Grafana is not ready, or the proxy is not established), the `grafana annotate` command SHALL fail with a clear error naming the unreachable Grafana endpoint and SHALL exit with a non-zero status. It SHALL NOT emit a failure event and return 0, and it SHALL NOT silently succeed.

#### Scenario: Unreachable Grafana produces a non-zero exit

- **WHEN** a user runs `grafana annotate` and the Grafana endpoint is unreachable
- **THEN** the command prints an error naming the unreachable Grafana endpoint
- **AND** the command exits with a non-zero status
- **AND** no annotation is reported as created

### Requirement: Global annotations render on the core dashboards

The system SHALL provision a tag-based annotation query on the core dashboards so that a global annotation (no dashboard scope) carrying the agreed tag renders on those dashboards' timelines. A global A/B marker SHALL be visible on the core dashboards without the operator manually adding an annotation query.

#### Scenario: A tagged global annotation appears on a core dashboard

- **WHEN** a global annotation carrying the agreed tag exists
- **AND** a user opens a core dashboard
- **THEN** the annotation renders on that dashboard's timeline via the provisioned tag-based annotation query

### Requirement: Back up Grafana annotations to an account-level S3 location

The system SHALL provide a `grafana backup` command that captures the Grafana annotations (`GET /api/annotations`) as a JSON artifact and uploads it to an account-level S3 location. The artifact SHALL NOT be written under a per-cluster prefix that cluster teardown sets to expire. On success the command SHALL report the resulting S3 URI. The command SHALL carry `@RequiresProxy` and reach the API over the proxied HTTP client.

#### Scenario: Backup uploads annotations and reports the URI

- **WHEN** a user runs `grafana backup` against an UP cluster with an S3 bucket configured
- **THEN** the Grafana annotations are captured as a JSON artifact
- **AND** the artifact is uploaded to an account-level Grafana backup location, not a per-cluster prefix subject to teardown expiration
- **AND** the resulting S3 URI is reported

#### Scenario: Backup fails fast with no S3 bucket

- **WHEN** a user runs `grafana backup` with no S3 bucket configured
- **THEN** the command fails fast with the same "run up first" message the other backups use
