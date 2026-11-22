## ADDED Requirements

### Requirement: Kits name in-process variable contributors
A kit SHALL be able to list Kotlin contributors in `kit.yaml` as `contributors: [<name>...]`. Each name SHALL match a `KitVariableContributor` registered in Koin. Before the steps of a kit's `start`, and before the steps of every command the kit declares under `commands:` (such as `cassandra-pods upgrade`), the kit runner SHALL call each contributor in list order. Contributors SHALL NOT run for `stop` or `uninstall`. A call takes the kit's resolved args, the cluster state and the kit directory, and returns a `KitContribution`, which holds:

- variables, added to every step's environment;
- files, written into the kit directory before the first step.

Cluster state variables SHALL still take precedence over contributed variables. A contributor that fails SHALL fail the command before any step runs, with the contributor's message. The runner SHALL NOT know any contributor's domain. A name with no registered contributor SHALL fail when `kit.yaml` is parsed, naming the contributor.

#### Scenario: Contributed variable reaches the steps
- **WHEN** a kit lists a contributor that returns the variable `EDL_TARBALL_SHA256=<hex>`, and the kit is started
- **THEN** every `start` step's environment holds `EDL_TARBALL_SHA256=<hex>`

#### Scenario: Contributed file written before the first step
- **WHEN** a contributor returns the file `resolved-artifact.env`
- **THEN** that file exists in the kit directory when the first `start` step runs

#### Scenario: Contributor failure stops the start
- **WHEN** a contributor throws because the version cannot be resolved
- **THEN** the command exits non-zero with the contributor's message, no step runs, and no Kubernetes resource is created

#### Scenario: Unknown contributor name
- **WHEN** `kit.yaml` lists `contributors: [no-such-thing]`
- **THEN** parsing the kit fails with a message naming `no-such-thing`

#### Scenario: Cluster state not shadowed
- **WHEN** a contributor returns `DB_NODE_COUNT=99`
- **THEN** the steps see the cluster's real `DB_NODE_COUNT`

### Requirement: A kit command can rewrite one recorded install arg
The kit engine SHALL provide one operation that rewrites the value of a single recorded install arg in the kit's `resolved-args.env` and keeps every other arg as it was. Later commands SHALL see the new value. A kit command such as `upgrade` uses it to record the version it applied.

#### Scenario: One arg rewritten
- **WHEN** `CASSANDRA_VERSION` is rewritten from `5.0` to `5.0.4`
- **THEN** `resolved-args.env` holds `CASSANDRA_VERSION=5.0.4`, every other arg is unchanged, and a later `start` sees `5.0.4`
