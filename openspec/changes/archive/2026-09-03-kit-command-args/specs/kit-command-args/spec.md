## ADDED Requirements

### Requirement: Kit command args declaration
A kit SHALL be able to declare named CLI options scoped to individual commands via a `commands:` map in `kit.yaml`. Each entry maps a command name to a description and list of arg specs using the same `KitArgSpec` structure already used for install-time args.

#### Scenario: Command with args appears in help
- **WHEN** a user runs `easy-db-lab <kit> <command> --help`
- **THEN** the declared args for that command appear as named options with their descriptions and defaults

#### Scenario: Arg value passed to script as env var
- **WHEN** a user runs `easy-db-lab <kit> <command> --some-flag value`
- **THEN** the corresponding variable is set in the script's environment with the provided value

#### Scenario: Default value used when flag omitted
- **WHEN** a user runs `easy-db-lab <kit> <command>` without providing an optional arg
- **THEN** the declared default value is injected into the script's environment

#### Scenario: Runtime arg overrides install-time resolved arg
- **WHEN** a command declares an arg whose variable name matches one in `resolved-args.env`
- **THEN** the CLI-provided value takes precedence over the installed value for that invocation

### Requirement: Runtime args do not override cluster state vars
The system SHALL ensure that cluster state variables (e.g. `PRIVATE_IP`, `DB_NODE_COUNT`, `KUBECONFIG`) always take precedence over kit command args, preventing kit authors from accidentally shadowing infrastructure variables.

#### Scenario: Cluster state var not shadowed by kit arg
- **WHEN** a kit declares a command arg whose variable name matches a cluster state variable
- **THEN** the cluster state value is used, not the CLI-provided value

### Requirement: Commands declared for lifecycle phases
A kit SHALL be able to declare args on lifecycle phase commands (`start`, `stop`, `uninstall`, `backup`, `restore`) using the same `commands:` map, not only on script-based workload commands.

#### Scenario: Start command with declared arg
- **WHEN** a kit declares args under `commands: start:` and a user passes those flags to `<kit> start`
- **THEN** the values are injected into the environment for all start phase steps

### Requirement: Required arg enforcement
If a command arg is declared with `required: true`, the CLI SHALL reject the invocation with an error if the flag is not provided.

#### Scenario: Missing required arg fails fast
- **WHEN** a user runs a command that has a required arg without providing it
- **THEN** PicoCLI prints a usage error and exits non-zero before the script runs
