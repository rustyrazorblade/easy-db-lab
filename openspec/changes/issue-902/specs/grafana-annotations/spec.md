## MODIFIED Requirements

### Requirement: Grafana annotations are mirrored to Loki

Grafana SHALL remain where annotations are written: `grafana annotate`, the Cilium install marker, and the Grafana UI.  The system SHALL mirror annotations to Loki as log lines under the cluster's tenant: right after each annotation the tool creates, and every annotation inside Loki's accepted window at `grafana backup` and before teardown.  Loki's accepted window is from `Constants.Loki.MAX_ENTRY_AGE_HOURS` (8760 hours) ago to `Constants.Loki.MAX_ENTRY_AHEAD_HOURS` (24 hours) ahead.  An annotation outside the window SHALL NOT be mirrored and SHALL NOT fail the mirror: it SHALL be reported with the `Event.Grafana.AnnotationsOutsideLokiWindow` warning naming its id, and SHALL be kept in Grafana and in the S3 JSON backup.  Each annotation SHALL be its own Loki stream, labelled with `cluster`, `experiment`, `edl_tenant`, `source="annotation"` and its Grafana id, carrying its tags, dashboard, panel and end time as metadata.  Mirroring the same annotation twice SHALL NOT create a second entry.

#### Scenario: A tool annotation reaches Loki at once
- **WHEN** a user runs `grafana annotate` on a cluster in tenant `acme`
- **THEN** Loki stores the annotation under tenant `acme` with the cluster label

#### Scenario: A mirrored annotation carries the experiment and the tenant
- **WHEN** the mirror sends an annotation of a cluster in tenant `acme` with experiment `e1`
- **THEN** its Loki stream carries `cluster`, `experiment=e1` and `edl_tenant=acme`

#### Scenario: A UI annotation reaches Loki before teardown
- **WHEN** an operator creates an annotation in the Grafana UI and later runs `down`
- **THEN** Loki stores that annotation before any infrastructure is torn down

#### Scenario: An annotation outside Loki's window is reported and kept
- **WHEN** a Grafana annotation dated more than 8760 hours ago, or more than 24 hours ahead, is in Grafana and the annotations are mirrored at `grafana backup` or before teardown
- **THEN** that annotation is not sent to Loki, and every annotation inside the window is mirrored
- **AND** the `AnnotationsOutsideLokiWindow` warning names its id, the mirror does not fail, and the annotation stays in Grafana and in the S3 JSON backup

#### Scenario: Mirroring is idempotent
- **WHEN** the same annotation is mirrored twice
- **THEN** a Loki query returns it once
