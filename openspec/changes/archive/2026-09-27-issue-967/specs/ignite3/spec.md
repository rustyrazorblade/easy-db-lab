## MODIFIED Requirements

### Requirement: OTLP Metrics

The kit SHALL configure Ignite 3 to push metrics to the cluster's OTel Collector via OTLP after cluster initialization.

#### Scenario: Metrics exporter is configured at start

- **WHEN** `ignite3 start` completes cluster initialization
- **THEN** the system configures Ignite's OTLP exporter pointing to `http://${CONTROL_HOST_PRIVATE}:4318/v1/metrics` using the `http/protobuf` protocol

#### Scenario: Metrics flow into Mimir

- **WHEN** the OTLP exporter is configured and Ignite is running
- **THEN** Ignite metrics appear in Mimir and are visible in Grafana
