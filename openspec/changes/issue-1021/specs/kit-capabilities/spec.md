## MODIFIED Requirements

### Requirement: sql capability type

The `sql` capability type SHALL register a `sql` subcommand under the kit group that
executes SQL statements against the kit's JDBC endpoint. The capability SHALL read
connection details from the first endpoint of type `jdbc` in the kit's existing
`endpoints:` declaration, so no separate connection block is needed.

The `sql` capability SHALL accept an optional `user` (JDBC username, defaulting to empty
string), an optional `password` (JDBC password, defaulting to empty string), and an optional
`driver-class` (fully-qualified JDBC driver class to force-load before connecting, required
for drivers that do not auto-register via ServiceLoader).

The `sql` command SHALL send the password as the JDBC `password` property only when it is not
blank. A kit with no password SHALL connect exactly as before. No output of the `sql` command,
success or failure, SHALL contain the password.

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

#### Scenario: Password is sent when set
- **WHEN** a kit declares `capabilities: [{type: sql, user: root, password: X}]`
- **THEN** the JDBC connection properties carry `password=X`
- **AND** the password does not appear in the command output or in an error event

#### Scenario: Blank password is not sent
- **WHEN** a kit's `sql` capability has no `password` or a blank one
- **THEN** the JDBC connection properties carry no `password` property
