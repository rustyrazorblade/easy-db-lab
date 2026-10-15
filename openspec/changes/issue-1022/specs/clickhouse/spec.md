## ADDED Requirements

### Requirement: Native endpoint declares the default database
The `clickhouse` kit's `Native` endpoint in `kit.yaml` SHALL declare `database: "default"`, so a bench kit that targets it gets `TARGET_NATIVE_DATABASE=default`.  `KitEndpoint.formatUrl` SHALL ignore the database for a `native` endpoint and return `host:port` as before.

#### Scenario: Native endpoint database parsed
- **WHEN** the built-in `clickhouse/kit.yaml` is parsed
- **THEN** its endpoint of type `native` on port 30900 has `database` equal to `default`

#### Scenario: Native endpoint URL unchanged
- **WHEN** `formatUrl("10.0.1.5")` is called on that endpoint
- **THEN** it returns `10.0.1.5:30900`
