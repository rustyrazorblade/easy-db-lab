## MODIFIED Requirements

### Requirement: Kit command args declaration
A kit SHALL be able to declare named CLI options scoped to individual commands via a `commands:` map in `kit.yaml`. Each entry maps a command name to a description and list of arg specs using the same `KitArgSpec` structure already used for install-time args. The install-arg and command-arg options SHALL be built by one shared arg-option builder. An optional arg that the user does not give and that has no default SHALL NOT be recorded, so its variable is absent from the script's environment rather than set to a placeholder string such as `null`. A boolean arg that the user does not give SHALL be `false`.

#### Scenario: Command with args appears in help
- **WHEN** a user runs `easy-db-lab <kit> <command> --help`
- **THEN** the declared args for that command appear as named options with their descriptions and defaults

#### Scenario: Arg value passed to script as env var
- **WHEN** a user runs `easy-db-lab <kit> <command> --some-flag value`
- **THEN** the corresponding variable is set in the script's environment with the provided value

#### Scenario: Default value used when flag omitted
- **WHEN** a user runs `easy-db-lab <kit> <command>` without providing an optional arg
- **THEN** the declared default value is injected into the script's environment

#### Scenario: Unset optional arg with no default is not recorded
- **WHEN** a command declares an optional string arg with no default and the user runs the command without it
- **THEN** the arg's variable is not set in the script's environment
- **AND** no step sees the string `null` for it

#### Scenario: Unset boolean arg is false
- **WHEN** a command declares a boolean arg and the user runs the command without it
- **THEN** the arg's variable is `false` in the script's environment

#### Scenario: Given boolean arg is true
- **WHEN** a command declares a boolean arg and the user passes its flag
- **THEN** the arg's variable is `true` in the script's environment

#### Scenario: Install args and command args behave the same
- **WHEN** an install arg and a command arg are declared with the same type, default and required settings
- **THEN** both are parsed, defaulted and recorded the same way

#### Scenario: Runtime arg overrides install-time resolved arg
- **WHEN** a command declares an arg whose variable name matches one in `resolved-args.env`
- **THEN** the CLI-provided value takes precedence over the installed value for that invocation

## ADDED Requirements

### Requirement: Repeatable command args
A command arg of type `string` SHALL accept `repeatable: true`. A repeatable arg SHALL accept its flag any number of times, and its variable SHALL hold every given value in order, joined with a newline, so a script reads them with `while IFS= read -r`. A repeatable arg that is not given SHALL NOT be recorded. `repeatable: true` on a non-string arg, or on a top-level install arg (whose values are stored one `KEY=VALUE` per line in `resolved-args.env`), SHALL be rejected when `kit.yaml` loads, with an error that names the arg.

#### Scenario: Repeated flag accumulates
- **WHEN** a command declares `--env` as a repeatable string arg and the user runs it with `--env A=1 --env B=2`
- **THEN** the arg's variable holds `A=1` and `B=2` on two lines, in that order

#### Scenario: Single value of a repeatable arg
- **WHEN** the user gives a repeatable arg once
- **THEN** the arg's variable holds that one value

#### Scenario: Repeatable top-level install arg is rejected
- **WHEN** a `kit.yaml` declares `repeatable: true` on a top-level `args:` entry
- **THEN** loading the kit fails with an error that names the arg

#### Scenario: Repeatable non-string arg is rejected
- **WHEN** a `kit.yaml` declares `repeatable: true` on a command arg of type `int` or `boolean`
- **THEN** loading the kit fails with an error that names the arg

### Requirement: kit info lists command args
`kit info <kit>` SHALL list the args of each command declared under `commands:` (including lifecycle phases such as `start`), grouped under the command name, with each arg's flag, variable, description and default, in addition to the top-level install args.

#### Scenario: Start args listed
- **WHEN** a kit declares args under `commands: start: args:` and the user runs `easy-db-lab kit info <kit>`
- **THEN** the output lists each `start` arg under `start` with its flag, variable, description and default

#### Scenario: Every ferrosa start option listed
- **WHEN** the user runs `easy-db-lab kit info ferrosa`
- **THEN** it lists every `start` option: `--version`, `--image`, `--storage`, `--log-level`, `--heap-profile`, `--heap-sample` and `--env`

#### Scenario: Repeatable arg is marked
- **WHEN** a command declares a repeatable arg and the user runs `easy-db-lab kit info <kit>`
- **THEN** the arg is shown as repeatable
