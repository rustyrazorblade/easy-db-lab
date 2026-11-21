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

### Requirement: Kit endpoints resolve to the nodes that serve them

Any kit endpoint MAY declare a `pod-selector`: a label selector, with `${KIT_NAME}` filled
with the kit instance name, for the one pod that serves the endpoint's port as a `hostPort`.
Its serving node SHALL be the host IP of a pod that matches the selector in the kit's runtime
namespace (`default` without a runtime), is running, has every container ready, is not being
deleted, and runs on a node of the endpoint's node type; with several such pods, the newest (the
smallest age, then the first by name), so a client follows a rollout. No consumer SHALL fall back
to another node of the endpoint's node type. An endpoint no pod serves SHALL carry the reason: no
node of the type, the pod query failed (with its cause), no running and ready pod matches (naming
the selector and the namespace), or the matching pods run only on nodes of another type (naming
their IPs). When the cluster API cannot be reached (the SOCKS proxy failed), no pod query SHALL be
made, and the reason SHALL be that failure.

An endpoint with no `pod-selector` is served on every node of its node type (a NodePort). The
resolution SHALL be kit-generic (no kit name or driver appears in it), and every consumer SHALL
use it (`services/KitEndpointHost`):

- `sql` SHALL connect to the serving node, or to the first node of the type for a NodePort.
  When no node serves the endpoint, `sql` SHALL emit `Sql.EndpointNotServed` (kit, endpoint,
  reason; it says no statement ran), attempt no connection, and exit non-zero with a message
  other than a failed statement's.
- A bench kit's `TARGET_*` variables (spec `kit-cross-targeting`) SHALL name the serving node,
  or the first node of the type for a NodePort. A `pod-selector` endpoint with no serving pod
  SHALL fail the phase before its steps run.
- `<kit> status` and `status` / the server's status (spec `server`) SHALL list a `pod-selector`
  endpoint only at its serving node. When no pod serves it, `<kit> status` SHALL print one
  `<endpoint>: not served: <reason>` line for it, and `status` SHALL print the reason under the
  kit and carry it in `endpointsUnavailable`.
- `<kit> start` SHALL emit `Kit.EndpointsAvailable` with the served endpoints and, in
  `unserved`, each endpoint no pod serves with its reason; it SHALL emit the event when every
  endpoint is unserved.
- `kit info` reads no cluster API, so it SHALL list a `pod-selector` endpoint by its bare port
  with a pointer to `<instance> status`, never at a node address.

The Presto and Trino UI and `JDBC` endpoints, both served by the coordinator's hostPort 8080,
SHALL declare the coordinator pod as their `pod-selector`.

#### Scenario: Coordinator on a node other than the first
- **WHEN** a kit's `jdbc` endpoint declares a `pod-selector`
- **AND** the matching ready pod runs on an app node other than the first one in cluster state
- **THEN** `sql` connects to that pod's node, through the SOCKS bridge when one is in use

#### Scenario: No pod serves the endpoint
- **WHEN** a kit's `jdbc` endpoint declares a `pod-selector`
- **AND** no running, ready, non-terminating pod matches it
- **THEN** `sql` emits an error naming the endpoint, the selector and the namespace, and no connection is attempted

#### Scenario: Pod query fails
- **WHEN** listing the pods that match the selector fails
- **THEN** `sql` emits an error that carries the cause, and no connection is attempted

#### Scenario: The serving pod runs on a node of another type
- **WHEN** the only running, ready pod matching an `app` endpoint's selector runs on a db node
- **THEN** the endpoint is not served, and the reason names the pod's node IP and the node type

#### Scenario: Kit status shows an endpoint no pod serves
- **WHEN** a kit's coordinator is scaled to 0 and the user runs `<kit> status`
- **THEN** it prints one line per `pod-selector` endpoint: the endpoint name, `not served` and the reason

#### Scenario: Status with the proxy down
- **WHEN** the SOCKS proxy cannot be established and the user runs `status`
- **THEN** no pod is listed for a running kit's `pod-selector` endpoints
- **AND** those endpoints are shown unavailable with the first line of the proxy failure as the reason, which names the tunnel log; the ssh transcript is not repeated per kit
- **AND** the kit's NodePort endpoints are still listed

#### Scenario: A bench kit targets the coordinator's node
- **WHEN** a bench kit runs with `TARGET=presto`
- **AND** the presto coordinator runs on an app node other than the first one in cluster state
- **THEN** `TARGET_JDBC_URL` names the coordinator's node

#### Scenario: A bench kit's target serves no endpoint
- **WHEN** a bench kit runs a phase other than `stop` or `uninstall` with `--target trino`
- **AND** no pod serves a trino `pod-selector` endpoint
- **THEN** the phase fails before its script runs, with `Kit.TargetNotServed` naming the target kit, the endpoint and the reason (the selector and the namespace)

#### Scenario: Status shows the coordinator's node only
- **WHEN** the presto kit is running with its coordinator on the second app node
- **THEN** `presto status` and `status` list the UI and `JDBC` endpoints at that node, and at no other app node

#### Scenario: kit info does not guess the node
- **WHEN** the user runs `kit info presto` in a cluster workspace
- **THEN** the `JDBC` endpoint is listed by its port with a pointer to `presto status`, and no app node address

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

