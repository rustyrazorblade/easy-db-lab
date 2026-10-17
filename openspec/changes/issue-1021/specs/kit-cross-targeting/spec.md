## MODIFIED Requirements

### Requirement: TARGET_* env vars injected at start time
When a bench kit with a `kit-ref` arg is started, the framework SHALL read the target
kit's `kit.yaml` from its directory in the workspace and inject `TARGET_*` environment
variables derived from each declared endpoint into the start script's environment.

Each `jdbc`, `postgresql`, and `mysql` endpoint SHALL also inject a password variable
(`TARGET_JDBC_PASSWORD`, `TARGET_PG_PASSWORD`, `TARGET_MYSQL_PASSWORD`) from the target
kit's `sql` capability `password` field. The variable SHALL be an empty string when the
target kit has no password. The framework SHALL NOT print the password.

#### Scenario: JDBC endpoint produces TARGET_JDBC_* vars
- **WHEN** a bench kit starts with `TARGET=clickhouse`
- **AND** `clickhouse/kit.yaml` declares a `jdbc` endpoint on port 30123 with scheme
  `clickhouse` and path `/default?compress=0`
- **AND** cluster state contains a db node with private IP `10.0.1.5`
- **THEN** the start script environment contains:
  - `TARGET_JDBC_URL=jdbc:clickhouse://10.0.1.5:30123/default?compress=0`
  - `TARGET_JDBC_USER=default` (from the target kit's `sql` capability `user` field)
  - `TARGET_JDBC_PASSWORD=` (empty, because the capability declares no `password`)
  - `TARGET_JDBC_DRIVER=<driver-class>` (from the `sql` capability `driver-class` field,
    or empty string if absent)

#### Scenario: PostgreSQL wire endpoint produces TARGET_PG_* vars
- **WHEN** a bench kit starts with `TARGET=somedb`
- **AND** `somedb/kit.yaml` declares a `postgresql` endpoint on port 5432 with
  `database: mydb` and `user: bench`
- **AND** cluster state contains a db node with private IP `10.0.1.5`
- **THEN** the start script environment contains:
  - `TARGET_PG_HOST=10.0.1.5`
  - `TARGET_PG_PORT=5432`
  - `TARGET_PG_USER=bench`
  - `TARGET_PG_PASSWORD=` (empty if not declared)
  - `TARGET_PG_DATABASE=mydb`

#### Scenario: MySQL wire endpoint produces TARGET_MYSQL_* vars
- **WHEN** a bench kit starts with `TARGET=somedb`
- **AND** `somedb/kit.yaml` declares a `mysql` endpoint on port 3306 with `database: bench`
- **AND** cluster state contains a db node with private IP `10.0.1.5`
- **THEN** the start script environment contains:
  - `TARGET_MYSQL_HOST=10.0.1.5`
  - `TARGET_MYSQL_PORT=3306`
  - `TARGET_MYSQL_USER=` (empty if not declared)
  - `TARGET_MYSQL_PASSWORD=` (empty if not declared)
  - `TARGET_MYSQL_DATABASE=bench`

#### Scenario: Password from the target sql capability
- **WHEN** a bench kit starts with `TARGET=singlestore`
- **AND** the installed `singlestore/kit.yaml` declares a `sql` capability with `password: X`
- **THEN** `TARGET_MYSQL_PASSWORD=X` and `TARGET_JDBC_PASSWORD=X` are in the start script environment

#### Scenario: Multiple endpoint types all injected
- **WHEN** the target kit declares both a `jdbc` endpoint and a `postgresql` endpoint
- **THEN** both `TARGET_JDBC_*` and `TARGET_PG_*` vars are present in the environment

#### Scenario: Target kit directory missing
- **WHEN** a bench kit starts with `TARGET=nonexistent`
- **AND** no `nonexistent/` directory exists in the workspace
- **THEN** no `TARGET_*` vars are injected
- **AND** the start script proceeds (and fails naturally if it references those vars)

## ADDED Requirements

### Requirement: sysbench authenticates with the target password

The sysbench kit SHALL build its database driver args once, as a shell array, in `bin/lib/target-db.sh`, which `prepare`, `start`, and `stop` source from the script directory. When `TARGET_MYSQL_PASSWORD` or `TARGET_PG_PASSWORD` is not empty, the args SHALL include `--mysql-password` or `--pgsql-password`. The create-database pod SHALL get the password through the `MYSQL_PWD` environment variable, not on its command line. `stop` SHALL list the pod's events instead of running `kubectl describe pod`, so the pod's command line is never printed. When the target has no password, sysbench SHALL behave as it does today. The SQL that `oltp_read_write` runs SHALL work on SingleStore with its default table type.

#### Scenario: oltp_read_write on SingleStore
- **WHEN** `sysbench --target <singlestore kit>` runs prepare and then start with `oltp_read_write`
- **THEN** both finish with no SQL errors
- **AND** start reports non-zero TPS

#### Scenario: Every phase uses the password
- **WHEN** the target has a password
- **THEN** the cleanup, create-database, prepare, run, and stop steps all authenticate with it

#### Scenario: Password with special characters
- **WHEN** the target password holds spaces, `*`, or single quotes
- **THEN** every sysbench phase authenticates with it
- **AND** the password does not appear in sysbench output

#### Scenario: Passwordless targets are unchanged
- **WHEN** the target has no password (tidb, postgres, clickhouse)
- **THEN** the sysbench args carry no password flag and the create-database pod has no `MYSQL_PWD`
- **AND** the existing sysbench tests pass
