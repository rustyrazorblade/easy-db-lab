## MODIFIED Requirements

### Requirement: Journal entries have proper timestamps

Journal entries collected from exec-run tools SHALL have timestamps assigned by systemd at the time each line was written, enabling accurate correlation with other log sources in Loki.

#### Scenario: Timestamps enable cross-source correlation

- **GIVEN** an `inotifywait` event occurs at the same time as a Cassandra log entry
- **WHEN** both are queried in Loki
- **THEN** both entries SHALL have timestamps within the same second, not offset by ingestion delay.
