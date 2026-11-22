## MODIFIED Requirements

### Requirement: Catalog Sources

The system SHALL support automatic catalog injection for the following database kits when they are running alongside Presto: Cassandra, ClickHouse, and PostgreSQL.

A catalog properties file MUST exist in `kits/presto/catalogs/<kit-name>.properties.template` for each supported database kit. The `update-catalogs.sh` hook reads `RUNNING_KITS` and injects catalogs for any kit that has a matching properties file.

#### Scenario: Cassandra catalog injected
- **GIVEN** both `cassandra` and `presto` are running
- **WHEN** `update-catalogs.sh` executes
- **THEN** a `cassandra` catalog is present in Presto.

#### Scenario: ClickHouse catalog injected
- **GIVEN** both `clickhouse` and `presto` are running
- **WHEN** `update-catalogs.sh` executes
- **THEN** a `clickhouse` catalog is present in Presto.

#### Scenario: Postgres catalog injected
- **GIVEN** both `postgres` and `presto` are running
- **WHEN** `update-catalogs.sh` executes
- **THEN** a `postgres` catalog is present in Presto using the `postgresql` connector pointed at `postgres-rw.<namespace>.svc:5432`, in the cluster namespace.

#### Scenario: Catalog omitted when kit not running
- **WHEN** a database kit is not running
- **THEN** its catalog is not injected into Presto.

