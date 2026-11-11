## ADDED Requirements

### Requirement: Integration tests verify metrics flow after a kit starts
After a kit's `start` phase succeeds in an integration test, the test SHALL wait up to 30 seconds and then assert that at least 10 metric series with `job="<kit>"` are visible in VictoriaMetrics before proceeding. This applies to every scrape-type kit that has an integration test.

#### Scenario: Metrics assertion follows a successful start
- **WHEN** an integration test's `start` step succeeds for a scrape-type kit
- **THEN** the test SHALL query `http://$CONTROL_HOST_PRIVATE:8428/api/v1/series?match[]={job="<kit>"}`
- **AND** SHALL assert the response contains at least 10 series
- **AND** SHALL fail with a clear error message if fewer than 10 series are found within 30 seconds

#### Scenario: Metrics verification reads CONTROL_HOST_PRIVATE from env.sh
- **WHEN** the metrics verification step runs
- **THEN** it SHALL read `CONTROL_HOST_PRIVATE` from the sourced `env.sh`
- **AND** SHALL NOT rely on any kit-specific environment variable
