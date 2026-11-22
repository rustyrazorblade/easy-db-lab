## MODIFIED Requirements

### Requirement: Presto Integration

When both the `postgres` and `presto` kits are running, Presto SHALL automatically expose a `postgres` catalog using the `postgresql` connector, pointing to the CNPG primary service (`postgres-rw.<namespace>.svc:5432`, in the cluster namespace).

#### Scenario: Postgres catalog exposed in Presto
- **GIVEN** both `postgres start` and `presto start` have been run
- **WHEN** Presto's `update-catalogs.sh` hook executes
- **THEN** a `postgres` catalog is present in Presto and `SHOW CATALOGS` includes `postgres`.

#### Scenario: No catalog without postgres kit
- **WHEN** only presto is running (no postgres kit)
- **THEN** no `postgres` catalog appears in Presto.

