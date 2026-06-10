## ADDED Requirements

### Requirement: kit-ref arg type for cross-kit targeting
A kit arg with `type: kit-ref` SHALL declare a dependency on a running kit's SQL
endpoints. The `capability` field is advisory metadata documenting intent; the `variable`
field names the env var that stores the target kit name (conventionally `TARGET`).

#### Scenario: kit-ref arg declared in kit.yaml
- **WHEN** a `kit.yaml` contains an arg with `type: kit-ref`
- **THEN** the arg is parsed without error and `KitArgSpec.type` is `KIT_REF`

#### Scenario: Unknown arg types are ignored gracefully
- **WHEN** a `kit.yaml` contains an arg with an unrecognised type
- **THEN** deserialization does not throw; the arg defaults to `STRING` type

### Requirement: Instance directory named after bench kit and target
When a bench kit is installed and its `kit-ref` arg is provided, the install command
SHALL create a directory named `<kit-name>-<target-kit-name>` in the cluster workspace
rather than the kit's base name.

#### Scenario: Bench kit installed with target
- **WHEN** the user runs `easy-db-lab kit install sysbench --target clickhouse`
- **THEN** a directory named `sysbench-clickhouse` is created in the cluster workspace
- **AND** the directory contains the rendered kit files

#### Scenario: Same bench kit installed with different target
- **WHEN** the user runs `easy-db-lab kit install sysbench --target mysql`
- **THEN** a directory named `sysbench-mysql` is created
- **AND** the `sysbench-clickhouse` directory is unaffected

#### Scenario: Bench kit appears as its own CLI subcommand
- **WHEN** `sysbench-clickhouse` and `sysbench-mysql` directories exist in the workspace
- **THEN** both `easy-db-lab sysbench-clickhouse` and `easy-db-lab sysbench-mysql` are
  registered as CLI subcommands
- **AND** each has independent `start`, `stop`, and `status` subcommands

### Requirement: TARGET_* env vars injected at start time
When a bench kit with a `kit-ref` arg is started, the framework SHALL read the target
kit's `kit.yaml` from its directory in the workspace and inject `TARGET_*` environment
variables derived from each declared endpoint into the start script's environment.

#### Scenario: JDBC endpoint produces TARGET_JDBC_* vars
- **WHEN** a bench kit starts with `TARGET=clickhouse`
- **AND** `clickhouse/kit.yaml` declares a `jdbc` endpoint on port 30123 with scheme
  `clickhouse` and path `/default?compress=0`
- **AND** cluster state contains a db node with private IP `10.0.1.5`
- **THEN** the start script environment contains:
  - `TARGET_JDBC_URL=jdbc:clickhouse://10.0.1.5:30123/default?compress=0`
  - `TARGET_JDBC_USER=default` (from the target kit's `sql` capability `user` field)
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
  - `TARGET_PG_DATABASE=mydb`

#### Scenario: MySQL wire endpoint produces TARGET_MYSQL_* vars
- **WHEN** a bench kit starts with `TARGET=somedb`
- **AND** `somedb/kit.yaml` declares a `mysql` endpoint on port 3306 with `database: bench`
- **AND** cluster state contains a db node with private IP `10.0.1.5`
- **THEN** the start script environment contains:
  - `TARGET_MYSQL_HOST=10.0.1.5`
  - `TARGET_MYSQL_PORT=3306`
  - `TARGET_MYSQL_USER=` (empty if not declared)
  - `TARGET_MYSQL_DATABASE=bench`

#### Scenario: Multiple endpoint types all injected
- **WHEN** the target kit declares both a `jdbc` endpoint and a `postgresql` endpoint
- **THEN** both `TARGET_JDBC_*` and `TARGET_PG_*` vars are present in the environment

#### Scenario: Target kit directory missing
- **WHEN** a bench kit starts with `TARGET=nonexistent`
- **AND** no `nonexistent/` directory exists in the workspace
- **THEN** no `TARGET_*` vars are injected
- **AND** the start script proceeds (and fails naturally if it references those vars)

### Requirement: runningKits tracks bench instances by instance name
`ClusterState.runningKits` SHALL store the instance name (e.g., `sysbench-clickhouse`)
for bench kit instances, not the base kit name. This allows multiple instances of the
same bench kit to be tracked independently.

#### Scenario: Two bench instances tracked independently
- **WHEN** `sysbench-clickhouse start` and `sysbench-mysql start` both complete
  successfully
- **THEN** `runningKits` contains both `sysbench-clickhouse` and `sysbench-mysql`
- **AND** stopping `sysbench-clickhouse` does not affect `sysbench-mysql`
