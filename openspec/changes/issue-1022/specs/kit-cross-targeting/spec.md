## MODIFIED Requirements

### Requirement: kit-ref arg type for cross-kit targeting
A kit arg with `type: kit-ref` SHALL declare a dependency on another installed kit.  The `variable` field names the env var that stores the target kit name (conventionally `TARGET`).  Two optional fields state what the target must provide, and `kit install` SHALL enforce both before it writes the kit:

- `capability`: a capability type the target kit must declare.  When the target does not declare it, install SHALL fail with `Target kit '<target>' does not have the '<capability>' capability required by <kit>`.
- `endpoints`: a list of endpoint types (for example `[native, http]`) that the target kit must declare.  Each entry SHALL parse to `KitEndpoint.EndpointType`, so an unknown type fails when `kit.yaml` is parsed.  When the target lacks one or more listed types, install SHALL fail with one message that names every missing type: `Target kit '<target>' does not have the '<type>' endpoint required by <kit>` for one type, and `Target kit '<target>' does not have the '<type1>' and '<type2>' endpoints required by <kit>` for more than one.

The two fields are independent.  When neither is set, install SHALL perform no target check beyond the arg itself.  When the target kit is not installed, install SHALL fail with the existing "is not installed" message.  These checks SHALL live in a pure `KitRefTargetValidator` and SHALL run before any file is written to the workspace.

#### Scenario: kit-ref arg declared in kit.yaml
- **WHEN** a `kit.yaml` contains an arg with `type: kit-ref`
- **THEN** the arg is parsed without error and `KitArgSpec.type` is `KIT_REF`

#### Scenario: Unknown arg types are ignored gracefully
- **WHEN** a `kit.yaml` contains an arg with an unrecognised type
- **THEN** deserialization does not throw; the arg defaults to `STRING` type

#### Scenario: endpoints list parsed from kit.yaml
- **WHEN** a `kit.yaml` kit-ref arg declares `endpoints: [native, http]`
- **THEN** `KitArgSpec.endpoints` equals `[NATIVE, HTTP]`

#### Scenario: Unknown endpoint type in the endpoints list fails at parse
- **WHEN** a `kit.yaml` kit-ref arg declares `endpoints: [nativ]`
- **THEN** parsing the `kit.yaml` fails

#### Scenario: Target missing a required capability
- **WHEN** a kit-ref arg declares `capability: sql`
- **AND** `--target` names an installed kit that declares no `sql` capability
- **THEN** install fails with `Target kit '<target>' does not have the 'sql' capability required by <kit>`
- **AND** nothing is written to the workspace

#### Scenario: Target missing one required endpoint type
- **WHEN** `kit install clickhouse-benchmark --target tidb` runs
- **AND** the installed `tidb` kit declares an `http` endpoint and no `native` endpoint
- **THEN** install fails with `Target kit 'tidb' does not have the 'native' endpoint required by clickhouse-benchmark`
- **AND** nothing is written to the workspace

#### Scenario: Target missing every required endpoint type
- **WHEN** a kit-ref arg declares `endpoints: [native, http]`
- **AND** `--target` names an installed kit that declares neither a `native` nor an `http` endpoint
- **THEN** install fails with one message, `Target kit '<target>' does not have the 'native' and 'http' endpoints required by <kit>`

#### Scenario: Target not installed
- **WHEN** a kit-ref arg declares `endpoints: [native, http]`
- **AND** `--target` names a kit that is not installed in the workspace
- **THEN** install fails with the existing "is not installed" message
- **AND** nothing is written to the workspace

#### Scenario: Target has every required endpoint type
- **WHEN** `kit install clickhouse-benchmark --target clickhouse` runs
- **AND** the installed `clickhouse` kit declares a `native` and an `http` endpoint
- **THEN** the endpoint check passes and install continues

#### Scenario: No capability and no endpoints means no target check
- **WHEN** a kit-ref arg declares neither `capability` nor `endpoints`
- **THEN** install does not load or check the target kit's `kit.yaml`

### Requirement: TARGET_* env vars injected at start time
When a bench kit with a `kit-ref` arg runs any phase, the framework SHALL read the target kit's `kit.yaml` from its directory in the workspace and inject `TARGET_*` environment variables derived from each declared endpoint into the script's environment.  `KitEndpoint.toTargetVars` SHALL handle every `KitEndpoint.EndpointType` in an exhaustive `when`, so a new endpoint type does not compile until it states its variables.  The `native` type SHALL yield `TARGET_NATIVE_HOST`, `TARGET_NATIVE_PORT`, `TARGET_NATIVE_USER` and `TARGET_NATIVE_DATABASE`.  The `https` and `cql` types SHALL yield no variables.

#### Scenario: JDBC endpoint produces TARGET_JDBC_* vars
- **WHEN** a bench kit starts with `TARGET=clickhouse`
- **AND** `clickhouse/kit.yaml` declares a `jdbc` endpoint on port 30123 with scheme `clickhouse` and path `/default?compress=0`
- **AND** cluster state contains a db node with private IP `10.0.1.5`
- **THEN** the start script environment contains:
  - `TARGET_JDBC_URL=jdbc:clickhouse://10.0.1.5:30123/default?compress=0`
  - `TARGET_JDBC_USER=default` (from the target kit's `sql` capability `user` field)
  - `TARGET_JDBC_DRIVER=<driver-class>` (from the `sql` capability `driver-class` field, or empty string if absent)

#### Scenario: PostgreSQL wire endpoint produces TARGET_PG_* vars
- **WHEN** a bench kit starts with `TARGET=somedb`
- **AND** `somedb/kit.yaml` declares a `postgresql` endpoint on port 5432 with `database: mydb` and `user: bench`
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

#### Scenario: Native endpoint produces TARGET_NATIVE_* vars
- **WHEN** a bench kit runs with `TARGET=clickhouse`
- **AND** `clickhouse/kit.yaml` declares a `native` endpoint on port 30900 with `node-type: db` and `database: default`, and a `sql` capability with `user: default`
- **AND** cluster state lists db nodes whose first private IP is `10.0.1.5`
- **THEN** the script environment contains:
  - `TARGET_NATIVE_HOST=10.0.1.5`
  - `TARGET_NATIVE_PORT=30900`
  - `TARGET_NATIVE_USER=default`
  - `TARGET_NATIVE_DATABASE=default`

#### Scenario: Native endpoint on a target with no sql capability
- **WHEN** the target kit declares a `native` endpoint and no `sql` capability
- **THEN** `TARGET_NATIVE_USER` is an empty string

#### Scenario: HTTPS and CQL endpoints produce no vars
- **WHEN** the target kit declares only an `https` endpoint and a `cql` endpoint
- **THEN** no `TARGET_*` variable is injected for those endpoints

#### Scenario: Multiple endpoint types all injected
- **WHEN** the target kit declares both a `jdbc` endpoint and a `postgresql` endpoint
- **THEN** both `TARGET_JDBC_*` and `TARGET_PG_*` vars are present in the environment

#### Scenario: Target kit directory missing
- **WHEN** a bench kit starts with `TARGET=nonexistent`
- **AND** no `nonexistent/` directory exists in the workspace
- **THEN** no `TARGET_*` vars are injected
- **AND** the start script proceeds (and fails naturally if it references those vars)
