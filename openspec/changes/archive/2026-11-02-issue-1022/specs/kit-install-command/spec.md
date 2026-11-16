## ADDED Requirements

### Requirement: Install args with no value are recorded as empty strings
`kit install` SHALL record every declared top-level arg in the kit's `resolved-args.env`.  When the user gives no value and the arg has no default, install SHALL record an empty string for its variable.  Install SHALL fill these empty values after it applies the declared defaults, so an unresolved `${VAR}` default (one that is never handed to picocli) is still applied and not replaced by an empty string.  The recorded empty value SHALL overwrite a variable of the same name that the operator's shell exports when a kit process runs.

#### Scenario: Optional install arg left out
- **WHEN** a kit declares an install arg `--extension` with no default
- **AND** the user runs `kit install <kit>` without `--extension`
- **THEN** `resolved-args.env` holds the arg's variable set to an empty string

#### Scenario: Unresolved default is kept
- **WHEN** a kit declares an install arg with default `${DB_NODE_COUNT}`
- **AND** the template variables do not hold `DB_NODE_COUNT`
- **AND** the user does not give the arg
- **THEN** `resolved-args.env` holds the default text, not an empty string

#### Scenario: Exported shell variable does not leak into a kit process
- **WHEN** the operator's shell exports `EXTENSION=foo`
- **AND** `resolved-args.env` records `EXTENSION` as an empty string
- **THEN** every kit process for that kit sees `EXTENSION` as an empty string

### Requirement: Boolean install args accept an optional value
A `boolean` install arg SHALL take an optional value (picocli arity `0..1`) in the install factory.  A bare `--flag` SHALL mean `true`, and `--flag false` and `--flag=false` SHALL record `false`.

#### Scenario: Boolean install arg turned off
- **WHEN** the user runs `kit install clickhouse --s3-cache-on-write false`
- **THEN** install succeeds and records `false` for the arg's variable

#### Scenario: Boolean install arg given as a bare flag
- **WHEN** the user runs `kit install clickhouse --s3-cache-on-write`
- **THEN** install records `true` for the arg's variable

#### Scenario: Boolean install arg with an equals value
- **WHEN** the user runs `kit install clickhouse --s3-cache-on-write=false`
- **THEN** install records `false` for the arg's variable
