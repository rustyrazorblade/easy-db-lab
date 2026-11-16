## ADDED Requirements

### Requirement: Status lists the endpoints of each running kit

The `status` command and the server's status (REST `GET /status` and the MCP status) SHALL list, for each installed kit recorded as running, the endpoints that the kit's `kit.yaml` declares.  Each endpoint SHALL be its NodePort on each host of the endpoint's node type, at the host's private IP.  A stopped kit SHALL show no endpoints, even when other pods of the kit still run.  No kit SHALL have a fixed section of its own.  In the server's status, `accessInfo.kits` SHALL hold these endpoints, one entry per running kit, and SHALL replace the fixed `clickhouse` entry.  If a running kit's `kit.yaml` cannot be read, `status` SHALL print `(endpoints unavailable: cannot read kit.yaml)` under the kit and SHALL render the later sections, and the kit's `accessInfo.kits` entry SHALL carry the same reason in `endpointsUnavailable` with an empty endpoint list.

#### Scenario: A running kit shows its NodePort endpoints

- **WHEN** the clickhouse kit is running and the user runs `status`
- **THEN** the `=== KITS ===` section marks clickhouse as running
- **AND** under it, `status` prints the NodePort endpoints that its `kit.yaml` declares, on each host of the endpoint's node type

#### Scenario: A stopped kit shows no endpoints

- **WHEN** the clickhouse kit is installed but not running, and its Keeper pods still run
- **THEN** `status` lists clickhouse as not running, with no endpoints
- **AND** `status` prints no ClickHouse section

#### Scenario: The server's status lists running kits

- **WHEN** a client sends `GET /status` while one kit is running and another is stopped
- **THEN** `accessInfo.kits` holds one entry, for the running kit, with its name and each endpoint's name, type and address
- **AND** `accessInfo` has no `clickhouse` entry

#### Scenario: A running kit's kit.yaml cannot be read

- **WHEN** a kit is recorded as running and its `kit.yaml` cannot be read
- **THEN** `status` prints `(endpoints unavailable: cannot read kit.yaml)` under the kit
- **AND** `status` renders the sections after the kits
- **AND** the kit's `accessInfo.kits` entry has an empty endpoint list and `endpointsUnavailable` set to `cannot read kit.yaml`
