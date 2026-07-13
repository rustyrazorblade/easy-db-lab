# Kit Capabilities

## Purpose

Declarative database capabilities in `kit.yaml` that generate CLI commands automatically,
eliminating per-kit Kotlin service and command classes for standard patterns.

## Requirements

### REQ-KCAP-001: capabilities block in kit.yaml

`kit.yaml` SHALL support an optional `capabilities:` list. Each entry declares a named
capability type and its configuration. Unrecognised capability types are ignored.

#### Scenario: Recognised capability registers a command
- **WHEN** `kit.yaml` contains a `capabilities:` list with a recognised type
- **THEN** the corresponding CLI command is registered under that kit's subcommand group

#### Scenario: No capabilities block leaves behaviour unchanged
- **WHEN** `kit.yaml` contains no `capabilities:` block
- **THEN** kit behaviour is unchanged from today

### REQ-KCAP-002: sql capability type

The `sql` capability type SHALL register a `sql` subcommand under the kit group that
executes SQL statements against the kit's JDBC endpoint.

The `sql` capability SHALL read connection details from the kit's existing `endpoints:`
declaration — specifically the first endpoint of type `jdbc`. No separate connection
block is needed.

The `sql` capability SHALL accept:
- `user` (string, optional) — JDBC username; defaults to empty string
- `driver-class` (string, optional) — fully-qualified JDBC driver class to force-load
  before connecting; required for drivers that do not auto-register via ServiceLoader

#### Scenario: Inline statement executes against jdbc endpoint
- **WHEN** a kit declares `capabilities: [{type: sql, user: easy-db-lab}]` and has a
  `jdbc` endpoint
- **THEN** `easy-db-lab <kit> sql "<statement>"` executes the query
  and displays results in tabular format

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

### REQ-KCAP-003: Capabilities and @KitCommand commands are additive

Capability-generated commands and `@KitCommand`-annotated Kotlin commands SHALL both
appear under the same kit subcommand group. They do not conflict.

#### Scenario: Capability and annotated commands coexist
- **WHEN** a kit declares a `sql` capability and also has `@KitCommand`-annotated
  classes
- **THEN** both appear as subcommands under the kit group

### REQ-KCAP-004: Existing per-kit SQL commands are removed

The `presto sql` and `clickhouse sql` commands SHALL be implemented via the `sql`
capability. The dedicated per-kit Kotlin service and command classes are deleted.

#### Scenario: presto sql uses the generic capability
- **WHEN** the user runs `easy-db-lab presto sql "SELECT 1"`
- **THEN** the query executes via the generic sql capability, not a presto-specific class

#### Scenario: clickhouse sql uses the generic capability
- **WHEN** the user runs `easy-db-lab clickhouse sql "SELECT 1"`
- **THEN** the query executes via the generic sql capability, not a clickhouse-specific class
