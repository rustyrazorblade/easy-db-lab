## ADDED Requirements

### Requirement: KAFKA endpoint type in KitEndpoint.EndpointType
The `KitEndpoint.EndpointType` enum SHALL include a `KAFKA` variant serialized as `"kafka"` in kit.yaml.

#### Scenario: kafka type parsed from kit.yaml
- **WHEN** a kit.yaml endpoint declares `type: kafka`
- **THEN** `KitEndpoint.EndpointType.KAFKA` is deserialized without error

#### Scenario: Unknown endpoint type still fails
- **WHEN** a kit.yaml endpoint declares an unrecognised type string
- **THEN** deserialization fails with a meaningful error

### Requirement: KAFKA endpoint formatUrl returns host:port
`KitEndpoint.formatUrl(ip)` for `EndpointType.KAFKA` SHALL return `"$ip:$port"` — no scheme prefix.

#### Scenario: formatUrl for kafka endpoint
- **WHEN** `formatUrl("10.0.0.5")` is called on an endpoint with type `KAFKA` and port `9092`
- **THEN** the result is `"10.0.0.5:9092"`

#### Scenario: formatUrl for external kafka endpoint
- **WHEN** `formatUrl("100.64.1.2")` is called on an endpoint with type `KAFKA` and port `30092`
- **THEN** the result is `"100.64.1.2:30092"`
