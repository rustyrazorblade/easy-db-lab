## ADDED Requirements

### Requirement: postgresql endpoint type in kit.yaml
`kit.yaml` SHALL support `type: postgresql` in the `endpoints:` list, representing a
PostgreSQL wire-protocol listener. An optional `database` field names the default
database.

#### Scenario: postgresql endpoint declared and parsed
- **WHEN** a `kit.yaml` contains an endpoint with `type: postgresql`
- **THEN** `KitEndpoint.type` is `POSTGRESQL` with correct port and optional `database`

#### Scenario: postgresql endpoint not in formatUrl
- **WHEN** `KitEndpoint.formatUrl()` is called on a `POSTGRESQL` endpoint
- **THEN** it returns the host:port string (same as `NATIVE`/`CQL`)

### Requirement: mysql endpoint type in kit.yaml
`kit.yaml` SHALL support `type: mysql` in the `endpoints:` list, representing a MySQL
wire-protocol listener. An optional `database` field names the default database.

#### Scenario: mysql endpoint declared and parsed
- **WHEN** a `kit.yaml` contains an endpoint with `type: mysql`
- **THEN** `KitEndpoint.type` is `MYSQL` with correct port and optional `database`

### Requirement: database field on KitEndpoint
`KitEndpoint` SHALL support an optional `database: String` field (default empty string)
used by wire-protocol endpoints to identify the default database name.

#### Scenario: database field present
- **WHEN** an endpoint declares `database: mydb`
- **THEN** `KitEndpoint.database` equals `"mydb"`

#### Scenario: database field absent
- **WHEN** an endpoint omits the `database` field
- **THEN** `KitEndpoint.database` is an empty string
