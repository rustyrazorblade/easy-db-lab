## ADDED Requirements

### Requirement: Kit names that match a built-in command
A workspace kit whose directory name matches a built-in top-level command SHALL NOT be skipped silently. At startup the CLI SHALL emit an event naming the kit and the built-in command it collides with, and SHALL keep the built-in command. `kit install` SHALL refuse an instance name that matches a built-in top-level command, with a message naming the command, before it writes any file.

#### Scenario: Colliding workspace kit is reported
- **WHEN** the workspace has a kit directory named `cassandra` with a `kit.yaml` and a `bin/` script, and the CLI starts
- **THEN** an event names the kit `cassandra` and says it collides with the built-in `cassandra` command
- **AND** the built-in `cassandra` command is unchanged

#### Scenario: Install refuses a reserved name
- **WHEN** `kit install` would produce an instance named after a built-in top-level command
- **THEN** the command exits non-zero with a message naming the command
- **AND** no kit directory is written

#### Scenario: Non-colliding kit registers as before
- **WHEN** the workspace has a kit named `cassandra-pods` and the CLI starts
- **THEN** `cassandra-pods` is registered as a command and no collision event is emitted
