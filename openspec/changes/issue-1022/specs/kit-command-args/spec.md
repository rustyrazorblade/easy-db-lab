## MODIFIED Requirements

### Requirement: Kit command args declaration
A kit SHALL be able to declare named CLI options scoped to individual commands via a `commands:` map in `kit.yaml`.  Each entry maps a command name to a description and list of arg specs using the same `KitArgSpec` structure already used for install-time args.

Every declared arg SHALL reach the script's environment.  When the user does not give an arg that has no default, the runner factory SHALL record an empty string for its variable, never the string `null`, and the declared empty value SHALL overwrite a variable of the same name that the operator's shell exports.  A script tests such an arg with `[ -n "${VAR}" ]`.

A `boolean` arg SHALL take an optional value (picocli arity `0..1`).  A bare `--flag` SHALL mean `true`, and `--flag true`, `--flag=true`, `--flag false` and `--flag=false` SHALL set the stated value.  The help text shows the option as `--flag[=<value>]`.

#### Scenario: Command with args appears in help
- **WHEN** a user runs `easy-db-lab <kit> <command> --help`
- **THEN** the declared args for that command appear as named options with their descriptions and defaults

#### Scenario: Arg value passed to script as env var
- **WHEN** a user runs `easy-db-lab <kit> <command> --some-flag value`
- **THEN** the corresponding variable is set in the script's environment with the provided value

#### Scenario: Default value used when flag omitted
- **WHEN** a user runs `easy-db-lab <kit> <command>` without providing an optional arg
- **THEN** the declared default value is injected into the script's environment

#### Scenario: Unset arg with no default reaches the script as an empty string
- **WHEN** a command declares `--queries` with no default
- **AND** the user runs the command without `--queries`
- **THEN** the script's environment holds `QUERIES` set to an empty string
- **AND** it does not hold the string `null`

#### Scenario: Unset arg does not take an exported shell variable
- **WHEN** the operator's shell exports `SETTINGS=host=x`
- **AND** a command declares `--settings` (variable `SETTINGS`) with no default
- **AND** the user runs the command without `--settings`
- **THEN** the script's environment holds `SETTINGS` set to an empty string

#### Scenario: Boolean arg given as a bare flag
- **WHEN** a command declares a `boolean` arg `--randomize` with default `true`
- **AND** the user runs the command with a bare `--randomize`
- **THEN** the script's environment holds `RANDOMIZE=true`

#### Scenario: Boolean arg turned off with a value
- **WHEN** a command declares a `boolean` arg `--randomize` with default `true`
- **AND** the user runs the command with `--randomize=false`, or with `--randomize false`
- **THEN** the script's environment holds `RANDOMIZE=false`

#### Scenario: Runtime arg overrides install-time resolved arg
- **WHEN** a command declares an arg whose variable name matches one in `resolved-args.env`
- **THEN** the CLI-provided value takes precedence over the installed value for that invocation
