## MODIFIED Requirements

### Requirement: Integration tests verify metrics flow after start
After a workload's `start` phase succeeds in an integration test, the test script SHALL wait up to 30 seconds and then assert that at least 10 metric series with `job="<workload>"` are visible in VictoriaMetrics before proceeding. This applies to all scrape-type workloads that have integration tests.

#### Scenario: Presto integration test asserts metrics are flowing
- **WHEN** `bin/tests/presto` runs the "Starting Presto" step and it succeeds
- **THEN** the test SHALL query `http://$CONTROL_HOST_PRIVATE:8428/api/v1/series?match[]={job="presto"}`
- **AND** SHALL assert the response contains at least 10 series
- **AND** SHALL fail with a clear error message if fewer than 10 series are found within 30 seconds

#### Scenario: ClickHouse integration test asserts metrics are flowing
- **WHEN** `bin/tests/clickhouse` runs the "Starting ClickHouse" step and it succeeds
- **THEN** the test SHALL query `http://$CONTROL_HOST_PRIVATE:8428/api/v1/series?match[]={job="clickhouse"}`
- **AND** SHALL assert the response contains at least 10 series
- **AND** SHALL fail with a clear error message if fewer than 10 series are found within 30 seconds

#### Scenario: Metrics verification step uses CONTROL_HOST_PRIVATE from env.sh
- **WHEN** the metrics verification step runs
- **THEN** it SHALL read `CONTROL_HOST_PRIVATE` from the sourced `env.sh` (already sourced by `bin/test` before delegating to the test script)
- **AND** SHALL NOT rely on any workload-specific environment variable
