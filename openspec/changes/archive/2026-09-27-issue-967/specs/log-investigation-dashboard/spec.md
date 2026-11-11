## MODIFIED Requirements

### Requirement: Log volume histogram

The dashboard SHALL include a time-series panel showing log volume (count over time) that responds to all active filters.

#### Scenario: Volume spike visibility

- **WHEN** a burst of error logs occurs in a 1-minute window
- **THEN** the histogram SHALL show a visible spike at that time

#### Scenario: Filters apply to histogram

- **WHEN** user filters by Service = `cassandra`
- **THEN** the histogram SHALL only count Cassandra logs
