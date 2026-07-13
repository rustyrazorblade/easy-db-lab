## ADDED Requirements

### Requirement: presto sql command
The system SHALL provide a `presto sql` command that executes SQL against a running
Presto cluster, giving the same in-tool "run a quick query" UX as `cassandra cql`.
The command MUST accept SQL either as an inline positional argument (`presto sql <query>`)
or from a file (`presto sql --file <path>`).

#### Scenario: Inline query returns tabular results
- **WHEN** the user runs `presto sql "SELECT count(*) FROM cassandra.ks.tbl"`
- **THEN** the query is submitted to the Presto cluster and the results are displayed in
  tabular format with column headers

#### Scenario: Query read from a file
- **WHEN** the user runs `presto sql --file query.sql`
- **THEN** the SQL is read from the file and executed against the Presto cluster

#### Scenario: No SQL provided prints usage
- **WHEN** the user runs `presto sql` with neither an inline query nor `--file`
- **THEN** a usage hint is emitted and no query is submitted

### Requirement: presto sql visibility is gated on the presto kit
The `presto sql` command SHALL only be visible and executable when the presto kit is
installed. Registration MUST NOT interfere with the kit's own `presto start` and
`presto stop` lifecycle subcommands.

#### Scenario: sql appears only when presto is installed
- **WHEN** the presto kit is installed
- **THEN** `presto sql` is registered under the `presto` command group alongside
  `presto start` and `presto stop`

#### Scenario: Lifecycle subcommands are preserved
- **WHEN** the `presto sql` command is registered under the presto group
- **THEN** `presto start` and `presto stop` continue to work unchanged

#### Scenario: kit info lists the sql command
- **WHEN** the user runs `kit info presto`
- **THEN** the Commands section lists `presto sql` with the description declared on the
  command

### Requirement: Presto HTTP API execution
The `presto sql` command SHALL submit statements to the Presto HTTP API by POSTing the
SQL to `/v1/statement` on the app node's private IP (port 8080) and following the
`nextUri` links until pagination is exhausted, accumulating all columns and rows. The
request MUST route through the SOCKS proxy when Tailscale direct connectivity is not
active, reusing the shared HTTP client factory.

#### Scenario: Paginated results are fully accumulated
- **WHEN** a query returns results across multiple `nextUri` pages
- **THEN** all pages are fetched and every row is included in the displayed output

#### Scenario: Query error emits a structured error
- **WHEN** the Presto API returns an error for the submitted statement
- **THEN** the error message is emitted as a structured error event and the command fails

#### Scenario: No app nodes emits an error before any request
- **WHEN** no app nodes exist in cluster state
- **THEN** an error is emitted before any HTTP request is made to the Presto API
