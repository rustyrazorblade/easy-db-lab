## MODIFIED Requirements

### Requirement: Status command shows connection endpoints
The status command SHALL display connection endpoints declared in the installed `kit.yaml` with private IPs resolved from cluster state. The installed `kit.yaml` is rendered at install time, so the displayed values (database names, JDBC paths) are the installed values. The status command SHALL NOT print a capability password.

#### Scenario: HTTP endpoint displayed
- **WHEN** an endpoint with `type: http` is declared
- **THEN** output shows `<name>   http   http://<private-ip>:<port>`

#### Scenario: JDBC endpoint displayed
- **WHEN** an endpoint with `type: jdbc` and `scheme: <scheme>` is declared
- **THEN** output shows `<name>   jdbc   jdbc:<scheme>://<private-ip>:<port>/<path>`

#### Scenario: Native endpoint displayed
- **WHEN** an endpoint with `type: native` or `type: cql` is declared
- **THEN** output shows `<name>   native   <private-ip>:<port>`

#### Scenario: MySQL and PostgreSQL endpoints displayed
- **WHEN** an endpoint with `type: mysql` or `type: postgresql` and `database: <db>` is declared
- **THEN** output shows `mysql://<private-ip>:<port>/<db>` or `postgresql://<private-ip>:<port>/<db>`

#### Scenario: Installed database name displayed
- **WHEN** `singlestore` was installed with `--database bench`
- **THEN** `singlestore status` shows the MySQL wire and JDBC endpoints on port 30306 with the database `bench`
- **AND** the output does not contain the root password

#### Scenario: Multiple nodes produce multiple URLs
- **WHEN** an endpoint targets `node-type: app` and there are two app nodes
- **THEN** one URL per node is printed

#### Scenario: No endpoints declared
- **WHEN** `install.yaml` has no `endpoints:` section
- **THEN** status output shows running state only, with no endpoints section
