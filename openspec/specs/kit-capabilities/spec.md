# Kit Capabilities

## Purpose

Declarative database capabilities in `kit.yaml` that generate CLI commands automatically,
eliminating per-kit Kotlin service and command classes for standard patterns.
## Requirements

### Requirement: capabilities block in kit.yaml

`kit.yaml` SHALL support an optional `capabilities:` list. Each entry declares a named
capability type and its configuration. Unrecognised capability types MUST be ignored
without raising an error.

#### Scenario: Recognised capability registers a command
- **WHEN** `kit.yaml` contains a `capabilities:` list with a recognised type
- **THEN** the corresponding CLI command is registered under that kit's subcommand group

#### Scenario: No capabilities block leaves behaviour unchanged
- **WHEN** `kit.yaml` contains no `capabilities:` block
- **THEN** kit behaviour is unchanged from today

#### Scenario: Unrecognised capability type is ignored
- **WHEN** `kit.yaml` declares a capability whose type is not recognised
- **THEN** deserialization succeeds and the unrecognised capability produces no command

### Requirement: sql capability type

The `sql` capability type SHALL register a `sql` subcommand under the kit group that
executes SQL statements against the kit's JDBC endpoint. The capability SHALL read
connection details from the kit's existing `endpoints:` declaration — specifically the
first endpoint of type `jdbc` — so no separate connection block is needed.

The `sql` capability SHALL accept an optional `user` (JDBC username, defaulting to empty
string) and an optional `driver-class` (fully-qualified JDBC driver class to force-load
before connecting, required for drivers that do not auto-register via ServiceLoader).

#### Scenario: Inline statement executes against jdbc endpoint
- **WHEN** a kit declares `capabilities: [{type: sql, user: easy-db-lab}]` and has a
  `jdbc` endpoint
- **THEN** `easy-db-lab <kit> sql "<statement>"` executes the query and displays results
  in tabular format

#### Scenario: SQL read from a file
- **WHEN** the user runs `easy-db-lab <kit> sql --file query.sql`
- **THEN** SQL is read from the file and executed

#### Scenario: Trailing semicolon is stripped
- **WHEN** a trailing semicolon is present in the SQL statement
- **THEN** it is stripped before execution

#### Scenario: Successful query emits structured output
- **WHEN** the query succeeds
- **THEN** column names and row values are emitted as a structured output event

#### Scenario: Failed query emits structured error
- **WHEN** the query fails
- **THEN** the error message is emitted as a structured error event

#### Scenario: No SQL provided prints usage
- **WHEN** no SQL is provided (neither inline nor `--file`)
- **THEN** usage text is printed and the service is not called

#### Scenario: driver-class is force-loaded
- **WHEN** a `driver-class` is specified
- **THEN** that class is force-loaded before the JDBC connection is attempted

#### Scenario: No matching nodes emits an error
- **WHEN** no nodes of the endpoint's node type exist in cluster state
- **THEN** an error is emitted before any connection is attempted

### Requirement: Capabilities and @KitCommand commands are additive

Capability-generated commands and `@KitCommand`-annotated Kotlin commands SHALL both
appear under the same kit subcommand group. They MUST NOT conflict.

#### Scenario: Capability and annotated commands coexist
- **WHEN** a kit declares a `sql` capability and also has `@KitCommand`-annotated classes
- **THEN** both appear as subcommands under the kit group

### Requirement: Existing per-kit SQL commands are removed

The `presto sql` and `clickhouse sql` commands SHALL be implemented via the `sql`
capability. The dedicated per-kit Kotlin service and command classes MUST be deleted.

#### Scenario: presto sql uses the generic capability
- **WHEN** the user runs `easy-db-lab presto sql "SELECT 1"`
- **THEN** the query executes via the generic sql capability, not a presto-specific class

#### Scenario: clickhouse sql uses the generic capability
- **WHEN** the user runs `easy-db-lab clickhouse sql "SELECT 1"`
- **THEN** the query executes via the generic sql capability, not a clickhouse-specific class

### Requirement: kit-ref is a valid arg type in the capabilities arg system
A new `kit-ref` type SHALL be a valid arg type in the `capabilities` arg system
(see the `capabilities block in kit.yaml` requirement), alongside the existing `string`, `int`, `boolean`, and `float` types.
It is usable in the `args:` block of any kit, with the same `flag`, `variable`,
`description`, and `required` fields.

#### Scenario: kit-ref arg parsed alongside other arg types
- **WHEN** a `kit.yaml` declares args including one with `type: kit-ref`
- **THEN** all args including the `kit-ref` entry deserialize without error

#### Scenario: kit-ref arg appears in kit info output
- **WHEN** the user runs `easy-db-lab kit info <bench-kit-name>`
- **THEN** the `kit-ref` arg is listed with its flag, variable, and description

### Requirement: sql capability reaches the endpoint through the SOCKS tunnel when active

The `sql` capability SHALL reach the kit's JDBC endpoint through the SOCKS tunnel when a SOCKS proxy port is published, and directly otherwise. The `sql` subcommand SHALL declare its dependency on the proxy so the tunnel is established and verified before a query is attempted; a query SHALL NOT be attempted against a broken transport.

This routing SHALL be transport-only and driver-agnostic: it MUST work for any kit's JDBC endpoint regardless of driver (raw-TCP such as postgresql/mysql, or HTTP-based such as trino/presto/clickhouse), MUST NOT introduce per-kit or per-driver branching in the SQL execution path, and MUST NOT require any SOCKS or proxy configuration in `kit.yaml`.

#### Scenario: sql over a SOCKS-only cluster
- **GIVEN** a running cluster with Tailscale disabled and the SOCKS5 proxy up
- **WHEN** the user runs `easy-db-lab postgres sql "SELECT 1"`
- **THEN** the query SHALL execute through the tunnel and return its result

#### Scenario: sql over a Tailscale cluster is unchanged
- **GIVEN** a running cluster with Tailscale enabled (no SOCKS proxy port published)
- **WHEN** the user runs `easy-db-lab <kit> sql "<statement>"`
- **THEN** the query SHALL connect directly to the endpoint's private IP with behavior identical to before this change

#### Scenario: sql establishes the tunnel before connecting
- **WHEN** a `sql` subcommand is invoked on a provisioned, Tailscale-disabled cluster
- **THEN** the SOCKS proxy SHALL be established and verified before the JDBC connection is attempted
- **AND** if the proxy cannot be established the command SHALL fail rather than attempting a direct connection to an unreachable private IP

#### Scenario: Routing is kit- and driver-agnostic
- **WHEN** any kit with a JDBC endpoint declares a `sql` capability
- **THEN** SOCKS routing SHALL apply uniformly without kit-specific or driver-specific code in the SQL execution path
- **AND** without any SOCKS or proxy settings appearing in the kit's `kit.yaml`

