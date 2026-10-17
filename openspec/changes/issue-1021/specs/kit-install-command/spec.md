## ADDED Requirements

### Requirement: Installed kit.yaml is rendered at install time

`kit install` SHALL render the kit's `kit.yaml` through the same `__VAR__` substitution that renders `.template` files (`TemplateService.renderKitTemplate`), with the cluster variables and the resolved arg values, before it writes the installed `kit.yaml`. The existing port patch SHALL still apply. Every later reader of the installed `kit.yaml` (start, stop, status, the `sql` command, `TARGET_*` injection) SHALL see the resolved values. A `kit.yaml` with no placeholders SHALL install unchanged.

#### Scenario: Placeholder resolved from an arg
- **WHEN** a kit's `kit.yaml` holds `password: "__SINGLESTORE_PASSWORD__"` and an endpoint `database: "__SINGLESTORE_DATABASE__"`
- **AND** the user runs `kit install <kit> --password X --database bench`
- **THEN** the installed `kit.yaml` holds `password: "X"` and `database: "bench"`

#### Scenario: Placeholder resolved from a default
- **WHEN** the user installs the same kit with no `--password` and no `--database`
- **THEN** the installed `kit.yaml` holds the arg defaults

#### Scenario: Kit without placeholders is unchanged
- **WHEN** a kit whose `kit.yaml` holds no `__VAR__` placeholder is installed
- **THEN** the installed `kit.yaml` equals today's output

### Requirement: kit info renders placeholders with arg defaults

`kit info <kit>` SHALL render the packaged `kit.yaml` placeholders with the arg defaults before it shows endpoints, so an endpoint shows a real database name rather than a `__VAR__` placeholder. `kit info` SHALL NOT print a capability password.

#### Scenario: kit info shows the default database
- **WHEN** `kit info singlestore` runs
- **THEN** each endpoint shows the database `test`
- **AND** no `__VAR__` placeholder appears in the output

### Requirement: Install arg pattern validation

A `KitArgSpec` SHALL accept an optional `pattern`, a regular expression that the whole value must match. `kit install` SHALL check every arg that has a pattern before it renders or writes anything. When a value does not match, the install SHALL fail with a message that names the arg's flag. The message SHALL NOT contain the value. An arg without a pattern SHALL accept any value, as today.

#### Scenario: Value matches the pattern
- **WHEN** an arg declares `pattern: "[A-Za-z0-9_]+"` and the user passes `bench_1`
- **THEN** the install proceeds

#### Scenario: Value does not match the pattern
- **WHEN** an arg declares a pattern and the user passes a value that does not match
- **THEN** the install fails with a message that names the arg's flag
- **AND** nothing is written to the workspace
- **AND** the message does not contain the value

#### Scenario: Arg without a pattern
- **WHEN** an arg declares no pattern
- **THEN** any value is accepted
