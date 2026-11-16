## ADDED Requirements

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
