## MODIFIED Requirements

### Requirement: Catalog Sources

The system SHALL support automatic catalog injection for the following database kits when they are running alongside Presto: Cassandra, ClickHouse, and PostgreSQL.

A catalog properties file MUST exist in `kits/presto/catalogs/<kit-name>.properties.template` for each supported database kit. The `update-catalogs.sh` hook reads `RUNNING_KITS` and injects catalogs for any kit that has a matching properties file.

#### Scenario: Cassandra catalog

- **GIVEN** both `cassandra` and `presto` are running, **WHEN** `update-catalogs.sh` executes, **THEN** a `cassandra` catalog is present in Presto.

#### Scenario: ClickHouse catalog

- **GIVEN** both `clickhouse` and `presto` are running, **WHEN** `update-catalogs.sh` executes, **THEN** a `clickhouse` catalog is present in Presto.

#### Scenario: PostgreSQL catalog

- **GIVEN** both `postgres` and `presto` are running, **WHEN** `update-catalogs.sh` executes, **THEN** a `postgres` catalog is present in Presto using the `postgresql` connector pointed at `postgres-rw.default.svc.cluster.local:5432`.

#### Scenario: No catalog for absent kit

- **WHEN** a database kit is not running, **THEN** its catalog is not injected into Presto.
